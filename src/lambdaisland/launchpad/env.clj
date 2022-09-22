(ns lambdaisland.launchpad.env
  "Make environment variables modifiable from within Java, and use that to watch a
  .env file for changes, and hot reload them.

  This is *very* dirty, it uses reflection to get at various private bits of
  Java, it relies on implementation details of OpenJDK, and it requires breaking
  module isolation (the process has to start with
  `--add-opens=java.base/java.lang=ALL-UNNAMED`
  `--add-opens=java.base/java.util=ALL-UNNAMED`). We also rely on jnr-posix to
  get to the underlying setenv system call, for good measure.

  But hey it works!"
  (:require [lambdaisland.dotenv :as dotenv])
  (:import (java.nio.file Path Files LinkOption)))

(set! *warn-on-reflection* true)

(defn accessible-field ^java.lang.reflect.Field [^Class klz field]
  (doto (.getDeclaredField klz field)
    (.setAccessible true)))

(defn get-static [field]
  (let [klz (Class/forName (namespace field))]
    (.get (accessible-field klz
                            (name field)) klz)))

(defn get-field [^Object instance field]
  (.get (accessible-field (.getClass instance) (str field)) instance))

(defn set-field! [klz field obj val]
  (.set (accessible-field klz field) obj val))

(defn set-static! [klz field val]
  (set-field! klz field klz val))

(def ^java.util.Map theEnvironment
  (get-static 'java.lang.ProcessEnvironment/theEnvironment))

(def ^java.lang.ProcessEnvironment$StringEnvironment theUnmodifiableEnvironment
  (get-field (get-static 'java.lang.ProcessEnvironment/theUnmodifiableEnvironment) 'm))

(def ^jnr.posix.POSIX posix (jnr.posix.POSIXFactory/getPOSIX))

(defn new-value [^String str]
  (assert (= -1 (.indexOf str "\u0000")))
  (let [^java.lang.reflect.Constructor init
        (first (.getDeclaredConstructors java.lang.ProcessEnvironment$Value))]
    (.setAccessible init true)
    (.newInstance init (into-array Object ["XXX" (.getBytes "XXX")]))))

(defn new-variable [^String str]
  (assert (and (= -1 (.indexOf str "="))
               (= -1 (.indexOf str "\u0000"))))
  (let [^java.lang.reflect.Constructor init
        (first (.getDeclaredConstructors java.lang.ProcessEnvironment$Value))]
    (.setAccessible init true)
    (.newInstance init (into-array Object ["XXX" (.getBytes "XXX")]))))

(defn setenv
  ([env]
   (run! (fn [[k v]] (setenv k v)) env))
  ([^String var ^String val]
   ;; This one is used by ProcessBuilder
   (.put theEnvironment (new-value var) (new-variable val))
   ;; This one is used by System/getenv
   (.put theUnmodifiableEnvironment var val)
   ;; Also change the actual OS environment for the process
   (.setenv posix var val 1)))

(defn exists?
  "Does the given path exist."
  [path]
  (Files/exists path (into-array LinkOption [])))

(defn dotenv-watch-handler [paths]
  (let [paths (map #(Path/of % (into-array String [])) paths)]
    (fn [_]
      (setenv
       (apply merge
              (map #(when (exists? %)
                      (dotenv/parse-dotenv (Files/readString %)))
                   paths))))))

(defn watch-handlers []
  (let [h (dotenv-watch-handler [".env" ".env.local"])]
    (cond-> {}
      (exists? ".env")
      (assoc ".env" h)
      (exists? ".env.local")
      (assoc ".env.local" h))))
