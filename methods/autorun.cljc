(ns kanjo.methods.autorun
  "autorun.py — kanjō AUTONOMOUS financial-disclosure heartbeat on the kotoba Datom log.
  ADR-2606032000. 1:1 Clojure port of `methods/autorun.py`.

  Each heartbeat the actor runs its whole disclosure pipeline ITSELF, with no human in the loop:
    observe (load the OFFLINE merged disclosed-fact graph) → split filings / facts
      → by-company-year → derive ratios + YoY (:synthesized) → sector/currency aggregates
        (coverage-honest, no cross-currency FX sums)
      → PERSIST a content-addressed transaction to the append-only kotoba Datom log
        (graph datoms + derived :fin.metric + :fin.agg), linking the previous tx's CID.

  Constitutional posture holds by construction: only disclosed primary-filing FACTS + transparent
  ratios are representable — never a rating, valuation, solvency verdict, FORECAST, or buy/sell call
  (G2/G4); derived metrics/aggregates carry :sourcing :synthesized and are NEVER re-ingested as
  disclosed facts (G5); a restatement is a new fact + :superseded-by, never a deletion (G11, 非終末論).

  The loop is deterministic / resume-safe (cycle drives tx-id + as-of → same CIDs) and append-only.
  WHAT STAYS GATED (G7): it NEVER fetches live EDGAR/EDINET and NEVER pushes to a live kotoba node.
  Ingest is the offline merged/seed graph; persistence is the LOCAL append-only log.

  House style: requires only the GOOD sibling .cljc ports (kanjo-edn + analyze + kotoba), not any
  stub. (The Python `__main__` argparse demo printer is preserved behind #?(:clj …) as -main.)"
  (:require [kanjo.methods.kanjo-edn :as kanjo-edn]
            [kanjo.methods.analyze :as analyze]
            [kanjo.methods.kotoba :as kotoba]
            #?(:clj [clojure.java.io :as io])))

(def base-as-of 20260609)

#?(:clj (def here (-> *file* io/file .getAbsoluteFile .getParentFile)))
#?(:clj (def data (when here (io/file (.getParentFile here) "data"))))
#?(:clj (def merged (when data (io/file data "facts.merged.kotoba.edn"))))
#?(:clj (def seed (when data (io/file data "seed-financial-facts.kotoba.edn"))))
#?(:clj (def log-default (when data (io/file data "kanjo.datoms.kotoba.edn"))))

#?(:clj
   (defn- graph-path
     "Resolve the disclosed-fact graph path: explicit > merged (if present) > seed."
     [graph-path]
     (cond
       (some? graph-path) graph-path
       (and merged (.exists ^java.io.File merged)) merged
       :else seed)))

#?(:clj
   (defn run-cycle
     "One autonomous heartbeat: observe → derive ratios/YoY + aggregates → persist a
     content-addressed Datom transaction (graph + derived :fin.metric + :fin.agg). cycle drives
     tx-id + as-of."
     ([cycle] (run-cycle cycle nil log-default))
     ([cycle graph-path-arg log-path]
      (let [rows (kanjo-edn/read-file (str (graph-path graph-path-arg)))  ; observe — OFFLINE (G7: no live fetch)
            facts (filterv #(and (map? %) (contains? % ":fin.fact/id")) rows)
            filings (filterv #(and (map? %) (contains? % ":fin.filing/id")) rows)
            cy (analyze/by-company-year facts)
            metrics (analyze/derive-metrics cy)            ; :synthesized ratios + YoY (G5)
            aggs (analyze/aggregates cy)                   ; coverage-honest sector/currency aggregates
            datoms (into (kotoba/graph-datoms rows) (kotoba/derived-datoms metrics aggs))
            tx (kotoba/make-tx datoms :tx-id cycle :as-of (+ base-as-of cycle)
                               :prev-cid (kotoba/head-cid log-path))
            cid (kotoba/append-tx tx log-path)]            ; PERSIST to append-only LOCAL kotoba log
        {"cycle" cycle
         "filings" (count filings)
         "facts" (count facts)
         "companies" (count cy)
         "metrics" (count metrics)
         "aggregates" (count aggs)
         "datoms" (count datoms)
         "cid" cid}))))

#?(:clj
   (defn run-autonomous
     ([] (run-autonomous 3 nil log-default))
     ([cycles] (run-autonomous cycles nil log-default))
     ([cycles graph-path-arg log-path]
      (let [beats (mapv #(run-cycle % graph-path-arg log-path)
                        (range 1 (inc cycles)))]
        {"cycles" cycles
         "beats" beats
         "log_length" (count (kotoba/read-log log-path))
         "head_cid" (kotoba/head-cid log-path)
         "chain" (kotoba/verify-chain log-path)}))))

#?(:clj
   (defn -main
     "CLI entry: run N autonomous heartbeats → LOCAL kotoba Datom log. --cycles/--graph/--log/--fresh
     (file I/O at the edge)."
     [& argv]
     (let [argv (vec argv)
           arg-after (fn [flag dflt] (let [i (.indexOf argv flag)]
                                       (if (>= i 0) (nth argv (inc i)) dflt)))
           cycles (let [v (arg-after "--cycles" nil)] (if v (Long/parseLong v) 3))
           graph-arg (let [g (arg-after "--graph" nil)] (when g (io/file g)))
           log-path (io/file (arg-after "--log" (str log-default)))]
       (when (and (some #{"--fresh"} argv) (.exists log-path)) (.delete log-path))
       (let [res (run-autonomous cycles graph-arg log-path)]
         (println (str "# kanjo — AUTONOMOUS financial-disclosure over the kotoba Datom log "
                       "(offline ingest, LOCAL persist; live EDGAR/EDINET / live-node push stays G7-gated)\n"))
         (doseq [bt (get res "beats")]
           (println (str "  ♥ cycle " (get bt "cycle") ": " (get bt "filings")
                         " filings / " (get bt "facts") " facts / "
                         (get bt "companies") " companies · metrics " (get bt "metrics")
                         " · aggregates " (get bt "aggregates")
                         " +" (get bt "datoms") " datoms → cid " (subs (get bt "cid") 0 14) "…")))
         (let [ch (get res "chain")]
           (println (str "\n  log: " (get res "log_length") " tx · head "
                         (subs (get res "head_cid") 0 14) "… · chain "
                         (if (get ch "ok") "OK ✓" (str "BROKEN at " (get ch "broken_at")))
                         " · disclosed facts + :synthesized ratios, non-adjudicating / no forecast (G2/G4)")))))))
