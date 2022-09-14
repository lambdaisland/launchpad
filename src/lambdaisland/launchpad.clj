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
   [nil "--cider-nrepl" "Include the CIDER nREPL middleware"]
   [nil "--refactor-nrepl" "Include the refactor-nrepl middleware"]
   [nil "--cider-connect" "Automatically connect CIDER"]
   [nil "--emacs" "Shorthand for --cider-nrepl --refactor-nrepl --cider-connect"]])

(def default-nrepl-version "1.0.0")
(def default-cider-version "0.28.3")
(def default-refactor-nrepl-version "3.5.2")
(def classpath-coords
  {:mvn/version "0.4.44"}
  #_{:local/root "/home/arne/github/lambdaisland/classpath"})

(def default-launchpad-coords
  "Version coordinates for Launchpad, which we use to inject ourselves into the
  project dependencies for runtime support. Only used when we are unable to find
  the current version in `bb.edn`"
  {:mvn/version "RELEASE"})

(def verbose? (some #{"-v" "--verbose"} *command-line-args*))

(defn debug [& args] (when verbose? (apply println "[DEBUG]" args)))
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
      (:cider-nrepl options)
      (add-mw 'cider.nrepl/cider-middleware)
      (:refactor-nrepl options)
      (add-mw 'refactor-nrepl.middleware/wrap-refactor))))

(defn compute-extra-deps [{:keys [options] :as ctx}]
  (let [assoc-dep #(update %1 :extra-deps assoc %2 %3)]
    (cond-> ctx
      true
      (assoc-dep 'nrepl/nrepl {:mvn/version default-nrepl-version})
      (:cider-nrepl options)
      (assoc-dep 'cider/cider-nrepl {:mvn/version (or (emacs-cider-version) default-cider-version)})
      (:refactor-nrepl options)
      (assoc-dep 'refactor-nrepl/refactor-nrepl {:mvn/version (or (emacs-refactor-nrepl-version) default-refactor-nrepl-version)} ))))

(defn find-free-nrepl-port [ctx]
  (assoc ctx :nrepl-port (free-port)))

(defn read-deps-edn [ctx]
  (let [deps-edn (edn/read-string (slurp "deps.edn") )
        deps-local (when (.exists (io/file "deps.local.edn"))
                     (edn/read-string (slurp "deps.local.edn") ))]

    (-> ctx
        (update :aliases (fnil into []) (:launchpad/aliases deps-local))
        (update :main-opts (fnil into []) (:launchpad/main-opts deps-local))
        (assoc :deps-edn (merge-with (fn [a b]
                                       (cond
                                         (and (map? a) (map? b))
                                         (merge a b)
                                         (and (vector? a) (vector? b))
                                         (into a b)
                                         :else
                                         b))
                                     deps-edn deps-local)))))

(defn handle-cli-args [{:keys [executable project-root deps-edn main-opts] :as ctx}]
  (let [{:keys [options
                arguments
                summary
                errors]}
        (tools-cli/parse-opts main-opts cli-opts)]
    (when (or errors (:help options))
      (let [aliases (keys (:aliases deps-edn))]
        (println (str executable " <options> [" (str/join "|" (map #(subs (str %) 1) aliases)) "]+") ))
      (println)
      (println summary)
      (System/exit 0))

    (-> ctx
        (update :env (fnil into {}) (System/getenv))
        (update :aliases (fnil into []) (map keyword arguments))
        (assoc :options (if (:emacs options)
                          (assoc options
                                 :cider-nrepl true
                                 :refactor-nrepl true
                                 :cider-connect true)
                          options)))))

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

(defn clojure-cli-args [{:keys [aliases nrepl-port middleware extra-deps eval-forms] :as ctx}]
  (cond-> ["clojure"
           "-J-XX:-OmitStackTraceInFastThrow"
           (str "-J-Dlambdaisland.launchpad.aliases=" (str/join "," (map #(subs (str %) 1) aliases)))
           #_(str "-J-Dlambdaisland.launchpad.extra-dep-source=" (pr-str {:deps extra-deps}))
           ]
    (seq aliases)
    (conj (str/join (cons "-A" aliases)))
    extra-deps
    (into ["-Sdeps" (pr-str {:deps extra-deps})])
    :->
    (into ["-M" "-e" (pr-str `(do ~@eval-forms))])
    middleware
    (into [])))

(defn run-nrepl-server [{:keys [nrepl-port middleware] :as ctx}]
  (update ctx :eval-forms (fnil conj [])
          '(require 'nrepl.cmdline)
          `(nrepl.cmdline/-main "--port" ~(str nrepl-port) "--middleware" ~(pr-str middleware))))

(defn include-hot-reload-deps [{:keys [extra-deps aliases] :as ctx}]
  (as-> ctx <>
    (update <> :extra-deps assoc 'com.lambdaisland/classpath classpath-coords)
    (update <> :eval-forms (fnil conj [])
            '(require 'lambdaisland.classpath.watch-deps
                      'lambdaisland.launchpad.deps)
            `(lambdaisland.classpath.watch-deps/start!
              ~{:aliases (mapv keyword aliases)
                :include-local-roots? true
                :basis-fn 'lambdaisland.launchpad.deps/basis
                :watch-paths (when (.exists (io/file "deps.local.edn"))
                               ['(lambdaisland.classpath.watch-deps/canonical-path "deps.local.edn")])
                :launchpad/extra-deps `'~(:extra-deps <>)}))))

(defn start-shadow-build [{:keys [deps-edn aliases] :as ctx}]
  (let [build-ids (concat (:launchpad/shadow-build-ids deps-edn)
                          (mapcat #(get-in deps-edn [:aliases % :launchpad/shadow-build-ids]) aliases))]
    ;; FIXME filter this down to builds that exist in the combined shadow config
    (when (seq build-ids)
      (debug "Starting shadow-cljs builds" build-ids))
    (if (seq build-ids)
      (-> ctx
          (update :middleware (fnil conj []) 'shadow.cljs.devtools.server.nrepl/middleware)
          (assoc :shadow-cljs/build-ids build-ids)
          (update :eval-forms (fnil conj [])
                  '(require 'lambdaisland.launchpad.shadow)
                  `(lambdaisland.launchpad.shadow/start-builds!
                    ~@build-ids)))
      ctx)))

(defn find-launchpad-coords []
  (or
   (when (.exists (io/file "bb.edn"))
     (get-in (edn/read-string (slurp "bb.edn")) [:deps 'com.lambdaisland/launchpad]))
   default-launchpad-coords))

(defn include-launchpad-deps [{:keys [extra-deps] :as ctx}]
  (update ctx :extra-deps assoc 'com.lambdaisland/launchpad (find-launchpad-coords)))

(defn start-process [{:keys [options aliases nrepl-port env] :as ctx}]
  (let [args (clojure-cli-args ctx)]
    (apply info (map shellquote args))
    (let [process (process args {:env env :out :inherit :err :inherit})]
      (future
        (System/exit (.waitFor process)))
      (assoc ctx :clojure-process process))))

(defn maybe-connect-emacs [{:keys [options nrepl-port project-root] :as ctx}]
  (when (:cider-connect options)
    (debug "Connecting CIDER with project-dir" project-root)
    (eval-emacs
     `(~'let ((~'repl (~'cider-connect-clj (~'list
                                            :host "localhost"
                                            :port ~nrepl-port
                                            :project-dir ~project-root))))

       ~@(for [build-id (:shadow-cljs/build-ids ctx)
               :let [init-sym (symbol "launchpad" (name build-id))]]
           `(~'progn
             (~'setf (~'alist-get '~init-sym
                      ~'cider-cljs-repl-types)
              (~'list ~(pr-str
                        `(shadow.cljs.devtools.api/nrepl-select ~build-id))))
             (~'cider-connect-sibling-cljs (~'list
                                            :cljs-repl-type '~init-sym
                                            :host "localhost"
                                            :port ~nrepl-port
                                            :project-dir ~project-root)))))))
  ctx)

(def default-steps [find-free-nrepl-port
                    read-deps-edn
                    handle-cli-args
                    compute-middleware
                    compute-extra-deps
                    include-hot-reload-deps
                    include-launchpad-deps
                    run-nrepl-server
                    start-process
                    wait-for-nrepl
                    maybe-connect-emacs
                    ])

(defn find-project-root []
  (loop [dir (.getParent (io/file *file*))]
    (if (or (not dir)
            (.exists (io/file dir "deps.edn")))
      dir
      (recur (.getParent (io/file dir))))))

(defn main
  ([{:keys [steps executable project-root]
     :or {steps default-steps
          project-root (find-project-root)}}]

   (reduce #(%2 %1)
           {:main-opts *command-line-args*
            :executable (or executable
                            (str/replace *file*
                                         (str project-root "/")
                                         ""))
            :project-root project-root}
           steps)
   @(promise)))
