(ns org.timmc.heim-logger.v2
  "Version 2 of logger."
  (:require [cheshire.core :as json]
            [gniazdo.core :as ws]
            [org.timmc.heim-logger.common :as cm]))

(defn send-msg
  [session msg]
  (ws/send-msg @(:socket @session)
               (json/generate-string msg)))

(defn on-connect
  [session _jetty-ws-session]
  (send-msg session {:type "nick"
                     :data {:name "hillbot v2"}}))

(defn on-receive
  [session raw]
  (let [msg (try
              (json/parse-string raw true)
              (catch Throwable t
                (println "Raw message was:" raw)
                (throw t)))
        mtype (:type msg)
        data (:data msg)]
    (when-let [error (:error msg)]
      (println msg))
    (case mtype
      "ping-event"
      (send-msg session
                {:type "ping-reply",
                 :data {:time (:time data)}})

      "send-event"
      (let [{:keys [sender, content]} data
            {nick :name, id :id} sender]
        (println (format "RECV %s(%s) -> %s"
                         nick id content)))

      "join-event"
      (println "JOIN" (:name data))
      
      "part-event"
      (println "PART" (:name data))
      
      "nick-event"
      (println "NICK" (:from data) "->" (:to data))

      ;; :else
      (println "received" mtype))))

(defn connect
  "Connect to a room and yield a session."
  [room]
  (let [session (atom {:socket (promise)})
        socket (ws/connect
                (cm/address "euphoria.io" room)
                :on-connect (partial on-connect session)
                :on-receive (partial on-receive session))]
    ;; This is how we tie the asynchronous knot: Block any use of the
    ;; socket until we have time to set it! (Otherwise there's a race
    ;; condition where we might want to send a reply before reaching
    ;; this line -- would require a fast connection and a long context
    ;; switch or GC pause, of course.)
    (deliver (:socket @session) socket)
    session))

;; --

(defn start
  "Start logger and return a session object."
  [server room logfile]
  (println "starting" room)
  (.start (Thread. (while true
                     5)))
  {:room room})

(defn stop
  "Halt logger using session object."
  [session]
  (println "halting" session)
  (flush))

(defn -main
  "Run logger and close it on shutdown. Arguments:

- server is the heim host (requires secure websocket protocol)
- room is the heim room (without ampersand)
- logfile is the file where logs will be written to (and read from for
  catchup)"
  [server room logfile]
  (let [session (start server room logfile)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(stop session) "logger shutdown hook"))))
