#!/usr/bin/env bb
;; Working Clojure port of methods/analyze.py (replaces the failed unit_refactor cljc stub).
(ns kanjo.methods.analyze
  "kanjō 勘定 — analyze cell (ADR-2606032000). Reads the disclosed-fact graph and emits
  AGGREGATE-FIRST observations: per-company per-fiscal-year derived ratios (:fin.metric,
  :synthesized G5), year-over-year growth where ≥2 fiscal years are present (as-of history),
  and sector/currency aggregates (:fin.agg, coverage-honest, never a market total; no FX
  cross-currency sums in R0).

  NON-ADJUDICATING (G2) / NO ADVICE (G4): every number is either a figure the company disclosed
  or a transparent ratio of two disclosed figures — never a rating, valuation, or recommendation,
  and NEVER a forecast (only reported actuals).

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/analyze.clj"
  (:require [kanjo.methods.kanjo-edn :as ke]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private this-file *file*)
(defn- actor-root [] (-> this-file io/file .getAbsoluteFile .getParentFile .getParentFile))

;; which canonical concepts each derived :fin.metric depends on (= concept_map.metric_inputs)
(def metric-inputs
  {"operating-margin" ["operating-income" "revenue"]
   "net-margin" ["net-income" "revenue"]
   "gross-margin" ["gross-profit" "revenue"]
   "roe" ["net-income" "total-equity"]
   "roa" ["net-income" "total-assets"]
   "equity-ratio" ["total-equity" "total-assets"]
   "current-ratio" ["current-assets" "current-liabilities"]})

;; fallback company meta (name / sector / country) for the seed cohort
(def company-meta
  {"org.corp.jp.toyota"    ["Toyota Motor" :automotive "JP"]
   "org.corp.jp.sony"      ["Sony Group" :electronics "JP"]
   "org.corp.jp.nintendo"  ["Nintendo" :consumer "JP"]
   "org.corp.us.apple"     ["Apple" :electronics "US"]
   "org.corp.us.microsoft" ["Microsoft" :software "US"]})

(defn load-company-meta
  "Join kabuto's :company graph (SSoT) for name/sector/country; fall back to inlined meta."
  []
  (let [kabuto-seed (io/file (actor-root) ".." "kabuto" "data" "seed-public-companies.kotoba.edn")]
    (if (.exists kabuto-seed)
      (reduce (fn [m r]
                (if-let [cid (and (map? r) (:company/id r))]
                  (assoc m cid [(:company/name r (get-in m [cid 0] cid))
                                (:company/sector r (get-in m [cid 1] :unknown))
                                (:company/country r (get-in m [cid 2] "?"))])
                  m))
              company-meta (ke/read-file kabuto-seed))
      company-meta)))

(def ^:private meta* (delay (load-company-meta)))

(defn load [path]
  (let [rows (ke/read-file path)
        filings (into {} (keep (fn [r] (when (:fin.filing/id r) [(:fin.filing/id r) r])) rows))
        facts (filterv :fin.fact/id rows)]
    [filings facts]))

(defn by-company-year
  "{company {fy {concept(no colon) [value unit scale]}}} — :consolidated context only."
  [facts]
  (reduce (fn [m f]
            (if (not= (:fin.fact/context f) :consolidated)
              m
              (let [co (:fin.fact/company f)
                    fy (Integer/parseInt (subs (:fin.fact/period-end f) 0 4))
                    concept (str/replace (str (:fin.fact/concept f)) #"^:" "")]
                (assoc-in m [co fy concept]
                          [(double (:fin.fact/value f)) (:fin.fact/unit f) (:fin.fact/scale f)]))))
          {} facts))

(defn- r4 [x] (/ (Math/round (* (double x) 10000.0)) 10000.0))

(defn- metric [co fy kind value basis]
  {:fin.metric/id (str "metric." co "." fy "." kind)
   :fin.metric/company co
   :fin.metric/fiscal-year fy
   :fin.metric/kind (keyword kind)
   :fin.metric/value (r4 value)
   :fin.metric/basis basis
   :fin.metric/sourcing :synthesized})

(defn derive-metrics [cy]
  (vec
   (mapcat
    (fn [[co years]]
      (mapcat
       (fn [[fy concepts]]
         (let [vals (into {} (map (fn [[k v]] [k (first v)]) concepts))
               prev (get years (dec fy))
               prev-vals (when prev (into {} (map (fn [[k v]] [k (first v)]) prev)))]
           (concat
            (keep (fn [[kind [num den]]]
                    (when (and (contains? vals num) (contains? vals den) (not= (vals den) 0))
                      (metric co fy kind (/ (vals num) (vals den)) (str num "/" den))))
                  metric-inputs)
            (when prev
              (keep (fn [[kind concept]]
                      (when (and (contains? vals concept) (contains? prev-vals concept))
                        (let [p (prev-vals concept)]
                          (when (not= p 0)
                            (metric co fy kind (/ (- (vals concept) p) p)
                                    (str concept "[" fy "] vs " concept "[" (dec fy) "]"))))))
                    [["revenue-yoy" "revenue"]
                     ["operating-income-yoy" "operating-income"]
                     ["net-income-yoy" "net-income"]])))))
       years))
    cy)))

(defn aggregates
  "Σ revenue per (sector, currency) — coverage-honest; NEVER cross-currency summed."
  [cy]
  (let [m @meta*
        accs (reduce (fn [acc [co years]]
                       (let [sector (get-in m [co 1] :unknown)]
                         (reduce (fn [acc [fy concepts]]
                                   (if-let [[val unit _] (get concepts "revenue")]
                                     (update acc [sector unit fy]
                                             (fn [a] (-> (or a {:sum 0.0 :n 0})
                                                         (update :sum + val) (update :n inc))))
                                     acc))
                                 acc years)))
                     {} cy)]
    (for [[[sector unit fy] a] (sort-by (fn [[k _]] (mapv str k)) accs)]
      {:fin.agg/id (str "agg.sector." (str/replace (str sector) #"^:" "") "."
                        (str/replace (str unit) #"^:" "") "." fy ".revenue")
       :fin.agg/dimension :sector
       :fin.agg/key (str/replace (str sector) #"^:" "")
       :fin.agg/fiscal-year fy
       :fin.agg/concept :revenue
       :fin.agg/sum (:sum a)
       :fin.agg/n (:n a)
       :fin.agg/sourcing :synthesized})))

(defn main [& argv]
  (let [seed (or (first (remove #(str/starts-with? % "--") argv))
                 (str (io/file (actor-root) "data" "seed-financial-facts.kotoba.edn")))
        [filings facts] (load seed)
        cy (by-company-year facts)
        metrics (derive-metrics cy)
        aggs (aggregates cy)]
    (println (format "kanjō: %d filings, %d facts, %d companies → %d metrics, %d aggregates"
                     (count filings) (count facts) (count cy) (count metrics) (count aggs)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
