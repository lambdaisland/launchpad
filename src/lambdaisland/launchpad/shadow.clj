(ns ^:no-doc ;TODO re-enable
  lambdaisland.launchpad.shadow
  (:require [clojure.java.io :as io]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [lambdaisland.classpath :as licp])
  (:import (java.nio.file Path)))

(def process-root
  "The directory where the JVM is running, as a Path"
  (.toAbsolutePath (Path/of "" (into-array String []))))

(defn find-shadow-roots
  "Find all libraries included via :local/root that haev a shadow-cljs.edn at
  their root."
  []
  (keep (fn [{:local/keys [root]}]
          (when (and root (.exists (io/file root "shadow-cljs.edn")))
            root))
        (vals (:libs (licp/read-basis)))))

(defn read-shadow-config
  "Slurp in a shadow-cljs.edn, applying normalization and defaults."
  [file]
  (-> (config/read-config file)
      (config/normalize)
      (->> (merge config/default-config))))

(defn relativize
  "Given module-path as a relative-path inside module-root, return a relative path
  based off process-root."
  [process-root module-root module-path]
  (-> process-root
      (.relativize
       (.resolve module-root module-path))
      str) )

(defn update-build-keys
  "Update `:output-to`/`:output-dir` in a shadow build config, such that is
  resolved against the process root, rather than the module root."
  [process-root module-root build]
  (cond-> build
    (:output-dir build)
    (update :output-dir (partial relativize process-root module-root))
    (:output-to build)
    (update :output-to (partial relativize process-root module-root))))

(defn merged-shadow-config
  "Given multiple locations that contain a shadow-cljs.edn, merge them into a
  single config, where the path locations have been updated."
  [module-paths]
  (-> (apply
       merge-with
       (fn [a b]
         (cond
           (and (map? a) (map? b))
           (merge a b)
           (and (set? a) (set? b))
           (into a b)
           :else
           b))
       (for [module-path module-paths
             :let [module-root (.toAbsolutePath (Path/of module-path (into-array String [])))
                   config-file (str (.resolve module-root "shadow-cljs.edn"))
                   module-name (str (.getFileName module-root))]]
         (-> config-file
             read-shadow-config
             (update
              :builds
              (fn [builds]
                (into {}
                      (map (fn [[k v]]
                             (let [build-id k
                                   ;; Not sure yet if this is a good idea
                                   #_(if (qualified-keyword? k)
                                       k
                                       (keyword module-name (name k)))]
                               [build-id
                                (assoc (update-build-keys process-root module-root v)
                                       :build-id build-id
                                       :js-options {:js-package-dirs [(str module-path "/node_modules")]})])))

                      builds))))))
      (assoc :deps {})
      (dissoc :source-paths :dependencies)))

(defn merged-config
  "Return a complete, combined, shadow-cljs config map, that combines all
  shadow-cljs.edn files found in projects that were references via :local/root."
  []
  (merged-shadow-config (find-shadow-roots)))

(defn start-builds! [& build-ids]
  (when (nil? @runtime/instance-ref)
    (let [config (merged-config)]
      (server/start! config)
      (doseq [build-id build-ids]
        (-> (get-in config [:builds build-id])
            (assoc :build-id build-id)
            api/watch))
      (loop []
        (when (nil? @runtime/instance-ref)
          (Thread/sleep 250)
          (recur))))))

#_(start-builds :main)

(require 'shadow.build)
