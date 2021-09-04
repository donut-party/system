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

- Basic Usage
  - Components
  - Refs
  - Constant Instances
  - Signals
  - Systems
- Advanced Usage
  - Groups and local refs
  - stages
  - local and group refs
  - component-ids
  - before and after
  - "output"
  - validation
  - subsystems
  - config multimethod
- Purpose
  - Organization aid
  - Application startup and shutdown
  - Virtual environment
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

### Architecture aid

We make application code more understandable and maintainable by identifying a
system's responsibilities and organizing code around those responsibilities so
that they can be considered and developed in isolation - in other words,
implementing a system architecture.

It's not obvious how to do implement and convey your system's architecture in a
functional programming language like Clojure, where it's pretty much one giant
pool of functions, and boundaries (namespaces, marking functions private) are
more like swim lanes you can easily duck under than walls enforcing isolation.


donut.system allows you to codify your application's areas of responsibility.

donut.system helps you out by giving you a way to define components.

Just by having components has a subtle benefit.

### Application startup and shutdown

### Virtual environment

One of the challenges of building a non-trivial application with Clojure is 


- the challenge of having no boundaries
  - public or private
- managing interactions with the external world and with external services
- allocating and de-allocating resources in order
- supporting a REPL workflow




## Basic Usage

To use donut.system, you define a _system_ that contains _component
definitions_. A component definition can include _references_ to other
components and _signal handlers_ that specify behavior. 

Here's an example system that defines a `:printer` component and a `:stack`
component. When it receives the `:start` signal, the `:printer` pops an item off
the `:stack` and prints it once a second:

``` clojure
(ns donut.examples.printer
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:services {:stack {:start (fn [{:keys [items]} _ _] (atom (vec (range items))))
                       :stop  (fn [_ instance _] (reset! instance []))
                       :items 10}}
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


(def Stack
  {:start (fn [_ _ _] (atom (vec (range 10))))
   :stop  (fn [_ instance _] (reset! instance []))})
```

In this example, you define `system`, a map that contains just one key,
`::ds/defs`. `::ds/defs` is a map of _component groups_, of which there are two:
`:services` and `:app`. The `:services` group has one component definition for a
`:stack` component, and the `:app` group has one component definition for a
`:printer` component. (`:app` and `:services` are arbitrary names with no
special meaning; you can name groups whatever you want.)

Both component definitions contain `:start` and `:stop` signal handlers, and the
`:printer` component definition contains a _ref_ to the `:stack` component.

You start the system by calling `(ds/signal system :start)` - this produces an
updated system map, `running-system`, which you then use when stopping the
system with `(ds/signal running-system :stop)`.

### Components

Components have _definitions_ and _instances._

A component definition (_component def_ or just _def_ for short) is an entry in
the `::ds/defs` map of a system map. A component definition can be a map, as
this system with a single component definition shows:

``` clojure
(def Stack
  {:start (fn [{:keys [items]} _ _] (atom (vec (range items))))
   :stop  (fn [_ instance _] (reset! instance []))
   :items 10})

(def system {::ds/defs {:services {:stack Stack}}})
```

Components are organized under _component groups_. I cover some interesting
things you can do with groups below, but for now you can just consider them an
organizational aid. This system map includes the component group `:services`.

Note that there's no special reason to break out the `Stack` component
definition into a top-level var. I just thought it would make the example more
readable.

A def map can contain signal handlers, which are used to create component
_instances_ and implement component _behavior_. A def can also contain
additional configuration values that will get passed to the signal handlers.

In the example above, we've defined `:start` and `:stop` signal handlers. Signal
handlers are just functions with three arguments. The first argument is the
component definition itself: you can see that the `:start` handler destructures
`items` out of its first argument. The value of `items` will be `10`.

This approach to defining components lets us easily modify them. If you want to
mock out a component, you just have to use `assoc-in` to assign a new `:start`
signal handler.

Signal handlers return a _component instance_, which is stored in the system map
under `::ds/instances`. Try this to see a system's instances:

``` clojure
(::ds/instances (ds/signal system :start))
```

Component instances are passed as the second argument to signal handlers. When
you `:start` a `Stack` it creates a new atom, and when you `:stop` it the atom
is passed in as the second argument to the `:stop` signal handler.

This is how you can allocate and deallocate the resources needed for your
system: the `:start` handler will create a new object or connection or thread
pool or whatever, and place that under `::ds/instances`. The instance is passed
to the `:stop` handler, which can call whatever functions or methods are needed
to to deallocate the resource.

You don't have to define a handler for every signal. Components that don't have
a handler for a signal are essentially skipped.

### Refs

Component defs can contains _refs_, references to other components that resolve
to that component's instance when signal handlers are called. Let's look at our
stack printer again:

``` clojure
(def system
  {::ds/defs
   {:services {:stack {:start (fn [{:keys [items]} _ _] (atom (vec (range items))))
                       :stop  (fn [_ instance _] (reset! instance []))
                       :items 10}}
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
```

The last line includes `:stack (ds/ref [:services :stack])`. `ds/ref` is a
function that returns a `Ref`, a little record that identifies another
component. Components are identified with tuples of `[group-name
component-name]`.

These refs are used to determine the order in which signals are applied to
components. Since the `:printer` refers to the `:stack`, we know that it depends
on a `:stack` instance to function correctly. Therefore, when we send a
`:start` signal, it's handled by `:stack` before `:printer.`

The `:printer` component's `:start` signal handle destructures `stack` from its
first argument. It's value is the `:stack` component's instance, the atom that
gets created by `:stack`'s `:start` signal handler.

### Constant Instances

Component defs don't have to be maps. If you use a non-map value, that value is
considered to be the component's instance. This can be useful for configuration.
Consider this system:

``` clojure
(ns donut.examples.ring
  (:require [donut.system :as ds]
            [ring.adapter.jetty :as rj]))

(def system
  {::ds/defs
   {:env  {:http-port 8080}
    :http {:server  {:start   (fn [{:keys [handler options]} _ _]
                                (rj/run-jetty handler options))
                     :stop    (fn [_ instance _]
                                (.stop instance))
                     :handler (ds/ref :handler)
                     :options {:port  (ds/ref [:env :http-port])
                               :join? false}}
           :handler (fn [_req]
                      {:status  200
                       :headers {"ContentType" "text/html"}
                       :body    "It's donut.system, baby!"})}}})
```

The component `[:env :http-port]` is defined as the value `8080`. It's referred
to by the `[:http :server]` component. When the `[:http :server]`'s `:start`
handler is applied, it destructures `options` from its first argument. `options`
will be the map `{:port 8080, join? false}`.

This is just a little bit of sugar to make it easier to work with donut.system.
It would be annoying and possibly confusing to have to write something like

``` clojure
(def system
  {::ds/defs
   {:env {:http-port {:start (constantly 8080)}}}})
```

### Signals

We've seen how you can specify signal handlers for components, but what is a
signal? The best way to understand them is behaviorally: when you call the
`ds/signal` function on a system, then each component's signal handler gets
called in the correct order. I had to pick some to convey the idea of "make all
the components do a thing", and signal handling seemed like a good metaphor.

Using the term "signal" could be misleading, though, in that it implies some
kind of communication medium: a socket, a semaphor, an interrupt. That's not the
case. Internally, it's all just plain ol' function calls. If I talk about
"sending" a signal, nothing's actually being sent. And anyway, even if something
were getting sent, that shouldn't matter to you in using the library; it would
be an implementation detail that should be transparent to you.

donut.system provides some sugar for built in functions: instead of calling
`(ds/signal system :start)` you can call `(ds/start system)`.

### Custom Signals

There's a more interesting reason for the use of _signal_, though: I want signal
handling to be extensible. Out of the box, donut.system recognizes `:start`,
`:stop`, `:suspend`, and `:resume`, but it's possible to handle
additional signals -- maybe `:validate` or `:status`. To do that, you just need
to add a little configuration to your system:

``` clojure
(def system
  {::ds/defs    {;; components go here
                 }
   ::ds/signals {:status   {:order :topsort}
                 :validate {:order :reverse-topsort}}})
```

`::ds/signals` is a map where keys are signal names are maps are configuration.
There's only one configuration option, `:order`, and the values can be
`:topsort` or `:reverse-topsort`. This specifies the order that components'
signal handlers should be called. `:topsort` means that if Component A refers to
Component B, then Component A's handler will be called first; reverse is, well,
the reverse.

The map you specify under `::ds/signals` will get merged with the default signal
map:

``` clojure
(def default-signals
  "which graph to follow to apply signal"
  {:start   {:order :reverse-topsort}
   :stop    {:order :topsort}
   :suspend {:order :topsort}
   :resume  {:order :reverse-topsort}})
```

### Systems

Systems organize components and provide a systematic way of initiating component
behavior. You send a signal to a system, and the system ensures its components
handle the signal in the correct order.

As you've seen, systems are implemented as maps. I sometimes refer to these maps
as _system maps_ or _system states_. It can be useful, for example, to think of
`ds/signal` as taking a system state as an argument and returning a new state.

donut.system follows a pattern that you might be used to if you've used
interceptors: it places as much information as possible in the system map and
uses that to drive execution. This lets us do cool and useful stuff like define
custom signals.

One day I'd like to write more about the advantages of taking the "world in a
map" approach. In the mean time, [this Lambda Island blog post on Coffee
Grinders](https://lambdaisland.com/blog/2020-03-29-coffee-grinders-2) does a
good job of explaining it.

## Advanced Usage

The topics covered so far should let you get started defining components and
systems in your own projects. donut.system can also handle more complex use
cases.

### Groups and local refs

All component definitions are organized into groups. As someone who compulsively
lines up pens and straightens stacks of brochures, I think this extra level of
tidiness is inherently good and needs no further explanation. The inclusion of
component groups unlocks some useful capabilities that are less obvious, though,
so let's talk about those. Component groups make it easier to:

- Create multiple instances of a component
- Send signals to sub-selections of components
- Designate system stages

I'll describe what I mean by "multiple instances" here, and I'll explain the
rest in later sections.

Let's say for some reason you want to run multiple HTTP servers. Here's how you
could do that:

``` clojure
(ns donut.examples.multiple-http-servers
  (:require [donut.system :as ds]
            [ring.adapter.jetty :as rj]))


(def HTTPServer
  {:start   (fn [{:keys [handler options]} _ _]
              (rj/run-jetty handler options))
   :stop    (fn [_ instance _]
              (.stop instance))
   :handler (ds/ref :handler)
   :options {:port  (ds/ref :port)
             :join? false}})

(def system
  {::ds/defs
   {:http-1 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 1"})
             :port    8080}

    :http-2 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 2"})
             :port    9090}}})
```

First, we define the component `HTTPServer`. Notice that it has two refs,
`(ds/ref :handler)` and `(ds/ref :port)`. These differ from the refs you've seen
so far, which have been tuples of `[group-name component-name]`. Refs of the
form `(ds/ref component-name)` are _local refs_, and will resolve to the
component of the given name within the same group.

You could create multiple instances of an HTTP server without groups, sure, but
it would be more tedious and typo-prone. The fact is, some components actually
are part of a group, so it makes sense to have first-class support for groups.

### Selecting components

You can select parts of a system to send a signal to:

``` clojure
(let [running-system (ds/signal system :start #{[:group-1 :component-1]
                                                [:group-1 :component-2]})]
  (ds/signal running-system :stop))
```

First, we call `ds/start` and pass it an optional second argument, a set of
_selected components_ This will filter out all components that aren't
descendants of `[:group-1 :component-1]` or `[:group-2 :component-2]` and send
the `:start` signal only to them.

Your selection is stored in the system state that gets returned, so when you
call `(ds/stop running-system)` it only sends the `:stop` signal to the
components that had received the `:start` signal.

You can also select component groups by using just the group's name for your
selection, like so:

``` clojure
(ds/signal system :start #{:group-1})
```

### Stages

It might be useful to signal parts of your system in stages. For example, you
might want to instantiate a logger and error reporter and use those if an
exception is thrown when starting other components:

``` clojure
;; This is mostly pseudocode
(def system
  {::ds/defs
   {:boot {:logger         {:start ...
                            :stop  ...}
           :error-reporter {:start ...
                            :stop  ...}}
    :app  {:server {:start ...}}}})

(let [booted-system  (ds/signal system :start #{:boot})
      logger         (get-in booted-system [::ds/instances :boot :logger])
      error-reporter (get-in booted-system [::ds/instances :boot :error-reporter])]
  (try (ds/signal booted-system :start)
       (catch Exception e
         (log logger e)
         (report-error error-report e))))
```

Note that you would need to make the `:start` handlers for `:logger` and
`:error-reporter` _idempotent_, meaning that calling `:start` on an
already-started component should not create a new instance but use an existing
one. The code would look something like this:

``` clojure
(fn [config instance _]
  (or instance
      (create-logger config)))
```

### Before, After, "Output", and ::base

You can define `:before-` and `:after-` handlers for signals:

``` clojure
(def system
  {::ds/defs
   {:app {:server {:before-start (fn [_ _ _] (prn "before-start"))
                   :start        (fn [_ _ _] (prn "start"))
                   :after-start  (fn [_ _ _] (prn "after-start"))}}}})
```

### Validation

### Subsystems

### Named configs

## How it compares to alternatives

## Other options

- [Integrant](https://github.com/weavejester/integrant)
- [mount](https://github.com/tolitius/mount)
- [Component](https://github.com/stuartsierra/component)
- [Clip](https://github.com/juxt/clip)

## TODO

- REPL tools
- async signal handling
