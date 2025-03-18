(ns jepsen-kiwidb.core
  (:require [jepsen
             [checker :as checker]
             [cli :as cli]
             [generator :as gen]
             [nemesis :as nemesis]
             [tests :as tests]]
            [jepsen-kiwidb
             [db :as kiwidb]
             [client :as kclient]
             [set :as set]]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.checker.timeline :as timeline]))

(defn kiwidb-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (let [workload (set/workload)]
    (merge tests/noop-test
           opts
           {:pure-generators true
            :name            "kiwidb"
            :os              ubuntu/os
            :db              (kiwidb/kiwidb)
            :client          (:client workload)
            :nemesis         (nemesis/partition-random-halves)
            :checker         (checker/compose
                              {:perf (checker/perf)
                               :clock (checker/clock-plot)
                               :timeline (timeline/html)
                               :stats (checker/stats)
                               :exceptions (checker/unhandled-exceptions)
                               :workload (:checker workload)})
            :generator       (->> (gen/mix [kclient/lrange kclient/lpush])
                                  (gen/stagger 1/10) ; The time interval for each operation.
                                  (gen/nemesis
                                   (cycle [(gen/sleep 5)
                                           {:type :info, :f :start}
                                           (gen/sleep 5)
                                           {:type :info, :f :stop}]))
                                  (gen/time-limit 30) ; The time limit for the test.
                                  )})))

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
