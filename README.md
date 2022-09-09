# Launchpad

<!-- badges -->
[![cljdoc badge](https://cljdoc.org/badge/com.lambdaisland/launchpad)](https://cljdoc.org/d/com.lambdaisland/launchpad) [![Clojars Project](https://img.shields.io/clojars/v/com.lambdaisland/launchpad.svg)](https://clojars.org/com.lambdaisland/launchpad)
<!-- /badges -->

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

Everyone has a right to submit patches to launchpad, and thus become a contributor.

Contributors MUST

- adhere to the [LambdaIsland Clojure Style Guide](https://nextjournal.com/lambdaisland/clojure-style-guide)
- write patches that solve a problem. Start by stating the problem, then supply a minimal solution. `*`
- agree to license their contributions as MPL 2.0.
- not break the contract with downstream consumers. `**`
- not break the tests.

Contributors SHOULD

- update the CHANGELOG and README.
- add tests for new functionality.

If you submit a pull request that adheres to these rules, then it will almost
certainly be merged immediately. However some things may require more
consideration. If you add new dependencies, or significantly increase the API
surface, then we need to decide if these changes are in line with the project's
goals. In this case you can start by [writing a pitch](https://nextjournal.com/lambdaisland/pitch-template),
and collecting feedback on it.

`*` This goes for features too, a feature needs to solve a problem. State the problem it solves, then supply a minimal solution.

`**` As long as this project has not seen a public release (i.e. is not on Clojars)
we may still consider making breaking changes, if there is consensus that the
changes are justified.
<!-- /contributing -->

<!-- license -->
## License

Copyright &copy; 2022 Arne Brasseur and Contributors

Licensed under the term of the Mozilla Public License 2.0, see LICENSE.
<!-- /license -->
