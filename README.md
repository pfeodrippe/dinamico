[![Clojars Project](https://img.shields.io/clojars/v/io.github.pfeodrippe/dinamico.svg)](https://clojars.org/io.github.pfeodrippe/dinamico)

# Dinâmico

Wrapper over the
[json_dynamic_widget](https://github.com/peiffer-innovations/json_dynamic_widget)
Dart/Flutter library.

> This library provides Widgets that are capable
of building themselves from JSON structures.

_Json Dynamic Widget library_

## Reasoning

Recently I have been looking a way to write mobile apps in the most
convenient possible way (me, a backend developer). Flutter looked
promising and [hype enough](https://flutter.dev/showcase/nubank), so
I've pursued to learn it, although there is no way to write it from
Clojure... [yet](https://github.com/Tensegritics/ClojureDart). Dart
looks verbose, but it seems had to do its job.

After some loooooong couple hours, I was not happy with it. Asked in the
[Brazilian Clojure Telegram](https://telegram.me/clojurebrasil) group
how people in Nubank were using this in conjuction with their Clojure
backend, they must have some secret, right?

In fact they had, [Eric Dallo](https://github.com/ericdallo) works
there and told us that they use Clojure in the backend to build the
screens and Flutter is mostly the presentation layer. I wonder how
this worked and if there was some kind of library already for doing
it, was it something the community was working on?

Yes, it was,
[json_dynamic_widget](https://github.com/peiffer-innovations/json_dynamic_widget)
is one of the options. Although I guess Nubank itself does not rely on
it as it seems wasteful in resources.

Take a look at the library repo explanation, but for TLDR, it lets you
build widgets dynamically with JSON. Add some dart code and the
core of your app can be described in your backend (following the
Server Side Rendering or Server Driver UI approach (software is a
pendulum!) which you also can find in other frameworks, e.g. React
Native).

The widgets are maps with some top-level keys (e.g. `:type`,
`:children`) and, using EDN, you can describe a widget in the
following way (the example below is a a centered card with its front
over the back).

``` clojure
{:type :scaffold,
 :args
 {:body
  {:type :center,
   :children
   [{:type :column,
     :args {:mainAxisAlignment :center},
     :children
     [{:type :asset_image,
       :args {:name "images/card/card-front.png"}}
      {:type :asset_image,
       :args {:name "images/card/card-back.png"}}]}]}}}
```

It's a little bit verbose (but very descriptive) and I would also
have to refer to the original docs (for the original Flutter
widgets or in the library documentation), there was no convenient way
know what I could do upfront and which were the available
options/arguments for a given widget.

So the goals are to create convenience and to have minimal
documentation when writing code, this is what this library tries to
address.

## Example

Require `(require '[com.pfeodrippe.dinamico.core :as dn])`.

Using the example from our last section, we would have the equivalent
code as

``` clojure
(ns my-app
  (:require
   [com.pfeodrippe.dinamico.core :as dn]))

(defn flutter-app
  []
  (dn/scaffold
   {:body
    (dn/center
     (dn/column
      {:mainAxisAlignment :center}
      [(dn/asset-image {:name "images/card/card-front.png"})
       (dn/asset-image {:name "images/card/card-back.png"})]))}))
```

It seems almost the same, but the good thing is that now things are
reified, they are not only data, you can inspect them.

For example, there is a `dn/-doc` function
to inspect the whole schema (in Malli).

``` clojure
(dn/-doc dn/column)

;; =>
[:schema
 {:registry
  #:com.pfeodrippe.dinamico.core{:text_baseline
                                 [:schema
                                  ....}
 [:map
  [:textBaseline
   {:optional true}
   [:ref :com.pfeodrippe.dinamico.core/text_baseline]]
  [:crossAxisAlignment
  ...]]]
```

Or you can check the available args, just check the docs/arglits of your
function, example from Emacs + Cider below for the `dn/column` function.

``` clojure
com.pfeodrippe.dinamico.core/column
 [opts-child-or-children]
 [{:keys [crossAxisAlignment mainAxisAlignment mainAxisSize textBaseline textDirection verticalDirection]} child-or-children]
  Not documented.

Definition location unavailable.

[back]
```

Or you can check a given arg (which is one key from the arglist) using
the 2-arity version of `dn/-doc`.

``` clojure
(dn/-doc dn/column :mainAxisAlignment)

;; =>
[:schema
 {:registry {}}
 [:enum :center :end :spaceAround :spaceBetween :spaceEvenly :start]]
```

## Usage

First you need to add some Dart code to handle the JSON data that arrives
from the backend, refer to the
[main-dev.dart](dev-resources/main-dev.dart) file and use it as your
`main.dart` file.

To build your Clojure server you can use anything, let's use
`http-kit` + `reitit` here, see the deps below (leiningen style).

``` clojure
;; Json.
[metosin/jsonista "0.3.5"]

;; Routes.
[metosin/reitit "0.5.13"]

;; Server.
[ring/ring "1.9.4"]
[http-kit/http-kit "2.5.3"]
[metosin/muuntaja "0.6.8"]
```

Then the server code can be

``` clojure
(ns my-app
  (:require
   [com.pfeodrippe.dinamico.core :as dn]
   [jsonista.core :as json]
   [clj-http.client :as http]
   [muuntaja.core :as m]
   [org.httpkit.server :refer [run-server]]
   [reitit.ring :as ring]
   [reitit.ring.coercion :as rrc]
   [reitit.ring.middleware.muuntaja :as muuntaja]))

(defn- flu
  [m]
  {:status 200
   :body m})

(defn flutter-app
  []
  (dn/scaffold
   {:body
    (dn/center
     (dn/column
      {:mainAxisAlignment :center}
      ;; We assume that you have these images on your flutter repo.
      [(dn/asset-image {:name "images/card/card-front.png"})
       (dn/asset-image {:name "images/card/card-back.png"})]))}))

(defn flutter-app-get
  [_]
  ;; We use the var version for hot reload without the need to restart
  ;; the server.
  (flu (#'flutter-app)))

(defn routes
  []
  [["/flutter-app" {:get flutter-app-get}]])

(defn app
  [components]
  (ring/ring-handler
   (ring/router
    (routes)
    {:data {:components components
            :muuntaja m/instance
            :middleware [muuntaja/format-middleware
                         rrc/coerce-exceptions-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware]}})
   (ring/routes
    (ring/create-resource-handler
     {:path "/"})
    (ring/create-default-handler
     {:not-found (constantly {:status 404 :body "Not found"})}))))

;; Restart or init the server.
(when (resolve 'server)
  (eval '(server)))
(def server (run-server (app {}) {:join? false
                                  :port 3001}))
```

Start your REPL and hot reload as you do in Clojure (for development
purposes, the mobile app should be fetching the dynamic widget every
200 ms), you don't need to evaluate the whole buffer again, just start
the server once and you are good.

## Internals

`json_dynamic_widget` has its schemas available in the json-schema
format, so we had to create a json-schema parser (based on
https://github.com/metosin/malli/pull/211).

These schemas were converted to Malli (to be used as the source of
truth) and then we have created a [schemas.edn](resources/schemas.edn)
that is bundled into the library.

When you use Dinâmico, it reads `schemas.edn` once and dynamically creates the
vars that you can use to create beautiful Flutter apps from Clojure.
