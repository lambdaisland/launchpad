(ns lambdaisland.launchpad.deps
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.deps.alpha :as deps]))

(defn basis [opts]
  (let [deps-local-file (io/file "deps.local.edn")
        deps-local      (when (.exists deps-local-file)
                          (edn/read-string (slurp deps-local-file)))
        extra-deps      (update deps-local :deps merge (:launchpad/extra-deps opts))]
    (deps/create-basis (-> opts
                           (update :aliases concat (:launchpad/aliases deps-local))
                           (assoc :extra extra-deps)))))
