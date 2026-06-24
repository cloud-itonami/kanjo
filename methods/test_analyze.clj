#!/usr/bin/env bb
;; Working Clojure port of the analyze portion of tests/test_kanjo.py.
(ns kanjo.methods.test-analyze
  "kanjō 勘定 — analyze tests (derived ratios + YoY as-of + currency-honest aggregates + G5).
  (concept_map GAAP-normalization tests stay with the Python suite until concept_map.clj lands.)

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/test_analyze.clj"
  (:require [kanjo.methods.analyze :as a]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private this-file *file*)
(defn- seed []
  (-> this-file io/file .getAbsoluteFile .getParentFile .getParentFile
      (io/file "data" "seed-financial-facts.kotoba.edn")))
(defn- cy [] (a/by-company-year (second (a/load (seed)))))

(deftest derived-ratios-match-disclosed-arithmetic
  (let [metrics (into {} (map (fn [m] [[(:fin.metric/company m) (:fin.metric/fiscal-year m)
                                        (:fin.metric/kind m)] (:fin.metric/value m)])
                              (a/derive-metrics (cy))))]
    ;; Apple FY2024: operating-income 123216 / revenue 391035
    (is (< (Math/abs (- (metrics ["org.corp.us.apple" 2024 :operating-margin]) (/ 123216 391035.0))) 1e-3))
    ;; Apple equity-ratio: total-equity 56950 / total-assets 364980
    (is (< (Math/abs (- (metrics ["org.corp.us.apple" 2024 :equity-ratio]) (/ 56950 364980.0))) 1e-3))))

(deftest yoy-only-when-two-years-present
  (let [metrics (a/derive-metrics (cy))
        toyota-yoy (filter #(and (= (:fin.metric/company %) "org.corp.jp.toyota")
                                 (= (:fin.metric/kind %) :revenue-yoy)) metrics)
        apple-yoy (filter #(and (= (:fin.metric/company %) "org.corp.us.apple")
                                (= (:fin.metric/kind %) :revenue-yoy)) metrics)]
    (is (= 1 (count toyota-yoy)))   ; Toyota has FY2023 + FY2024
    (is (< (Math/abs (- (:fin.metric/value (first toyota-yoy)) (/ (- 45095000 37154000) 37154000.0))) 1e-3))
    (is (empty? apple-yoy))))       ; only FY2024 in seed

(deftest aggregates-never-cross-currency
  (let [aggs (a/aggregates (cy))
        currencies (set (map #(nth (clojure.string/split (:fin.agg/id %) #"\.") 3) aggs))]
    ;; id = agg.sector.<sector>.<currency>.<fy>.revenue → currency at index 3
    (is (clojure.set/subset? currencies #{"jpy" "usd" "eur" "gbp"}))
    (is (every? #(>= (:fin.agg/n %) 1) aggs))))

(deftest metrics-are-synthesized-not-facts
  (doseq [m (a/derive-metrics (cy))]
    (is (= (:fin.metric/sourcing m) :synthesized))))

(when (= *file* (System/getProperty "babashka.file"))
  (require 'clojure.set 'clojure.string)
  (let [{:keys [fail error]} (run-tests 'kanjo.methods.test-analyze)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
