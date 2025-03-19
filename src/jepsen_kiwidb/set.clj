(ns jepsen-kiwidb.set
  "Tests"
  (:require [clojure.tools.logging :as log]
            [jepsen
             [client :as client]
             [checker :as checker]
             [generator :as gen]
             [util :as util]]
            [jepsen-kiwidb
             [client :as kclient]]
            [taoensso.carmine :as car]))

(defrecord SetClient [conn]
  client/Client
  (open! [this _ node]
    (kclient/delay-exceptions  5
                               (log/info "Client opening connection to" node)
                               (let [c (kclient/open node)]
                                 (log/info "Client opened connection to" node)
                                 (assoc this :conn c))))
  (setup! [_ _])

  (invoke! [_ _ op]
    (kclient/with-exceptions op
      (case (:f op)
        :read (let [value
                    (mapv util/parse-long (car/wcar conn (car/lrange "set-jepsen-test" 0 -1)))]
                (assoc op :type :ok, :value value))
        :add  (do (car/wcar conn (car/lpush "set-jepsen-test" (:value op)))
                  (assoc op :type :ok)))))

  (teardown! [_ _])

  (close! [_ _]))

(defn lrange [] {:type :invoke, :f :read, :value nil})

(defn lpush [] {:type :invoke, :f :add, :value (rand-int 5)})

(defn workload
  "set workload by lpush and lrange"
  []
  {:client (SetClient. nil)
   :checker (checker/set-full)
   :generator (gen/mix [lrange lpush])})
