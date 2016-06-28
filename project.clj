(defproject org.timmc/heim-logger "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 ;; v1
                 [aleph "0.4.1"]
                 [cheshire "5.6.3"]
                 ;; v2
                 ]
  :main ^:no-aot org.timmc.heim-logger.v1
  :jvm-opts ["-Xmx100M"]
  )
