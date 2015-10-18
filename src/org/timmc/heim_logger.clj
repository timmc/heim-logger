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

(def nick "hillbot")

(defn send-message
  [{:as session :keys [sender wc]} msg]
  (send sender (fn serial-send [_]
                 (doto wc (s/put! (json/generate-string msg)))))
  nil)

(defn auto-respond
  [{:as session :keys [state sender]} msg]
  (dosync
   (case (:type msg)
     "hello-event"
     (do (alter state assoc :whoami (-> msg :data :id))
         (send-message session {:type "nick" :data {:name nick}}))

     "ping-event"
     (send-message session
                   {:type "ping-reply"
                    :data {:time (-> msg :data :time)}})

     "snapshot-event"
     (alter state assoc :lifecycle :joined)

     "bounce-event"
     (alter state assoc :lifecycle :authing)

     "send-event"
     (println (format "%s \"%s\" said:\n%s"
                      (-> msg :data :sender :id)
                      (-> msg :data :sender :name)
                      (-> msg :data :content)))

     "join-event"
     (println (format "%s \"%s\" joined"
                      (-> msg :data :id)
                      (-> msg :data :name)))

     "part-event"
     (println (format "%s \"%s\" left"
                      (-> msg :data :id)
                      (-> msg :data :name)))

     "nick-event"
     (println (format "%s changed nick from \"%s\" to \"%s\""
                      (-> msg :data :id)
                      (-> msg :data :from)
                      (-> msg :data :to)))

     ("nick-reply")
     nil

     (prn msg))))

(defn react-to
  [{:as session :keys [wc state sender]}]
  (while true ;; (not= (:lifecycle @state) :end)
    (let [msg (json/parse-string @(s/take! wc) true)]
      (auto-respond session msg))))

(def initial-state
  {:lifecycle :handshake})

(defn run
  "Start a session and yield it as a map of:

- :state A ref of session state
- :wc Websocket client
- :sender An agent used to send messages in coordination with state changes"
  [server room]
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
                 :sender sender}
        reactor (future (try (react-to session)
                             (catch Throwable t
                               (.printStackTrace t)
                               (throw t))))]
    session))

;; TODO
#_      (when (:throttled msg)
        (println "Throttled, sleeping.")
        (Thread/sleep 300))

(defn -main
  "Demo connection to heim"
  [server room]
  (let [session (run server room)]
    ))
