# Unreleased

## Added

## Fixed

## Changed

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