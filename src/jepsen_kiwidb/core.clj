(ns jepsen-kiwidb.core
  (:require [clojure.tools.logging :as log]
            [jepsen
             [cli :as cli]
             [generator :as gen]
             [client :as client]
             [tests :as tests]]
            [jepsen-kiwidb
             [db :as kiwidb]
             [client :as kclient]]
            [taoensso.carmine :as car]
            [jepsen.os.ubuntu :as ubuntu]))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (kclient/delay-exceptions  5
                               (log/info "Client opening connection to" node)
                               (let [c (kclient/open node)]
                                 (log/info "Client opened connection to" node)
                                 (assoc this :conn c))))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :lrange (car/wcar (:conn this) (car/lrange "foo" 0 -1))))

  (teardown! [this test])

  (close! [_ test]))

(defn kiwidb-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:pure-generators true
          :name            "kiwidb"
          :os              ubuntu/os
          :db              (kiwidb/kiwidb)
          :client          (Client. nil)
          :generator       (->> kclient/lrange
                                (gen/stagger 1)
                                (gen/nemesis nil)
                                (gen/time-limit 15))}))

; because we are in docker, we need to specify the ssh private key
; lein run test --ssh-private-key /root/.ssh/id_rsa -n n1 -n n2 -n n3
(defn -main
  "Handles command line arguments. Can either run a test,
  or a web server for browsing results."
  [& args]
  (cli/run! (merge
             (cli/single-test-cmd {:test-fn kiwidb-test})
             (cli/serve-cmd))
            args))
