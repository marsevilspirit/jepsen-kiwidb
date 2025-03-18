(ns jepsen-kiwidb.client
  "Helper functions for working with Carmine, out kiwi client."
  (:require [taoensso.carmine :as car]
            [slingshot.slingshot :refer [try+]]))

(defmacro with-exceptions
  "Takes an operation, an idempotent :f set, and a body; evaluates body,
  converting known exceptions to failed ops."
  [op & body]
  `(try+ ~@body
         (catch [:prefix :err] e#
           (if (re-find #"err" (str e#))
             (assoc ~op :type :fail, :error (str e#))))

         (catch [:prefix :moved] e#
           (assoc ~op :type :fail, :error :moved));
         ))

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
   (let [spec (merge {:host node ; Like n1, n2, n3...
                      :port 9221 ; kiwidb port
                      :timeout 10000}
                     opts)
         wcar-opts {:pool client-conn-pool, :spec spec}]
     wcar-opts)))

(defn lrange [_ _] {:type :invoke, :f :read, :value nil})
(defn lpush  [_ _] {:type :invoke, :f :add, :value (rand-int 5)})
