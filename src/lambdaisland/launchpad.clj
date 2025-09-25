(ns lambdaisland.launchpad
  (:require
   [babashka.wait :as wait]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [lambdaisland.launchpad.env :as env]
   [lambdaisland.launchpad.log :refer :all]
   [lambdaisland.cli :as cli])
  (:import
   (java.io InputStream OutputStream)
   (java.lang Process ProcessBuilder)
   (java.net ServerSocket)
   (java.util.concurrent TimeUnit)))

(defonce processes (atom []))

(defn cleanup [sig]
  (println "[launchpad] Received" (str sig))
  (doseq [process @processes]
    (print "[launchpad] Killing pid" (str (.pid process) "..."))
    (flush)
    (.destroy process)
    (println (str " exit=" (.waitFor process)))
    (flush))
  (System/exit 0))

(defmacro set-signal-handler!
  [signal f]
  `(sun.misc.Signal/handle
    (sun.misc.Signal. ~signal)
    (proxy [sun.misc.SignalHandler] []
      (handle [signal#] (~f signal#)))))

(set-signal-handler! "INT" cleanup)
(set-signal-handler! "TERM" cleanup)

(def flags
  ["-v,--verbose"          {:doc   "Print debug information"
                            :value true}
   "-p,--nrepl-port PORT"  {:doc      "Start nrepl on port. Defaults to 0 (= random)"
                            :parse-fn #(Integer/parseInt %)}
   "-b,--nrepl-bind ADDR"  {:doc     "Bind address of nrepl, by default \"127.0.0.1\"."
                            :default "127.0.0.1"}
   "--[no-]emacs"          {:doc "Shorthand for --cider-nrepl --refactor-nrepl --cider-connect"}
   "--[no-]vs-code"        {:doc "Alias for --cider-nrepl"}
   "--[no-]cider-nrepl"    {:doc "Include CIDER nREPL dependency and middleware"}
   "--[no-]refactor-nrepl" {:doc "Include refactor-nrepl dependency and middleware"}
   "--[no-]cider-connect"  {:doc "Automatically connect Emacs CIDER"}
   "--[no-]portal"         {:doc "Include djblue/portal as a dependency, and define (user/portal)"}
   "--[no-]sayid"          {:doc "Include Sayid dependency and middleware"}
   "--[no-]debug-repl"     {:doc "Include gfredericks/debug-repl dependency and middleware"}
   "--[no-]go"             {:doc "Call (user/go) on boot"}
   "--[no-]namespace-maps" {:doc "Disable *print-namespace-maps* through nREPL middleware"}
   "--[no-]prefix"         {:doc "Disable per-process prefix"}])

(def library-versions
  (:deps (edn/read-string (slurp (io/resource "launchpad/deps.edn")))))

;; (def default-launchpad-coords
;;   "Version coordinates for Launchpad, which we use to inject ourselves into the
;;   project dependencies for runtime support. Only used when we are unable to find
;;   the current version in `bb.edn`"
;;   {:mvn/version "RELEASE"})

(defn shellquote [a]
  (let [a (str a)]
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
      a)))

(def ansi-fg-color-codes
  {:black 30
   :red 31
   :green 32
   :yellow 33
   :blue 34
   :magenta 35
   :cyan 36
   :white 37})

(defn ansi-bold [& parts]
  (str "\u001b[1m" (str/join " " parts) "\u001b[0m"))

(defn ansi-fg [color & parts]
  (str "\u001b[" (if (keyword? color)
                   (get ansi-fg-color-codes color)
                   color) "m"
       (str/join " " parts)
       "\u001b[0m"))

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

(defn emacs-cider-coords
  "Find the CIDER version that is currently in use by the running Emacs instance."
  []
  (when (emacs-require 'cider)
    {:mvn/version
     (read-string
      (eval-emacs '(if (boundp 'cider-required-middleware-version)
                     cider-required-middleware-version
                     (upcase cider-version))))}))

(defn emacs-refactor-nrepl-coords
  "Find the refactor-nrepl version that is required by the `clj-refactor` version
  installed in Emacs."
  []
  (when (emacs-require 'clj-refactor)
    {:mvn/version
     (read-string
      (eval-emacs 'cljr-injected-middleware-version))}))

(defn add-nrepl-middleware [& mws]
  (fn [ctx]
    (update ctx :middleware (fnil into []) mws)))

(defn library-in-deps? [ctx libname]
  (or (contains? (get-in ctx [:deps-edn :deps]) libname)
      (some
       (fn [extra-deps]
         (contains? extra-deps libname))
       (map (comp :extra-deps val)
            (select-keys (get-in ctx [:deps-edn :aliases])
                         (:aliases ctx))))))

(defn assoc-extra-dep [ctx libname & [version]]
  (if (library-in-deps? ctx libname)
    ctx
    (update ctx :extra-deps
            assoc libname
            (or version
                (get library-versions libname)))))

(defn find-launchpad-coords []
  (when (.exists (io/file "bb.edn"))
    (get-in (edn/read-string (slurp "bb.edn")) [:deps 'com.lambdaisland/launchpad])))

(defn inject-optional-deps-and-middleware [{:keys [options] :as ctx}]
  (cond-> ctx
    true
    (-> (assoc-extra-dep 'nrepl/nrepl)
        (assoc-extra-dep 'com.lambdaisland/launchpad (find-launchpad-coords)))

    (:cider-nrepl ctx)
    (-> (assoc-extra-dep 'cider/cider-nrepl (emacs-cider-coords))
        ((add-nrepl-middleware 'cider.nrepl/cider-middleware)))

    (:refactor-nrepl ctx)
    (-> (assoc-extra-dep 'refactor-nrepl/refactor-nrepl (emacs-refactor-nrepl-coords))
        ((add-nrepl-middleware 'refactor-nrepl.middleware/wrap-refactor)))

    (:sayid ctx)
    (-> (assoc-extra-dep 'com.billpiel/sayid)
        ((add-nrepl-middleware 'com.billpiel.sayid.nrepl-middleware/wrap-sayid)))

    (:debug-repl ctx)
    (-> (assoc-extra-dep 'com.gfredericks/debug-repl)
        ((add-nrepl-middleware 'com.gfredericks.debug-repl/wrap-debug-repl)))

    (false? (:namespace-maps ctx))
    ((add-nrepl-middleware 'lambdaisland.launchpad.middleware/wrap-no-print-namespace-maps))

    (:portal ctx)
    (-> (assoc-extra-dep 'djblue/portal)
        (update :eval-forms (fnil conj [])
                '(when-not (resolve 'user/portal)
                   (do
                     (intern 'user 'portal-instance (atom nil))
                     (intern
                      'user
                      (with-meta 'portal {:doc "Open a Portal window and register a tap handler for it. The result can be
  treated like an atom."})
                      (fn portal
                        []
                        (let [p ((requiring-resolve 'portal.api/open) @@(resolve 'user/portal-instance))]
                          (reset! @(resolve 'user/portal-instance) p)
                          (add-tap (requiring-resolve 'portal.api/submit))
                          p)))))))))

(defn get-nrepl-port [ctx]
  (assoc ctx :nrepl-port (or (:nrepl-port ctx)
                             (free-port))))

(defn get-nrepl-bind [ctx]
  (assoc ctx :nrepl-bind (:nrepl-bind ctx)))

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
        (update :java-args (fnil into []) (concat
                                           (:launchpad/jvm-opts deps-system)
                                           (:launchpad/jvm-opts deps-local)))
        (merge
         (:launchpad/options deps-system)
         (:launchpad/options deps-local))
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
  (cond
    (some? (:emacs ctx))
    (merge {:cider-nrepl (:emacs ctx)
            :refactor-nrepl (:emacs ctx)
            :cider-connect (:emacs ctx)}
           (dissoc ctx :emacs))
    (some? (:vs-code ctx))
    (merge {:cider-nrepl (:emacs ctx)}
           (dissoc ctx :vs-code))
    :else
    ctx))

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

(defn stack-traces-to-stderr
  "Make Clojure print out its stack traces, instead of writing them to a file"
  [ctx]
  (update ctx
          :java-args conj
          "-Dclojure.main.report=stderr"))

(defn jdk-allow-attach-self
  "Set the allowAttachSelf property, needed on JDK21+ in order to cancel
  evaluation."
  [ctx]
  (update ctx
          :java-args conj
          "-Djdk.attach.allowAttachSelf"))

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

(defn clojure-cli-args [{:keys [trace-load? aliases requires nrepl-port java-args middleware extra-deps paths alias-defs eval-forms] :as ctx}]
  (cond-> (:clojure-cli-command ctx ["clojure"])
    :-> (into (map #(str "-J" %)) java-args)
    (seq aliases)
    (conj (str/join (cons "-A" aliases)))
    (or extra-deps paths alias-defs)
    (into ["-Sdeps" (pr-str {:deps extra-deps
                             :paths paths
                             :aliases alias-defs})])
    :->
    (into ["-M" "-e" (pr-str `(do
                                ~(when trace-load?
                                   '(alter-var-root (var clojure.core/*loading-verbosely*) (constantly true)))
                                ~(when (seq requires)
                                   (cond->
                                       (list* 'require (map #(list 'quote %) requires))
                                     trace-load? (concat [:verbose])))
                                ~@eval-forms))])
    middleware
    (into [])))

(defn maybe-go [{:keys [options] :as ctx}]
  (cond-> ctx
    (:go ctx)
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
    (assoc-extra-dep <> 'com.lambdaisland/classpath)
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
                        ['(lambdaisland.classpath.watch-deps/canonical-path "deps.local.edn")])
         :launchpad/extra-deps `'~(:extra-deps <>)}))))

(defn watch-dotenv [ctx]
  (-> ctx
      (assoc-extra-dep 'com.github.jnr/jnr-posix)
      (assoc-extra-dep 'com.lambdaisland/classpath)
      (update :java-args conj
              "--add-opens=java.base/java.lang=ALL-UNNAMED"
              "--add-opens=java.base/java.util=ALL-UNNAMED")
      (update :env #(->> env/watch-paths
                         (map env/->path)
                         (filter env/exists?)
                         (map env/parse-dotenv)
                         (apply merge %)))
      (update :requires concat ['lambdaisland.launchpad.env 'lambdaisland.launchpad.env.hacks])
      (register-watch-handlers
       `(lambdaisland.launchpad.env.hacks/watch-handlers
         {:watch-paths ~(mapv #(list '.resolve 'lambdaisland.classpath.watch-deps/process-root-path %)
                              env/watch-paths)
          :parse-fn   'lambdaisland.launchpad.env/parse-dotenv}))))

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
          (assoc-extra-dep 'thheller/shadow-cljs)
          ;; tools.deps pulls in an old Guava, which causes issues with shadow-cljs
          (assoc-extra-dep 'com.google.guava/guava)
          (update :middleware (fnil conj []) 'shadow.cljs.devtools.server.nrepl/middleware)
          (assoc :shadow-cljs/build-ids build-ids)
          (assoc :shadow-cljs/connect-ids connect-ids)
          (update :eval-forms (fnil conj [])
                  '(require 'lambdaisland.launchpad.shadow)
                  `(let [config# (lambdaisland.launchpad.shadow/merged-config)]
                     (apply
                      lambdaisland.launchpad.shadow/start-builds! config#
                      (filter (set (keys (:builds config#)))
                              ~(vec build-ids))))))
      ctx)))

(defn maybe-connect-emacs [{:keys [options nrepl-port project-root] :as ctx}]
  (when (:cider-connect ctx)
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
  (println (ansi-fg :green "Launching")
           (ansi-bold (ansi-fg :green "Clojure"))
           (ansi-fg :green "on nREPL port")
           (ansi-fg :magenta (:nrepl-port ctx)))
  (let [opts (filter (comp true? val) ctx)
        aliases (:aliases ctx)]
    (when (seq opts)
      (println (ansi-fg :green "Options:")
               (str/join ", " (map (comp (partial ansi-fg :magenta) name key) opts))))
    (when (seq aliases)
      (println (ansi-fg :green "Aliases:")
               (str/join ", " (map (comp (partial ansi-fg :magenta) str) aliases)))))
  ;; #_(apply println "Java flags: " (:java-args ctx))
  ;; (println "\nMiddleware: " )
  ;; (doseq [a (:middleware ctx)] (println "-" a))
  ;; (print "\nExtra Deps:")
  ;; (pprint/print-table (map (fn [[k v]]
  ;;                            {:lib k
  ;;                             :coords v})
  ;;                          (:extra-deps ctx)))
  ctx)

(defn pipe-process-output
  "Prefix output from a process with, prefixing it"
  [^java.lang.Process proc prefix]
  (let [out (.getInputStream proc)
        err (.getErrorStream proc)
        newline? (volatile! true)
        ^bytes buffer (make-array Byte/TYPE 1024)]
    (doseq [[^InputStream from ^OutputStream to] [[out System/out] [err System/err]]]
      (future
        (loop []
          (let [size (.read from buffer)]
            (when (pos? size)
              (dotimes [i size]
                (when @newline?
                  (when prefix
                    (.write to (.getBytes prefix)))
                  (vreset! newline? false))
                (let [b (aget buffer i)]
                  (.write to (int b))
                  (when (= (int \newline) b)
                    (vreset! newline? true))))))
          (Thread/sleep 100)
          (recur))))
    proc))

(defn run-process
  ([ctx opts]
   ((run-process opts) ctx))
  ([{:keys [cmd prefix working-dir
            background? timeout-ms check-exit-code? env
            show-command?]
     :or {working-dir "."
          check-exit-code? true
          show-command? true}}]
   (fn [ctx]
     (let [working-dir  (io/file working-dir)
           proc-builder (doto (ProcessBuilder. (map str cmd))
                          (.inheritIO)
                          (.directory working-dir))
           _ (.putAll (.environment proc-builder) (or env (:env ctx)))
           color (mod (hash (or prefix (first cmd))) 8)
           prefix (str "[" (ansi-fg (+ 30 color) (or prefix (first cmd))) "] ")
           process (pipe-process-output
                    (.start proc-builder)
                    (when-not (false? (:prefix ctx))
                      prefix))
           _ (swap! processes conj process)
           ctx (update ctx :processes (fnil conj []) process)]
       (when show-command?
         (apply println (str prefix "$") (map shellquote cmd)))
       (if background?
         ctx
         (let [exit (if timeout-ms
                      (.waitFor process timeout-ms TimeUnit/MILLISECONDS)
                      (.waitFor process))]
           (when (and check-exit-code? (not= 0 exit))
             (println prefix "Exited with non-zero exit code: " exit)
             (System/exit exit))
           ctx))))))

(defn start-clojure-process [{:keys [aliases nrepl-port] :as ctx}]
  (let [args (clojure-cli-args ctx)]
    (apply debug (map shellquote args))
    ((run-process {:cmd args
                   :ctx-process-key :clojure-process
                   :background? true
                   :show-command? false}) ctx)))

(def before-steps [handle-cli-args
                   get-nrepl-port
                   get-nrepl-bind
                   ;; inject dependencies and enable behavior
                   inject-optional-deps-and-middleware
                   include-hot-reload-deps
                   watch-dotenv
                   start-shadow-build
                   maybe-go
                   ;; extra java flags
                   disable-stack-trace-elision
                   stack-traces-to-stderr
                   jdk-allow-attach-self
                   inject-aliases-as-property
                   include-watcher
                   print-summary
                   run-nrepl-server])

(def after-steps [wait-for-nrepl
                  ;; stuff that happens after the server is up
                  maybe-connect-emacs])

(def default-steps (concat before-steps
                           [start-clojure-process]
                           after-steps))

(def ^:deprecated start-process start-clojure-process)

(defn find-project-root []
  (loop [dir (.getParent (io/file *file*))]
    (if (or (not dir)
            (.exists (io/file dir "deps.edn")))
      dir
      (recur (.getParent (io/file dir))))))

(defn initial-context [ctx]
  (let [project-root (:project-root ctx (find-project-root))]
    (-> {:main-opts *command-line-args*
         :executable (or (:executable ctx)
                         (str/replace *file*
                                      (str project-root "/")
                                      ""))
         :env (into {} (System/getenv))
         :steps (:default-steps ctx)
         :project-root project-root
         :middleware []
         :java-args []
         :eval-forms []}
        (merge ctx)
        read-deps-edn)))

(defn process-steps [ctx steps]
  (reduce #(%2 %1) ctx steps))

(defn run-launchpad [{:keys [steps
                             start-steps
                             end-steps
                             pre-steps
                             post-steps] :as ctx}]
  (let [ctx (process-steps
             (update ctx :aliases concat (map keyword (::cli/argv ctx)))
             (or steps
                 (concat
                  start-steps
                  before-steps
                  pre-steps
                  [start-clojure-process]
                  post-steps
                  after-steps
                  end-steps)))
        processes (:processes ctx)]
    (System/exit (apply min (for [p processes]
                              (.waitFor p))))))

(defn main [opts]
  (cli/dispatch* {:init (initial-context opts)
                  :flags flags
                  :command #'run-launchpad}))
