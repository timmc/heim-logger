(defproject org.timmc/heim-logger "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.8.0"]
   [cheshire "5.6.3"]

   ;; v1

   [aleph "0.4.1"]
   ;; JAXB APIs for aleph to link against in newer JDKs. According to
   ;; https://stackoverflow.com/questions/43574426 JAXB is present in
   ;; JDK 6/7/8, and while it is present in 9 and 10, it is in a module
   ;; that is not loaded by default. In 11, it is missing entirely.
   ;; This has only been tested with JDK 11, manually.
   [javax.xml.bind/jaxb-api "2.3.1"]
   [com.sun.xml.bind/jaxb-impl "2.3.2"]
   [org.glassfish.jaxb/jaxb-runtime "2.3.2"]
   [javax.activation/javax.activation-api "1.2.0"]

   ;; v2

   ;; I had trouble with http.async.client -- I got a
   ;; 403 when connecting to euphoria.io.
   [stylefruits/gniazdo "1.0.0"]
   ]
  :main ^:no-aot org.timmc.heim-logger.v1
  :jvm-opts ["-Xmx100M"]
  )
