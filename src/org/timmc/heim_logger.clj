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
                 (let [omsg (json/generate-string msg)]
                   (doto wc (s/put! omsg)))))
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
    (let [raw @(s/take! wc)
          msg (try
                (json/parse-string raw true)
                (catch Throwable t
                  (println "Raw message was:" raw)
                  (throw t)))]
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

(defn oldest-msg
  "Find the oldest message-data in a coll of them."
  [msg-datas]
  (max-1 (fn compare-times [x y]
           ;; Reverse sort, get msg with min time
           (- (:time y) (:time x)))
         msg-datas))

(defn newest-msg
  "Find the newest message-data in a coll of them."
  [msg-datas]
  (max-1 (fn compare-times [x y]
           (- (:time x) (:time y)))
         msg-datas))

(defn log-messages
  "Log message datas to file and note the newest."
  [{:as session :keys [state]} msg-datas]
  (let [w (-> @state :logger :writer)]
    (binding [*out* w]
      (doseq [m msg-datas]
        (println (json/generate-string {:hl-type "message"
                                        :data m}))))
    (.flush w)))

(defn mark-latest-message
  "Given some new message-datas, potentially note one of them as the
newest message we've seen. Return the newest message value."
  [{:as session :keys [state]} msg-datas]
  (get-in
   (alter state
          (fn [oldstate]
            (let [prev-last-seen (-> oldstate :logger :last-seen-msg)
                  newest (newest-msg (concat msg-datas
                                             (when prev-last-seen
                                               [prev-last-seen])))]
              (assoc-in oldstate [:logger :last-seen-msg] newest))))
   [:logger :last-seen-msg]))

(defn mark-catchup-complete
  "Make a mark in the log indicating where we'll need to catch up to
next time."
  [{:as session :keys [state]} msg]
  (println "Marking catchup complete to" (:id msg))
  (let [w (-> @state :logger :writer)]
    (binding [*out* w]
      (println (json/generate-string {:hl-type "caughtup"
                                      :data msg})))
    (.flush w)))

(defn done-catching-up?
  "Have we encountered overlap with the previous catchup marker?"
  [{:as session :keys [state]} msg-datas]
  (let [catchup-to-id (-> @state :logger :catchup-to-id)]
    (contains? (set (map :id msg-datas))
               catchup-to-id)))

(defn do-send-event
  "Log a single new message to file."
  [session msg]
  (let [msg-datas [(get-in msg [:data])]]
    (log-messages session msg-datas)
    (mark-latest-message session msg-datas)))

(defn ask-for-backlog
  [{:as session :keys [state]} before-id]
  (println "Asking for backlog before" before-id)
  ;; TODO: Use max log request size of 1000, but implement backoff if
  ;; we get errors like:
  ;; WARNING: error in websocket client
  ;; io.netty.handler.codec.CorruptedFrameException: Max frame length of 65536 has been exceeded.
  (send-message session
                {:type "log"
                 :data {:n 100
                        :before before-id}}))

(defn do-backlog-events
  "Log an entire snapshot or log reply to file."
  [session msg]
  ;; TODO: Check if lifecycle is actually :catchup before proceeding?
  (let [recent-log (get-in msg [:data :log])]
    (log-messages session recent-log)
    (let [newest (mark-latest-message session recent-log)]
      (if (or (empty? recent-log)
              (done-catching-up? session recent-log))
        (do (println "All caught up, no more backlog. Now just tailing.")
            (alter (:state session)
                   assoc-in [:logger :lifecycle] :tailing)
            (mark-catchup-complete session newest))
        (ask-for-backlog session (:id (oldest-msg recent-log)))))))

(defn do-before-halt
  "Actions to take on halt."
  [{:as session :keys [state]} _msg]
  ;; Only mark newer messages as caughtup if log catchup finished
  (when (= (-> @state :logger :lifecycle) :tailing)
    (mark-catchup-complete session (-> @state :logger :last-seen-msg)))
  (some-> @state :logger :writer .close))

(def logger-dispatch
  "Reactive dispatch overlay for logger."
  (merge
   (base-dispatch "hillbot")
   {"send-event"
    #'do-send-event

    "snapshot-event"
    #'do-backlog-events

    "log-reply"
    #'do-backlog-events

    :unknown-type
    ;; (fn [session msg]
    ;;   (println "Unknown event type:" (:type msg)))
    do-nothing

    :pre
    #'debug-print-message

    :halt
    #'do-before-halt}))

(defn find-last-catchup-id
  "Find the ID of the last message we'd caught up to, or nil."
  [logfile]
  (with-open [rdr (io/reader logfile :encoding "UTF-8")]
    (->> (json/parsed-seq rdr true)
         (filter #(= (:hl-type %) "caughtup"))
         (last)
         (:data)
         (:id))))

(defn start-logger
  "Start logging and yield a session object."
  [server room logfile]
  (let [w (io/writer logfile :encoding "UTF-8" :append true)
        session (run server room
                     {:logger
                      {:lifecycle :catchup
                       ;; Last message ID from last session,
                       ;; used for backlog catchup
                       :catchup-to-id (find-last-catchup-id logfile)
                       ;; Last message from this session, used
                       ;; to mark place for next session's
                       ;; catchup.
                       :last-seen-msg nil
                       :path logfile
                       :writer w}}
                     logger-dispatch)]
    session))

(defn -main
  "Run logger and close it on shutdown. Arguments:

- server is the heim host (requires secure websocket protocol)
- room is the heim room (without ampersand)
- logfile is the file where logs will be written to (and read from for catchup)"
  [server room logfile]
  (let [session (start-logger server room logfile)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(halt session) "logger shutdown hook"))))
