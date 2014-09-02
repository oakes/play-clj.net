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

(defn ^:parse read-edn
  [s]
  (try (edn/read-string s)
    (catch Exception _)))

(def context (delay (ZContext.)))

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
  [socket]
  (if (map? socket)
    (do
      (disconnect! (get-obj socket :network :sender))
      (disconnect! (get-obj socket :network :receiver))
      (future-cancel (get-obj socket :network :receiver-thread)))
    (.destroySocket @context socket)))

(defn broadcast!
  [socket topic message]
  (.send (or (get-obj socket :network :sender) socket)
    (pr-str [topic message])))

(defn client-listen!
  [socket callback]
  (future (loop []
            (let [topic (.recvStr socket)
                  message (read-edn (.recvStr socket))]
              (when (and topic message)
                (if (map? callback)
                  (let [execute-fn! (get-obj callback :execute-fn-on-gl!)
                        options (get-obj callback :options)]
                    (execute-fn! (:on-receive options)
                                 :topic (keyword topic)
                                 :message message))
                  (callback (keyword topic) message))
                (recur))))))

(defn server-listen!
  [send-socket receive-socket]
  (future (loop []
            (let [[topic message] (read-edn (.recvStr receive-socket))]
              (when (and topic message)
                (.sendMore send-socket (name topic))
                (.send send-socket message)))
            (recur))))

(defn sender
  []
  (.createSocket @context ZMQ/PUSH))

(defn receiver
  []
  (.createSocket @context ZMQ/SUB))

(defn client
  ([screen topics]
    (client screen topics client-send-address client-receive-address))
  ([screen topics send-address receive-address]
    (let [push (sender)
          sub (receiver)]
      {:sender (doto push (.connect send-address))
       :receiver (doto sub
                   (.connect receive-address)
                   (#(apply subscribe! % topics)))
       :receiver-thread (client-listen! sub screen)})))

(defn server
  ([]
    (server server-send-address server-receive-address))
  ([send-address receive-address]
    (let [pub (.createSocket @context ZMQ/PUB)
          pull (.createSocket @context ZMQ/PULL)]
      {:sender (doto pub (.bind send-address))
       :receiver (doto pull (.bind receive-address))
       :receiver-thread (server-listen! pub pull)})))

(defn -main
  [& args]
  (server))
