(ns org.timmc.heim-logger
  (:require [aleph.http :as ah]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [manifold.stream :as s]))

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
  ;; FIXME: Really shouldn't run dispatched things in a dosync,
  ;; there's no reason to believe they won't do side-effects!
  ;; However, it does provide the convenience of not releasing msg
  ;; sends until the state has been altered...
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
  (let [oops (select-keys msg [:error :throttled :throttled_reason])]
    (if (every? (some-fn nil? false?) (keys oops))
      (do ;; successful event/reply
        #_(println msg))
      (do ;; something's wrong
        (println msg)))))

(defn log-messages
  "Log message datas to file."
  [session event-datas]
  (let [w (-> session :state deref :logger :writer)]
    (binding [*out* w]
      (doseq [m event-datas]
        (println (json/generate-string {:hl-type "message"
                                        :data m}))))
    (.flush w)))

(defn do-send-event
  "Log a single new message to file."
  [session msg]
  (log-messages session [(get-in msg [:data])]))

(defn oldest-msg-id
  [msgs]
  (:id (max-1 (fn compare-times [x y]
                ;; Reverse sort, get msg with min time
                (- (:time y) (:time x)))
              msgs)))

(defn ask-for-backlog
  [session before-id]
  (println "Asking for backlog before" before-id)
  (send-message session
                {:type "log"
                 :data {:n 1000
                        :before before-id}}))

(defn do-snapshot-event
  "Log an entire snapshot to file."
  [session msg]
  (let [recent-log (get-in msg [:data :log])]
    (log-messages session recent-log)
    ;; Start downloading entire room history
    (println "Switching to full catchup mode!")
    ;; Should maybe check if lifecycle is :waiting-for-snapshot ?
    (alter (:state session) assoc-in [:logger :lifecycle] :full-catchup)
    (ask-for-backlog session (oldest-msg-id recent-log))))

(defn do-log-reply
  "Write backlog to file, ask for more if possible."
  [session msg]
  (let [recent-log (get-in msg [:data :log])]
    (if (empty? recent-log)
      (do (println "All caught up, no more backlog. Now just tailing.")
          (alter (:state session) assoc-in [:logger :lifecycle] :tailing))
      (do (log-messages session recent-log)
          (ask-for-backlog session (oldest-msg-id recent-log))))))

(def logger-dispatch
  "Reactive dispatch overlay for logger."
  (merge
   (base-dispatch "hillbot")
   {"send-event"
    #'do-send-event

    "snapshot-event"
    #'do-snapshot-event

    "log-reply"
    #'do-log-reply

    :unknown-type
    ;; (fn [session msg]
    ;;   (println "Unknown event type:" (:type msg)))
    do-nothing

    :pre
    #'debug-print-message

    :halt
    (fn [session _msg]
      (some-> session :state deref :logger :writer .close))}))

(defn find-last-logged
  "Find the most recently logged message."
  [logfile]
  (with-open [rdr (io/reader logfile :encoding "UTF-8")]
    (max-1 (fn compare-times [x y]
             (- (get-in x [:data :time])
                (get-in y [:data :time])))
           (json/parsed-seq rdr true))))

(defn -main
  "Demo connection to heim"
  [server room logfile]
  (let [w (io/writer logfile :encoding "UTF-8" :append true)
        ;; FIXME unused
        last-logged-id (:id (:data (find-last-logged logfile)))
        _ (println "Last logged message ID:" last-logged-id)
        session (run server room
                     {:logger {:lifecycle :waiting-for-snapshot
                               :path logfile
                               :writer w}}
                     logger-dispatch)]
    session))
