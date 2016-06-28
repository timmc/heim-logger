(ns org.timmc.heim-logger.common
  "Common utilities and configuration between clients."
  (:require [clojure.string :as str])
  (:import (java.net URLEncoder)))

(defn pencode
  "Percent-encode for URLs (conservatively.)"
  [s]
  (str/replace (URLEncoder/encode s "UTF-8") "+" "%20"))

(defn address
  [server room]
  (format "wss://%s/room/%s/ws" server (pencode room)))
