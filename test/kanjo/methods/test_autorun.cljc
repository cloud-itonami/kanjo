(ns kanjo.methods.test-autorun
  "test_autorun.py — kanjō autonomous financial-disclosure heartbeat + kotoba Datom-log invariants.
  ADR-2606032000. 1:1 Clojure port of methods/test_autorun.py (clojure.test/deftest+is mirroring the
  ok() asserts).

  Guards the autonomy + persistence + non-adjudication contract for the fleet:
    - the loop persists one content-addressed tx per heartbeat to an append-only log;
    - the log is a verifiable commit-DAG (every CID recomputes; tamper is detected);
    - it is deterministic / resume-safe (same cycles → same CIDs) and append-only;
    - G5 sourcing-honesty: every persisted derived :fin.metric / :fin.agg carries
      :sourcing :synthesized — never re-ingested as a disclosed fact;
    - G2/G4 non-adjudicating / no-advice / no-forecast: the log carries disclosed facts + ratios
      and NO rating/recommendation/target/forecast attr;
    - it does NO external I/O (offline ingest, local persist — G7 stays gated)."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])
            [kanjo.methods.autorun :as autorun]
            [kanjo.methods.kotoba :as kotoba]))

#?(:clj
   (defn- tmp-log []
     (let [f (java.io.File/createTempFile "tmp" ".datoms.kotoba.edn")]
       (.delete f)
       f)))

(deftest test-heartbeat-persists
  (let [log (tmp-log)]
    (try
      (let [res (autorun/run-autonomous 3 autorun/seed log)]
        (is (= 3 (get res "log_length")) "one tx per heartbeat")
        (is (every? #(> (get % "datoms") 0) (get res "beats")) "every heartbeat persisted datoms")
        (is (every? #(> (get % "metrics") 0) (get res "beats")) "derived ratios computed + persisted")
        (is (get (get res "chain") "ok") "commit-DAG verifies (chain OK)")
        (is (str/starts-with? (get res "head_cid") "b") "head CID is content-addressed"))
      (finally (.delete log)))))

(deftest test-deterministic-resume-safe
  (let [a (tmp-log) b (tmp-log)]
    (try
      (let [ra (autorun/run-autonomous 3 autorun/seed a)
            rb (autorun/run-autonomous 3 autorun/seed b)]
        (is (= (mapv #(get % "cid") (get ra "beats"))
               (mapv #(get % "cid") (get rb "beats")))
            "same cycles → same CIDs (deterministic / resume-safe)"))
      (finally (.delete a) (.delete b)))))

(deftest test-append-only-and-tamper
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 autorun/seed log)
      (let [first-log (kotoba/read-log log)]
        (autorun/run-cycle 2 autorun/seed log)
        (let [second-log (kotoba/read-log log)]
          (is (= (count second-log) (inc (count first-log)))
              "second heartbeat appends, does not rewrite")
          (is (= (get (nth second-log 1) ":tx/prev") (get (nth first-log 0) ":tx/cid"))
              "tx 2 links tx 1's CID (commit-DAG)")
          ;; tamper an earlier tx (flip a derived :synthesized → :authoritative on tx 1)
          (let [lines (str/split-lines (slurp log))
                tampered (mapv (fn [ln]
                                 (if (str/includes? ln ":tx/id 1 ")
                                   (str/replace-first ln
                                                      ":fin.metric/sourcing :synthesized"
                                                      ":fin.metric/sourcing :authoritative")
                                   ln))
                               lines)]
            (spit log (str (str/join "\n" tampered) "\n"))
            (let [v (kotoba/verify-chain log)]
              (is (and (not (get v "ok")) (= 0 (get v "broken_at")))
                  "tampering an earlier tx breaks the chain")))))
      (finally (.delete log)))))

(deftest test-g5-derived-synthesized
  ;; G5: every derived metric/agg must declare :synthesized — never masquerade as a disclosed fact.
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 autorun/seed log)
      (let [tx (nth (kotoba/read-log log) 0)
            datoms (get tx ":tx/datoms")
            ;; group by entity; {entity {attr value}}
            ent-attrs (reduce (fn [m d] (assoc-in m [(nth d 1) (nth d 2)] (nth d 3))) {} datoms)
            derived-ents (for [[e at] ent-attrs
                               :when (some #(or (str/starts-with? (str %) ":fin.metric/")
                                                (str/starts-with? (str %) ":fin.agg/"))
                                           (keys at))]
                           e)]
        (is (> (count derived-ents) 0) "derived :fin.metric / :fin.agg entities persisted")
        (doseq [e derived-ents]
          (let [srcs (vec (for [[k v] (get ent-attrs e)
                                :when (str/ends-with? (str k) "/sourcing")]
                            v))]
            (is (and (seq srcs) (every? #(= % ":synthesized") srcs))
                (str "derived entity " e " declares :sourcing :synthesized (G5)")))))
      (finally (.delete log)))))

(deftest test-g2-g4-no-advice-no-forecast
  (let [log (tmp-log)]
    (try
      (autorun/run-cycle 1 autorun/seed log)
      (let [tx (nth (kotoba/read-log log) 0)
            attrs (set (map #(str (nth % 2)) (get tx ":tx/datoms")))]
        (doseq [forbidden [":fin.metric/rating" ":fin.metric/recommendation" ":fin.metric/target-price"
                           ":fin.metric/forecast" ":fin.fact/forecast" ":fin.metric/buy-sell"
                           ":fin.metric/valuation" ":rating" ":recommendation"]]
          (is (not (contains? attrs forbidden))
              (str "no advice/forecast attr `" forbidden "` in the log (G2/G4)")))
        (let [ops (set (map #(nth % 0) (get tx ":tx/datoms")))]
          (is (= #{":db/add"} ops)
              "every datom is append-only :db/add (restatement = new fact, 非終末論 G11)")))
      (finally (.delete log)))))

(deftest test-no-external-io
  (let [methods-dir autorun/here   ; the actor's methods/ dir (absolute, resolved by autorun)
        src (str (slurp (io/file methods-dir "autorun.cljc"))
                 (slurp (io/file methods-dir "kotoba.cljc")))]
    (doseq [banned ["urllib" "http.client" "socket" "requests" "subprocess"]]
      (is (not (str/includes? src banned))
          (str "autorun/kotoba does no external I/O (no `" banned "`)")))))

;; NOTE: the danjo exemplar pins exact tx CIDs vs python3, but kanjō's shared EDN reader
;; (kanjo.methods.kanjo-edn — a pre-existing sibling we MUST NOT modify) materializes maps
;; into Clojure hash-maps, which do NOT preserve the source's key INSERTION order. Python
;; dicts do, so graph-datoms fan-out order — and therefore the canonical JSON preimage and the
;; resulting sha256 — differs between the two runtimes. This is NOT a port defect: the loop is
;; still deterministic / resume-safe (verified above) and the chain still verifies; only the
;; absolute hash value diverges, and the Python test_autorun.py suite itself asserts no fixed CID
;; (it only checks determinism + chain integrity). So no byte-for-byte CID-parity assertion is
;; ported — doing so would require changing kanjo-edn to an ordered-map reader, which is out of
;; scope (the existing analyze.cljc + its tests rely on the current reader unchanged).

#?(:clj
   (do
     (defn -main [& _] (run-tests 'kanjo.methods.test-autorun))
     (when (= *file* (System/getProperty "babashka.file")) (-main))))
