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

(defn ^:private str->bytes
  [s]
  (.getBytes s ZMQ/CHARSET))

(defn ^:private read-edn
  [s]
  (try (edn/read-string s)
    (catch Exception _)))

(defn ^:private client-listen!
  [socket screen-or-fn]
  (try
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
          (recur))))
    (catch Exception _)))

(defn ^:private server-listen!
  [send-socket receive-socket]
  (try
    (loop []
      (let [[topic message] (read-edn (.recvStr receive-socket))]
        (when (and topic message)
          (.sendMore send-socket (name topic))
          (.send send-socket (pr-str message))))
      (recur))
    (catch Exception _)))

(defn subscribe!
  "Subscribes `client` the given `topics`. The `client` is a hash map returned
by the client function, or a play-clj screen map that contains a client hash map
associated with the :network key.

    (subscribe! screen :my-game-level-2)"
  [client & topics]
  (doseq [t topics]
    (.subscribe (get-obj client :network :receiver) (str->bytes (name t)))))

(defn unsubscribe!
  "Unsubscribes `client` the given `topics`. The `client` is a hash map returned
by the client function, or a play-clj screen map that contains a client hash map
associated with the :network key.

    (unsubscribe! screen :my-game-level-2)"
  [client & topics]
  (doseq [t topics]
    (.unsubscribe (get-obj client :network :receiver) (str->bytes t))))

(defn disconnect!
  "Closes the sockets and interrupts the receiver thread. The `client` is a hash
map returned by the client function, or a play-clj screen map that contains a
client hash map associated with the :network key.

    (let [screen (update! screen :network (client screen))]
      (disconnect! screen))"
  [client]
  (doto (get-obj client :network :context)
    (.destroySocket (get-obj client :network :sender))
    (.destroySocket (get-obj client :network :receiver)))
  (.interrupt (get-obj client :network :thread)))

(defn broadcast!
  "Sends a `message` with the connected server, to be broadcasted to all peers
subscribed to the `topic`. The `client` is a hash map returned by the client
function, or a play-clj screen map that contains a client hash map associated
with the :network key.

    (let [screen (update! screen :network (client screen [:my-game-position]))]
      (broadcast! screen :my-game-position {:x 10 :y 5}))"
  [client topic message]
  (let [encoded-message (pr-str [topic message])
        message-size (count (str->bytes encoded-message))]
    (if (> message-size max-message-size)
      (throw (Exception. (str "Message is too large to broadcast: "
                              message-size
                              " > "
                              max-message-size)))
      (.send (get-obj client :network :sender) encoded-message ZMQ/NOBLOCK)))
  nil)

(defn client
  "Returns a hash map containing sender and receiver sockets, both of which are
connected to the `send-address` and `receive-address` (localhost by default).
The receiver socket is also subscribed to the `topics`. The `screen-or-fn` is a
callback function taking two arguments, or a play-clj screen map (in which case,
the callback will be the screen's :on-network-receive function).

    (client screen [:my-game-position])
    (client screen [:my-game-position] \"tcp://localhost:4708\" \"tcp://localhost:4707\")"
  ([screen-or-fn]
    (client screen-or-fn []))
  ([screen-or-fn topics]
    (client screen-or-fn topics client-send-address client-receive-address))
  ([screen-or-fn topics send-address receive-address]
    (let [context (ZContext.)
          push (.createSocket context ZMQ/PUSH)
          sub (.createSocket context ZMQ/SUB)]
      {:sender (doto push (.connect send-address))
       :receiver (doto sub
                   (.connect receive-address)
                   (#(apply subscribe! % topics)))
       :thread (doto (Thread. #(client-listen! sub screen-or-fn)) .start)
       :context context})))

(defn server
  "Returns a hash map containing sender and receiver sockets, both of which are
bound to the `send-address` and `receive-address` (localhost by default).

    (server)
    (server \"tcp://localhost:4707\" \"tcp://localhost:4708\")"
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
