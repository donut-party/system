# donut.system

donut.system is a dependency injection library for Clojure and ClojureScript
that introduces *system* and *component* abstractions to:

- help you organize your application
- manage your application's (stateful) startup and shutdown behavior
- provide a light virtual environment for your application, making it easier to
  mock services for testing

``` clojure
;; deps.edn
{donut.system {:mvn/version "0.0.1"}}

;; lein
[donut.system "0.0.1"]

;; require
[donut.system :as ds]
```

**Table of Contents**

- Purpose
  - Organization aid
  - Application startup and shutdown
  - Virtual environment
- How to use it
  - Systems
  - Signals
  - Components
  - refs
  - Subsystems
  - Sugar
- How it compares to alternatives
- Implementation overview
- Other options

## Purpose

When building a non-trivial Clojure application you're faced with some problems
that don't have obvious solutions:

- How do I break this into smaller parts that can be considered in
  isolation?
- How do I fit those parts back together?
- How do I manage resources like database connections and thread pools?

donut.system helps you address these problems by giving you tools for defining
*components*, specifying their *behavior*, and composing them into *systems*.

### Organization aid



- Identify the areas of responsibility
- Demarcate their interfaces
- A single place to observe these relationships

### Application startup and shutdown

### Virtual environment

One of the challenges of building a non-trivial application with Clojure is 


- the challenge of having no boundaries
  - public or private
- managing interactions with the external world and with external services
- allocating and de-allocating resources in order
- supporting a REPL workflow




## How to use it

To use donut.system, you define _components_ that handle _signals_. These
component definitions refer to each other with _refs_ and live in a _system
map_. Here's an example system that defines a `:printer` component and a
`:stack` component. The `:printer` pops an item off the `:stack` and prints it
once a second:

``` clojure
(ns donut.examples.printer
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:services {:stack {:start (fn [_ _ _] (atom (vec (range 10))))
                       :stop  (fn [_ instance _] (reset! instance []))}}
    :app      {:printer {:start (fn [{:keys [stack]} _ _]
                                  (doto (Thread.
                                         (fn []
                                           (prn "peek:" (peek @stack))
                                           (swap! stack pop)
                                           (Thread/sleep 1000)
                                           (recur)))
                                    (.start)))
                         :stop  (fn [_ instance _]
                                  (.interrupt instance))
                         :stack (ds/ref [:services :stack])}}}})

;; start the system, let it run for 5 seconds, then stop it
(let [running-system (ds/signal system :start)]
  (Thread/sleep 5000)
  (ds/signal running-system :stop))
```

In this example, you define `system`, a map representing a sytem that contains
just one key, `::ds/defs`. `::ds/defs` is a map of _component groups_, of which
there are two: `:services` and `:app`. The `:services` group has one component
definition for a `:stack` component, and the `:app` group has one component
definition for a `:printer` component.

Both component definitions contain `:start` and `:stop` signal handlers, and the
`:printer` component definition contains a _ref_ to the `:stack` component.

You start the system by calling `(ds/signal system :start)` - this produces an
updated system map, `running-system`, which you then use when stopping the
system with `(ds/signal running-system :stop)`.

### Systems

When using donut.system, you produce behavior by sending _signals_ to a
_system_. 

### Components

### Refs

### Signal handling

### Sugar

## How it compares to alternatives

## Other options

- [Integrant](https://github.com/weavejester/integrant)
- [mount](https://github.com/tolitius/mount)
- [Component](https://github.com/stuartsierra/component)
- [Clip](https://github.com/juxt/clip)

## TODO

- REPL tools
- async signal application
