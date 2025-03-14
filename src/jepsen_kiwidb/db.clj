(ns jepsen-kiwidb.db
  "Database automation"
  (:require [clojure.tools.logging :as log]
            [jepsen
             [control :as c]
             [db :as db]
             [util :as util]]
            [jepsen.control
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

(def log-file (str dir "/kiwidb.log")) ; /root/kiwidb.log
(def pid-file (str dir "/kiwidb.pid")) ; /root/kiwidb.pid
(def binary "/root/kiwi")
(def cli-binary "/bin/redis-cli")
(def config-file "kiwi.conf")

(def build-file
  "A file we create to track the last built version; speeds up compilation."
  "jepsen-built-version")

(def kiwi-repo-url "https://github.com/arana-db/kiwi")
(def kiwi-version "unstable")

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
  [test node]
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
      dir)))

(defn deploy-kiwi!
  "Uploads kiwi binaries built from the given directory."
  [build-dir]
  (log/info "Deploying kiwidb, build-dir:", build-dir)
  (c/exec :mkdir :-p dir)
  (log/info "mkdir -p" dir)
  (doseq [f ["kiwi"]]
    (c/exec :cp (str build-dir "/bin/" f) (str dir "/"))
    (log/info "cp" (str build-dir "/bin/" f) (str dir "/"))
    (c/exec :cp (str build-dir "/etc/conf/" config-file) (str dir "/"))
    (log/info "cp" (str build-dir "/etc/conf/" config-file) (str dir "/"))
    (c/exec :chmod "+x" (str dir "/" f))
    (log/info "chmod +x" (str dir "/" f))))

(defn kiwidb
  "kiwidb"
  []
  (reify db/DB
    (setup! [this test node] ; arguements are [db, test, node]
      (log/info node "Setting up kiwidb")
      (let [kiwi (-> test (build-kiwi! node) deploy-kiwi!)])
      (log/info node "Starting kiwidb" kiwi-version)
      (cu/start-daemon!
       {:logfile log-file
        :pidfile pid-file
        :chdir   dir}
       binary ; ./kiwi
       config-file) ;config-file is used as: ./kiwi [./kiwi.conf]
      (Thread/sleep 10000))

    (teardown! [_ _ node]
      (log/info node "Tearing down kiwidb" kiwi-version)
      (cu/stop-daemon! pid-file)
      (log/info "Remove db:" (str dir "/" "db"))
      (c/su (c/exec :rm :-rf (str dir "/" "db")))
      (log/info "Remove log:" (str dir "/" "logs"))
      (c/su (c/exec :rm :-rf (str dir "/" "logs")))
      (log/info "Remove log-file:" log-file)
      (c/su (c/exec :rm log-file)))

    db/LogFiles
    (log-files [_ test node]
      (log/info (str "Log files for " node " are " log-file))
      [log-file])))
