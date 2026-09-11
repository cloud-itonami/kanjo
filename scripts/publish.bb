#!/usr/bin/env kbb
;; kanjō self-publish — thin wrapper delegating to the SHARED kototama organism runtime
;; (kototama/lib/actor/publish.bb). Actor config is actor.edn. Run: bb methods/publish.bb [--live]
(require '[babashka.process :refer [shell]])
(def root (-> *file* (java.io.File.) .getAbsoluteFile .getParentFile .getParentFile))
(def runtime (str root "/../../com-junkawasaki/kototama/lib/actor/publish.bb"))
(apply shell "bb" runtime "--actor" (str root) *command-line-args*)
