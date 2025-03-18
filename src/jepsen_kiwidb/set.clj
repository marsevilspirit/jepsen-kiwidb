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
        :read (assoc op :type :ok,
                     :value (mapv util/parse-long (car/wcar conn (car/lrange "foo" 0 -1))))
        :add  (do (car/wcar conn (car/lpush "foo" (:value op)))
                  (assoc op :type :ok)))))

  (teardown! [_ _])

  (close! [_ _]))

(defn workload
  "set workload by lpush and lrange"
  []
  {:client (SetClient. nil)
   :checker (checker/set)
   :generator (gen/mix [kclient/lrange kclient/lpush])})
