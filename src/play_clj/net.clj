(ns play-clj.net
  (:require [clojure.edn :as edn])
  (:import [org.zeromq ZContext ZMQ])
  (:gen-class))

(def ^:private server-send-address "tcp://localhost:4707")
(def ^:private server-receive-address "tcp://localhost:4708")
(def ^:private client-send-address server-receive-address)
(def ^:private client-receive-address server-send-address)

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
  [socket screen]
  (try
    (loop []
      (let [topic (.recvStr socket)
            message (read-edn (.recvStr socket))]
        (when (and topic message)
          (if (map? screen)
            (let [execute-fn! (get-obj screen :execute-fn-on-gl!)
                  options (get-obj screen :options)]
              (execute-fn! (:on-network-receive options)
                           :topic (keyword topic)
                           :message message))
            (screen (keyword topic) message))
          (recur))))
    (catch Exception _)))

(defn ^:private server-listen!
  [send-socket receive-socket]
  (loop []
    (let [[topic message] (read-edn (.recvStr receive-socket))]
      (when (and topic message)
        (.sendMore send-socket (name topic))
        (.send send-socket (pr-str message))))
    (recur)))

(defn subscribe!
  [socket & topics]
  (doseq [t topics]
    (.subscribe (or (get-obj socket :network :receiver) socket)
      (get-bytes t))))

(defn unsubscribe!
  [socket & topics]
  (doseq [t topics]
    (.unsubscribe (or (get-obj socket :network :receiver) socket)
      (get-bytes t))))

(defn disconnect!
  ([socket]
    (let [context (get-obj socket :network :context)]
      (disconnect! context (get-obj socket :network :sender))
      (disconnect! context (get-obj socket :network :receiver)))
    (.interrupt (get-obj socket :network :receiver-thread)))
  ([context socket]
    (.destroySocket context socket)
    nil))

(defn broadcast!
  [socket topic message]
  (.send (or (get-obj socket :network :sender) socket)
    (pr-str [topic message]))
  nil)

(defn client
  ([screen]
    (client screen []))
  ([screen topics]
    (client screen topics client-send-address client-receive-address))
  ([screen topics send-address receive-address]
    (let [context (ZContext.)
          push (.createSocket context ZMQ/PUSH)
          sub (.createSocket context ZMQ/SUB)]
      {:sender (doto push (.connect send-address))
       :receiver (doto sub
                   (.connect receive-address)
                   (#(apply subscribe! % topics)))
       :receiver-thread (doto (Thread. #(client-listen! sub screen)) .start)
       :context context})))

(defn server
  ([]
    (server server-send-address server-receive-address))
  ([send-address receive-address]
    (let [context (ZContext.)
          pub (.createSocket context ZMQ/PUB)
          pull (.createSocket context ZMQ/PULL)]
      {:sender (doto pub (.bind send-address))
       :receiver (doto pull (.bind receive-address))
       :receiver-thread (doto (Thread. #(server-listen! pub pull)) .start)
       :context context})))

(defn -main
  [& args]
  (server))
