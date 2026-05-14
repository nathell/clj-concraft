(ns user)

(require '[nextjournal.clerk :as clerk])

(clerk/serve! {:browse? false, :port 7654, :watch-paths ["notebooks"]})
