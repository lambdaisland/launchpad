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