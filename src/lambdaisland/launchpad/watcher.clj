(ns lambdaisland.launchpad.watcher
  "Higher level wrapper around Beholder.

  Beholder watches directories, not files. We want to watch specific files in
  specific directories. This can be done with [[watch!]], which will start the
  minimum number of watchers to cover all directories, and will dispatch to the
  right handler based on the changed file."
  (:require [nextjournal.beholder :as beholder])
  (:import java.util.regex.Pattern
           java.nio.file.LinkOption
           java.nio.file.Files
           java.nio.file.Paths
           java.nio.file.Path))

(defonce watchers (atom nil))

(defn path ^Path [root & args]
  (if (and (instance? Path root) (not (seq args)))
    root
    (Paths/get (str root) (into-array String args))))

(defn canonical-path [p]
  (.toRealPath (path p) (into-array LinkOption [])))

(defn parent-path [p]
  (.getParent (path p)))

(require 'clojure.pprint)

(defn watch!
  "Watch a number of files, takes a map from filename (string) to
  handler (receives a map with `:type` and `:path`, as with Beholder)."
  [file->handler]
  (let [file->handler (update-keys file->handler canonical-path)
        directories (distinct (map parent-path (keys file->handler)))
        ;; in case of nested directories, only watch the top-most one
        directories (remove (fn [d]
                              (some #(and (not= d %)
                                          (.startsWith d %)) directories))
                            directories)]
    (swap! watchers
           (fn [w]
             (when w
               (run! beholder/stop w))
             (doall
              (for [dir directories]
                (beholder/watch
                 (fn [{:keys [type path] :as event}]
                   (when-let [f (get file->handler path)]
                     (try
                       (f event)
                       (catch Exception e
                         (prn e)))))
                 (str dir))))))))

(comment
  (watch!
   {"/home/arne/Gaiwan/slack-widgets/deps.edn" prn
    "/home/arne/Gaiwan/slack-widgets/.env" prn
    "/home/arne/Gaiwan/slack-widgets/.envx" prn
    "/home/arne/Gaiwan/slack-widgets/backend/deps.edn" prn}))
