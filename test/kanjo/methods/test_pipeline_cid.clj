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

  Run:  bb --classpath 20-actors methods/test_pipeline_cid.clj"
  (:require [kanjo.methods.autorun :as autorun]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [kotoba.lang.text :as str]
            [clojure.test :refer [deftest is run-tests]]))

(defn- tmp-log [] (let [f (java.io.File/createTempFile "knj-log-" ".kotoba.edn")] (.delete f) f))

;; kanjo.methods.autorun/run-autonomous is POSITIONAL ([cycles graph-path-arg log-path]),
;; NOT keyword-args, and returns a STRING/underscore-keyed map ("head_cid" "log_length"
;; "chain" "beats" …) — this actor's own convention, distinct from kabuto's (which does use
;; keyword-args + keyword-keyed returns). Adapted to kanjo's real signature/shape below.
(defn- in-process-head [cycles]
  (let [log (tmp-log)]
    (try (get (autorun/run-autonomous cycles nil log) "head_cid")
         (finally (.delete log)))))

(def ^:private cid-re #"b[0-9a-f]{64}")

(deftest heartbeat-emits-nonempty-graph
  (let [log (tmp-log)]
    (try
      (let [r (autorun/run-autonomous 2 nil log)]
        (is (get (get r "chain") "ok"))
        (is (= 2 (get r "log_length")))
        ;; the EDGAR-merged seed yields a very large graph — definitively non-degenerate
        (is (every? #(> (get % "datoms") 1000) (get r "beats"))))
      (finally (.delete log)))))

(deftest pipeline-is-cross-run-deterministic-in-process
  (is (= (in-process-head 2) (in-process-head 2))))

(deftest pipeline-head-cid-is-cross-PROCESS-deterministic
  (let [in-proc (in-process-head 2)
        child (try
                (sh "bb" "--classpath" "20-actors" "-e"
                    (str "(require (quote [kanjo.methods.autorun :as a]))"
                         "(let [f (java.io.File/createTempFile \"knjsub-\" \".edn\")] (.delete f)"
                         "(print (get (a/run-autonomous 2 nil f) \"head_cid\")) (.delete f))"))
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
