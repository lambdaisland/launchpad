# 0.39.171-alpha (2025-02-25 / 0731c0a)

## Fixed

- Bring back passing in aliases at the command line

# 0.38.168-alpha (2025-02-25 / 6f20908)

## Changed

- Upgrade dependencies

# 0.37.162-alpha (2025-02-11 / 6ec2e55)

## Fixed

- Add an `:nrepl.middleware/descriptor` to silence the warning

# 0.36.159-alpha (2025-02-09 / eafa135)

## Added

- `--[no-]namespace-maps` option (disable `*print-namespace-maps*`)

## Changed

- Boolean flags are now defined as `--[no-]...`, so that you can override
  local/global/project defaults from the command line, e.g. `bin/launchpad
  --no-go`

# 0.35.156-alpha (2025-01-09 / 0592491)

## Fixed

- Fix `--emacs` flag

# 0.34.152-alpha (2024-12-04 / 37c62ad)

## Changed

- Use lambdaisland/cli for cli arg handling
- Dependency upgrades

# 0.33.149-alpha (2024-07-24 / cf4dc37)

## Fixed

- Re-release of previous version (missed an unmerged commit)

# 0.32.144-alpha (2024-07-24 / 9e20958)

## Fixed

- Update the launchpad version loaded at runtime

# 0.31.142-alpha (2024-07-15 / 35813ab)

## Added

- Warn when merging shadow-cljs configs and build-ids conflict

# 0.30.139-alpha (2024-06-12 / 3221f94)

## Changed

- Make sure child processes are killed when launchpad receives a INT/TERM/KILL signal

# 0.29.135-alpha (2024-06-03 / 00df813)

## Added

- Add new flag `:trace-load?`, which sets the `*loading-verbosely*` var at startup
- Support watching entire directories through the file watcher interface

# 0.28.129-alpha (2024-02-27 / 0c8c519)

## Added

- Print aliases being used
- Add two-arg (uncurried) `run-process`

# 0.27.126-alpha (2024-02-26 / 5f25d2d)

## Changed

- Merge consecutive calls to `lambdaisland.launchpad.watcher/watch!`, so it's
  easier to use from user code
- Don't show full clojure invocation by default, can be seen with `--verbose`

# 0.26.123-alpha (2024-01-29 / d471611)

## Fixed

- Fix the `--portal`/`:portal` option

# 0.25.120-alpha (2024-01-22 / cd0da82)

## Added

- Additional flags for injection Portal, Sayid, debug-repl
- `--vs-code` as alias for `--cider-nrepl`
- Allow setting options as a map (:launchpad/options) in `deps.local.edn` /
  `~/clojure/deps.edn`

## Changed

- More succinct and nice looking output on boot. Only show nREPL port and launchpad flags.

# 0.24.117-alpha (2024-01-22 / 8008ab4)

## Changed

- Immediately initialize the context with the current system environment, so
  that it is available even in `start-steps`.

# 0.23.114-alpha (2024-01-22 / dd05ea6)

## Added

- Inject shadow-cljs deps when needed and not already present
- Support for more fine grained step customization with `start-steps`/`end-steps`/`pre-steps`/`post-steps`

## Changed

- Check deps before injecting extra deps (cider, nrepl, shadow, etc). Declared
  deps versions always get precedence.
- More succinct output while starting up
- Show command line invocations that are being run

# 0.22.110-alpha (2024-01-17 / 95c22dc)

## Added

- Allow setting :java-args, :middleware, :eval-forms directly from the top-level opts
- Add facilities for running nested processes, `lauchpad/run-process`

# 0.21.106-alpha (2024-01-10 / 8fc8faf)

## Fixed

- Add an explicit dependency on guava, to make sure we don't get old versions
  that clash with shadow-cljs

# 0.20.103-alpha (2024-01-10 / 1a5e504)

## Changed

- Upgrade dependencies

# 0.18.97-alpha (2023-11-14 / 4275a6c)

## Fixed

- Fix an issue with `.env` reloading messing up Java's env var representation

# 0.17.93-alpha (2023-09-06 / 84369b0)

## Added

- Added a `:launchpad/shadow-connect-ids` option, for cases where you don't want
  to auto-connect to every build in `:launchpad/shadow-build-ids`

# 0.16.88-alpha (2023-03-08 / 67de06e)

## Changed

- Bump dependencies

# 0.15.79-alpha (2023-01-20 / 2b06d8e)

## Added

- Allow setting nrepl port/bind from CLI
- Provide a warning when connecting to emacs fails, rather than exiting

## Changed

- Dependency version bumps

# 0.14.72-alpha (2022-12-12 / 06eed64)

## Added

- Add support for top-level shadow-cljs config/builds, not only sub-project builds

## Fixed

- Better handle `:paths` in `deps.local.edn`, ensure they are picked up at boot,
  not only at first classpath reload

# 0.13.67-alpha (2022-11-25 / 07ac499)

## Added

- Support setting :lauchpad/main-opts in .clojure/deps.edn

# 0.12.64-alpha (2022-10-26 / a4fdb16)

## Added

- Write the current classpath to `.cpcache/launchpad.cp`, for integrating third
  parties like clojure-lsp. (configure `cat .cpache/launchpad.cp` as your
  `:classpath-cmd`)
- Call `(user/go)` in a `try/catch`
- Start the watcher on a separate thread, it can take a long time to boot, and
  meanwhile we shouldn't block REPL startup.

## Fixed

- Pick up any `:deps` from `deps.local.edn` at startup, not at the first
  classpath reload
  
## Changed

- Disable directory-watcher file hashing, it gets prohibitively slow

# 0.11.59-alpha (2022-10-21 / 8454771)

## Fixed

- Fixes cljdoc build. There should be no changes to launchpad users.

# 0.9.49-alpha (2022-10-12 / d0123fe)

## Fixed

- Couple the lifetime of the Clojure process to the lifetime of launchpad, exit
  launchpad when the process dies, and kill the process when launchpad exits

# 0.8.46-alpha (2022-10-07 / 4c68918)

## Fixed

- Fixed some of the watching behavior
- Watch files even if they don't yet exist (pick up when they get created)
- Better deal with `:aliases {...}` in `deps.local.edn`
- Clean up output

## Added

- `--go` flag, automatically call `(user/go)`

# 0.7.39-alpha (2022-09-22 / 1ec66df)

## Fixed

- Fixed previous botched release

# 0.6.36-alpha (2022-09-22 / 1755f62)

## Fixed

- Handle missing `.env` or `.env.local`

# 0.5.33-alpha (2022-09-22 / c37380a)

## Fixed

- Handle missing `deps.local.edn`

# 0.4.29-alpha (2022-09-22 / 3ce3eb7)

## Fixed

- Make sure `--cider-nrepl` works, even when `emacsclient` is not available,
  e.g. for Calva

# 0.3.26-alpha (2022-09-20 / 79a8d9a)

## Added

- Watch .env / .env.local

## Changed

- Improve shadow-cljs support

# 0.2.20-alpha (2022-09-15 / d95503f)

## Added
- Add nrepl/nrepl as an extra dependency by default (#1)

# 0.1.14-alpha (2022-09-09 / 6796c21)

Initial release

## Added

- Load and watch deps.edn and deps.local.edn
- lambdaisland.classpath integration
- Support for cider-nrepl, refactor-nrepl
- Basic support for shadow-cljs cljs nREPL-base REPL
- Auto-connect for Emacs
