(ns play-clj.net
  (:require [play-clj.net-utils :as u])
  (:import [org.zeromq ZContext ZMQ ZMQ$Socket])
  (:gen-class))

(defn ^:private client-listen!
  [^ZMQ$Socket socket screen]
  (try
    (loop []
      (let [topic (.recvStr socket)
            message (u/read-edn (.recvStr socket))]
        (when (and topic message)
          (if (map? screen)
            (let [execute-fn! (u/get-obj screen :execute-fn-on-gl!)
                  options (u/get-obj screen :options)]
              (execute-fn! (:on-network-receive options)
                           :topic (keyword topic)
                           :message message))
            (screen (keyword topic) message))
          (recur))))
    (catch Exception _)))

(defn ^:private server-listen!
  [^ZMQ$Socket send-socket ^ZMQ$Socket receive-socket]
  (loop []
    (let [[topic message] (u/read-edn (.recvStr receive-socket))]
      (when (and topic message)
        (.sendMore send-socket (name topic))
        (.send send-socket (pr-str message))))
    (recur)))

(defn subscribe!
  "Subscribes the client the given `topics`. The `screen` is a play-clj screen
map that contains a client hash map associated with the :network key.

    (subscribe! screen :my-game-level-2)"
  [screen & topics]
  (doseq [t topics]
    (.subscribe (u/get-obj screen :network :receiver) (u/str->bytes (name t)))))

(defn unsubscribe!
  "Unsubscribes the client the given `topics`. The `screen` is a play-clj screen
map that contains a client hash map associated with the :network key.

    (unsubscribe! screen :my-game-level-2)"
  [screen & topics]
  (doseq [t topics]
    (.unsubscribe (u/get-obj screen :network :receiver) (u/str->bytes t))))

(defn disconnect!
  "Closes the sockets and interrupts the receiver thread. The `screen` is a
play-clj screen map that contains a client hash map associated with the :network
key.

    (let [screen (update! screen :network (client screen))]
      (disconnect! screen))"
  [screen]
  ; destroy the send and receive sockets
  (doto (u/get-obj screen :network :context)
    (.destroySocket (u/get-obj screen :network :sender))
    (.destroySocket (u/get-obj screen :network :receiver)))
  ; stop the thread
  (.interrupt (u/get-obj screen :network :thread))
  ; remove from the networks atom if it exists
  (some-> u/*networks* (swap! dissoc (or (:network screen) screen)))
  nil)

(defn broadcast!
  "Sends a `message` with the connected server, to be broadcasted to all peers
subscribed to the `topic`. The `screen` is a play-clj screen map that contains a
client hash map associated with the :network key.

    (let [screen (update! screen :network (client screen [:my-game-position]))]
      (broadcast! screen :my-game-position {:x 10 :y 5}))"
  [screen topic message]
  (let [encoded-message (pr-str [topic message])
        message-size (count (u/str->bytes encoded-message))]
    (if (> message-size u/max-message-size)
      (throw (Exception. (str "Message is too large to broadcast: "
                              message-size " > " u/max-message-size)))
      (.send (u/get-obj screen :network :sender) encoded-message ZMQ/NOBLOCK)))
  nil)

(defn client
  "Returns a hash map containing sender and receiver sockets, both of which are
connected to the `send-address` and `receive-address`. The receiver socket is
also subscribed to the `topics`. The `screen` is a play-clj screen map, whose
:on-network-receive function will run when a message is received.

    (client screen [:my-game-position])
    (client screen [:my-game-position]
            \"tcp://localhost:4707\" \"tcp://localhost:4708\")"
  ([screen]
    (client screen []))
  ([screen topics]
    (client screen topics u/client-send-address u/client-receive-address))
  ([screen topics send-address receive-address]
    (let [context (ZContext.)
          push (.createSocket context ZMQ/PUSH)
          sub (.createSocket context ZMQ/SUB)
          c {:sender (doto push (.connect send-address))
             :receiver (doto sub
                         (.connect receive-address)
                         ((partial apply subscribe!) topics))
             :thread (doto (Thread. #(client-listen! sub screen)) .start)
             :context context}]
      ; add to the networks atom if it exists
      (some-> u/*networks* (swap! assoc c #(disconnect! c)))
      ; return client
      c)))

(defn server
  "Returns a hash map containing sender and receiver sockets, both of which are
bound to the `send-address` and `receive-address`.

    (server)
    (server \"tcp://*:4708\" \"tcp://*:4707\")"
  ([]
    (server u/server-send-address u/server-receive-address))
  ([send-address receive-address]
    (let [context (ZContext.)
          pub (.createSocket context ZMQ/PUB)
          pull (.createSocket context ZMQ/PULL)
          _ (.setMaxMsgSize pull u/max-message-size)
          s {:sender (doto pub (.bind send-address))
             :receiver (doto pull (.bind receive-address))
             :thread (doto (Thread. #(server-listen! pub pull)) .start)
             :context context}]
      ; add to the networks atom if it exists
      (some-> u/*networks* (swap! assoc s #(disconnect! s)))
      ; return server
      s)))

(defn ^:private -main
  [& args]
  (server))
