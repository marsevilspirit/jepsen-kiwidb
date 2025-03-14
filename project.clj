(defproject jepsen-kiwidb "0.1.0-SNAPSHOT"
  :description "A Jepsen test for kiwidb"
  :url "https://github.com/arana-db/kiwi"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.8-SNAPSHOT"]
                 [com.taoensso/carmine "3.4.1"]]
  :repl-options {:init-ns jepsen-kiwidb.core}
  :main jepsen-kiwidb.core)
