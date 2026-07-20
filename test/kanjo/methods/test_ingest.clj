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

;; Adversarial fixture (2026-07-20 duration-guard fix): a synthetic filer whose
;; us-gaap:Revenues element carries TWO points for the SAME fy/fp:"FY"/form:"10-K" —
;; a true full-year point (start/end ~365 days apart) and a SPURIOUS quarterly point
;; (start/end ~92 days apart, same "end" as the annual point) mislabeled with the same
;; filing-level fp/form/fy metadata. This mirrors the real "selected quarterly
;; financial data" Q4 footnote pattern that silently corrupted Apple/Microsoft/Texas
;; Instruments FY2020, Costco FY2017, Nvidia FY2016-17, and P&G FY2020 revenue prior
;; to this fix (dedup-latest's "last wins" arbitrarily picked whichever point the raw
;; JSON iterated last).
(def ^:private edgar-obj-mixed-duration
  {"cik" 999999
   "facts" {"us-gaap" {"Revenues"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2020
                                         "start" "2020-01-01" "end" "2020-12-31"
                                         "val" 100000000000 "accn" "annual" "filed" "2021-02-01"}
                                        {"fp" "FY" "form" "10-K" "fy" 2020
                                         "start" "2020-10-01" "end" "2020-12-31"
                                         "val" 30000000000 "accn" "q4-footnote" "filed" "2021-02-01"}]}}}}})

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

(deftest edgar-duration-guard-rejects-quarterly-mislabeled-as-annual
  (let [[filings facts] (ing/parse-edgar-companyfacts edgar-obj-mixed-duration "org.corp.us.synthetic")]
    (is (= (count filings) 1))
    ;; only the TRUE annual point survives; the same-fy/fp/form quarterly point is rejected
    (is (= (count facts) 1))
    (let [f (first facts)]
      (is (= (get f ":fin.fact/concept") ":revenue"))
      (is (= (get f ":fin.fact/value") 100000.0))    ; annual value (base → millions), NOT the 30000.0 Q4 figure
      (is (= (get f ":fin.fact/sourcing") ":authoritative")))))

;; Adversarial fixtures (2026-07-20 element-priority follow-up fix, same-family as
;; the duration-guard fix above — see src/kanjo/methods/ingest.cljc
;; `element-priority` / the evidence trail above it). Both elements below are
;; genuinely annual (~365-day, or instant) points with the SAME fy/fp:"FY"/
;; form:"10-K" — the duration guard does NOT reject either one. The bug this
;; guards against: :fin.fact/id has no element/tag component, so two elements
;; mapped to the same canonical concept for the same company+fy collide, and the
;; OLD "last wins" behavior picked whichever the raw us-gaap map iterated last —
;; arbitrary w.r.t. JSON key order, not a deliberate broad-vs-narrow choice.
;;
;; Case A (magnitude default correct): Revenues (broad, real total) vs
;; SalesRevenueNet (narrow, product-sales-only) — mirrors the real Oracle
;; FY2010/2011 and Costco FY2017 bug instances. The broader/LARGER value must
;; survive regardless of which order the raw map lists the two elements in.
(def ^:private edgar-obj-revenue-collision-order-a
  {"cik" 999998
   "facts" {"us-gaap" {"Revenues"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2017
                                         "end" "2017-08-31" "start" "2016-09-01"
                                         "val" 129025000000 "accn" "broad" "filed" "2017-10-18"}]}}
                       "SalesRevenueNet"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2017
                                         "end" "2017-08-31" "start" "2016-09-01"
                                         "val" 126172000000 "accn" "narrow" "filed" "2017-10-18"}]}}}}})

;; Same facts, elements listed in the OPPOSITE order in the source map (Clojure
;; array-maps preserve literal order for small maps) — proves the fix is NOT
;; order-dependent.
(def ^:private edgar-obj-revenue-collision-order-b
  {"cik" 999998
   "facts" {"us-gaap" {"SalesRevenueNet"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2017
                                         "end" "2017-08-31" "start" "2016-09-01"
                                         "val" 126172000000 "accn" "narrow" "filed" "2017-10-18"}]}}
                       "Revenues"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2017
                                         "end" "2017-08-31" "start" "2016-09-01"
                                         "val" 129025000000 "accn" "broad" "filed" "2017-10-18"}]}}}}})

;; Case B (name-based override needed): Revenues (SMALLER, correct) vs
;; RevenueFromContractWithCustomerExcludingAssessedTax (LARGER, but wrong for
;; total company revenue) — mirrors the real Mastercard FY2019/2020/2021 bug
;; instance, where a genuine same-duration/fy/fp/form XBRL point under the
;; "broader-sounding" element is actually a gross/pre-rebate figure, not the
;; company's real total revenue. A pure "larger wins" rule gets this WRONG
;; (verified against Mastercard's real reported net revenue); this pair needs
;; the `element-priority` name override instead.
(def ^:private edgar-obj-revenue-collision-name-override
  {"cik" 999997
   "facts" {"us-gaap" {"RevenueFromContractWithCustomerExcludingAssessedTax"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2019
                                         "end" "2019-12-31" "start" "2019-01-01"
                                         "val" 24980000000 "accn" "gross" "filed" "2020-02-14"}]}}
                       "Revenues"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2019
                                         "end" "2019-12-31" "start" "2019-01-01"
                                         "val" 16883000000 "accn" "net" "filed" "2020-02-14"}]}}}}})

;; Case C (name-based override, instant/balance-sheet facts): StockholdersEquity
;; (parent-only, LARGER here) vs StockholdersEquityIncludingPortionAttributable-
;; ToNoncontrollingInterest (consolidated total, SMALLER here because this
;; synthetic filer has a small negative/deficit noncontrolling interest) —
;; mirrors real PepsiCo/Qualcomm/Cisco/Boeing instances. ASC 810 requires
;; consolidated "total equity" to include NCI, so the …IncludingNCI tag is the
;; definitionally-correct canonical total-equity value even though it is
;; numerically smaller here; a pure "larger wins" rule would get this wrong.
(def ^:private edgar-obj-equity-collision-name-override
  {"cik" 999996
   "facts" {"us-gaap" {"StockholdersEquity"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2016
                                         "end" "2016-12-31" "val" 63586000000
                                         "accn" "parent-only" "filed" "2017-02-15"}]}}
                       "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2016
                                         "end" "2016-12-31" "val" 63585000000
                                         "accn" "consolidated-with-nci-deficit" "filed" "2017-02-15"}]}}}}})

(deftest edgar-element-priority-prefers-broader-revenue-magnitude-order-independent
  (doseq [obj [edgar-obj-revenue-collision-order-a edgar-obj-revenue-collision-order-b]]
    (let [[filings facts rejected] (ing/parse-edgar-companyfacts obj "org.corp.us.synthetic-broad-narrow")]
      (is (= (count filings) 1))
      (is (= (count facts) 1))
      (let [f (first facts)]
        (is (= (get f ":fin.fact/concept") ":revenue"))
        (is (= (get f ":fin.fact/value") 129025.0))               ; Revenues (broad) survives, NOT SalesRevenueNet
        (is (= (get f ":fin.fact/concept-raw") "us-gaap:Revenues")))
      (is (= (count rejected) 1))                                 ; loser is logged, not silently dropped
      (is (= (get (first rejected) ":rejected-concept-raw") "us-gaap:SalesRevenueNet"))
      (is (= (get (first rejected) ":rejected-value") 126172.0))
      (is (= (get (first rejected) ":kept-value") 129025.0)))))

(deftest edgar-element-priority-name-override-beats-larger-gross-revenue-tag
  (let [[filings facts rejected] (ing/parse-edgar-companyfacts edgar-obj-revenue-collision-name-override "org.corp.us.synthetic-mastercard-like")]
    (is (= (count filings) 1))
    (is (= (count facts) 1))
    (let [f (first facts)]
      (is (= (get f ":fin.fact/concept") ":revenue"))
      ;; Revenues (16883, smaller) wins over RevenueFromContract... (24980, larger) --
      ;; magnitude-only would wrongly pick 24980.
      (is (= (get f ":fin.fact/value") 16883.0))
      (is (= (get f ":fin.fact/concept-raw") "us-gaap:Revenues")))
    (is (= (count rejected) 1))
    (is (= (get (first rejected) ":rejected-concept-raw") "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax"))
    (is (= (get (first rejected) ":rejected-value") 24980.0))))

(deftest edgar-element-priority-name-override-total-equity-includes-nci-deficit
  (let [[filings facts rejected] (ing/parse-edgar-companyfacts edgar-obj-equity-collision-name-override "org.corp.us.synthetic-nci-deficit")]
    (is (= (count filings) 1))
    (is (= (count facts) 1))
    (let [f (first facts)]
      (is (= (get f ":fin.fact/concept") ":total-equity"))
      ;; …IncludingNCI (63585, smaller) wins over StockholdersEquity (63586, larger) --
      ;; magnitude-only would wrongly pick the parent-only 63586.
      (is (= (get f ":fin.fact/value") 63585.0))
      (is (= (get f ":fin.fact/concept-raw")
             "us-gaap:StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest")))
    (is (= (count rejected) 1))
    (is (= (get (first rejected) ":rejected-concept-raw") "us-gaap:StockholdersEquity"))
    (is (= (get (first rejected) ":rejected-value") 63586.0))))

;; Regression fixture: the SAME element repeated across multiple accessions for the
;; SAME fy (a real, common EDGAR pattern -- e.g. Alphabet's fy:2015 Revenues point
;; recurs, sometimes with a different value, in every later 10-K's prior-year
;; comparative column) must NOT go through the new element-priority/magnitude
;; tie-break -- that pre-existing "same tag, multiple filings" collision keeps the
;; ORIGINAL last-in-source-order-wins behavior. Verified during this fix: applying
;; "largest wins" here would have been WRONG in general (a later restated
;; comparative that reclassifies a divested unit out of continuing operations is
;; typically SMALLER, not larger, than the original). This fixture's smaller,
;; LATER-listed point must win over the larger, EARLIER-listed point.
(def ^:private edgar-obj-same-element-restated-smaller
  {"cik" 999995
   "facts" {"us-gaap" {"Revenues"
                       {"units" {"USD" [{"fp" "FY" "form" "10-K" "fy" 2015
                                         "start" "2015-01-01" "end" "2015-12-31"
                                         "val" 74989000000 "accn" "original-2015-10K" "filed" "2016-02-11"}
                                        {"fp" "FY" "form" "10-K" "fy" 2015
                                         "start" "2015-01-01" "end" "2015-12-31"
                                         "val" 66001000000 "accn" "restated-comparative-in-2017-10K" "filed" "2018-02-06"}]}}}}})

(deftest edgar-same-element-multi-accession-keeps-last-wins-not-magnitude
  (let [[filings facts rejected] (ing/parse-edgar-companyfacts edgar-obj-same-element-restated-smaller "org.corp.us.synthetic-restated")]
    (is (= (count filings) 1))
    (is (= (count facts) 1))
    (let [f (first facts)]
      (is (= (get f ":fin.fact/concept") ":revenue"))
      ;; last-in-source-order (66001, the LATER restated comparative) wins, NOT the
      ;; larger 74989 -- magnitude is not applied within a single element.
      (is (= (get f ":fin.fact/value") 66001.0)))
    ;; same-element repeats are not element-priority collisions -- nothing logged.
    (is (empty? rejected))))

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
