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

(defn respond
  [state msg]
  (case (:type msg)
    "hello-event"
    [(assoc state :whoami (-> msg :data :id))]

    "ping-event"
    [state {:type "ping-reply"
            :data {:time (-> msg :data :time)}}]

    "snapshot-event"
    [(assoc state :lifecycle :joined)]

    "bounce-event"
    [(assoc state :lifecycle :authing)]

    [state]))

(defonce client
  (atom nil))

(defn spin
  [wc]
  (loop [state {:lifecycle :handshake}]
    (let [msg (json/parse-string @(s/take! wc) true)]
      (println "RECV" (:type msg))
      (when (:throttled msg)
        (println "Throttled, sleeping.")
        (Thread/sleep 300))
      (let [[state reply] (respond state msg)
            state (assoc state :last-msg msg)]
        (when reply
          (s/put! wc (json/generate-string reply)))
        (if (= (:lifecycle state) :joined)
          state
          (recur state))))))

(defn -main
  "Demo connection to heim"
  [server room]
  (let [url (address server room)
        wc (reset! client @(ah/websocket-client url))
        spinner (future (try (spin wc)
                             (catch Throwable t
                               (.printStackTrace t)
                               (throw t))))]
    spinner))
