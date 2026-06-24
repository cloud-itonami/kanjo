#!/usr/bin/env bb
;; Working Clojure port of the concept_map portion of tests/test_kanjo.py.
(ns kanjo.methods.test-concept-map
  "kanjō 勘定 — concept_map GAAP-normalization tests (methods/concept_map.clj).

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/test_concept_map.clj"
  (:require [kanjo.methods.concept-map :as cm]
            [kanjo.methods.analyze :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(deftest cross-gaap-revenue-normalizes-to-one-concept
  ;; JP-GAAP, US-GAAP and IFRS revenue elements all land on "revenue"
  (is (= (cm/canonical "jppfs_cor:NetSales" "jgaap") "revenue"))
  (is (= (cm/canonical "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax" "usgaap") "revenue"))
  (is (= (cm/canonical "ifrs-full:Revenue" "ifrs") "revenue")))

(deftest ordinary-income-is-jgaap-only
  ;; 経常利益 maps under JGAAP but has NO US-GAAP / IFRS twin (honest non-comparability)
  (is (= (cm/canonical "jppfs_cor:OrdinaryIncome" "jgaap") "ordinary-income"))
  (is (nil? (cm/canonical "OrdinaryIncome" "usgaap")))
  (is (nil? (cm/canonical "OrdinaryIncome" "ifrs")))
  (is (str/includes? (get-in cm/concepts ["ordinary-income" :note]) "JGAAP-only")))

(deftest unmapped-element-returns-nil
  (is (nil? (cm/canonical "us-gaap:SomeUnknownTag" "usgaap"))))

(deftest bare-element-without-prefix-maps
  (is (= (cm/canonical "NetSales" "jgaap") "revenue"))
  (is (= (cm/canonical "Assets" "ifrs") "total-assets")))

(deftest concept-map-and-analyze-metric-inputs-agree
  ;; consistency: the two copies of metric-inputs must not drift
  (is (= (cm/metric-inputs) a/metric-inputs)))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kanjo.methods.test-concept-map)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
