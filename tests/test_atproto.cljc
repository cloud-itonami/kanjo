(ns kanjo.tests.test-atproto
  "kanjō 勘定 — atproto cell tests. The constitutional guard (G2/G4), the
  content-addressed record key (resume-safe determinism), and record shape."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kanjo.methods.kanjo-edn :as kanjo-edn]
            [kanjo.methods.atproto :as at]))

(def seed
  (-> (io/file *file*) .getParentFile .getParentFile
      (io/file "data" "seed-financial-facts.kotoba.edn") .getAbsolutePath))

;; ── G2/G4 guard ──────────────────────────────────────────────────────────────

(deftest guard-passes-disclosed-concepts
  ;; "operating income" must NOT trip "rating" (word boundaries, not substring)
  (is (= "Toyota disclosed operating income ¥5,353,000M"
         (at/assert-clean "Toyota disclosed operating income ¥5,353,000M"))))

(deftest guard-rejects-english-advice
  (doseq [bad ["we recommend you buy" "price target raised" "rating: outperform"
               "this looks undervalued" "analyst forecast"]]
    (is (thrown? clojure.lang.ExceptionInfo (at/assert-clean bad))
        (str "should reject: " bad))))

(deftest guard-rejects-japanese-advice
  (doseq [bad ["買い推奨です" "目標株価は1万円" "業績予想を上方修正" "割安だ"]]
    (is (thrown? clojure.lang.ExceptionInfo (at/assert-clean bad))
        (str "should reject: " bad))))

;; ── content-addressed rkey — deterministic & resume-safe ─────────────────────

(deftest rkey-is-deterministic
  (is (= (at/rkey "fact.x") (at/rkey "fact.x")))
  (is (not= (at/rkey "fact.x") (at/rkey "fact.y")))
  (is (str/starts-with? (at/rkey "fact.x") "kanjo")))

;; ── record shape from the real seed graph ────────────────────────────────────

(deftest compose-builds-clean-surface
  (let [rows (kanjo-edn/read-file seed)
        filings (filter #(get % ":fin.filing/id") rows)
        facts   (filter #(get % ":fin.fact/id") rows)
        {:keys [profile disclosures posts]} (at/compose filings facts "2026-01-01T00:00:00Z")]
    (is (= "app.bsky.actor.profile" (get profile "$type")))
    ;; one disclosure record per fact
    (is (= (count facts) (count disclosures)))
    (is (every? #(= "com.etzhayyim.kanjo.disclosure" (get (:record %) "$type")) disclosures))
    ;; every disclosure carries G5 sourcing + provenance
    (is (every? #(contains? #{":authoritative" ":representative" ":synthesized"}
                            (get (:record %) "sourcing")) disclosures))
    ;; posts are a headline subset, all app.bsky.feed.post, all G2/G4-clean by construction
    (is (pos? (count posts)))
    (is (<= (count posts) (count disclosures)))
    (is (every? #(= "app.bsky.feed.post" (get (:record %) "$type")) posts))
    (is (every? #(at/assert-clean (get (:record %) "text")) posts))
    ;; each post embeds its structured disclosure record by AT-URI
    (is (every? #(str/includes? (get-in (:record %) ["embed" "record" "uri"])
                                "com.etzhayyim.kanjo.disclosure") posts))))

;; ── JSON writer ──────────────────────────────────────────────────────────────

(deftest json-writer-escapes-and-types
  (is (= "{\"a\":1,\"b\":[true,null]}" (at/->json {"a" 1 "b" [true nil]})))
  (is (= "\"x\\\"y\"" (at/->json "x\"y"))))
