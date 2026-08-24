(ns repl-sessions.poke
  (:require
   [lambdaisland.classpath :as cp]
   [clojure.java.io :as io]))

(io/resource "lambdaisland/uri.cljc")

(require 'lambdaisland.uri)

(clojure.lang.RT/baseLoader)

(cp/classloader-chain)
(cp/classloader-chain
 (cp/context-classloader))

(cp/classloader-chain
 (clojure.lang.RT/baseLoader))

@clojure.lang.Compiler/LOADER
