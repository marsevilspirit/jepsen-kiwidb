(ns jepsen-kiwidb.db
  "Database automation"
  (:require [clojure.tools.logging :as log]
            [jepsen [db :as db]]))

(def build-dir
  "A remote directory for us to clone projects and compile them."
  "/tmp/jepsen-kiwidb/build")

(def dir
  "The remote directory where we deploy kiwi to"
  "/root")

(def log-file (str dir "/kiwidb.log"))
(def pid-file (str dir "/kiwidb.pid"))
(def binary "/bin/kiwi")
(def cli-binary "/bin/redis-cli")
(def config-file "/kiwi.conf")

(defn kiwidb
  "kiwidb"
  []
  (reify db/DB
    (setup! [_ _ node] ; arguements are [db, test, node]
      (log/info node "Setting up kiwidb"))

    (teardown! [_ _ node]
      (log/info node "Tearing down kiwidb"))))
