(ns lambdaisland.launchpad.deps
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.deps.alpha :as deps]
   [clojure.string :as str]
   [lambdaisland.classpath.watch-deps :as watch-deps]
   [lambdaisland.classpath :as licp]))

(defn basis [opts]
  (let [deps-local-file (io/file "deps.local.edn")
        deps-local      (when (.exists deps-local-file)
                          (edn/read-string (slurp deps-local-file)))
        extra-deps      (update deps-local :deps merge (:launchpad/extra-deps opts))]
    (deps/create-basis (-> opts
                           (update :aliases concat (:launchpad/aliases deps-local))
                           (assoc :extra extra-deps)))))

(defn write-cpcache-file
  "Write the current classpath to well-known location. Can be used to configure
  things like LSP that try to guess the classpath without having a running
  process.

  e.g. .lsp/config.edn
  ```
  {:project-specs [:classpath-cmd [\"cat\" \".cpcache/launchpad.edn\"]]}
  ```
  "
  []
  (spit ".cpcache/launchpad.cp"
        (str/join
         ":"
         (mapcat second (licp/classpath-chain)))))

(defn watch-handlers [opts]
  (let [basis (basis opts)
        deps-paths (cond-> [(watch-deps/path watch-deps/process-root-path "deps.edn")
                            (watch-deps/path watch-deps/process-root-path "deps.local.edn")]
                     (:include-local-roots? opts)
                     (into (->> (vals (:libs basis))
                                (keep :local/root)
                                (map watch-deps/canonical-path)
                                (map #(watch-deps/path % "deps.edn"))))
                     (string? (:extra opts))
                     (conj (watch-deps/canonical-path (:extra opts)))
                     :always
                     (concat (:watch-paths opts)))
        handler (fn [e]
                  (#'watch-deps/on-event deps-paths opts e)
                  (write-cpcache-file))]
    (into {}
          (map (fn [p]
                 [(str p) handler]))
          deps-paths)))
