(ns jepsen-kiwidb.db
  "Database automation"
  (:require [clojure.tools.logging :as log]
            [clojure
             [string :as str]]
            [jepsen
             [control :as c]
             [core :as jepsen]
             [db :as db]
             [util :as util]]
            [jepsen.control
             [net :as cn]
             [util :as cu]]
            [slingshot.slingshot :refer [try+ throw+]]))

; for slingshot
(def e)

(def build-dir
  "A remote directory for us to clone projects and compile them."
  "/tmp/jepsen-kiwidb/build")

(def dir
  "The remote directory where we deploy kiwi to"
  "/root")

(def db (str dir "/db"))
(def logs (str dir "/logs"))
(def log-file (str dir "/kiwidb.log")) ; /root/kiwidb.log
(def pid-file (str dir "/kiwidb.pid")) ; /root/kiwidb.pid
(def binary "/root/kiwi")
; (def cli-binary "/bin/redis-cli")
(def redis-cli "redis-cli")
(def config-file "kiwi.conf")

(def build-file
  "A file we create to track the last built version; speeds up compilation."
  "jepsen-built-version")

(def kiwi-repo-url "https://github.com/marsevilspirit/kiwi")
(def kiwi-version "fix-errcode-moved")

(defn checkout-repo!
  "Checks out a repo at the given version into a directory in build/ named
  `dir`. Returns the path to the build directory."
  [repo-url dir version]
  (let [full-dir (str build-dir "/" dir)]
    (when-not (cu/exists? full-dir)
      (c/cd build-dir
            (log/info "build-dir: entering" build-dir)
            (c/exec :mkdir :-p build-dir)
            (log/info "exec mkdir -p" build-dir)
            (log/info "execing git clone" repo-url dir)
            (c/exec :git :clone repo-url dir)
            (log/info "execed git clone" repo-url dir)))

    (c/cd full-dir
          (log/info "full-dir: entering" full-dir)
          (try+ (c/exec :git :checkout version)
                (catch [:exit 1] e
                  (if (re-find #"pathspec .+ did not match any file" (:err e))
                    (do ; Ah, we're out of date
                      (log/warn "No such version" version)
                      (log/info "execing git fetch")
                      (c/exec :git :fetch)
                      (log/info "execed git fetch")
                      (log/info "execing git checkout" version)
                      (c/exec :git :checkout version)
                      (log/info "execed git checkout" version))
                    (throw+ e)))))
    full-dir))

(def build-locks
  "We use these locks to prevent concurrent builds."
  (util/named-locks))

; how to use this macro:
; https://github.com/taoensso/carmine/wiki/1-Getting-started
(defmacro wcar* [& body] `(car/wcar my-wcar-opts ~@body))

(defmacro with-build-version
  "Takes a test, a repo name, a version, and a body. Builds the repo by
  evaluating body, only if it hasn't already been built. Takes out a lock on a
  per-repo basis to prevent concurrent builds. Remembers what version was last
  built by storing a file in the repo directory. Returns the result of body if
  evaluated, or the build directory."
  [node repo-name version & body]
  `(util/with-named-lock build-locks [~node ~repo-name]
     (let [build-file# (str build-dir "/" ~repo-name "/" build-file)]
       (if (try+ (= (str ~version) (c/exec :cat build-file#))
                 (catch [:exit 1] e# ; Not found
                   false))
         ; Already built
         (str build-dir "/" ~repo-name)
         ; Build
         (let [res# (do ~@body)]
           ; Log version
           (c/exec :echo ~version :> build-file#)
           res#)))))

(defn build-kiwi!
  "Compiles kiwi, and returns the directory we built in."
  [_ node]
  (with-build-version node "kiwi" kiwi-version
    (let [dir (checkout-repo! kiwi-repo-url "kiwi" kiwi-version)]
      (log/info "Building kiwi" kiwi-version)
      (c/cd dir
            (log/info "execed cd" dir)

            ; every time clean and then rebuild kiwidb.
            ; This will consume a lot of time and take up a lot of memory,
            ; especially when 5 nodes start simultaneously (in docker), which is
            ; very likely to cause the operating system to run out of memory (OOM).
            ; (log/info "execing bash ./etc/script/build.sh --clear")
            ; (c/exec :bash "./etc/script/build.sh" :--clear)
            ; (log/info "execed bash ./etc/script/build.sh --clear")

            (log/info "execing bash ./etc/script/build.sh")
            (c/exec :bash "./etc/script/build.sh")
            (log/info "execed bash ./etc/script/build.sh"))
      [dir])))

(defn deploy-kiwi!
  "Uploads kiwi binaries built from the given directory."
  [build-dir]
  (log/info "Deploying kiwidb, build-dir:", build-dir)
  (c/exec :mkdir :-p dir)
  (log/info "mkdir -p" dir)
  (doseq [f ["kiwi"]]
    (log/info "cp" (str build-dir "/bin/" f) (str dir "/"))
    (c/exec :cp (str build-dir "/bin/" f) (str dir "/"))
    (log/info "cp" (str build-dir "/etc/conf/" config-file) (str dir "/"))
    (c/exec :cp (str build-dir "/etc/conf/" config-file) (str dir "/"))
    (log/info "chmod +x" (str dir "/" f))
    (c/exec :chmod "+x" (str dir "/" f))))

(defn cli!
  "Runs a Redis CLI command. Includes a 3s timeout."
  [& args]
  (c/su (apply c/exec :timeout "3s" redis-cli args)))

(defn raft-info-str
  "Returns the current cluster state as a string."
  []
  (let [str (cli! :--raw "-p" "9221" "INFO" "RAFT")]
    str))

; like: raft_node0:addr=172.18.0.7,port=9231
(defn parse-raft-info-node
  "Parses a node string in a raft-info string, which is a list of k=v pairs."
  [s]
  (->> (str/split s #",")
       (map (fn parse [part]
              (let [[k v] (str/split part #"=")
                    k (keyword k)]
                [k (case k
                     (:port)
                     (util/parse-long v)
                     v)])))
       (into {})))

(defn parse-raft-info-kv
  "Parses a string key and value of the raft info string into a
  key path (for assoc-in) and value [ks v]."
  [k v]
  (let [k (keyword k)]
    (case k
      (:raft_role :raft_state) [[k] (keyword v)]

      (:raft_group_id :raft_node_id :raft_peer_id :raft_leader_id) [[k] v]

      (:raft_current_term) [[k] (util/parse-long v)]

      ; This is a raft_node0, raft_node1, ... entry; file it in :nodes
      (if (re-find #"^raft_node(\d+)$" (name k))
        ; if return
        [[:nodes k] (parse-raft-info-node v)]

        ; else return
        [[k] v];
        ))))

; parse sush as:
; raft_group_id:1833cd1f0891fdc16677a6641e2a4f46
; raft_node_id:kiwi:172.18.0.7:9231:0:0
; raft_peer_id:172.18.0.7:9231:0:0
; raft_state:up
; raft_role:LEADER
; raft_leader_id:172.18.0.7:9231:0:0
; raft_current_term:4
; raft_node0:addr=172.18.0.7,port=9231
(defn raft-info
  "Current cluster state as a map."
  []
  (-> (raft-info-str)
      str/split-lines
      (->> (reduce (fn parse-line [state line]
                     (cond
                       ; blank
                       (re-find #"^\s*$" line)
                       state

                       ; comment
                       (re-find #"^# " line)
                       state

                       ; k:v pair
                       (re-find #"^(.+?):(.*)$" line)
                       (let [[_ k v] (re-find #"^(.+?):(.*)$" line)
                             [ks v] (parse-raft-info-kv k v)]
                         (assoc-in state ks v))

                       ; Unknown line
                       :else
                       (throw+ {:type :raft-info-parse-error
                                :line line})))
                   {}))

      ; (#(do (log/info "reduce result:" %)
      ;       %)) ; keep the result

      ; remove :nodes key
      (update-in [:nodes] vals)))

; return like: {172.18.0.3 n1, 172.18.0.7 n2, 172.18.0.4 n3}
(def node-ips
  "Returns a map of node names to IP addresses. Memoized."
  (memoize
   (fn node-ips- [test]
     (->> (:nodes test)
          (map (juxt identity cn/ip))
          (into {})))))

(defn node-state
  "
  {:node \"n1\"
   :role :leader
   :id   kiwi:ip:port:0:0}
  "
  [test]
  (let [ip->node (into {} (map (juxt val key) (node-ips test)));
        states (c/on-nodes test
                           (fn xform [test node]
                             ; We take our local raft info, and massage it
                             ; into a set of {:node n1, :id 123} maps,
                             ; combining info about the local node and
                             ; other nodes.
                             (try+ (let [ri (raft-info)]
                                     ; Other nodes
                                     (->> (:nodes ri)
                                          (map (fn xform-node [n]
                                                 {:id (:id n)
                                                  :node (ip->node (:addr n))}))
                                          ; Local node
                                          (cons {:id (:raft_node_id ri)
                                                 :role (:raft_role ri)
                                                 :node node})))

                                   ; Couldn't run redis-cli
                                   (catch [:exit 1] e [])
                                   (catch [:exit 255] e [])
                                   (catch [:exit 124] e []))))]
; Now we merge information from all nodes.
    (->> states
         vals
         (apply concat)
         (group-by :node)
         vals
         (map (fn [views-of-node]
                (apply merge views-of-node))))))

(defn kiwidb
  "kiwidb"
  []
  (let [tcpdump (db/tcpdump {:ports [9221]
                             :filter "host control"});
       ; This atom helps us track which nodes have been removed from the
       ; cluster, when we can delete their data, etc. It'll be lazily
       ; initialized as a part of the setup process, and tracks a map of node
       ; names to membership states. Each membership state is a map like
       ;
       ;  {:state   A keyword for the node state
       ;   :remover A future which is waiting for the node to be removed}
       ;
       ; States are one of
       ;
       ;   :out       - Not in the cluster, data files removed.
       ;   :joining   - We are about to, or are in the process of, joining.
       ;   :live      - Could be in the cluster, at least ostensibly. We enter
       ;                this state before init or join.
       ;   :removing  - We are about to, or have requested, that this node be
       ;                removed. A future will be waiting to clean up its data,
       ;                but won't act until *after* the node is no longer
       ;                present in the removing node's node map.
        meta-members (atom {})
        running (atom 1)]

    (reify db/DB
      (setup! [this test node]
        ; TODO: add tcpdump flag in core
        (when (:tcpdump test) (db/setup! tcpdump test node))

        (log/info node "Setting up kiwidb")
        ; like deploy-kiwi!(build-kiwi!(node))
        (let [_ (-> test (build-kiwi! node) deploy-kiwi!)])
        (log/info node "Starting kiwidb" kiwi-version)

        ; Start
        (db/start! this test node)

        ; Wait for kiwi ready.
        (Thread/sleep 4000)

        (if (= node (jepsen/primary test))
          ; redis raft.cluster init
          ; leader do this
          (do (cli! :-p "9221" :-h (str (cn/ip node)) :raft.cluster :init)
              (swap! meta-members assoc node {:state :live})
              (log/info "Main init done, syncing")
              (jepsen/synchronize test 20000))

          ; Compilation can be slow and join on secondaries.
          ; follower do this
          (do (log/info "Waiting for main init")
              (jepsen/synchronize test 20000); Ditto
              (swap! running inc)
              (Thread/sleep (* 5000 @running))
              (log/info node "Joining")
              ; Port is mandatory here
              (swap! meta-members assoc node {:state :live})
              (log/info "redis-cli -p 9221 -h" (str (cn/ip node)) "raft.cluster join" (str (cn/ip (jepsen/primary test)) ":9221"))
              (cli! :-p "9221" :-h (str (cn/ip node)) :raft.cluster :join (str (cn/ip (jepsen/primary test)) ":9221"))
              (log/info node "raft join done");
              ))

        ; wait join
        (Thread/sleep 20000)
        (log/info :meta-members meta-members)
        (log/info :raft-info (raft-info))
        (log/info :node-state (node-state test))
        (log/info node "started kiwidb")) ; wait for starting kiwi server to be ready

      (teardown! [this test node]
        (log/info node "Tearing down kiwidb" kiwi-version)
        (db/kill! this test node)
        (log/info "Remove db:" db)
        (c/su (c/exec :rm :-rf db))
        (log/info "Remove logs:" logs)
        (c/su (c/exec :rm :-rf logs))
        (log/info "Remove log-file:" log-file)
        (c/su (c/exec :rm :-rf log-file))
        (when (:tcpdump test)
          (db/teardown! tcpdump test node)))

      db/Primary
      (setup-primary! [_ _ _]) ; nothing to do

      (primaries [_ test]
        (->> (node-state test)))

      db/Kill
      (start! [_ _ node]
        (c/su
         (log/info node :starting :kiwi)
         (cu/start-daemon!
          {:logfile log-file
           :pidfile pid-file
           :chdir   dir}
          binary ; ./kiwi
          config-file ; config-file is used as: ./kiwi [./kiwi.conf]
          )))

      (kill! [_ _ _]
        (c/su
         (cu/stop-daemon! binary pid-file)))

      db/LogFiles
      (log-files [_ _ node]
        (log/info (str "Log files for " node " are " log-file))
        [log-file]))))
