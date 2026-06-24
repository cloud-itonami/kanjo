#!/usr/bin/env bb
;; Working Clojure port of methods/autorun.py — the autonomous financial-disclosure heartbeat.
(ns kanjo.methods.autorun
  "kanjō 勘定 — AUTONOMOUS financial-disclosure heartbeat on the kotoba Datom log (ADR-2606032000).

  Each heartbeat: observe (OFFLINE merged/seed graph, G7 no live feed) → split filings/facts →
  by-company-year → derive ratios + YoY + sector/currency aggregates → PERSIST a content-addressed
  transaction (graph datoms + derived :fin.metric + :fin.agg) linking the prev CID. Deterministic /
  resume-safe; no external I/O; live EDGAR/EDINET push stays G7-gated. Non-adjudicating / no-advice /
  no-forecast (G2/G4); every derived datom :sourcing :synthesized (G5).

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/autorun.clj --cycles 3 --fresh"
  (:require [kanjo.methods.kanjo-edn :as ke]
            [kanjo.methods.analyze :as a]
            [kanjo.methods.kotoba :as k]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private this-file *file*)
(defn- data-dir [] (-> this-file io/file .getAbsoluteFile .getParentFile .getParentFile (io/file "data")))
(defn- merged [] (io/file (data-dir) "facts.merged.kotoba.edn"))
(defn- seed [] (io/file (data-dir) "seed-financial-facts.kotoba.edn"))
(defn- default-log [] (io/file (data-dir) "kanjo.datoms.kotoba.edn"))
(def BASE-AS-OF 20260608)

(defn- graph-path [gp] (or gp (let [m (merged)] (if (.exists m) m (seed)))))

(defn run-cycle
  [cycle & {:keys [graph-path* log-path]}]
  (let [log-path (or log-path (default-log))
        gp (graph-path graph-path*)
        rows (ke/read-file gp)
        [filings facts] (a/load gp)
        cy (a/by-company-year facts)
        metrics (a/derive-metrics cy)
        aggs (a/aggregates cy)
        datoms (vec (concat (k/graph-datoms rows) (k/derived-datoms metrics aggs)))
        tx (k/make-tx datoms :tx-id cycle :as-of (+ BASE-AS-OF cycle) :prev-cid (k/head-cid log-path))
        cid (k/append-tx tx log-path)]
    {:cycle cycle :filings (count filings) :facts (count facts) :companies (count cy)
     :metrics (count metrics) :aggregates (count aggs) :datoms (count datoms) :cid cid}))

(defn run-autonomous [& {:keys [cycles graph-path* log-path] :or {cycles 3}}]
  (let [log-path (or log-path (default-log))
        beats (mapv #(run-cycle % :graph-path* graph-path* :log-path log-path) (range 1 (inc cycles)))]
    {:cycles cycles :beats beats :log-length (count (k/read-log log-path))
     :head-cid (k/head-cid log-path) :chain (k/verify-chain log-path)}))

(defn -main [& argv]
  (let [args (vec argv)
        cyc-idx (.indexOf args "--cycles")
        cycles (if (>= cyc-idx 0) (Integer/parseInt (nth args (inc cyc-idx))) 3)
        log-idx (.indexOf args "--log")
        log-path (if (>= log-idx 0) (io/file (nth args (inc log-idx))) (default-log))]
    (when (and (some #{"--fresh"} args) (.exists (io/file log-path))) (.delete (io/file log-path)))
    (let [res (run-autonomous :cycles cycles :log-path log-path)]
      (println (str "# kanjō — AUTONOMOUS financial-disclosure observation over the kotoba Datom log "
                    "(offline ingest, LOCAL persist; live EDGAR/EDINET stays G7-gated)\n"))
      (doseq [bt (:beats res)]
        (println (format "  ♥ cycle %d: %d filings / %d facts / %d cos · %d metrics · %d aggregates +%d datoms → cid %s…"
                         (:cycle bt) (:filings bt) (:facts bt) (:companies bt) (:metrics bt)
                         (:aggregates bt) (:datoms bt) (subs (:cid bt) 0 14))))
      (let [ch (:chain res)]
        (println (format "\n  log: %d tx · head %s… · chain %s · disclosed facts + :synthesized ratios, never a verdict (G2/G4/G5)"
                         (:log-length res) (subs (:head-cid res) 0 14)
                         (if (:ok ch) "OK ✓" (str "BROKEN at " (:broken-at ch)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
