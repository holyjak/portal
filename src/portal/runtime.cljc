(ns portal.runtime
  #?(:cljs (:require-macros portal.runtime))
  (:refer-clojure :exclude [read])
  (:require [clojure.datafy :refer [datafy nav]]
            [portal.runtime.cson :as cson]
            #?(:clj  [portal.sync  :as a]
               :cljs [portal.async :as a])))

(defonce ^:dynamic *session* nil)
(defonce ^:private id (atom 0))
(defn- next-id [] (swap! id inc))
(defonce sessions (atom {}))

(defn get-session [session-id]
  (-> @sessions
      (get session-id)
      (assoc :session-id session-id)))

(defn open-session [{:keys [session-id] :as session}]
  (merge
   (get-session session-id)
   {:value-cache (atom {})}
   session))

(defonce request (atom nil))

(defn- set-timeout [f timeout]
  #?(:clj  (future (Thread/sleep timeout) (f))
     :cljs (js/setTimeout f timeout)))

(defn broadcast-change [_watch-key a old new]
  (when-not (= old new)
    (set-timeout
     #(when (= @a new)
        (when-let [request @request]
          (request {:op :portal.rpc/update-versions :body new})))
     100)))

(defn- atom? [o]
  #?(:clj  (instance? clojure.lang.Atom o)
     :cljs (satisfies? cljs.core/IAtom o)))

(defonce ^:private watch-registry (atom {}))
(add-watch watch-registry ::watch-key #'broadcast-change)

(defn- watch-atom [a]
  (when-not (contains? @watch-registry a)
    (swap!
     watch-registry
     (fn [atoms]
       (if (contains? atoms a)
         atoms
         (do
           (add-watch
            a
            ::watch-key
            (fn [_watch-key a _old _new]
              (swap! watch-registry update a inc)))
           (assoc atoms a 0)))))))

(defn- value->id [value]
  (let [k [:value value]]
    (-> (:value-cache *session*)
        (swap!
         (fn [cache]
           (if (contains? cache k)
             cache
             (let [id (next-id)]
               (assoc cache [:id id] value k id)))))
        (get k))))

(defn- value->id? [value]
  (get @(:value-cache *session*) [:value value]))

(defn- id->value [id]
  (get @(:value-cache *session*) [:id id]))

(defn- to-object [value tag rep]
  (when (atom? value) (watch-atom value))
  (cson/tag
   "object"
   (cson/to-json
    {:id     (value->id value)
     :type   (pr-str (type value))
     :tag    tag
     :rep    rep
     :meta   (meta value)})))

(extend-type #?(:clj Object :cljs default)
  cson/ToJson
  (-to-json [value]
    (to-object value :object nil)))

(defn- var->symbol [v]
  (let [m (meta v)]
    (symbol (str (:ns m)) (str (:name m)))))

#?(:bb (def clojure.lang.Var (type #'type)))

(extend-type #?(:clj  clojure.lang.Var
                :cljs cljs.core/Var)
  cson/ToJson
  (-to-json [value]
    (to-object value :var (var->symbol value))))

(defn- can-meta? [value]
  #?(:clj  (or (instance? clojure.lang.IObj value)
               (instance? clojure.lang.Var value))
     :cljs (implements? IMeta value)))

(defn- has? [m k]
  (try
    (k m)
    (catch #?(:clj Exception :cljs :default) _e)))

(defn- no-cache [value]
  (or (not (coll? value))
      (not (can-meta? value))
      (has? value :portal.rpc/id)))

(defn- id-coll [value]
  (if (no-cache value)
    value
    (if-let [id (value->id? value)]
      (cson/->Tagged "ref" id)
      (vary-meta value
                 merge
                 (cond-> {::id (value->id value)}
                   (record? value)
                   (assoc ::type (type value)))))))

(defn write [value session]
  (binding [*session* session]
    (cson/write
     value
     (merge
      session
      {:transform id-coll}))))

(defn- ref-> [value]
  (id->value (second value)))

(defn read [string session]
  (binding [*session* session]
    (cson/read
     string
     (merge
      session
      {:default-handler
       (fn [value]
         (case (first value)
           "ref"    (ref-> value)
           (cson/->Tagged (first value) (cson/json-> (second value)))))}))))

(defonce tap-list (atom (list)))

(defn update-value [new-value]
  (swap! tap-list conj new-value))

(defn clear-values
  ([] (clear-values nil identity))
  ([_request done]
   (reset! id 0)
   (reset! tap-list (list))
   (reset! (:value-cache *session*) {})
   (doseq [[a] @watch-registry]
     (remove-watch a ::watch-key))
   (reset! watch-registry {})
   (done nil)))

(defn update-selected
  ([value]
   (update-selected (:session-id *session*) value))
  ([session-id value]
   (swap! sessions assoc-in [session-id :selected] value)))

(def ^{:deprecated "0.16.0"} public-fns {})
(def ^{:deprecated "0.16.0"} fns {})
(def ^:private registry (atom {}))

(defn- get-functions [v]
  (keep
   (fn [[name opts]]
     (let [m      (meta (:var opts))
           result {:name name :doc (:doc m)}]
       (when-not (:private m)
         (if-let [predicate
                  (or (:predicate opts) (:predicate m))]
           (when (predicate v) result)
           result))))
   @registry))

(defn- get-tap-atom [] tap-list)

(defn- get-options []
  (merge
   {:name "portal"
    :version "0.15.1"
    :platform
    #?(:bb   "bb"
       :clj  "jvm"
       :cljs (cond
               (exists? js/process)        "node"
               (exists? js/PLANCK_VERSION) "planck"
               :else                        "web"))}
   (:options *session*)))

(defn- ping [] ::pong)

(defn- get-function [f]
  (get-in @registry [f :var]))

(defn invoke [{:keys [f args]} done]
  (try
    (let [f (if (symbol? f) (get-function f) f)]
      (a/let [return (apply f args)]
        (done {:return return})))
    (catch #?(:clj Exception :cljs js/Error) e
      (done {:return e}))))

(def ops {:portal.rpc/invoke #'invoke})

(defn- var->name [var]
  (let [{:keys [name ns]} (meta var)]
    (symbol (str ns) (str name))))

(defn register!
  ([var] (register! var {}))
  ([var opts]
   (swap! registry
          assoc (var->name var) (merge opts {:var var}))))

(defn- deref? [value]
  #?(:clj  (instance? clojure.lang.IRef value)
     :cljs (satisfies? cljs.core/IDeref value)))

(doseq [var [#'nav
             #'ping
             #'get-tap-atom
             #'get-options
             #'clear-values
             #'update-selected
             #'get-functions
             #'pr-str
             #'type
             #'datafy]]
  (register! var))

(doseq [[var opts] {#'deref {:predicate deref?}
                    #'meta  {:predicate can-meta?}}]
  (register! var opts))
