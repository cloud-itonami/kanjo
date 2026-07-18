(ns kanjo.tests.test-depgraph
  "kanjō 勘定 — depgraph cell tests (ADR-0003). The supply-graph join, the dependency
  metrics (依存関係), the readable-core subgraph, and the dependency-record shape."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kanjo.methods.kanjo-edn :as kanjo-edn]
            [kanjo.methods.depgraph :as dg]))

;; tiny fixture: a 3-supplier → shared-customer chokepoint
(def edges
  [{:from "org.corp.nl.asml" :to "org.corp.tw.tsmc" :criticality 0.95 :commodity ":litho" :sourcing ":representative"}
   {:from "org.corp.nl.asml" :to "org.corp.kr.samsung" :criticality 0.90 :commodity ":litho" :sourcing ":representative"}
   {:from "org.corp.tw.tsmc" :to "org.corp.us.apple" :criticality 0.80 :commodity ":chips" :sourcing ":representative"}
   {:from "org.corp.tw.tsmc" :to "org.corp.us.nvidia" :criticality 0.70 :commodity ":chips" :sourcing ":representative"}])

(def comps
  {"org.corp.nl.asml" {:name "ASML Holding" :sector ":semiconductors" :mcap 350.0}
   "org.corp.tw.tsmc" {:name "TSMC" :sector ":semiconductors" :mcap 950.0}
   "org.corp.us.apple" {:name "Apple" :sector ":electronics" :mcap 3500.0}})

(def rev {"org.corp.us.apple" {:revenue 391035.0 :unit ":usd" :fy 2024}})

(def chokepoint-seed
  (-> (io/file *file*) .getParentFile .getParentFile .getParentFile .getParentFile
      (io/file "data" "seed-supply-chokepoints.kotoba.edn") .getAbsolutePath))

(deftest out-criticality-finds-chokepoints
  (let [m (dg/node-metrics edges)]
    ;; ASML supplies 2 (Σ 1.85); TSMC supplies 2 (Σ 1.50) AND depends on ASML (in 0.95)
    (is (= 2 (get-in m ["org.corp.nl.asml" :customers])))
    (is (< 1.84 (get-in m ["org.corp.nl.asml" :out-crit]) 1.86))
    (is (< 1.49 (get-in m ["org.corp.tw.tsmc" :out-crit]) 1.51))
    (is (< 0.94 (get-in m ["org.corp.tw.tsmc" :in-crit]) 0.96))
    ;; a pure customer has no out-criticality
    (is (nil? (get-in m ["org.corp.us.apple" :out-crit])))))

(deftest core-subgraph-keeps-top-suppliers-edges
  (let [m (dg/node-metrics edges)
        core (dg/core-edges edges m 1)]   ;; top-1 supplier = ASML
    (is (every? #(or (= "org.corp.nl.asml" (:from %)) (= "org.corp.nl.asml" (:to %))) core))
    (is (= 2 (count core)))))

(deftest dependency-records-carry-provenance-and-join
  (let [recs (dg/dependency-records edges comps rev "2026-01-01T00:00:00Z")
        apple-edge (first (filter #(= "org.corp.us.apple" (get % ":dep/to")) recs))]
    (is (= ":supplier" (get apple-edge ":dep/relation")))
    (is (= "TSMC" (get apple-edge ":dep/from-name")))
    (is (true? (get apple-edge ":dep/customer-disclosed?")))    ;; Apple has disclosed financials
    (is (= ":representative" (get apple-edge ":dep/sourcing")))
    ;; a non-disclosed customer is honestly flagged false
    (is (false? (get (first (filter #(= "org.corp.kr.samsung" (get % ":dep/to")) recs))
                     ":dep/customer-disclosed?")))))

(deftest chokepoint-seed-anchors-non-us-suppliers
  ;; the :representative chokepoint seed must JOIN the supply graph by org.corp.* key
  ;; so the systemic non-US chokepoints carry disclosed revenue (task #3 increment)
  (let [rows (kanjo-edn/read-file chokepoint-seed)
        filings (filter #(get % ":fin.filing/id") rows)
        facts (filter #(get % ":fin.fact/id") rows)
        rev (dg/disclosed-revenue facts filings)]
    (is (contains? rev "org.corp.tw.tsmc"))
    (is (contains? rev "org.corp.nl.asml"))
    (is (contains? rev "org.corp.jp.denso"))
    ;; primary-disclosure source only (G1, ADR-0004 extended set) + every fact :representative (G5)
    (is (every? #(contains? #{":edgar" ":edinet" ":companies-house" ":eu-oam" ":dart" ":cninfo"}
                            (get % ":fin.filing/source")) filings))
    ;; KRW/CNY/SEK units now ingestable — Samsung (KRW) + CATL (CNY) join the graph
    (is (= ":krw" (-> (filter #(= "fil.kr.dart.samsung-electronics.2024" (get % ":fin.filing/id")) filings)
                      first (get ":fin.filing/currency"))))
    (is (contains? rev "org.corp.cn.catl"))
    (is (every? #(= ":representative" (get % ":fin.fact/sourcing")) facts))
    ;; TSMC revenue is in the right order of magnitude (USD ~90bn, in :millions)
    (is (< 80000 (:revenue (rev "org.corp.tw.tsmc")) 100000))))

(deftest dot-is-renderable-and-honest
  (let [m (dg/node-metrics edges)
        dot (dg/->dot edges comps rev m)]
    (is (str/starts-with? dot "digraph"))
    (is (str/includes? dot "resilience map, not a target list"))   ;; G2 frame on the canvas
    (is (str/includes? dot "->"))))
