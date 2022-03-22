(ns com.pfeodrippe.dinamico.helper
  "If you are going to use this namespace, make sure you are using
  the https://pub.dev/packages/dinamico_dart dart library in your mobile app.
  There enable the new functions using `Dinamico.register`."
  (:require
   [clojure.string :as str]
   [malli.core :as m]
   [malli.destructure :as md]))

(def -routes* (atom {}))

(def -infer (comp :schema md/parse))

(defn- parse-action
  [op args]
  (format "${httpAction({'op': '%s', 'args': {%s}})}"
          (symbol op)
          (->> args
               (mapv name)
               (mapv #(format "'%s': %s" % %))
               (str/join ", "))))

(defmacro with-action
  "Macro that enables you to call a action seamless from the mobile app, e.g.
  from a `:onPressed`, `:onTag` etc.

  `params` should **always** have one parameter. Use destructure for it
  (see examples below), which also enable the \"listening\" of these states.

  You must have a POST handler for the `/dinamico/http-action` route.

  See the `dinamico.helper` ns docstring.

  -- Usage --
  ;; Assuming you passed to `Dinamico.register` in the mobile app a
  ;; action called `redirect`.
  (with-action ::open-id-card [_]
    [[:redirect \"/id-card\"]])

  ;; `set-value` is a built-in action. In this example we are exchanging
  ;; the contents of the text field.
  (with-action ::exchange [{:keys [text1 text2]}]
    [[:set-value {:text1 text2 :text2 text1}]
     [:redirect \"/home\"]])"
  [op params & body]
  (let [ks (-> (-infer params)
               m/ast
               :children
               first
               :keys
               :map
               :value
               :keys
               keys
               vec)]
    `(do (swap! -routes* assoc ~op
                (fn ~params
                  ~@body))
         ~(parse-action op ks))))

(defn http-action
  "Pass body-params to your action. This function is usually
  used in our handler for `/dinamico/http-action` route."
  [params]
  (let [{:keys [op args]} params]
    ((get @-routes* (keyword op)) args)))
