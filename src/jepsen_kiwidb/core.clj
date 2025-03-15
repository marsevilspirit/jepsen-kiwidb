(ns jepsen-kiwidb.core
  (:require [clojure.tools.logging :as log]
            [jepsen
             [checker :as checker]
             [cli :as cli]
             [generator :as gen]
             [client :as client]
             [util :as util]
             [tests :as tests]]
            [jepsen-kiwidb
             [db :as kiwidb]
             [client :as kclient]]
            [taoensso.carmine :as car]
            [jepsen.os.ubuntu :as ubuntu]
            [slingshot.slingshot :refer [throw+]]))

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
      :read (assoc op :type :ok,
                   :value (mapv util/parse-long (car/wcar conn (car/lrange "foo" 0 -1))))
      :add  (do (car/wcar conn (car/lpush "foo" (:value op)))
                (assoc op :type :ok))))

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
          :checker         (checker/set)
          :generator       (->> (gen/mix [kclient/lrange kclient/lpush])
                                (gen/stagger 1) ; The time interval for each operation.
                                (gen/nemesis nil)
                                (gen/time-limit 5) ; The time limit for the test.
                                )}))

; because we are in docker, we need to specify the ssh private key.
; lein run test --ssh-private-key /root/.ssh/id_rsa -n n1 -n n2 -n n3
(defn -main
  "Handles command line arguments. Can either run a test,
  or a web server for browsing results."
  [& args]
  (cli/run! (merge
             (cli/single-test-cmd {:test-fn kiwidb-test})
             (cli/serve-cmd))
            args))
