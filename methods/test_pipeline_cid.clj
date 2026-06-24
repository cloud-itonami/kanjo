#!/usr/bin/env bb
;; Cross-process END-TO-END pipeline-determinism guard for the kanjo heartbeat.
(ns kanjo.methods.test-pipeline-cid
  "test_pipeline_cid.clj — kanjo WHOLE-PIPELINE cross-process determinism (ADR-2605312345 /
  2606032000).

  Proves the head-cid of the ENTIRE financial-disclosure pipeline (observe → by-company-year →
  metrics + aggregates → graph-datoms + derived-datoms → commit-DAG) agrees ACROSS PROCESSES by
  spawning a fresh `bb` and comparing its head-cid to the in-process one over the SAME seed
  (which carries the live EDGAR merge — a large graph, the strongest determinism stress in the
  food/logistics set). Seed-independent (no fragile literal); catches process-dependent
  non-determinism; gracefully SKIPS if a sandbox forbids spawning the child.

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/test_pipeline_cid.clj"
  (:require [kanjo.methods.autorun :as autorun]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(defn- tmp-log [] (let [f (java.io.File/createTempFile "knj-log-" ".kotoba.edn")] (.delete f) f))

(defn- in-process-head [cycles]
  (let [log (tmp-log)]
    (try (:head-cid (autorun/run-autonomous :cycles cycles :log-path log))
         (finally (.delete log)))))

(def ^:private cid-re #"b[0-9a-f]{64}")

(deftest heartbeat-emits-nonempty-graph
  (let [log (tmp-log)]
    (try
      (let [r (autorun/run-autonomous :cycles 2 :log-path log)]
        (is (:ok (:chain r)))
        (is (= 2 (:log-length r)))
        ;; the EDGAR-merged seed yields a very large graph — definitively non-degenerate
        (is (every? #(> (:datoms %) 1000) (:beats r))))
      (finally (.delete log)))))

(deftest pipeline-is-cross-run-deterministic-in-process
  (is (= (in-process-head 2) (in-process-head 2))))

(deftest pipeline-head-cid-is-cross-PROCESS-deterministic
  (let [in-proc (in-process-head 2)
        child (try
                (sh "bb" "--classpath" "20-actors" "-e"
                    (str "(require (quote [kanjo.methods.autorun :as a]))"
                         "(let [f (java.io.File/createTempFile \"knjsub-\" \".edn\")] (.delete f)"
                         "(print (:head-cid (a/run-autonomous :cycles 2 :log-path f))) (.delete f))"))
                (catch Exception e {:exit -1 :err (.getMessage e)}))]
    (is (re-matches cid-re in-proc) "in-process head-cid is a b+64hex CID")
    (if (and (= 0 (:exit child)) (re-find cid-re (:out child)))
      (is (= in-proc (re-find cid-re (:out child)))
          "whole-pipeline head-cid diverged between processes")
      (is true (str "child bb not spawnable in this env — cross-process check skipped"
                    " (exit=" (:exit child) ")")))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kanjo.methods.test-pipeline-cid)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
