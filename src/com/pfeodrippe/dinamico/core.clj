(ns com.pfeodrippe.dinamico.core
  "Code to parse based on https://github.com/metosin/malli/pull/211/files."
  (:refer-clojure :exclude [comment])
  (:require
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [jsonista.core :as json]
   [malli.core :as m]
   [malli.util :as mu]))

(def ^:private schema-names
  "From https://github.com/peiffer-innovations/json_dynamic_widget/tree/main/lib/src/schema/schemas."
  [:align
   :animated_align
   :animated_container
   :animated_cross_fade
   :animated_default_text_style
   :animated_opacity
   :animated_padding
   :animated_physical_model
   :animated_positioned_directional
   :animated_positioned
   :animated_size
   :animated_switcher
   :animated_theme
   :app_bar
   :aspect_ratio
   :asset_image
   :baseline
   :button_bar
   :card
   :center
   :checkbox
   :circular_progress_indicator
   :clip_oval
   :clip_path
   :clip_rect
   :clip_rrect
   :column
   :comment
   :conditional
   :container
   :cupertino_switch
   :custom_scroll_view
   :decorated_box
   :directionality
   :dropdown_button_form_field
   :dynamic
   :elevated_button
   :exclude_semantics
   :expanded
   :fitted_box
   :flat_button
   :flexible
   :floating_action_button
   :form
   :fractional_translation
   :fractionally_sized_box
   :gesture_detector
   :grid_view
   :hero
   :icon_button
   :icon
   :ignore_pointer
   :indexed_stack
   :ink_well
   :input_error
   :interactive_viewer
   :intrinsic_height
   :intrinsic_width
   :json_widget_data
   :layout_builder
   :limited_box
   :linear_progress_indicator
   :list_tile
   :list_view
   :material
   :measured
   :memory_image
   :merge_semantics
   :network_image
   :offstage
   :opacity
   :outlined_button
   :overflow_box
   :padding
   :placeholder
   :popup_menu_button
   :positioned
   :primary_scroll_controller
   :radio
   :raised_button
   :row
   :safe_area
   :save_context
   :scaffold
   :scroll_configuration
   :scrollbar
   :semantics
   :set_default_value
   :set_scroll_controller
   :set_value
   :set_widget
   :single_child_scroll_view
   :sized_box
   :sliver_grid
   :sliver_list
   :sliver_padding
   :sliver_to_box_adapter
   :stack
   :switch
   :testable
   :text_button
   :text_form_field
   :text
   :theme
   :tooltip
   :tween_animation
   :wrap])

(def ^:private ^:dynamic *registry*
  (m/default-schemas))

(def ^:private annotations #{#_ #_ #_ #_:title :description :default :examples})

(defn- annotations->properties [js-schema]
  (-> js-schema
      (select-keys annotations)
      (set/rename-keys {:examples :json-schema/examples})))

;; Utility Functions
(defn- map-values
  ([-fn] (map (fn [[k v]] [k (-fn v)])))
  ([-fn coll] (sequence (map-values -fn) coll)))

;; Parsing
(defmulti -type->malli :type)

(defn- $ref [v]
  ;; TODO to be improved
  (keyword (str *ns*)
           (-> (last (str/split v #"/"))
               (str/split #"\.json")
               (first))))

(declare object->malli)

(defn- schema->malli [js-schema]
  (let [-keys (set (keys js-schema))]
    (mu/update-properties
     (m/schema
      (cond
        ;; Aggregates
        (-keys :oneOf) (let [parsed-one-of (->> (:oneOf js-schema)
                                                (remove #(and (= (:type %) "string")
                                                              (:pattern %))))]
                         (cond
                           (and (= (count parsed-one-of) 1)
                                (= (:type (first parsed-one-of)) "string")
                                (:enum (first parsed-one-of)))
                           (into [:enum]
                                 (mapv keyword (:enum (first parsed-one-of))))

                           (and (= (count parsed-one-of) 2)
                                (contains? (set parsed-one-of) {:type "null"}))
                           (->> parsed-one-of
                                (remove #(= % {:type "null"}))
                                first
                                schema->malli)

                           :else
                           (into
                            ;; TODO Figure out how to make it exclusively select o schema
                            [:or]
                            (map schema->malli)
                            (:oneOf js-schema))))

        (-keys :anyOf) (into
                        [:or]
                        (map schema->malli)
                        (:anyOf js-schema))

        (-keys :allOf) (into
                        [:and]
                        (map schema->malli)
                        (:allOf js-schema))


        (-keys :type) (-type->malli js-schema)

        (-keys :enum) (into [:enum]
                            (if (= (:type js-schema) "string")
                              (mapv keyword (:enum js-schema))
                              (:enum js-schema)))

        (-keys :const) [:enum (:const js-schema)]

        (-keys :not) [:not (schema->malli (:not js-schema))]

        (-keys :$ref) [:ref ($ref (:$ref js-schema))]

        (-keys :properties) (object->malli js-schema)

        :else (throw (ex-info "Not supported" {:json-schema js-schema
                                               :reason ::schema-type})))
      {:registry *registry*})
     merge
     (annotations->properties js-schema))))

(defn- properties->malli [required [k v]]
  (cond-> [k]
    (nil? (required k)) (conj {:optional true})
    true (conj (schema->malli v))))

(defn- prop-size [pred?] (fn [-map] (pred? (count (keys -map)))))
(defn- min-properties [-min] (prop-size (partial <= -min)))
(defn- max-properties [-max] (prop-size (partial >= -max)))

(defn- with-min-max-poperties-size [malli v]
  (let [predicates [(some->> v
                             (:minProperties)
                             (min-properties)
                             (conj [:fn]))
                    (some->> v
                             (:maxProperties)
                             (max-properties)
                             (conj [:fn]))]]
    (cond->> malli
      (some some? predicates)
      (conj (into [:and]
                  (filter some?)
                  predicates)))))

(defn- object->malli [v]
  (let [required (into #{}
                       ;; TODO Should use the same fn as $ref
                       (map keyword)
                       (:required v))
        closed? (false? (:additionalProperties v))]
    (m/schema (-> [:map]
                  (cond-> false #_closed? (conj {:closed :true}))
                  (into
                   (map (partial properties->malli required))
                   (:properties v))
                  (with-min-max-poperties-size v))
              {:registry *registry*})))

(defmethod -type->malli "string" [{:keys [pattern minLength maxLength enum oneOf]}]
  ;; `format` metadata is deliberately not considered.
  ;; String enums are stricter, so they're also implemented here.
  (cond
    pattern [:re pattern]
    enum (into [:enum] (mapv keyword enum))
    :else [:string (cond-> {}
                     minLength (assoc :min minLength)
                     maxLength (assoc :max maxLength))]))

(defmethod -type->malli "integer" [{:keys [minimum maximum exclusiveMinimum exclusiveMaximum multipleOf]
                                   :or {minimum Integer/MIN_VALUE
                                        maximum Integer/MAX_VALUE}}]
  ;; On draft 4, exclusive{Minimum,Maximum} is a boolean.
  ;; TODO Decide on whether draft 4 will be supported
  ;; TODO Implement exclusive{Minimum,Maximum} support
  ;; TODO Implement multipleOf support
  ;; TODO Wrap, when it makes sense, the values below with range checkers, i.e. [:< maximum]
  ;; TODO extract ranges logic and reuse with number
  (cond
    (pos? minimum) pos-int?
    (neg? maximum) neg-int?
    :else int?))

(defmethod -type->malli "number" [p] number?)
(defmethod -type->malli "boolean" [p] boolean?)
(defmethod -type->malli "null" [p] nil?)
(defmethod -type->malli "object" [p] (object->malli p))
(defmethod -type->malli "array" [p] (let [items (or (:items p)
                                                   (:array p))]
                                     (cond
                                       (vector? items) (into [:tuple]
                                                        (map schema->malli)
                                                        items)
                                       (map? items) [:vector (schema->malli items)]
                                       :else (throw (ex-info "Not Supported" {:json-schema p
                                                                              :reason ::array-items})))))

(defn- fetch-schema*
  [url-or-schema-name]
  (println "Fetching" url-or-schema-name "...")
  (let [response (http/request {:url (if (str/starts-with? url-or-schema-name "https")
                                       url-or-schema-name
                                       (format "https://peiffer-innovations.github.io/flutter_json_schemas/schemas/json_dynamic_widget/%s.json"
                                               (name url-or-schema-name)))
                                :method :get
                                :throw-exceptions false})]
    (if (> (:status response) 299)
      {::error (if (= (:status response) 404)
                 ::error.not-found
                 response)}
      (-> response
          (update :body #(json/read-value % (json/object-mapper {:decode-key-fn keyword})))
          :body))))

(defonce ^:private fetch-schema (memoize fetch-schema*))

(defn- json-schema-document->malli [{:keys [:$id] :as obj}]
  (let [refs* (atom [])
        _ (walk/prewalk (fn [v]
                          (if (and (map-entry? v)
                                   (= (key v) :$ref))
                            (do (swap! refs* conj (val v))
                                v)
                            v))
                        obj)
        recursive? (contains? (set @refs*) $id)
        definitions (->> @refs*
                         (remove #{$id})
                         (mapv (fn [ref-url]
                                 [($ref ref-url)
                                  (json-schema-document->malli (fetch-schema ref-url))]))
                         (into {}))]
    (binding [*registry* (merge *registry*
                                {($ref (:$id obj)) :nil}
                                definitions)]
      (if recursive?
        (-> [:schema {:registry (merge {($ref (:$id obj)) (schema->malli obj)}
                                       definitions)}
             ($ref (:$id obj))]
            pr-str
            edn/read-string)
        (-> [:schema {:registry definitions}
             (schema->malli obj)]
            pr-str
            edn/read-string)))))

(defn- schema-name->malli
  [schema-name]
  (let [sch (fetch-schema schema-name)]
    {schema-name
     (if (::error sch)
       sch
       (json-schema-document->malli sch))}))

(defn -doc
  "Return schema (Malli format) for a function.

  The arity with `opt-k` is for the doc of some option key if
  it's a `ref`, e.g. `(-doc column :mainAxisAlignment)`."
  ([f]
   (::schema (meta f)))
  ([f-or-schema & opt-ks]
   (let [schema (if (m/schema? f-or-schema)
                  f-or-schema
                  (::schema (meta f-or-schema)))
         registry (:registry (m/properties schema))
         options (m/options schema)
         value (-> (m/ast schema)
                   :child
                   :keys
                   ((first opt-ks))
                   :value)
         sch-of-interest* (atom nil)
         sch (-> (walk/postwalk (fn [v]
                                  (if (and (map? v)
                                           (= (:type v) :ref))
                                    (let [sch (get registry (:value v))
                                          options' (m/options sch)]
                                      (reset! sch-of-interest* sch)
                                      (m/ast sch options'))
                                    v))
                                value)
                 (m/from-ast options))]
     (if-let [rest-ks (next opt-ks)]
       (apply -doc @sch-of-interest* rest-ks)
       (-> sch
           pr-str
           edn/read-string)))))

(doseq [[op sch] (->> (edn/read-string (slurp (io/resource "com/pfeodrippe/dinamico/schemas.edn")))
                      (into (sorted-map))
                      (remove (comp ::error val)))]
  (let [ks (->> (m/ast sch)
                :child
                :keys
                keys
                sort
                (mapv symbol))]
    (intern *ns* (with-meta (symbol (str/replace (name op) #"_" "-"))
                   {::schema sch
                    :arglists (list []
                                    '[opts-child-or-children]
                                    [(if (seq ks)
                                       {:keys ks}
                                       'opts)
                                     'child-or-children])})
            (with-meta (fn component
                         ([]
                          (component {} []))
                         ([opts-or-children]
                          (if (and (map? opts-or-children)
                                   (not (::component (meta opts-or-children))))
                            (component opts-or-children nil)
                            (component {} opts-or-children)))
                         ([opts child-or-children]
                          (with-meta (merge {:type op}
                                            (when (seq opts) {:args opts})
                                            (when (seq child-or-children)
                                              {:children (if (sequential? child-or-children)
                                                           child-or-children
                                                           [child-or-children])}))
                            {::component true})))
              {::schema sch}))))

(clojure.core/comment

  (def schemas
    (->> schema-names
         #_(drop 105)
         #_(take 20)
         (mapv schema-name->malli)
         (into (sorted-map))))

  (spit "resources/com/pfeodrippe/dinamico/schemas.edn"
        schemas)

  (->> schemas
       (remove (comp ::error val)))

  (schema-name->malli :ink_well)

  (json-schema-document->malli
   (fetch-schema "https://peiffer-innovations.github.io/flutter_json_schemas/schemas/json_theme/mouse_cursor.json"))

  ())
