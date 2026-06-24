(ns kanjo.tests.test-kanjo
  "kanjō 勘定 — unit tests. Clojure port of tests/test_kanjo.py (ADR-2606032000).

  Covers concept-map GAAP normalization, analyze derived ratios + YoY + aggregates,
  the ingest primary-disclosure parsers, and the minimal EDN reader round-trip — 1:1
  with the Python assertions (the seed graph drives the same arithmetic)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kanjo.methods.kanjo-edn :as kanjo-edn]
            [kanjo.methods.concept-map :as cmap]
            [kanjo.methods.analyze :as analyze]
            [kanjo.methods.ingest :as ingest]))

(def seed
  (-> (io/file *file*) .getParentFile .getParentFile
      (io/file "data" "seed-financial-facts.kotoba.edn") .getAbsolutePath))

;; ── concept-map: GAAP normalization ──────────────────────────────────────────

(deftest cross-gaap-revenue-normalizes-to-one-concept
  (is (= "revenue" (cmap/canonical "jppfs_cor:NetSales" "jgaap")))
  (is (= "revenue" (cmap/canonical "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax" "usgaap")))
  (is (= "revenue" (cmap/canonical "ifrs-full:Revenue" "ifrs"))))

(deftest ordinary-income-is-jgaap-only
  (is (= "ordinary-income" (cmap/canonical "jppfs_cor:OrdinaryIncome" "jgaap")))
  (is (nil? (cmap/canonical "OrdinaryIncome" "usgaap")))
  (is (nil? (cmap/canonical "OrdinaryIncome" "ifrs")))
  (is (str/includes? (nth (get cmap/CONCEPTS "ordinary-income") 5) "JGAAP-only")))

(deftest unmapped-element-returns-none
  (is (nil? (cmap/canonical "us-gaap:SomeUnknownTag" "usgaap"))))

;; ── analyze: derived ratios + YoY + aggregates ───────────────────────────────

(deftest derived-ratios-match-disclosed-arithmetic
  (let [[_filings facts] (analyze/load seed)
        cy (analyze/by-company-year facts)
        metrics (into {} (for [m (analyze/derive-metrics cy)]
                           [[(get m ":fin.metric/company") (get m ":fin.metric/fiscal-year")
                             (get m ":fin.metric/kind")] (get m ":fin.metric/value")]))]
    (is (< (Math/abs (- (get metrics ["org.corp.us.apple" 2024 ":operating-margin"]) (/ 123216.0 391035.0))) 1e-3))
    (is (< (Math/abs (- (get metrics ["org.corp.us.apple" 2024 ":equity-ratio"]) (/ 56950.0 364980.0))) 1e-3))))

(deftest yoy-only-when-two-years-present
  (let [[_filings facts] (analyze/load seed)
        cy (analyze/by-company-year facts)
        metrics (analyze/derive-metrics cy)
        toyota-yoy (filter #(and (= (get % ":fin.metric/company") "org.corp.jp.toyota")
                                 (= (get % ":fin.metric/kind") ":revenue-yoy")) metrics)
        apple-yoy (filter #(and (= (get % ":fin.metric/company") "org.corp.us.apple")
                                (= (get % ":fin.metric/kind") ":revenue-yoy")) metrics)]
    (is (= 1 (count toyota-yoy)))
    (is (< (Math/abs (- (get (first toyota-yoy) ":fin.metric/value") (/ (- 45095000.0 37154000.0) 37154000.0))) 1e-3))
    (is (empty? apple-yoy))))

(deftest aggregates-never-cross-currency
  (let [[_filings facts] (analyze/load seed)
        cy (analyze/by-company-year facts)
        aggs (analyze/aggregates cy)
        currencies (set (map #(nth (str/split (get % ":fin.agg/id") #"\.")
                                   (- (count (str/split (get % ":fin.agg/id") #"\.")) 3)) aggs))]
    (is (clojure.set/subset? currencies #{"jpy" "usd" "eur" "gbp"}))
    (doseq [a aggs] (is (>= (get a ":fin.agg/n") 1)))))

(deftest metrics-are-synthesized-not-facts
  (let [[_filings facts] (analyze/load seed)
        cy (analyze/by-company-year facts)]
    (doseq [m (analyze/derive-metrics cy)]
      (is (= ":synthesized" (get m ":fin.metric/sourcing"))))))

;; ── ingest: primary-disclosure parsers ───────────────────────────────────────

(deftest edgar-parser-scales-to-millions-and-filters-to-annual
  (let [edgar {"cik" 320193
               "facts" {"us-gaap"
                        {"RevenueFromContractWithCustomerExcludingAssessedTax"
                         {"units" {"USD" [{"end" "2024-09-28" "val" 391035000000 "fy" 2024 "fp" "FY" "form" "10-K" "accn" "X" "filed" "2024-11-01"}]}}
                         "NetIncomeLoss"
                         {"units" {"USD" [{"end" "2024-09-28" "val" 93736000000 "fy" 2024 "fp" "FY" "form" "10-K" "accn" "X" "filed" "2024-11-01"}
                                          {"end" "2024-06-29" "val" 1 "fy" 2024 "fp" "Q3" "form" "10-Q" "accn" "Y" "filed" "2024-08-01"}]}}}}}
        [_fl fa] (ingest/parse-edgar-companyfacts edgar "org.corp.us.apple")
        rev (first (filter #(= (get % ":fin.fact/concept") ":revenue") fa))
        ni (filter #(= (get % ":fin.fact/concept") ":net-income") fa)]
    (is (= 391035.0 (get rev ":fin.fact/value")))
    (is (= ":authoritative" (get rev ":fin.fact/sourcing")))
    (is (= 1 (count ni)))
    (is (= 93736.0 (get (first ni) ":fin.fact/value")))))

(deftest edinet-parser-drops-unmapped-and-keeps-ordinary-income
  (let [edinet {"company" "org.corp.jp.nintendo" "accounting" "jgaap" "fiscalYear" 2024
                "currency" "jpy" "periodEnd" "2024-03-31"
                "elements" [{"element" "jppfs_cor:NetSales" "value" 1671900 "scale" "millions" "context" "consolidated"}
                            {"element" "jppfs_cor:OrdinaryIncome" "value" 670813 "scale" "millions" "context" "consolidated"}
                            {"element" "jppfs_cor:UnmappedJunk" "value" 1 "scale" "millions" "context" "consolidated"}]}
        [_fl fa] (ingest/parse-edinet-elements edinet "org.corp.jp.nintendo")
        concepts (set (map #(get % ":fin.fact/concept") fa))]
    (is (= #{":revenue" ":ordinary-income"} concepts))
    (is (every? #(= ":authoritative" (get % ":fin.fact/sourcing")) fa))))

;; ── edn round-trip ───────────────────────────────────────────────────────────

(deftest edn-reader-parses-seed
  (let [rows (kanjo-edn/read-file seed)
        facts (filter #(contains? % ":fin.fact/id") rows)
        filings (filter #(contains? % ":fin.filing/id") rows)
        rev (first (filter #(str/ends-with? (get % ":fin.fact/id") "toyota.2024.pl.revenue.consolidated") facts))]
    (is (= 36 (count facts)))
    (is (= 6 (count filings)))
    (is (= 45095000.0 (get rev ":fin.fact/value")))
    (is (= ":revenue" (get rev ":fin.fact/concept")))))
