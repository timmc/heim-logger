(ns org.timmc.heim-logger
  (:require [aleph.http :as ah]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [manifold.stream :as s]))

(defn pencode
  "Percent-encode for URLs (conservatively.)"
  [s]
  (str/replace (java.net.URLEncoder/encode s "UTF-8") "+" "%20"))

(defn address
  [server room]
  (format "wss://%s/room/%s/ws" server (pencode room)))

(defn send-message
  [{:as session :keys [sender wc]} msg]
  (send sender (fn serial-send [_]
                 (doto wc (s/put! (json/generate-string msg)))))
  nil)


(defn base-dispatch
  "Base dispatch map for message types."
  [nick]
  {"hello-event"
   (fn [{:as session :keys [state]} msg]
     (alter state assoc-in [:base :whoami] (-> msg :data :id))
     (send-message session {:type "nick" :data {:name nick}}))

   "ping-event"
   (fn [session msg]
     (send-message session
                   {:type "ping-reply"
                    :data {:time (-> msg :data :time)}}))

   "snapshot-event"
   (fn [{:as session :keys [state]} msg]
     (alter state assoc-in [:base :lifecycle] :joined))

   "bounce-event"
   (fn [{:as session :keys [state]} msg]
     (alter state assoc-in [:base :lifecycle] :authing))
   })

(defn auto-respond
  [session msg dispatch]
  (dosync
   (when-let [pre-handler (get dispatch :pre)]
     (pre-handler session msg))
   (when-let [handler (get dispatch (:type msg)
                           (:unknown-type dispatch))]
     (handler session msg))))

(defn react-to
  [{:as session :keys [wc state sender dispatch]}]
  (while (not= (get-in @state [:base :lifecycle]) :ending)
    (let [msg (json/parse-string @(s/take! wc) true)]
      (auto-respond session msg dispatch))))

(def initial-state
  {:base {:lifecycle :handshake}})

(defn run
  "Start a session and yield it as a map of:

- :state A ref of session state
- :wc Websocket client
- :sender An agent used to send messages in coordination with state changes

Accepts server (string), room name (string, no ampersand),
more-state (map of additional state entries), and dispatch (function
of session and message)."
  [server room more-state dispatch]
  (let [url (address server room)
        wc @(ah/websocket-client url)
        state (ref (merge initial-state (or more-state {})))
        sender (agent nil
                      :error-handler (fn sender-error [a e]
                                       (println "Error in sender agent.")
                                       (.printStackTrace e))
                      :error-mode :fail)
        session {:state state
                 :wc wc
                 :sender sender
                 :dispatch dispatch}
        reactor (future (try (react-to session)
                             (catch Throwable t
                               (.printStackTrace t)
                               (throw t))))]
    session))

(defn halt
  "Halt the session by setting the lifecycle to :ending and calling
the :halt action if specified."
  [session]
  ;; Run any cleanup actions
  (try
    (when-let [halt-action (-> session :dispatch :halt)]
      (halt-action session nil))
    (catch Throwable t
      ;; Don't let anything get in the way of closing the socket.
      (.printStackTrace t)))
  ;; Mark lifecycle as ending to shut down reactor
  (dosync
   (alter (:state session) assoc-in [:base :lifecycle] :ending))
  ;; Kill it
  (-> session :wc .close))

;; TODO
#_      (when (:throttled msg)
        (println "Throttled, sleeping.")
        (Thread/sleep 300))

;; ============

(defn do-nothing
  [_session _msg])

(defn debug-print-message
  [_session msg]
  #_(println msg))

(defn log-messages
  "Log message datas to file."
  [session event-datas]
  (let [w (-> session :state deref :logger :writer)]
    (binding [*out* w]
      (doseq [m event-datas]
        (println (json/generate-string {:hl-type "message"
                                        :data m}))))
    (.flush w)))

(defn log-send-event
  "Log a single new message to file."
  [session send-msg]
  (log-messages session [(get-in send-msg [:data])]))

(defn log-snapshot-event
  "Log an entire snapshot to file."
  [session snapshot-msg]
  (log-messages session (get-in snapshot-msg [:data :log])))

(def logger-dispatch
  "Reactive dispatch overlay for logger."
  (merge
   (base-dispatch "hillbot")
   {"send-event"
    #'log-send-event

    "snapshot-event"
    #'log-snapshot-event

    :unknown-type
    ;; (fn [session msg]
    ;;   (println "Unknown event type:" (:type msg)))
    do-nothing

    :pre
    #'debug-print-message

    :halt
    (fn [session _msg]
      (some-> session :state deref :logger :writer .close))}))

;; Consider exporting to org.timmc/handy
(defn max-1
  "Find the largest element in coll with respect to the comparator,
yielding it as the single element of a collection. When coll is
empty, yields nil."
  [comparator coll]
  (when (seq coll)
    (reduce (fn [accum item]
              (if (neg? (comparator accum item))
                item
                accum))
            (first coll)
            (rest coll))))

;; FIXME unused
(defn find-last-logged
  "Find the most recently logged message."
  [logfile]
  (with-open [rdr (io/reader logfile :encoding "UTF-8")]
    (max-1 (fn compare-times [x y]
             (- (get-in x ["data" "time"])
                (get-in y ["data" "time"])))
           (json/parsed-seq rdr))))

(defn -main
  "Demo connection to heim"
  [server room logfile]
  (let [w (io/writer logfile :encoding "UTF-8" :append true)
        session (run server room
                     {:logger {:path logfile
                               :writer w}}
                     logger-dispatch)]
    session))
