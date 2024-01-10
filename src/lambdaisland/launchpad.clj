(ns lambdaisland.launchpad
  (:require [babashka.curl :as curl]
            [babashka.process :refer [process]]
            [babashka.wait :as wait]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.tools.cli :as tools-cli]
            [lambdaisland.dotenv :as dotenv])
  (:import java.net.ServerSocket))

(def cli-opts
  [["-h" "--help"]
   ["-v" "--verbose" "Print debug information"]
   ["-p" "--nrepl-port PORT" "Start nrepl on port. Defaults to 0 (= random)"
    :parse-fn #(Integer/parseInt %)]
   ["-b" "--nrepl-bind ADDR" "Bind address of nrepl, by default \"127.0.0.1\"."
    :default "127.0.0.1"]
   [nil "--cider-nrepl" "Include the CIDER nREPL middleware"]
   [nil "--refactor-nrepl" "Include the refactor-nrepl middleware"]
   [nil "--cider-connect" "Automatically connect CIDER"]
   [nil "--emacs" "Shorthand for --cider-nrepl --refactor-nrepl --cider-connect"]
   [nil "--go" "Call (user/go) on boot"]])

(def default-nrepl-version "1.0.0")

;; Unless we have a mechanism of automatically updating these I would use
;; `RELEASE` here, so non-emacs user always default to the latest version. This
;; is a good candidate for making this configurable, for explicitness.
(def default-cider-version "RELEASE")
(def default-refactor-nrepl-version "RELEASE")

(def classpath-coords {:mvn/version "0.5.48"})
(def jnr-posix-coords {:mvn/version "3.1.18"})

(def default-launchpad-coords
  "Version coordinates for Launchpad, which we use to inject ourselves into the
  project dependencies for runtime support. Only used when we are unable to find
  the current version in `bb.edn`"
  {:mvn/version "RELEASE"})

(def verbose? (some #{"-v" "--verbose"} *command-line-args*))

(defn debug [& args] (when verbose? (apply println (java.util.Date.) "[DEBUG]" args)))
(defn info [& args] (apply println (java.util.Date.) "[INFO]" args))
(defn warn [& args] (apply println (java.util.Date.) "[WARN]" args))
(defn error [& args] (apply println (java.util.Date.) "[ERROR]" args))

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
  (try
    (= (str lib) (str/trim (eval-emacs `(~'require '~lib))))
    (catch java.io.IOException e
      ;; We swallow this because if Emacs isn't installed we want to simply
      ;; return `nil`. People might want the CIDER or refactor-nrepl middlewares
      ;; even though they are not running Emacs.
      )))

(defn emacs-cider-version
  "Find the CIDER version that is currently in use by the running Emacs instance."
  []
  (when (emacs-require 'cider)
    (read-string
     (eval-emacs '(if (boundp 'cider-required-middleware-version)
                    cider-required-middleware-version
                    (upcase cider-version))))))

(defn emacs-refactor-nrepl-version
  "Find the refactor-nrepl version that is required by the `clj-refactor` version
  installed in Emacs."
  []
  (when (emacs-require 'clj-refactor)
    (read-string
     (eval-emacs 'cljr-injected-middleware-version))))

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
      (assoc-dep 'refactor-nrepl/refactor-nrepl {:mvn/version (or (emacs-refactor-nrepl-version) default-refactor-nrepl-version)}))))

(defn get-nrepl-port [ctx]
  (assoc ctx :nrepl-port (or (get-in ctx [:options :nrepl-port])
                          (free-port))))

(defn get-nrepl-bind [ctx]
  (assoc ctx :nrepl-bind (get-in ctx [:options :nrepl-bind])))

(defn maybe-read-edn [f]
  (when (.exists f) (edn/read-string (slurp f))))

(defn read-deps-edn [ctx]
  (let [deps-edn (edn/read-string (slurp "deps.edn"))
        deps-system (maybe-read-edn
                     (io/file (System/getProperty "user.home") ".clojure" "deps.edn"))

        deps-local (maybe-read-edn
                    (io/file "deps.local.edn"))]

    (-> ctx
        (update :aliases (fnil into []) (concat
                                         (:launchpad/aliases deps-system)
                                         (:launchpad/aliases deps-local)))
        (update :main-opts (fnil into []) (concat
                                           (:launchpad/main-opts deps-system)
                                           (:launchpad/main-opts deps-local)))
        (assoc :deps-edn (merge-with (fn [a b]
                                       (cond
                                         (and (map? a) (map? b))
                                         (merge a b)
                                         (and (vector? a) (vector? b))
                                         (into a b)
                                         :else
                                         b))
                                     deps-edn deps-local))
        ;; Pull these out and inject them into -Sdeps, otherwise they are only
        ;; picked up with the next reload
        (update :extra-deps merge (:deps deps-local))
        (assoc :paths (concat (:paths deps-edn) (:paths deps-local)))
        ;; It seems like if we set `{:aliases {}}` via `-Sdeps` it overrides
        ;; deps.edn aliases, rather than merging them, so we merge them
        ;; ourselves and pass them all to -Sdeps. Needs more testing to see if
        ;; this is really necessary.
        (assoc :alias-defs (merge (:aliases deps-edn)
                                  (:aliases deps-local))))))

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

(defn disable-stack-trace-elision [ctx]
  (update ctx
          :java-args conj
          "-XX:-OmitStackTraceInFastThrow"))

(defn inject-aliases-as-property [{:keys [aliases] :as ctx}]
  (update ctx
          :java-args conj
          (str "-Dlambdaisland.launchpad.aliases=" (str/join "," (map #(subs (str %) 1) aliases)))))

(defn include-watcher [{:keys [watch-handlers] :as ctx}]
  (if watch-handlers
    (-> ctx
        (update :requires conj 'lambdaisland.launchpad.watcher)
        (update :eval-forms (fnil conj [])
                `(future
                   (lambdaisland.launchpad.watcher/watch! ~watch-handlers))))))

(defn clojure-cli-args [{:keys [aliases requires nrepl-port java-args middleware extra-deps paths alias-defs eval-forms] :as ctx}]
  (cond-> ["clojure"]
    :-> (into (map #(str "-J" %)) java-args)
    (seq aliases)
    (conj (str/join (cons "-A" aliases)))
    (or extra-deps paths alias-defs)
    (into ["-Sdeps" (pr-str {:deps extra-deps
                             :paths paths
                             :aliases alias-defs})])
    :->
    (into ["-M" "-e" (pr-str `(do ~(when (seq requires)
                                     (list* 'require (map #(list 'quote %) requires)))
                                  ~@eval-forms))])
    middleware
    (into [])))

(defn maybe-go [{:keys [options] :as ctx}]
  (cond-> ctx
    (:go options)
    (update :eval-forms (fnil conj [])
            '(try
               (user/go)
               (catch Exception e
                 (println "(user/go) failed" e))))))

(defn run-nrepl-server [{:keys [nrepl-port nrepl-bind middleware] :as ctx}]
  (-> ctx
      (update :requires conj 'nrepl.cmdline)
      (update :eval-forms (fnil conj [])
              `(nrepl.cmdline/-main "--port" ~(str nrepl-port)
                                    "--bind" ~(str nrepl-bind)
                                    "--middleware" ~(pr-str middleware)))))

(defn register-watch-handlers [ctx handlers]
  (update ctx
          :watch-handlers
          (fn [h]
            (if h
              `(~'merge ~h ~handlers)
              handlers))))

(defn include-hot-reload-deps [{:keys [extra-deps aliases] :as ctx}]
  (as-> ctx <>
    (update <> :extra-deps assoc 'com.lambdaisland/classpath classpath-coords)
    (update <> :requires conj 'lambdaisland.launchpad.deps)
    (update <> :eval-forms (fnil conj [])
            `(lambdaisland.launchpad.deps/write-cpcache-file))
    (register-watch-handlers
     <>
     `(lambdaisland.launchpad.deps/watch-handlers
       ~{:aliases (mapv keyword aliases)
         :include-local-roots? true
         :basis-fn 'lambdaisland.launchpad.deps/basis
         :watch-paths (when (.exists (io/file "deps.local.edn"))
                        ;; FIXME: this means we don't "see" deps.local.edn if it
                        ;; gets created after launchpad started, we can do
                        ;; better than that.
                        ['(lambdaisland.classpath.watch-deps/canonical-path "deps.local.edn")]
                        [])
         :launchpad/extra-deps `'~(:extra-deps <>)}))))

(defn watch-dotenv [ctx]
  (-> ctx
      (update :extra-deps assoc 'com.github.jnr/jnr-posix jnr-posix-coords)
      (update :java-args conj
              "--add-opens=java.base/java.lang=ALL-UNNAMED"
              "--add-opens=java.base/java.util=ALL-UNNAMED")
      (update :env #(apply merge % (map (fn [p]
                                          (when (.exists (io/file p))
                                            (dotenv/parse-dotenv (slurp p))))
                                        [".env" ".env.local"])))
      (update :requires conj 'lambdaisland.launchpad.env)
      (register-watch-handlers '(lambdaisland.launchpad.env/watch-handlers))))

(defn start-shadow-build [{:keys [deps-edn aliases] :as ctx}]
  (let [build-ids (->> aliases
                       (mapcat #(get-in deps-edn [:aliases % :launchpad/shadow-build-ids]))
                       (concat (:launchpad/shadow-build-ids deps-edn))
                       distinct)
        connect-ids (->> aliases
                         (mapcat #(get-in deps-edn [:aliases % :launchpad/shadow-connect-ids]))
                         (concat (:launchpad/shadow-connect-ids deps-edn))
                         distinct)
        connect-ids (if (empty? connect-ids)
                      build-ids
                      connect-ids)]
    ;; FIXME filter this down to builds that exist in the combined shadow config
    (when (seq build-ids)
      (debug "Starting shadow-cljs builds" build-ids))
    (if (seq build-ids)
      (-> ctx
          (update :middleware (fnil conj []) 'shadow.cljs.devtools.server.nrepl/middleware)
          (assoc :shadow-cljs/build-ids build-ids)
          (assoc :shadow-cljs/connect-ids connect-ids)
          (update :eval-forms (fnil conj [])
                  '(require 'lambdaisland.launchpad.shadow)
                  `(apply
                    lambdaisland.launchpad.shadow/start-builds!
                    (filter (set (keys (:builds (lambdaisland.launchpad.shadow/merged-config))))
                            ~(vec build-ids)))))
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
    (apply debug (map shellquote args))
    (let [process (process args {:env env :out :inherit :err :inherit})]
      (assoc ctx :clojure-process process))))

(defn maybe-connect-emacs [{:keys [options nrepl-port project-root] :as ctx}]
  (when (:cider-connect options)
    (debug "Connecting CIDER with project-dir" project-root)
    (try
      (eval-emacs
       `(~'let ((~'repl (~'cider-connect-clj (~'list
                                              :host "localhost"
                                              :port ~nrepl-port
                                              :project-dir ~project-root))))

         ~@(for [connect-id (:shadow-cljs/connect-ids ctx)
                 :let [init-sym (symbol "launchpad" (name connect-id))]]
             `(~'progn
               (~'setf (~'alist-get '~init-sym
                        ~'cider-cljs-repl-types)
                (~'list ~(pr-str
                          `(shadow.cljs.devtools.api/nrepl-select ~connect-id))))
               (~'cider-connect-sibling-cljs (~'list
                                              :cljs-repl-type '~init-sym
                                              :host "localhost"
                                              :port ~nrepl-port
                                              :project-dir ~project-root))))))
      (catch java.io.IOException e
        (warn "Attempt to connect to emacs failed with exception: " (ex-message e)))))
  ctx)

(defn print-summary [ctx]
  (println "Aliases:")
  (doseq [a (:aliases ctx)] (println "-" a))
  #_(apply println "Java flags: " (:java-args ctx))
  (println "\nMiddleware: " )
  (doseq [a (:middleware ctx)] (println "-" a))
  (print "\nExtra Deps:")
  (pprint/print-table (map (fn [[k v]]
                             {:lib k
                              :coords v})
                           (:extra-deps ctx)))
  ctx)

(def before-steps [
                   read-deps-edn
                   handle-cli-args
                   get-nrepl-port
                   get-nrepl-bind
                   compute-middleware
                   ;; inject dependencies and enable behavior
                   compute-extra-deps
                   include-hot-reload-deps
                   include-launchpad-deps
                   watch-dotenv
                   start-shadow-build
                   maybe-go
                   ;; extra java flags
                   disable-stack-trace-elision
                   inject-aliases-as-property
                   ;; start the actual process
                   include-watcher
                   run-nrepl-server
                   print-summary])

(def after-steps [wait-for-nrepl
                  ;; stuff that happens after the server is up
                  maybe-connect-emacs])

(def default-steps (concat before-steps
                           [start-process]
                           after-steps))

(defn find-project-root []
  (loop [dir (.getParent (io/file *file*))]
    (if (or (not dir)
            (.exists (io/file dir "deps.edn")))
      dir
      (recur (.getParent (io/file dir))))))

(defn initial-context [{:keys [steps executable project-root]
                        :or {steps default-steps
                             project-root (find-project-root)}}]
  {:main-opts *command-line-args*
   :executable (or executable
                   (str/replace *file*
                                (str project-root "/")
                                ""))
   :project-root project-root})

(defn process-steps [ctx steps]
  (reduce #(%2 %1) ctx steps))

(defn main
  ([{:keys [steps] :or {steps default-steps} :as opts}]
   (let [ctx (process-steps (initial-context opts) steps)
         process (:clojure-process ctx)]
     (.addShutdownHook (Runtime/getRuntime)
                       (Thread. (fn [] (.destroy (:proc process)))))
     (System/exit (:exit @process)))))
