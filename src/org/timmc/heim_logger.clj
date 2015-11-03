(ns org.timmc.heim-logger
  (:require [clojure.string :as str]
            [aleph.http :as ah]
            [manifold.stream :as s]
            [cheshire.core :as json]))

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
  (while true ;; (not= (:lifecycle @state) :end)
    (let [msg (json/parse-string @(s/take! wc) true)]
      (auto-respond session msg dispatch))))

(def initial-state
  {:lifecycle :handshake})

(defn run
  "Start a session and yield it as a map of:

- :state A ref of session state
- :wc Websocket client
- :sender An agent used to send messages in coordination with state changes"
  [server room dispatch]
  (let [url (address server room)
        wc @(ah/websocket-client url)
        state (ref initial-state)
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

;; TODO
#_      (when (:throttled msg)
        (println "Throttled, sleeping.")
        (Thread/sleep 300))

;; ============

(defn do-nothing
  [session msg])

(def logger-dispatch
  (merge
   (base-dispatch "hillbot")
   {"send-event"
    do-nothing

    "join-event"
    do-nothing

    "part-event"
    do-nothing

    "nick-event"
    do-nothing

    "nick-reply"
    do-nothing

    :pre
    (fn [session msg]
      (prn msg))

    :unknown-type
    (fn [session msg]
      (println "Unknown event type:" (:type msg)))}))

(defn -main
  "Demo connection to heim"
  [server room]
  (let [session (run server room logger-dispatch)]
    ))
