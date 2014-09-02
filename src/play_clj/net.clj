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
  [obj k]
  (if (map? obj)
    (or (get obj k)
        (throw-key-not-found k))
    obj))

(defn ^:private get-bytes
  [k]
  (.getBytes (name k) ZMQ/CHARSET))

(defn ^:parse recv-read
  [socket]
  (try (some-> (.recvStr socket) edn/read-string)
    (catch Exception _)))

(def context (delay (ZContext.)))
(def futures (atom {}))

(defn receiver?
  [socket]
  (= ZMQ/SUB (.getType socket)))

(defn sender?
  [socket]
  (= ZMQ/PUSH (.getType socket)))

(defn subscribe!
  [socket topic]
  (doseq [s (if (map? socket)
              (filter receiver? (get-obj socket :sockets))
              [socket])]
    (.subscribe s (get-bytes topic))))

(defn unsubscribe!
  [socket topic]
  (doseq [s (if (map? socket)
              (filter receiver? (get-obj socket :sockets))
              [socket])]
    (.unsubscribe s (get-bytes topic))))

(defn broadcast!
  [socket topic message]
  (doseq [s (if (map? socket)
              (filter sender? (get-obj socket :sockets))
              [socket])]
    (.send s (pr-str [topic message]))))

(defn disconnect!
  [socket]
  (doseq [s (if (map? socket)
              (get-obj socket :sockets)
              [socket])]
    (.destroySocket @context s)
    (when-let [f (get @futures s)]
      (future-cancel f)
      (swap! futures dissoc s))))

(defn server
  ([]
    (server server-send-address server-receive-address))
  ([server-send-address server-receive-address]
    (let [pub (.createSocket @context ZMQ/PUB)
          pull (.createSocket @context ZMQ/PULL)]
      (.bind pub server-send-address)
      (.bind pull server-receive-address)
      (loop []
        (let [[topic message] (recv-read pull)]
          (when (and topic message)
            (.sendMore pub (name topic))
            (.send pub message)))
        (recur)))))

(defn sender
  ([]
    (sender client-send-address))
  ([address]
    (doto (.createSocket @context ZMQ/PUSH)
      (.connect address))))

(defn receiver
  ([callback topics]
    (receiver callback client-receive-address topics))
  ([callback topics address]
    (let [socket (doto (.createSocket @context ZMQ/SUB)
                   (.connect address))]
      (doseq [t topics]
        (subscribe! socket t))
      (->> (loop []
             (let [topic (.recvStr socket)
                   message (recv-read socket)]
               (when (and topic message)
                 (if (map? callback)
                   (let [execute-fn! (get-obj callback :execute-fn-on-gl!)
                         options (get-obj callback :options)]
                     (execute-fn! (:on-receive options)
                                  :topic (keyword topic)
                                  :message message))
                   (callback (keyword topic) message))
                 (recur))))
           future
           (swap! futures assoc socket))
      socket)))

(defn -main
  [& args]
  (future (server))
  (subscribe! (receiver println [] server-send-address) :test1)
  (broadcast! (sender server-receive-address) :test1 "Test!"))
