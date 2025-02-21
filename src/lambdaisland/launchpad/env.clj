(ns lambdaisland.launchpad.env
  (:require [lambdaisland.dotenv :as dotenv])
  (:import (java.nio.file Files LinkOption Path)))

(defn exists?
  "Does the given path exist."
  [path]
  (Files/exists path (into-array LinkOption [])))

(defn ->path
  [^String path]
  (Path/of path (into-array String [])))

(defn canonical-path
  [^Path path]
  (.toRealPath path (into-array LinkOption [])))

(def ^{:doc "The list of dotenv paths to watch.

Order counts as we use merge semantics when reading them in."}
  watch-paths [".env" ".env.local"])

(defn parse-dotenv
  [^Path path]
  (when (.exists (.toFile path))
    (-> (Files/readString path)
        (dotenv/parse-dotenv))))
