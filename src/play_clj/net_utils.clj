(ns play-clj.net-utils
  (:require [clojure.edn :as edn])
  (:import [org.zeromq ZMQ]))

(def client-send-address "tcp://localhost:4707")
(def client-receive-address "tcp://localhost:4708")
(def server-send-address "tcp://*:4708")
(def server-receive-address "tcp://*:4707")
(def max-message-size 1024)

; misc

(defn throw-key-not-found
  [k]
  (throw (Exception. (str "The keyword " k " is not found."))))

(defn get-obj
  [obj & ks]
  (if (map? obj)
    (or (get-in obj ks)
        (get obj (last ks))
        (throw-key-not-found (last ks)))
    obj))

; encoding/decoding

(defn str->bytes
  [^String s]
  (.getBytes s ZMQ/CHARSET))

(defn read-edn
  [s]
  (try (edn/read-string s)
    (catch Exception _)))

; allow storing disconnect functions

(def ^:dynamic *networks* nil)

(defn track-networks!
  []
  (intern 'play-clj.net-utils '*networks* (atom {})))

(defn disconnect-networks!
  []
  (when *networks*
    (doseq [[network f] (deref *networks*)]
      (f))))
