## Introduction

A library for painlessly adding networking support to play-clj games. Try the [example game](https://github.com/oakes/play-clj-examples/tree/master/minicraft-online). It uses the publish-subscribe mechanism in [JeroMQ](https://github.com/zeromq/jeromq), a pure Java implementation of ZeroMQ.

To use it, you subscribe to "topics", which are simply keywords like `:test`. When you broadcast a message to a topic, anyone subscribed to that topic will receive it. Thus, it's a good idea to make the topic names unique, like `:hello-world-test`.

Note that there is no support for direct connections between peers; you can only broadcast messages to a topic. If you subscribe to a topic you broadcast to, you will receive your own packets.

## Getting Started (with play-clj)

1. Clone this project and run `lein run` to run the server
2. Create a new project with `lein new play-clj hello-world`
3. Modify `desktop/project.clj` to use these dependencies:
 - [play-clj "0.3.11-SNAPSHOT"]
 - [play-clj.net "0.1.0-SNAPSHOT"]
4. Modify `desktop/src-common/hello_world/core.clj` to look like this:

```clojure
(ns hello-world.core
  (:require [play-clj.core :refer :all]
            [play-clj.net :refer :all]
            [play-clj.ui :refer :all]))

(defscreen main-screen
  :on-show
  (fn [screen entities]
    (update! screen
             :renderer (stage)
             ; Creates a networking client that subscribes to the :hello-world-test topic.
             ; No addresses are specified, so it will use localhost.
             :network (client screen [:hello-world-test]))
    (label "Hello world!" (color :white)))
  
  :on-render
  (fn [screen entities]
    (clear!)
    (render! screen entities))
  
  ; Broadcasts a message every time you click the screen.
  ; You are not limited to strings. For example, you could broadcast a map
  ; of values like this: (broadcast! screen :hello-world-test {:x 10 :y 5})
  :on-touch-down
  (fn [screen entities]
    (broadcast! screen :hello-world-test "Hello internet!"))
  
  ; Runs when you receive a message for a topic you're subscribed to.
  :on-network-receive
  (fn [screen entities]
    (case (:topic screen)
      :hello-world-test
      (when (string? (:message screen))
        (label (:message screen) (color :white)))))
  
  ; Disconnects from the server when you switch away from the screen.
  :on-dispose
  (fn [screen entities]
    (disconnect! screen)))

(defgame hello-world
  :on-create
  (fn [this]
    (set-screen! this main-screen)))
```

If you want to try a public server instead of your local one, create your networking client like this:

```clojure
(client screen
        [:hello-world-test]
        "tcp://play-clj.net:4707"
        "tcp://play-clj.net:4708")
```

This will use a public server I am running. You are welcome to use it, but there are no guarantees about uptime. Running your own server is easy! Just clone this repo on a server, run `lein uberjar`, and then `java -jar target/play-clj.net-...-standalone.jar &`.

## Getting Started (without play-clj)

While this library was meant for play-clj, it doesn't require it.

1. Clone this project and run `lein run` to run the server
2. Create a new project with `lein new app hello-world`
3. Modify `desktop/project.clj` to use this dependency:
 - [play-clj.net "0.1.0-SNAPSHOT"]
4. Modify `src/hello_world/core.clj` to look like this:

```clojure
(ns hello-world.core
  (:require [play-clj.net :refer :all])
  (:gen-class))

(defn on-receive
  [topic message]
  (println "Received" topic message))

(defn -main
  [& args]
  (let [c (client on-receive [:hello-world-test])]
    (broadcast! c :hello-world-test "Hello internet!")))
```

## Licensing

All files that originate from this project are dedicated to the public domain. I would love pull requests, and will assume that they are also dedicated to the public domain.
