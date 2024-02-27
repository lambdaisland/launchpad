# Launchpad

<!-- badges -->
[![cljdoc badge](https://cljdoc.org/badge/com.lambdaisland/launchpad)](https://cljdoc.org/d/com.lambdaisland/launchpad) [![Clojars Project](https://img.shields.io/clojars/v/com.lambdaisland/launchpad.svg)](https://clojars.org/com.lambdaisland/launchpad)
<!-- /badges -->

[**Watch the video!**](https://www.youtube.com/watch?v=kn9nvHEgzJY)

Launchpad is a Clojure dev process launcher.

It starts from these observations:

- Clojure development is done interactively
- This requires a nREPL connection between a Clojure process and an editor
- How Clojure/nREPL gets started varies by
  - editor (which middleware to include?)
  - project (how to start the system, cljs config)
  - individual (preferences in tooling, local roots)
- Projects are increasingly multi-module, either monorepo, or spread across repos
- We mainly rely on our editors to launch Clojure/nREPL because it is tedious
- Other tools could benefit from participating in the startup sequence (e.g. lambdaisland/classpath)
- Automating startup is done in an editor-specific way (.dir-locals.el, calva.replConnectSequences)
- And requires copying boilerplate around (user.clj)

And these preferences:

- We want project setup to be self-contained, so starting a process "just works"
- This should work for everyone on the team, no matter what editor they use
- We prefer running the process in a terminal for cleaner separation and control
- When working on multiple related projects we prefer a single JVM over multiple

## How it works

Launchpad is a babashka-compatible library, through a number of steps it builds
up a Clojure command line invocation, including JVM options, extra dependencies,
and forms to evaluate, including starting nREPL with selected middleware,
watching `deps.edn` files for changes, starting ClojureScript build watchers,
and hooking up your editor (if supported, otherwise a manual connect to nREPL is
needed).

It takes information from `deps.edn` (checked in) and `deps.local.edn` (not
checked in) and arguments passed in on the command line to determine which
modules to include, which middleware to add to nREPL, which shadow-cljs builds
to start, etc.

Launchpad integrates with `lambdaisland.classpath` to provide hot-reloading of
`deps.edn` and `deps.local.edn`, so you can add dependencies to your project,
switch a dependency to a newer version, or from a Maven-provided jar to a local
checkout, all without restarting your process and REPL. You can even activate
additional aliases without restarting. How cool is that?

## Project setup

See `template` for an example setup, you need a few different pieces.

* `bb.edn`

```clj
{:deps {com.lambdaisland/launchpad { ... }}}
```

* `bin/launchpad`

This is the default convention for launchpad-based projects, it provides a
recognizable, predictable entry point that people will be looking for in your
project, so use it. This is a simple babashka script invoking launchpad, but
there's lots of room to customize this. Want to check the Java version, launch
docker-compose, ensure environment variables are set up? You do that here.

```clj
#!/usr/bin/env bb

(require '[lambdaisland.launchpad :as launchpad])

(launchpad/main {})

;; (launchpad/main {:steps (into [(partial launchpad/ensure-java-version 17)]
;;                               launchpad/default-steps)})
```

* `deps.edn`

You need a top-level `deps.edn`, where you reference your sub-projects with
aliases and `:local/root`.

```clj
{:deps
 {... good place for dev tooling like portal, scope-capture ...}

 ;; Monorepo setup with two subprojects, if you have a multi-repo setup then use
 ;; paths like `"../proj1"`
 :aliases
 {:proj1
  {:deps {com.example/proj1 {:local/root "proj1"}}}

  :proj2
  {:deps {com.example/proj2 {:local/root "proj2"}}}}}
```

`proj1` and `proj2` are then folders with their own `deps.edn`, `src`, etc.

* `.gitignore`

Make sure to `.gitignore` the `deps.local.edn` file, this is where you can do
individual configuration.

```shell
echo deps.local.edn >> .gitignore
```

* `deps.local.edn`

This follows the structure like `deps.edn`, and gets passed to tools.deps as an
extra source, but there are a few special keys here that you can use to
configure launchpad.

```clj
{;; regular deps.edn stuff will work in here
 :deps {}
 :aliases {}

 ;; but some extra keys are supported to influence launchpad itself
 :launchpad/aliases [:proj1] ; additional aliases, will be added to whatever
                             ; aliases you specify on the command line
 :launchpad/main-opts ["--emacs"] ; additional CLI flags, so you can encode your
                                  ; own preferences
 :launchpad/shadow-build-ids [] ; which shadow builds to start, although it may
                                ; be preferable to configure this as part of
                                ; specific aliases in your main deps.edn
 ;; which shadow builds to automatically connect to if `--emacs` flag is provided
 :launchpad/shadow-connect-ids [] 
 }
```

You don't have to stop there, you could add a `dev/user.clj` (add "dev" to your
`:paths` in that case), add other useful scripts or repl sessions, maybe you
even want to put cross-project integration tests in this repo, but the above is
the main stuff you need.

## Using Launchpad

When invoking `bin/launchpad` you pass it any aliases you want to start, plus a
number of optional flags. These currently allow injecting the CIDER and/or
refactor-nrepl middleware, which will suffice to use launchpad with Emacs/CIDER,
VS Code/Calva, and probably others.

Please do file issues on Github for your favorite editor environment, we want to
eventually support every Clojure editor out there with a non-negligible user
base.

Emacs is currently best supported, since we are able to query Emacs to find
which versions of cider-nrepl and refactor-nrepl we should inject, and are also
able to instruct Emacs to connect to the REPL, so the whole process is smooth
and automated. This level of integration will not be possible with every editor,
but we can look into what options we have.

```clj
➜ bin/launchpad --help
bin/launchpad <options> [proj1|proj2]+

  -h, --help
  -v, --verbose         Print debug information
      --cider-nrepl     Include the CIDER nREPL middleware
      --refactor-nrepl  Include the refactor-nrepl middleware
      --cider-connect   Automatically connect CIDER
      --emacs           Shorthand for --cider-nrepl --refactor-nrepl --cider-connect
```

For example

```
➜ bin/launchpad backend frontend --emacs
[INFO] clojure -J-XX:-OmitStackTraceInFastThrow -J-Dlambdaisland.launchpad.aliases=backend,frontend -A:backend:frontend -Sdeps '{:deps {cider/cider-nrepl #:mvn{:version "0.28.5"}, refactor-nrepl/refactor-nrepl #:mvn{:version "3.5.2"}, com.lambdaisland/classpath #:mvn{:version "0.4.44"}, com.lambdaisland/launchpad #:local{:root "/home/arne/github/lambdaisland/launchpad"}}}' -M -e '(do (require (quote lambdaisland.classpath.watch-deps) (quote lambdaisland.launchpad.deps)) (lambdaisland.classpath.watch-deps/start! {:aliases [:backend :frontend], :include-local-roots? true, :basis-fn lambdaisland.launchpad.deps/basis, :watch-paths [(lambdaisland.classpath.watch-deps/canonical-path "deps.local.edn")], :launchpad/extra-deps (quote {cider/cider-nrepl #:mvn{:version "0.28.5"}, refactor-nrepl/refactor-nrepl #:mvn{:version "3.5.2"}, com.lambdaisland/classpath #:mvn{:version "0.4.44"}})}) (require (quote nrepl.cmdline)) (nrepl.cmdline/-main "--port" "41545" "--middleware" "[cider.nrepl/cider-middleware refactor-nrepl.middleware/wrap-refactor]"))'
nREPL server started on port 41545 on host localhost - nrepl://localhost:41545
```

At this point in Emacs you'll see this pop up:

```
[nREPL] Establishing direct connection to localhost:41545 ...
[nREPL] Direct connection to localhost:41545 established
```

For other editors connect to the given port manually.

## Writing custom steps

Launchpad performs a number of steps, currently these are

- `read-deps-edn` :  Read in `deps.edn`, `~/.clojure/deps.edn`, and `deps.local.edn`
- `handle-cli-args` : Parse CLI arguments
- `get-nrepl-port` : Figure out the nREPL port, either from a CLI argument or use a free port
- `get-nrepl-bind` : Figure out which device to bind to
- `compute-middleware` : Figure out the nREPL middleware to use (optionally CIDER, refactor-nrepl, and whatever else is configured)
- `compute-extra-deps` : Add additional dependencies if needed, like nREPL, CIDER, refactor-nrepl
- `include-hot-reload-deps` : Add `lambdaisland.classpath` and set it up for hot reloading deps.edn
- `include-launchpad-deps` : Add launchpad itself as a dependency, since it contains runtime helpers for dotenv and shadow-cljs handling
- `watch-dotenv` : Setup watch handlers to hot-reload `.env` and `.env.local`
- `start-shadow-build` : Start the shadow-cljs build process, if necessary
- `maybe-go` : Add `(user/go)` to the list of code to run on startup
- `disable-stack-trace-elision` : Add `-XX:-OmitStackTraceInFastThrow` to the JVM flags
- `inject-aliases-as-property` : Add `-Dlambdaisland.launchpad.aliases` as a JVM flag, so we know at runtime which aliases we started with
- `include-watcher` : Start up the main watcher which will handle deps.edn/.env watching
- `run-nrepl-server` : Add startup code to start nREPL
- `print-summary` : Print an overview of aliases and extra dependencies that are being included
- `start-process` : This is where we actually start Clojure
- `wait-for-nrepl` : Wait for nREPL to be available
- `maybe-connect-emacs` : Instruct Emacs to connect to nREPL

Each steps works on a context (`ctx`) map, mostly what you do is add stuff into
that context, which gets used to construct the final clojure/JVM startup
command. These are some of the keys in the context that you can read, set, or
update:

- `:java-args` : sequence of JVM arguments, without the leading `-J`
- `:requires` : namespaces to load (sequence of symbols)
- `:eval-forms` : code to evaluate (sequence of forms)
- `:options` : the result of parsing command line flags (map)
- `:watch-handlers` : map from file path to filesystem change handler function
- `:extra-deps` : additional dependencies to load, map from artifact name (symbol), to deps.edn coordinates map
- `:env` : environment variables to set (gets populated with `.env`/`.env.local`)
- `:middleware` : nREPL middleware to include, sequence of fully qualified symbols
- `:shadow-cljs/build-ids` : shadow-cljs build-ids to start
- `:shadow-cljs/connect-ids` : shadow-cljs build-ids to connect a REPL to
- `:clojure-process` : the Java Process object, once Clojure has started
- `:nrepl-port` : nREPL TCP port
- `:nrepl-bind` : nREPL device IP
- `:aliases` : deps.edn aliases in use
- `:main-opts` : CLI command line options (seq of string)
- `:deps-edn` : merged deps.edn map
- `:paths` : paths to add to the classpath

Most of the time you want to add extra steps either right before, or right after
`start-process`. The vars `before-steps` and `after-steps` are useful for that.

```clj
(require '[lambdaisland.launchpad :as launchpad]
         '[babashka.process :refer [process]])

(defn npm-install [ctx]
  (process '[npm install] {:out :inherit
                           :err :inherit})
  ctx)

(launchpad/main
 {:steps
  (concat launchpad/before-steps
          [npm-install
           launchpad/start-process]
          launchpad/after-steps)})
```

<!-- opencollective -->
## Lambda Island Open Source

<img align="left" src="https://github.com/lambdaisland/open-source/raw/master/artwork/lighthouse_readme.png">

&nbsp;

launchpad is part of a growing collection of quality Clojure libraries created and maintained
by the fine folks at [Gaiwan](https://gaiwan.co).

Pay it forward by [becoming a backer on our Open Collective](http://opencollective.com/lambda-island),
so that we may continue to enjoy a thriving Clojure ecosystem.

You can find an overview of our projects at [lambdaisland/open-source](https://github.com/lambdaisland/open-source).

&nbsp;

&nbsp;
<!-- /opencollective -->

<!-- contributing -->
## Contributing

Launchpad is not currently open to external contributions.

<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2022 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
