(ns lambdaisland.launchpad.log)

(def verbose? (some #{"-v" "--verbose"} *command-line-args*))

(defn debug [& args] (when verbose? (apply println #_(str (java.util.Date.)) "[DEBUG]" args)))
(defn info [& args] (apply println #_(str (java.util.Date.)) "[INFO]" args))
(defn warn [& args] (apply println #_(str (java.util.Date.)) "[WARN]" args))
(defn error [& args] (apply println #_(str (java.util.Date.)) "[ERROR]" args))
