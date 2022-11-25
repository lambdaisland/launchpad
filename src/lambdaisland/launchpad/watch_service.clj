(ns lambdaisland.launchpad.watch-service
  (:import
   (io.methvin.watchservice MacOSXListeningWatchService MacOSXListeningWatchService$Config)
   (java.net URI)
   (java.nio.file Path Paths FileSystems WatchEvent WatchEvent$Kind
                  StandardWatchEventKinds)))

(defn watch-service []
  (if (.. System (getProperty "os.name") toLowerCase (contains "mac"))
    (MacOSXListeningWatchService.
     (reify MacOSXListeningWatchService$Config
       (fileHasher [_])))
    (.newWatchService (FileSystems/getDefault))))

(def ws (watch-service))

(.register (Path/of (URI. "file:///home/arne/tmp/"))
           ws
           (into-array WatchEvent$Kind
                       [StandardWatchEventKinds/ENTRY_CREATE
                        StandardWatchEventKinds/ENTRY_MODIFY]
                       ))

(bean (.poll ws))

(def wk (.take ws))
