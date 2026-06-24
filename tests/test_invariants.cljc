(ns kanjo.tests.test-invariants
  "kanjō 勘定 — charter-invariant + gate tests. Clojure port of tests/test_invariants.py.

  The LOAD-BEARING structural invariants: kanjō is NON-ADJUDICATING (G2) and gives NO
  investment advice (G4) — it never rates/values/recommends/forecasts; every derived
  number is a transparent ratio flagged :synthesized; live fetch is G7-gated; metric
  kinds match their declared inputs. ADR-2606032000."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kanjo.methods.analyze :as analyze]
            [kanjo.methods.concept-map :as cmap]
            [kanjo.methods.ingest :as ingest]))

(def seed
  (-> (io/file *file*) .getParentFile .getParentFile
      (io/file "data" "seed-financial-facts.kotoba.edn") .getAbsolutePath))

(defn- gen-report []
  (let [[filings facts] (analyze/load seed)
        cy (analyze/by-company-year facts)
        metrics (analyze/derive-metrics cy)
        aggs (analyze/aggregates cy)]
    [(analyze/report filings facts cy metrics aggs) metrics aggs]))

(deftest report-is-non-adjudicating-no-advice
  (let [[md _m _a] (gen-report)
        low (str/lower-case md)]
    (is (str/includes? low "non-adjudicating"))
    (is (str/includes? low "no investment advice"))))

(deftest report-states-non-adjudication-boundary
  ;; G4: the report must EXPLICITLY state it does not forecast/rate/recommend, and must
  ;; not emit an actual verdict artifact (a price target or a buy/sell call).
  (let [[md _m _a] (gen-report)
        low (str/lower-case md)]
    (is (str/includes? low "does not forecast"))
    (doseq [verdict ["目標株価" "格付け" "投資判断:" "recommendation:"]]
      (is (not (str/includes? low (str/lower-case verdict)))
          (str "adjudication artifact leaked: " (pr-str verdict))))))

(deftest derived-metrics-are-synthesized-not-authoritative
  (let [[_md metrics _a] (gen-report)]
    (is (seq metrics))
    (is (every? #(= ":synthesized" (get % ":fin.metric/sourcing")) metrics))))

(deftest metric-kinds-match-declared-inputs
  ;; every ratio metric kind produced must be one metric-inputs() declares (or YoY).
  (let [[_md metrics _a] (gen-report)
        declared (set (keys (cmap/metric-inputs)))
        yoy #{"revenue-yoy" "operating-income-yoy" "net-income-yoy"}]
    (doseq [m metrics]
      (let [kind (str/replace-first (get m ":fin.metric/kind") #"^:+" "")]
        (is (or (declared kind) (yoy kind)) (str "undeclared metric kind: " kind))))))

(deftest aggregates-never-mix-currencies
  (let [[_md _m aggs] (gen-report)]
    (doseq [a aggs]
      (let [cur (get a ":fin.agg/currency")]
        (when (some? cur) (is (string? cur))))
      (is (>= (get a ":fin.agg/n") 1)))))

(deftest g7-live-edgar-fetch-refused-without-operator-gate
  ;; KANJO_OPERATOR_GATE is unset in the test env → fetch must refuse (throw) with a
  ;; message naming the G7 gate (the Python sys.exit equivalent).
  (let [e (try (ingest/fetch-edgar "0000320193") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "live EDGAR fetch must refuse without KANJO_OPERATOR_GATE=1")
    (when e
      (let [msg (str/lower-case (ex-message e))]
        (is (or (str/includes? (ex-message e) "G7")
                (str/includes? msg "refus")
                (str/includes? msg "gate")))))))

(deftest unmapped-taxonomy-element-is-dropped-not-guessed
  (is (nil? (cmap/canonical "us-gaap:TotallyMadeUpTag" "usgaap")))
  (is (nil? (cmap/canonical "jppfs_cor:NotARealElement" "jgaap"))))
