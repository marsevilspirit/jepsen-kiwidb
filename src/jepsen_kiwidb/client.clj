(ns jepsen-kiwidb.client
  "Helper functions for working with Carmine, out kiwi client."
  (:require [jepsen
             [client :as client]]
            [clojure.tools.logging :as log]
            [taoensso.carmine
             [connections :as conn]]
            [taoensso.carmine :as car :refer [wcar]]))

(defmacro delay-exceptions
  "Adds a short (n second) delay when an exception is thrown from body. Helpful
  for not spamming the log with reconnection attempts to a down server, at the
  cost of potentially missing the first moments of a server's life."
  [n & body]
  `(try ~@body
        (catch Exception e#
          (Thread/sleep (* ~n 1000))
          (throw e#))))

(defonce client-conn-pool (car/connection-pool {}))

(defn open
  "Opens a connection to a node. Our connections are Carmine IConnectionPools.
  Options are merged into the conn pool spec.
  How to use:
  (wcar (open node) (car/ping))"
  ([node] ; Like C++ overloading
   (open node {}))

  ([node opts]
   (let [spec (merge {:host node
                      :port 9221
                      :timeout 10000}
                     opts)
         wcar-opts {:pool client-conn-pool, :spec spec}]
     wcar-opts)))

(defn lrange [_ _] {:type :invoke, :f :lrange, :value nil})
(defn lpush  [_ _] {:type :invoke, :f :lpush, :value (rand-int 5)})
