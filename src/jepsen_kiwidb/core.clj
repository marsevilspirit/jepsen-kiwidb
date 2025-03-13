(ns jepsen-kiwidb.core
  (:require [jepsen
             [cli :as cli]
             [tests :as tests]]
            [jepsen-kiwidb
             [db :as kiwidb]]
            [jepsen.os.ubuntu :as ubuntu]))

(defn kiwidb-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:pure-generators true
          :name "kiwidb"
          :os ubuntu/os
          :db (kiwidb/kiwidb)}
         opts))

; because we are in docker, we need to specify the ssh private key
; lein run test --ssh-private-key /root/.ssh/id_rsa
(defn -main
  "Handles command line arguments. Can either run a test,
  or a web server for browsing results."
  [& args]
  (cli/run! (merge
             (cli/single-test-cmd {:test-fn kiwidb-test})
             (cli/serve-cmd))
            args))
