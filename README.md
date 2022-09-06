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
checked in, not yet implemented) and arguments passed in on the command line to
determine which modules to include, which middleware to add to nREPL, which
shadow-cljs builds to start, etc.


## Features

<!-- installation -->
## Installation

To use the latest release, add the following to your `bb.edn`: 

```
com.lambdaisland/launchpad {:mvn/version "0.0.0"}
```

or add the following to your `project.clj` ([Leiningen](https://leiningen.org/))

```
[com.lambdaisland/launchpad "0.0.0"]
```
<!-- /installation -->

## Rationale

## Usage

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
