# donut.system

donut.system is a dependency injection library for Clojure and ClojureScript
that introduces *system* and *component* abstractions to:

- help you organize your application
- manage your application's startup and shutdown behavior
- provide a light virtual environment for your application, making it easier to
  mock services for testing

``` clojure
;; deps.edn
{club.donutpower/system {:mvn/version "0.0.118"}}

;; lein
[club.donutpower/system "0.0.112"]

;; require
[donut.system :as ds]
```

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**

- [Basic Usage](#basic-usage)
  - [Components](#components)
  - [Refs](#refs)
  - [Constant Instances](#constant-instances)
  - [Signals](#signals)
  - [Custom Signals](#custom-signals)
  - [Systems](#systems)
- [Advanced Usage](#advanced-usage)
  - [Groups and local refs](#groups-and-local-refs)
  - [Selecting components](#selecting-components)
  - [Stages](#stages)
  - [Before, After, Validation, and "Channels"](#before-after-validation-and-channels)
  - [::ds/base](#dsbase)
  - [Subsystems](#subsystems)
  - [Config helpers](#config-helpers)
- [Purpose](#purpose)
  - [Architecture aid](#architecture-aid)
  - [Resource management](#resource-management)
  - [Virtual environment](#virtual-environment)
  - [Framework foundation](#framework-foundation)
- [Objections](#objections)
- [Alternatives](#alternatives)
- [Why Use This and Not That?](#why-use-this-and-not-that)
- [Acknowledgments](#acknowledgments)
- [Status: 🤔](#status-)
- [TODO](#todo)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

## Basic Usage

To use donut.system, you define a _system_ that contains _component
definitions_. A component definition can include _references_ to other
components and _signal handlers_ that specify behavior. 

Here's an example system that defines a `:printer` component and a `:stack`
component. When the system receives the `:start` signal, the `:printer` pops an
item off the `:stack` and prints it once a second:

``` clojure
(ns donut.examples.printer
  (:require [donut.system :as ds]))

(def system
  {::ds/defs
   {:services {:stack {:start (fn [{:keys [items]} _ _]
                                (atom (vec (range items))))
                       :stop  (fn [_ instance _] (reset! instance []))
                       :conf  {:items 10}}}
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
                         :conf  {:stack (ds/ref [:services :stack])}}}}})

;; start the system, let it run for 5 seconds, then stop it
(let [running-system (ds/signal system :start)]
  (Thread/sleep 5000)
  (ds/signal running-system :stop))

```

In this example, you define `system`, a map that contains just one key,
`::ds/defs`. `::ds/defs` is a map of _component groups_, of which there are two:
`:services` and `:app`. The `:services` group has one component definition,
`:stack`, and the `:app` group has one component definition, `:printer`. (`:app`
and `:services` are arbitrary names with no special meaning; you can name groups
whatever you want.)

Both component definitions contain `:start` and `:stop` signal handlers, as well
as a `:conf`. The `:printer` component's `:conf` contains a _ref_ to the
`:stack` component.

You start the system by calling `(ds/signal system :start)`. This produces an
updated system map (bound to `running-system`) which you then use when stopping
the system with `(ds/signal running-system :stop)`.

### Components

Components have _definitions_ and _instances._

A component definition (_component def_ or just _def_ for short) is an entry in
the `::ds/defs` map of a system map. A component definition can be a map, as
this system with a single component definition shows:

``` clojure
(def Stack
  {:start (fn [{:keys [items]} _ _] (atom (vec (range items))))
   :stop  (fn [_ instance _] (reset! instance []))
   :conf  {:items 10})

(def system {::ds/defs {:services {:stack Stack}}})
```

Components are organized under _component groups_. I cover some interesting
things you can do with groups below, but for now you can just consider them an
organizational aid. This system map includes the component group `:services`.

(Note that there's no special reason to break out the `Stack` component
definition into a top-level var. I just thought it would make the example more
readable.)

A def map can contain _signal handlers_, which are used to create component
_instances_ and implement component behavior. A def can also contain additional
configuration values that will get passed to the signal handlers.

In the example above, we've defined `:start` and `:stop` signal handlers. Signal
handlers are just functions with three arguments. The first argument is the
value of `:conf`: you can see that the `:start` handler destructures `items` out
of its first argument. The value of `items` will be `10`.

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
pool or whatever, and place that in the system map under `::ds/instances`. The
instance is passed to the `:stop` handler, which can call whatever functions or
methods are needed to to deallocate the resource.

You don't have to define a handler for every signal. Components that don't have
a handler for a signal are essentially skipped when you send a signal to a
system.

### Refs

Component defs can contains _refs_, references to other components that resolve
to that component's instance when signal handlers are called. Let's look at our
stack printer again:

``` clojure
(def system
  {::ds/defs
   {:services {:stack {:start (fn [{:keys [items]} _ _]
                                (atom (vec (range items))))
                       :stop  (fn [_ instance _] (reset! instance []))
                       :conf  {:items 10}}}
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
                         :conf  {:stack (ds/ref [:services :stack])}}}}})
```

The last line includes `:stack (ds/ref [:services :stack])`. `ds/ref` is a
function that returns a vector of the form `[:donut.system/ref component-key]`,
where `component-key` is a vector of the form `[group-name component-name]`.

These refs are used to determine the order in which signals are applied to
components. Since the `:printer` refers to the `:stack`, we know that it depends
on a `:stack` instance to function correctly. Therefore, when we send a
`:start` signal, it's handled by `:stack` before `:printer.`

The `:printer` component's `:start` signal handle destructures `stack` from its
first argument. Its value is the `:stack` component's instance, the atom that
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
    :http {:server  {:start (fn [{:keys [handler options]} _ _]
                                (rj/run-jetty handler options))
                     :stop  (fn [_ instance _]
                                (.stop instance))
                     :conf  {:handler (ds/ref :handler)
                             :options {:port  (ds/ref [:env :http-port])
                                       :join? false}}}
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
called in the correct order. I needed to convey the idea of "make all the
components do a thing", and signal handling seemed like a good metaphor.

Using the term "signal" could be misleading, though, in that it implies the use
of a communication primitive like a socket, a semaphor, or an interrup. That's
not the case. Internally, it's all just plain ol' function calls. If I talk
about "sending" a signal, nothing's actually being sent. And anyway, even if
something were getting sent, that shouldn't matter to you in using the library;
it would be an implementation detail that should be transparent to you.

donut.system provides some sugar for built-in signals: instead of calling
`(ds/signal system :start)` you can call `(ds/start system)`.

### Custom Signals

There's a more interesting reason for the use of _signal_, though: I want signal
handling to be extensible. Other component libraries use the term _lifecycle_,
which I think doesn't convey the sense of extensibility that's possible with
donut.system.

Out of the box, donut.system recognizes `:start`, `:stop`, `:suspend`, and
`:resume` signals, but it's possible to handle arbitrary signals -- say,
`:validate` or `:status`. To do that, you just need to add a little
configuration to your system:

``` clojure
(def system
  {::ds/defs    {;; components go here
                 }
   ::ds/signals {:status   {:order :topsort}
                 :validate {:order :reverse-topsort}}})
```

`::ds/signals` is a map where keys are signal names and values are configuration
maps. There's only one configuration key, `:order`, and the value can be
`:topsort` or `:reverse-topsort`. This specifies the order that components'
signal handlers should be called. `:topsort` means that if Component A refers to
Component B, then Component A's handler will be called first; reverse is, well,
the reverse.

The map you specify under `::ds/signals` will get merged with the default signal
map, which is:

``` clojure
(def default-signals
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
tidiness is inherently good and needs no further explanation.

The inclusion of component groups unlocks some useful capabilities that are less
obvious, though, so let's talk about those. Component groups make it easier to:

- Create multiple instances of a component
- Send signals to selections of components
- Designate system stages

I'll describe what I mean by "multiple instances" here, and I'll explain the
rest in later sections.

Let's say for some reason you want to run multiple HTTP servers. Here's how you
could do that:

``` clojure
(ns donut.examples.multiple-http-servers
  (:require
   [donut.system :as ds]
   [ring.adapter.jetty :as rj]))


(def HTTPServer
  {:start (fn [{:keys [handler options]} _ _]
            (rj/run-jetty handler options))
   :stop  (fn [_ instance _]
            (.stop instance))
   :conf  {:handler (ds/ref :handler)
           :options {:port  (ds/ref :port)
                     :join? false}}})

(def system
  {::ds/defs
   {:http-1 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 1"})
             :conf    {:port 8080}}

    :http-2 {:server  HTTPServer
             :handler (fn [_req]
                        {:status  200
                         :headers {"ContentType" "text/html"}
                         :body    "http server 2"})
             :conf    {:port 9090}}}})
```

First, we define the component `HTTPServer`. Notice that it has two refs,
`(ds/ref :handler)` and `(ds/ref :port)`. These differ from the refs you've seen
so far, which have been tuples of `[group-name component-name]`. Refs of the
form `(ds/ref component-name)` are _local refs_, and will resolve to the
component of the given name within the same group.

This little sprinkling of abstraction creates more possibilities for component
modularity and reuse. You could create multiple instances of an HTTP server
without groups, sure, but it would be more tedious and typo-prone. The fact is,
some components actually are part of a group, so it makes sense to have
first-class support for groups.

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

### Before, After, Validation, and "Channels"

You can define `:before-` and `:after-` handlers for signals:

``` clojure
(def system
  {::ds/defs
   {:app {:server {:before-start (fn [_ _ _] (prn "before-start"))
                   :start        (fn [_ _ _] (prn "start"))
                   :after-start  (fn [_ _ _] (prn "after-start"))}}}})
```

You can use these _lifecycle functions_ to gather information about your system
as it handles signals, and to perform validation. Let's look at a couple use
cases: printing signal progress and validating configs.

Here's how you might print signal progress:

``` clojure
(defn print-progress
  [_ _ {:keys [::ds/component-id]}]
  (prn component-id))

(def system
  {::ds/defs
   {:group {:component-a {:start       "component a"
                          :after-start print-progress}
            :component-b {:start       "component b"
                          :after-start print-progress}}}})

(ds/signal system :start)
;; =>
[:group :component-a]
[:group :component-b]
```

The function `print-progress` is used as the `:after-start` handler for both
`:component-a` and `:component-b`. It destructures `::ds/component-id` from the
third argument and prints it. 

We haven't seen the third argument used before; its value is the system map. The
current component's id gets assoc'd into the system map under
`::ds/component-id` prior to calling a signal handler.

The system map also has a collection of "channel" functions merged into it which
we can use to gather information about components and perform validation. Look
at how we destructure `->info` and `->validation` from the third argument in
these `:after-start` handlers:

``` clojure
(def system
  {::ds/defs
   {:group {:component-a {:start       "component a"
                          :after-start (fn [_ _ {:keys [->info]}]
                                         (->info "component a is valid"))}
            :component-b {:start       "component b"
                          :after-start (fn [_ _ {:keys [->validation]}]
                                         (->validation "component b is invalid"))
                          ;; This `:conf` is only here to create the dependency
                          ;; order for demonstration purpose
                          :conf        {:ref (ds/ref :component-a)}}
            :component-c {:start       "component-c"
                          :after-start (fn [_ _ _]
                                         (prn "this won't print"))
                          ;; This `:conf` is only here to create the dependency
                          ;; order for demonstration purpose
                          :conf        {:ref (ds/ref :component-b)}}}}})

(::ds/out (ds/signal system :start))
;; =>
{:info       {:group {:component-a "component a is valid"}},
 :validation {:group {:component-b "component b is invalid"}}}
```

Notice that `:component-c`'s `:after-start` handler doesn't get called. As it
predicts, the string "this won't print" doesn't get printed.

It's not obvious what's going on here, so let's step through it.

1. `:component-a`'s `:after-start` gets called first. It destructures the
   `->info` function out of the third argument. `->info` is a _channel function_
   and its purpose is to allow signal handlers to place a value somewhere in the
   system map in a convenient and consistent way. `->info` assoc'd into the
   system map before a signal handler is called, and it closes is over the
   "output path", which includes the current component id. This is why when you
   call `(->info "component a is valid")`, the string `"component a is valid"`
   ends up at the path `[::ds/out :info :group :component-a]`.
2. `(->info "component a is valid")` returns a system map, and that updated
   system map is conveyed forward to other components' signal handlers, until a
   final system map is returned by `ds/signal`.
   
   But what if you want to use `:after-start` to perform a side effect? What
   then?? Do these functions always have to return a system map?
   
   No. The rules for handling return values are:
   
   1. If a system map is returned, convey that forward
   2. Otherwise, if this is a _lifecycle function_ (`:before-start` or `:after-start`)
      ignore the return value
   3. Otherwise, this is a signal handler (`:start`). Place its return value
      under `::ds/instances`.
3. `(->validation "component b is invalid")` is similar to `->info` in that it
   places a value in the system map. However, it differs in that it also has
   implicit control flow semantics: if at any point a value is placed under
   `[::ds/out :validation]`, then the library will stop trying to send signals
   to that component's descendants. (It's actually a little more nuanced than
   that, and I cover those nuances below.)

One way you could make use of these features is to write something like this:

``` clojure
(ns donut.examples.validate
  (:require
   [donut.system :as ds]
   [malli.core :as m]))

(defn validate-conf
  [conf _ {:keys [->validation ::ds/component-def]}]
  (let [schema (:schema component-def)]
    (when-let [errors (and schema (m/explain schema conf))]
      (->validation errors))))

(def system
  {::ds/defs
   {:group {:component-a {:before-start validate-conf
                          :start        "component a"
                          :schema       [:map [:foo any?] [:baz any?]]}
            :component-b {:before-start validate-conf
                          :start        "component b"
                          :schema       [:map [:foo any?] [:baz any?]]}
            :component-c {:start "component-c"}}}})
```

We can create a generic `validate-component` function that checks whether a
component's definition contains a `:schema` key, and use that to validate the
rest of the component definition.

### ::ds/base

You can add `::ds/base` key to a system map to define a "base" component
definition that will get merged with the rest of your component defs. The last
example could be rewritten like this:

``` clojure
(ns donut.examples.validate
  (:require
   [donut.system :as ds]
   [malli.core :as m]))

(defn validate-conf
  [conf _ {:keys [->validation ::ds/component-def]}]
  (let [schema (:schema component-def)]
    (when-let [errors (and schema (m/explain schema conf))]
      (->validation errors))))

(def system
  {::ds/base {:before-start validate-conf}
   ::ds/defs
   {:group {:component-a {:start  "component a"
                          :schema [:map [:foo any?] [:baz any?]]}
            :component-b {:start  "component b"
                          :schema [:map [:foo any?] [:baz any?]]}
            :component-c {:start "component-c"}}}})
```

### Subsystems

Woe be unto you if you ever have to compose a system from subsystems. But if you
do, I've tried to make it straightforward. Check it out:

``` clojure
(ns donut.examples.subsystem
  (:require [donut.system :as ds]))

(defn mk-print-thread
  [prefix stack]
  (doto (Thread.
         (fn []
           (prn prefix (peek @stack))
           (swap! stack pop)
           (Thread/sleep 1000)
           (recur)))
    (.start)))

(defn print-worker-system
  [print-prefix]
  {::ds/defs
   {:workers
    {:print-worker {:start (fn [{:keys [stack]} _ _]
                             (mk-print-thread print-prefix stack))
                    :stop  (fn [_ instance _]
                             (.stop instance))
                    :conf  {:stack (ds/ref [:services :stack])}}}}})

(def system
  {::ds/defs
   {:services {:stack {:start (fn [_ _ _] (atom (vec (range 20))))
                       :stop  (fn [_ instance _] (reset! instance []))}}

    :printers {:printer-1 (ds/subsystem-component
                           (print-worker-system ":printer-1")
                           #{(ds/group-ref :services)})
               :printer-2 (ds/subsystem-component
                           (print-worker-system ":printer-2")
                           #{(ds/ref [:services :stack])})}}})
```

In this example, we're creating two subsystems (`[:printers printer-1]` and
`[:printers :printer-2]`) that pop items from a shared stack component defined
in the parent system, `[:services :stack]`.

We generate definitions for the subsystems with the function
`print-worker-system`, which returns a system definition with one component,
`[:workers :print-worker]`. The component def has a key, `:stack`, which
references `[:services :stack]`, but notice that there is no `[:services
:stack]` component in the `print-worker-system` definition.

The parent system wraps these subsystems with a call to
`ds/subsystem-component`. `ds/subsystem-component` returns a component def, a
map with a `:start` signal handler that "forwards" the signal to the subsystem.
The component def also includes the key `::ds/mk-signal-handler`, a privileged
key that acts as default signal handler. `::ds/mk-signal-handler` is responsible
for forwarding all other signals to the subsystem.

`ds/subsystem-component` takes an optional second argument, a set of refs that
should be imported into the subsystem. This is how the subsystems can reference
the parent system's component `[:services :stack]`.

### Config helpers

`donut.system/named-system` is a multimethod you can use to register system
maps. This can be useful for defining dev, test, and prod systems:

``` clojure
(defmethod ds/named-system :test
  [_]
  {::ds/defs ...})
```

Often you'll want to customize a config; you'll want to replace a component with
a mock, for example. You can use the function `ds/system` to merge a registered
system map with overrides:

``` clojure
(ds/system :test {[:services :queue] mock-queue})
```

Overrides are a map where keys are _def paths_, and values are whatever value
you want to be assoc'd in to that path under `::ds/defs`. The above code is
equivalent to this:

``` clojure
(update (ds/named-system :test)
        ::ds/defs
        (fn [defs]
          (reduce-kv (fn [new-defs path val]
                       (assoc-in new-defs path val))
                     defs
                     {[:services :queue] mock-queue})))
```

The signal helpers `ds/start`, `ds/stop`, `ds/suspend`, and `ds/resume` can take
either a system name or a system map, and can take optional overrides:

``` clojure
(ds/start :test) ;; <- system name
(ds/start {::ds/defs ...}) ;; <- system map

;; use named system, with overrides
(ds/start :test {[:services :queue] mock-queue})
```

The `start` helper also takes an optional third argument to select components:

``` clojure
(ds/start :test 
          {[:services :queue] mock-queue}
          #{[:app :http-server]} ;; <- component selection
          )
```


## Purpose

Now that we've covered how to use the library, let's talk about why you'd use
it.

When building a non-trivial Clojure application you're faced with some questions
that don't have obvious answers:

- How do I write code that's understandable and maintainable?
- How do I manage resources like database connections and thread pools?
- How do I manage test environments?

donut.system helps you address these problems by giving you tools for
encapsulating behavior in *components* and composing components into *systems*.

### Architecture aid

We can make application code more understandable and maintainable by identifying
a system's responsibilities and organizing code around those responsibilities so
that they can be considered and developed in isolation - in other words,
defining a system architecture an implementing it with healthy doses of loose
coupling and encapsulation.

It's not obvious how to do implement and convey your system's architecture in a
functional programming language like Clojure, where it's pretty much one giant
pool of functions, and boundaries (namespaces, marking functions private) are
more like swim lanes you can easily duck under than walls enforcing isolation.

Using a component library like donut.system is one way for you to introduce such
boundaries. When you program with components, you clarify your application's
functional concerns, you codify (literally!) the relationships between different
parts of your system, and you make the interfaces between them explicit. You
avoid creating a codebase where any random function can access any random
state - part of why you got into Clojure in the first place.

Components facilitate writing loosely-coupled code. The benefits of that are
well documented, but I'll briefly mention a couple here:

- Loosely-coupled code is easier to understand because it reduces the scope of the
  system you have to have in your head to understand what something is doing.
- Loosely-coupled code is easier to maintain because it reduces the scope of
  impact from changes.

Components also aid discoverability. A system definition serves as a map that
outlines the major "territories" of functionality, as well the entry point to
each.

### Resource management

donut.system helps allocate and deallocate resources like database connections
and thread pools in the correct order. It also provides a systematic approach to
accessing resources. When building an application, you have to manage these
tasks _somehow_; a component library like donut.system gives you the tools to
manage them in a consistent way.

I have a half-baked thought about component libraries serving a purpose similar
to tools like [systemd](https://systemd.io/), though in a much more limited
scope. I'm not sure exactly where you want to go with it, but: component
libraries are useful in building an application for reasons similar to why
systemd is useful in managing a machine. In both cases, you want some consistent
method for starting and stopping the actors in a computing environment. This
work is not central to whatever business problem you're trying to solve, but it
still has to get done, so it's nice to be able to use a tool that does that work
for you that you can learn once and use across different projects.

### Virtual environment

donut.system (and other component libraries) provide a kind of light-weight
virtual environment for your application. Usually there's one-to-one
relationship between a running process and a running application; component
systems make it possible to run many instances of an application within a single
process.

The biggest benefit this brings is the ability to run dev and test systems at
the same time. I can start a dev system with an HTTP server and a dev db
connection from the REPL, and from the same REPL run integration tests with a
separate HTTP server and db connection. It's a huge workflow improvement.

### Framework foundation

donut.system's component definitions are _just data_, which means that it's
possible for libraries to provide components that work with donut.system without
actually including a donut.system dependency. A library like
[cronut](https://github.com/troy-west/cronut), for example, could include the
following map for easy consumption in a donut.system project:

``` clojure
(def CronutComponent
  {:start (fn [conf _ _] (initialize conf))
   :stop  (fn [_ scheduler _] (shutdown scheduler))})
```

What if you want to define a component group without depending on donut.system?
You might want to do this if you have a collection of related components that
have local refs to each other. Here's how you could do that:

``` clojure
(def CoolLibComponentGroup
  {:component-a {:start (fn [conf _ _] ...)}
   :component-b {:start (fn [{:keys [component-a] _ _}])
                 :conf  {:component-a [:donut.system/ref :component-a]}}})
```

The key is that refs are represented with the vector `[:donut.system/ref
ref-key]`.

Whether or not this is actually a good idea remains to be seen, but my hope is
that it will provide a better foundation for writing higher-level, composable
libraries.

## Objections

Over the years, I've encountered two main objections to this approach:

- It forces premature abstraction
- It's too complex

TODO address these concerns. (They're not necessarily wrong!)

## Alternatives

Other Clojure libraries in the same space:

- [Integrant](https://github.com/weavejester/integrant)
- [mount](https://github.com/tolitius/mount)
- [Component](https://github.com/stuartsierra/component)
- [Clip](https://github.com/juxt/clip)

## Why Use This and Not That?

The Clojure ecosystem already has a number of useful component libraries. Why
should you use donut.system and not one of the libraries listed above? I'll
focus on Integrant, as this library was built to address some of the issues I
encountered with it. Before proceeding: Integrant has overall served me well for
years, and I'm very grateful to weavejester for creating it and for introducing
me to a new way of thinking about managing systems.

The problems I've run into with Integrant include:

- Defining alternative implementations
- Composing systems
- Creating multiple instances of groups of components

### Defining alternative implementations 

There's no canonical, straightforward way to define test instances of components
in Integrant. The two main options are:

1. Paramaterize component init
2. Use keyword hierarchies

Both approaches rely on a level of indirection that can make the code harder to
undersand, and they're both resistant to ad-hoc usage. Let's briefly look at
each approach, and then look at how to do this in donut.system.

#### Paramaterizing component init

Here's one way you could parameterize component initialization:

``` clojure
(defmethod ig/init-key ::component [_ {:keys [test?]}]
  (if test?
    test-config
    main-config))

(def default-system-config
  {::component {:test? false}})

;; in a test
(assoc-in default-system-config [::component :test?] true)
```

Your `system-config` configures `::component` with a flag indicating which
implementation to use. There are a few problems with this approach:

- It's up to you to apply it consistently.
- You must define all possible implementations up front. This is a problem if
  you want to e.g. mock out a component to reify a protocol such that it returns
  values that are only relevant for a specific test.
- There must always be distance between the context where the alternative
  implementation is defined (in the `ig/init-key` method definition) and the
  context where you're specifying that you want an alternative implementation;
  you define your test implementation and your test in separate places,
  increasing cognitive load.

#### Use keyword hierarchies

Here's how you could use keyword hierarchies to define alternative component
implementations:

``` clojure
(derive ::component-impl-a ::component)
(derive ::component-impl-b ::component)

(defmethod ig/init-key ::component-impl-a [_ _])
(defmethod ig/init-key ::component-impl-b [_ _])

(def default-system-config
  {::component-impl-a {}})

;; create test config
(-> default-system-config
    (dissoc ::component-impl-a)
    (assoc ::component-impl-b {}))
```

Note that you have to be sure to `dissoc` the component `::component-impl-a`.
Specifying an alternative implementation is not simply specifying what you
_want_, it also requires to you remove what you _don't want_.

#### Alternative implementations in donut.system

Here's how you provide an alternative implementation in donut.system:

``` clojure
(defn start-a [_ _ _])
(defn start-b [_ _ _])

(def default-system-config
  {::ds/defs {:component-group {:component {:start start-a}}}})

;; create test config
(assoc-in default-system-config [::ds/defs :component-group :component :start] start-b)
```

In this case you are just directly updating a map to specify the new behavior
you want. You don't even have to define `start-b` separately; you could pass in
an anonymous function.

### Composing systems

TODO

### Creating multiple instances of groups of components

TODO

## Acknowledgments

donut.system takes inspiration from Component, Integrant, and Clip.

## Status: 🤔

This library hasn't been used in production. I'm hoping some folks will find it
interesting and want to try it out so that we can work out kinks and improve it.

## TODO

- async signal handling
- more examples
- discuss the value of dependency injection
