(ns lambdaisland.launchpad
  (:require [babashka.curl :as curl]
            [babashka.process :refer [process]]
            [babashka.wait :as wait]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli])
  (:import java.net.ServerSocket))

(def cli-opts
  [["-h" "--help"]
   ["-v" "--verbose" "Print debug information"]
   [nil "--cider-mw" "Include the CIDER nREPL middleware"]
   [nil "--refactor-mw" "Include the refactor-nrepl middleware"]
   [nil "--cider-connect" "Automatically connect CIDER"]
   [nil "--emacs" "Shorthand for --cider-mw --refactor-mw --cider-connect"]])

(def default-cider-version "0.28.3")
(def default-refactor-nrepl-version "3.5.2")

(def verbose (some #{"-v" "--verbose"} *command-line-args*))

(defn debug [& args] (when verbose (apply println "[DEBUG]" args)))
(defn info [& args] (apply println "[INFO]" args))
(defn warn [& args] (apply println "[WARN]" args))
(defn error [& args] (apply println "[ERROR]" args))

(defn shellquote [a]
  (cond
    (and (str/includes? a "\"")
         (str/includes? a "'"))
    (str "'"
         (str/replace a "'" "'\"'\"'")
         "'")

    (str/includes? a "'")
    (str "\"" a "\"")

    (re-find #"\s|\"" a)
    (str "'" a "'")

    :else
    a))

(defn free-port
  "Find a free TCP port"
  []
  (with-open [sock (ServerSocket. 0)]
    (.getLocalPort sock)))

(defn ensure-java-version [expected-version ctx]
  (let [version (parse-long (last (re-find #"version \"([^\.\"]*)"
                                           (-> (shell/sh "java" "-version")
                                               :err
                                               (str/split #"\n")
                                               first))))]
    (debug "Found java" version)
    (when (not= expected-version version)
      (error "Found java" version "," "expected Java" expected-version)
      (System/exit -1))
    ctx))

(defn eval-emacs
  "Evaluate a LISP form inside Emacs through emacsclient"
  [form]
  (debug "emacsclient" "-e" (shellquote (pr-str form)))
  (let [res (shell/sh "emacsclient" "-e" (pr-str form))]
    (debug "result:" (pr-str res))
    (:out res)))

(defn emacs-require
  "Load/require an emacs package, will return `true` on success, `false` on failure.
  Throws if `emacsclient` is not found."
  [lib]
  (= (str lib) (str/trim (eval-emacs `(~'require '~lib)))))

(defn emacs-cider-version
  "Find the CIDER version that is currently in use by the running Emacs instance."
  []
  (if (emacs-require 'cider)
    (read-string
     (eval-emacs '(if (boundp 'cider-required-middleware-version)
                    cider-required-middleware-version
                    (upcase cider-version))))
    (warn "Failed to load `cider` in Emacs, is it installed?")))

(defn emacs-refactor-nrepl-version
  "Find the refactor-nrepl version that is required by the `clj-refactor` version
  installed in Emacs."
  []
  (if (emacs-require 'clj-refactor)
    (read-string
     (eval-emacs 'cljr-injected-middleware-version))
    (warn "Failed to load `clj-refactor` in Emacs, is it installed?")))

(defn compute-middleware
  "Figure out the nREPL middleware based on CLI flags"
  [{:keys [options] :as ctx}]
  (let [add-mw #(update %1 :middleware (fnil conj []) %2)]
    (cond-> ctx
      (:cider-mw options)
      (add-mw 'cider.nrepl/cider-middleware)
      (:refactor-mw options)
      (add-mw 'refactor-nrepl.middleware/wrap-refactor))))

(defn compute-extra-deps [{:keys [options] :as ctx}]
  (let [assoc-dep #(update %1 :extra-deps assoc %2 %3)]
    (cond-> ctx
      (:cider-mw options)
      (assoc-dep 'cider/cider-nrepl {:mvn/version (or (emacs-cider-version) default-cider-version)})
      (:refactor-mw options)
      (assoc-dep 'refactor-nrepl/refactor-nrepl {:mvn/version (or (emacs-refactor-nrepl-version) default-refactor-nrepl-version)} ))))

(defn find-free-nrepl-port [ctx]
  (assoc ctx :nrepl-port (free-port)))

(defn wait-for-nrepl [{:keys [nrepl-port] :as ctx}]
  (let [timeout 300000]
    (debug "Waiting for nREPL port to be reachable on" nrepl-port)
    (if-let [{:keys [took]} (wait/wait-for-port "localhost" nrepl-port {:timeout timeout :pause 1000})]
      (do
        (debug "nREPL port reachable after" (/ took 1000.0) "seconds")
        true)
      (do
        (warn "Couldn't connect to nREPL port on" nrepl-port " after " (/ timeout 1000) " seconds")
        false)))
  ;; if we connect to nREPL too soon after the port is opened things go wrong
  (Thread/sleep 3000)
  ctx)

(defn clojure-cli-args [{:keys [aliases nrepl-port middleware extra-deps] :as ctx}]
  (cond-> ["clojure"
           "-J-XX:-OmitStackTraceInFastThrow"
           (str/join ":" (cons "-A" aliases))]
    extra-deps
    (into ["-Sdeps" (pr-str {:deps extra-deps})])
    :->
    (into ["-M" "-m" "nrepl.cmdline" "--port" (str nrepl-port)])
    middleware
    (into ["--middleware" (pr-str middleware)])))

(defn start-nrepl [{:keys [options aliases nrepl-port env] :as ctx}]
  (let [args (clojure-cli-args ctx)]
    (apply info (map shellquote args))
    (process args {:env env :out :inherit :err :inherit}))
  ctx)

(defn maybe-connect-emacs [{:keys [options nrepl-port project-root] :as ctx}]
  (when (:cider-connect options)
    (debug "Connecting CIDER with project-dir" project-root)
    (eval-emacs
     `(~'cider-connect-clj (~'list
                            :host "localhost"
                            :port ~nrepl-port
                            :project-dir ~project-root))))
  ctx)

(def default-steps [find-free-nrepl-port
                    compute-middleware
                    compute-extra-deps
                    start-nrepl
                    wait-for-nrepl
                    maybe-connect-emacs])

(defn find-project-root []
  (loop [dir (.getParent (io/file *file*))]
    (if (or (not dir)
            (.exists (io/file dir "deps.edn")))
      dir
      (recur (.getParent (io/file dir))))))

(defn main
  ([{:keys [steps extra-cli-opts executable project-root]
     :or {steps default-steps
          project-root (find-project-root)}}]
   (let [executable (or executable
                        (str/replace *file*
                                     (str project-root "/")
                                     ""))
         {:keys [options
                 arguments
                 summary
                 errors]}
         (tools-cli/parse-opts *command-line-args* (into cli-opts extra-cli-opts))]

     (when (or errors (:help options) (empty? arguments))
       (println (str executable " <options> [alias]+") )
       (println)
       (println summary)
       (System/exit 0))

     (let [ctx {:options (if (:emacs options)
                           (assoc options
                                  :cider-mw true
                                  :refactor-mw true
                                  :cider-connect true)
                           options)
                :aliases arguments
                :project-root project-root
                :env (into {} (System/getenv))}]
       (reduce #(%2 %1) ctx steps))

     @(promise))))

(comment
  (let [options {:cider-mw true
                 :refactor-mw true
                 :env (into {} (System/getenv))
                 :project-root project-root}]
    (reduce #(%2 %1)
            {:options options}
            [find-free-nrepl-port
             compute-middleware
             compute-extra-deps
             #_start-nrepl
             #_wait-for-nrepl
             clojure-cli-args]))

  )
