(ns org.timmc.heim-logger.v2
  "Version 2 of logger."
  (:require [cheshire.core :as json]
            [gniazdo.core :as ws]
            [org.timmc.heim-logger.common :as cm])
  (:import (clojure.lang ExceptionInfo)
           (java.util.concurrent.atomic AtomicLong)))

(defn ssocket
  "Get the socket from a session."
  [session]
  @(:socket @session))

(defn send-packet
  "Send a heim packet of the given type and data and return immediately."
  [session ptype pdata]
  (ws/send-msg (ssocket session)
               (json/generate-string {:type (name ptype)
                                      :data pdata})))

(def blocking-call-timeout-ms
  "How many milliseconds to wait, maximum, on a blocking call."
  5000)

(defn now<-call-command-blocking-start
  "Redefable time lookup."
  []
  (System/currentTimeMillis))

(defn call-command-blocking
  "Send a command to the heim server and block on a reply.

Yield a response object or throw an exception-info with the following
fields:

- `:source` with constant `:call-command-blocking-receive`
- `:type` with either `:timeout` for receive timeout or `:unexpected`
  for an unknown exception
- `:packet-id` with the ID string of the packet that was
  sent (possibly useful if logging all Rx and Tx packets."
  [session ptype pdata]
  (let [packet-id ((:gen-unique-id @session))
        recv-promise (promise)]
    ;; Register a blocking request *before* sending it to avoid race
    ;; conditions.
    (swap! session assoc-in [:blocking-calls packet-id]
           ;; Recorded start time may overestimate length of call.
           {:start (now<-call-command-blocking-start)
            :result recv-promise})
    ;; Now send! We'll use the ID to find the response.
    (ws/send-msg (ssocket session)
                 (json/generate-string {:type (name ptype)
                                        :data pdata
                                        :id packet-id}))
    ;; Wait for results. Timeout may underestimate length of call.
    (try
      (let [response (deref recv-promise
                            blocking-call-timeout-ms ::timeout)]
        ;; Convert timeout-val into exception for consistency
        (if (= response ::timeout)
          (throw (ex-info "Timed out while waiting for blocking call response."
                          {:type :timeout
                           :source :call-command-blocking-receive
                           :packet-id packet-id}))
          response))
      (catch ExceptionInfo ei
        ;; Exclude EIs from the general Exception clause
        (throw ei))
      (catch Exception e
        (throw (ex-info (str "Unexpected exception while "
                             "receiving result from blocking call")
                        {:type :unexpected
                         :source :call-command-blocking-receive
                         :packet-id packet-id}
                        e)))
      (finally ;; Make sure to clean up either way.
        (swap! session update-in [:blocking-calls]
               dissoc packet-id)))))

(defn maybe-deliver-blocking-call-result
  "If the message is a response to a blocking call, deliver it."
  [session msg]
  (when-let [recv-id (:id msg)]
    (when-let [blocked-promise (get-in @session
                                       [:blocking-calls recv-id :result])]
      (deliver blocked-promise msg))))

(defn on-connect
  [session _jetty-ws-session]
  (send-packet session "nick" {:name "hillbot v2"}))

(defn on-receive
  [session raw]
  (let [msg (try
              (json/parse-string raw true)
              (catch Throwable t
                (println "Raw message was:" raw)
                (throw t)))
        mtype (:type msg)
        data (:data msg)]
    (maybe-deliver-blocking-call-result session msg)
    (when-let [error (:error msg)]
      (println "ERROR" msg))
    (case mtype
      "ping-event"
      (send-packet session "ping-reply" {:time (:time data)})

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
  (let [id-counter (AtomicLong.)
        session (atom { ;; Promise of an implementation-defined
                       ;; "socket" object we can send to or close.
                       :socket (promise)
                       ;; AtomicLong, source of unique IDs for packets.
                       :gen-unique-id #(str (.getAndIncrement id-counter))
                       ;; Registry of in-flight blocking calls, by packet-id.
                       :blocking-calls {}})
        socket (ws/connect
                (cm/address "euphoria.io" room)
                ;; Var indirection should allow code reloading during a session.
                :on-connect (partial #'on-connect session)
                :on-receive (partial #'on-receive session))]
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
