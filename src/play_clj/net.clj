(ns play-clj.net
  (:require [clojure.edn :as edn])
  (:import [org.zeromq ZContext ZMQ])
  (:gen-class))

(def ^:private server-send-address "tcp://localhost:4707")
(def ^:private server-receive-address "tcp://localhost:4708")
(def ^:private client-send-address server-receive-address)
(def ^:private client-receive-address server-send-address)
(def ^:private max-message-size 2048)

(defn ^:private throw-key-not-found
  [k]
  (throw (Exception. (str "The keyword " k " is not found."))))

(defn ^:private get-obj
  [obj & ks]
  (if (map? obj)
    (or (get-in obj ks)
        (get obj (last ks))
        (throw-key-not-found (last ks)))
    obj))

(defn ^:private get-bytes
  [k]
  (.getBytes (name k) ZMQ/CHARSET))

(defn ^:private read-edn
  [s]
  (try (edn/read-string s)
    (catch Exception _)))

(defn ^:private client-listen!
  [socket screen-or-fn]
  (loop []
    (let [topic (.recvStr socket)
          message (read-edn (.recvStr socket))]
      (when (and topic message)
        (if (map? screen-or-fn)
          (let [execute-fn! (get-obj screen-or-fn :execute-fn-on-gl!)
                options (get-obj screen-or-fn :options)]
            (execute-fn! (:on-network-receive options)
                         :topic (keyword topic)
                         :message message))
          (screen-or-fn (keyword topic) message))
        (recur)))))

(defn ^:private server-listen!
  [send-socket receive-socket]
  (loop []
    (let [[topic message] (read-edn (.recvStr receive-socket))]
      (when (and topic message)
        (.sendMore send-socket (name topic))
        (.send send-socket (pr-str message))))
    (recur)))

(defn ^:private subscribe!
  [client topics]
  (doseq [t topics]
    (.subscribe (get-obj client :network :receiver) (get-bytes t))))

(defn disconnect!
  [client]
  (let [context (get-obj client :network :context)]
    (.destroySocket context (get-obj client :network :sender))
    (.destroySocket context (get-obj client :network :receiver)))
  (.interrupt (get-obj client :network :thread)))

(defn broadcast!
  [client topic message]
  (let [encoded-message (pr-str [topic message])
        message-size (count (.getBytes encoded-message))]
    (if (> message-size max-message-size)
      (throw (Exception. (str "Message is too large to broadcast: "
                              message-size
                              " > "
                              max-message-size)))
      (.send (get-obj client :network :sender) encoded-message)))
  nil)

(defn client
  ([screen-or-fn]
    (client screen-or-fn []))
  ([screen-or-fn topics]
    (client screen-or-fn topics client-send-address client-receive-address))
  ([screen-or-fn topics send-address receive-address]
    (let [context (ZContext.)
          push (.createSocket context ZMQ/PUSH)
          sub (.createSocket context ZMQ/SUB)]
      {:sender (doto push (.connect send-address))
       :receiver (doto sub (.connect receive-address) (subscribe! topics))
       :thread (doto (Thread. #(client-listen! sub screen-or-fn)) .start)
       :context context})))

(defn server
  ([]
    (server server-send-address server-receive-address))
  ([send-address receive-address]
    (let [context (ZContext.)
          pub (.createSocket context ZMQ/PUB)
          pull (.createSocket context ZMQ/PULL)]
      (.setMaxMsgSize pull max-message-size)
      {:sender (doto pub (.bind send-address))
       :receiver (doto pull (.bind receive-address))
       :thread (doto (Thread. #(server-listen! pub pull)) .start)
       :context context})))

(defn -main
  [& args]
  (server))
