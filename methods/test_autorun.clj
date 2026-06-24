#!/usr/bin/env bb
;; Working Clojure port of methods/test_autorun.py.
(ns kanjo.methods.test-autorun
  "kanjō 勘定 — autonomous financial-disclosure heartbeat + kotoba Datom-log invariants
  (ADR-2606032000). Guards autonomy + persistence + G5 derived-:synthesized + G2/G4 non-adjudicating.

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/test_autorun.clj"
  (:require [kanjo.methods.autorun :as autorun]
            [kanjo.methods.kotoba :as kotoba]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private this-file *file*)
(defn- tmp-log []
  (let [f (java.io.File/createTempFile "kanjo" ".datoms.kotoba.edn")] (.delete f) f))

(deftest heartbeat-persists
  (let [log (tmp-log)]
    (try
      (let [res (autorun/run-autonomous :cycles 3 :log-path log)]
        (is (= (:log-length res) 3))
        (is (every? #(> (:datoms %) 0) (:beats res)))
        (is (:ok (:chain res)))
        (is (str/starts-with? (:head-cid res) "b")))
      (finally (.delete log)))))

(deftest deterministic-resume-safe
  (let [a (tmp-log) b (tmp-log)]
    (try
      (let [ra (autorun/run-autonomous :cycles 3 :log-path a)
            rb (autorun/run-autonomous :cycles 3 :log-path b)]
        (is (= (map :cid (:beats ra)) (map :cid (:beats rb)))))
      (finally (.delete a) (.delete b)))))

(deftest append-only-and-tamper
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 :log-path log)
      (let [first* (kotoba/read-log log)]
        (autorun/run-cycle 2 :log-path log)
        (let [second* (kotoba/read-log log)]
          (is (= (count second*) (inc (count first*))))
          (is (= (:tx/prev (nth second* 1)) (:tx/cid (nth first* 0))))
          (let [lines (str/split-lines (slurp log))
                done (atom false)
                tampered (mapv (fn [ln]
                                 (if (and (not @done) (not (str/starts-with? (str/trim ln) ";"))
                                          (str/includes? ln ":fin.metric/sourcing :synthesized"))
                                   (do (reset! done true)
                                       (str/replace-first ln ":fin.metric/sourcing :synthesized"
                                                          ":fin.metric/sourcing :authoritative"))
                                   ln)) lines)]
            (is @done "earliest tx located + tampered")
            (spit log (str (str/join "\n" tampered) "\n"))
            (let [v (kotoba/verify-chain log)]
              (is (and (not (:ok v)) (= (:broken-at v) 0)))))))
      (finally (.delete log)))))

(deftest g5-derived-synthesized
  ;; every entity carrying a :fin.metric/* or :fin.agg/* attr must declare :sourcing :synthesized
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 :log-path log)
      (let [datoms (:tx/datoms (nth (kotoba/read-log log) 0))
            ent-attrs (reduce (fn [m [_ e a v]] (assoc-in m [e a] v)) {} datoms)
            derived-ents (filter (fn [[_ at]] (some #(let [n (str %)]
                                                       (or (str/starts-with? n ":fin.metric/")
                                                           (str/starts-with? n ":fin.agg/"))) (keys at)))
                                 ent-attrs)]
        (is (pos? (count derived-ents)) "derived :fin.metric / :fin.agg entities persisted")
        (doseq [[_ at] derived-ents]
          (let [srcs (for [[k v] at :when (str/ends-with? (str k) "/sourcing")] v)]
            (is (and (seq srcs) (every? #(= % :synthesized) srcs)) "G5 derived :synthesized"))))
      (finally (.delete log)))))

(deftest derived-flagged-and-append-only-op
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 :log-path log)
      (let [ops (set (map first (:tx/datoms (nth (kotoba/read-log log) 0))))]
        (is (= ops #{:db/add})))
      (finally (.delete log)))))

(deftest g2-g4-no-advice-no-forecast
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 :log-path log)
      (let [attrs (set (map #(str (nth % 2)) (:tx/datoms (nth (kotoba/read-log log) 0))))]
        (doseq [forbidden [":fin.metric/rating" ":fin.metric/recommendation" ":fin.metric/target"
                           ":fin.metric/forecast" ":fin.metric/buy-sell" ":fin.metric/valuation"]]
          (is (not (contains? attrs forbidden)) (str "no advice/forecast attr " forbidden " (G2/G4)"))))
      (finally (.delete log)))))

(deftest no-external-io
  (let [dir (-> this-file io/file .getAbsoluteFile .getParentFile)
        src (str (slurp (io/file dir "autorun.clj")) (slurp (io/file dir "kotoba.clj")))]
    (doseq [banned ["urllib" "http.client" "babashka.http" "java.net.Socket" "shell" "ProcessBuilder"]]
      (is (not (str/includes? src banned))))))

(when (= *file* (System/getProperty "babashka.file"))
  (let [{:keys [fail error]} (run-tests 'kanjo.methods.test-autorun)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
