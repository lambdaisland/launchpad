(ns lambdaisland.launchpad.middleware
  "Launchpad-specific nREPL middleware")

;; [NOTE] This approach via middleware is the only way I've found to ensure that
;; this setting is off from the start.
(defn wrap-no-print-namespace-maps
  "Bind *print-namespace-maps* to false during nREPL requests"
  {:nrepl.middleware/descriptor {}}
  [h]
  (fn [msg]
    (binding [*print-namespace-maps* false]
      (h msg))))
