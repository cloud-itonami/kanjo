#!/usr/bin/env bb
;; Working Clojure test for methods/ingest.clj (no Python test existed; new coverage).
(ns kanjo.methods.test-ingest
  "Tests for the kanjō 勘定 PRIMARY-disclosure ingest bridge (methods/ingest.clj).

  Guards EDGAR/EDINET → :fin.fact mapping (canonical concept, base→millions, :authoritative),
  the seed-merge precedence (authoritative wins over :representative), and G1 (only mapped
  canonical concepts are admitted).

  Run:  bb --classpath 20-actors methods/test_ingest.clj"
  (:require [kanjo.methods.ingest :as ing]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private edgar-obj
  {"cik" 320193
   "facts" {"us-gaap" {"RevenueFromContractWithCustomerExcludingAssessedTax"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2024 "end" "2024-09-28"
                                         "val" 391035000000 "accn" "x" "filed" "2024-11-01"}
                                        {"fp" "Q3" "form" "10-Q" "fy" 2024 "end" "2024-06-29" "val" 1}]}}
                       "UnknownTag" {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2024 "val" 9}]}}}}})

(def ^:private edinet-obj
  {"company" "org.corp.jp.toyota" "accounting" "jgaap" "fiscalYear" 2024 "currency" "jpy"
   "periodEnd" "2024-03-31"
   "elements" [{"element" "jppfs_cor:NetSales" "value" 45095000 "scale" "millions" "context" "consolidated"}
               {"element" "jppfs_cor:OrdinaryIncome" "value" 5352000 "scale" "millions" "context" "consolidated"}
               {"element" "jppfs_cor:NoSuchTag" "value" 1 "context" "consolidated"}]})

(deftest edgar-maps-revenue-to-canonical-millions
  (let [[filings facts] (ing/parse-edgar-companyfacts edgar-obj "org.corp.us.apple")]
    (is (= (count filings) 1))
    ;; only the FY 10-K revenue point survives (Q3 dropped, UnknownTag unmapped → G1)
    (is (= (count facts) 1))
    (let [f (first facts)]
      (is (= (get f ":fin.fact/concept") ":revenue"))
      (is (= (get f ":fin.fact/value") 391035.0))    ; base → millions
      (is (= (get f ":fin.fact/unit") ":usd"))
      (is (= (get f ":fin.fact/sourcing") ":authoritative")))))

(deftest edinet-maps-jgaap-and-drops-unmapped
  (let [[filings facts] (ing/parse-edinet-elements edinet-obj "org.corp.jp.toyota")]
    (is (= (count filings) 1))
    (is (= (get (first filings) ":fin.filing/accounting") ":jgaap"))
    ;; NetSales→:revenue, OrdinaryIncome→:ordinary-income (JGAAP-only); NoSuchTag dropped (G1)
    (is (= (count facts) 2))
    (is (= (set (map #(get % ":fin.fact/concept") facts)) #{":revenue" ":ordinary-income"}))))

(deftest merge-authoritative-wins-over-representative
  (let [seed [{":fin.fact/id" "fact.x" ":fin.fact/sourcing" ":representative" ":fin.fact/value" 1.0}
              {":fin.fact/id" "fact.y" ":fin.fact/sourcing" ":representative"}]
        ingested-facts [{":fin.fact/id" "fact.x" ":fin.fact/sourcing" ":authoritative" ":fin.fact/value" 2.0}]
        merged (ing/merge-with-seed seed [] ingested-facts)
        by-id (into {} (map (juxt #(get % ":fin.fact/id") identity) merged))]
    (is (= (count merged) 2))
    (is (= (get (by-id "fact.x") ":fin.fact/value") 2.0))   ; authoritative replaced representative
    (is (= (get (by-id "fact.x") ":fin.fact/sourcing") ":authoritative"))
    (is (= (get (by-id "fact.y") ":fin.fact/sourcing") ":representative"))))  ; untouched

(deftest representative-never-overrides-authoritative
  (let [seed [{":fin.fact/id" "fact.z" ":fin.fact/sourcing" ":authoritative" ":fin.fact/value" 9.0}]
        rep [{":fin.fact/id" "fact.z" ":fin.fact/sourcing" ":representative" ":fin.fact/value" 0.0}]
        merged (ing/merge-with-seed seed [] rep)]
    (is (= (get (first merged) ":fin.fact/value") 9.0))))   ; authoritative seed kept

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kanjo.methods.test-ingest)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
