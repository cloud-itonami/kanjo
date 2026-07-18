(ns kanjo.methods.ingest
  "kanjō 勘定 — ingest cell: PRIMARY-disclosure → kotoba EAVT 決算 facts.
  Clojure port of methods/ingest.py (ADR-2606032000).

  Bridges primary public-disclosure artifacts (SEC EDGAR companyfacts JSON, JP
  EDINET pre-extracted element JSON) into the `:fin.filing/*` + `:fin.fact/*`
  vocabulary, normalizing every source taxonomy element onto a canonical concept
  via concept-map. Output facts are `:authoritative`; the seed stays
  `:representative` (merge keeps the more-authoritative source on id collision).

  Convention parity: maps carry STRING `\":fin.…/…\"` keys (the Python shape),
  values that are keywords stay `\":foo\"` strings. Pure transforms; the live
  EDGAR fetch (G7-gated) and file/network I/O sit at the JVM edge."
  (:require [clojure.string :as str]
            [kanjo.methods.concept-map :as cmap]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import [java.io File])))

;; CIK → org.corp.* id (shared kabuto/tsumugi space)
(def edgar-cik->org
  {"0000320193" "org.corp.us.apple"
   "0000789019" "org.corp.us.microsoft"
   "0001045810" "org.corp.us.nvidia"
   "0001018724" "org.corp.us.amazon"
   "0001652044" "org.corp.us.alphabet"
   "0001326801" "org.corp.us.meta"
   "0001067983" "org.corp.us.berkshire"
   "0001730168" "org.corp.us.broadcom"
   "0001318605" "org.corp.us.tesla"
   "0000050863" "org.corp.us.intel"
   "0000002488" "org.corp.us.amd"
   "0000723125" "org.corp.us.micron"})

;; which canonical concept lives on which statement (for :fin.fact/statement)
(def concept-stmt
  (into {} (for [[c [stmt & _]] cmap/concepts] [c stmt])))

(defn- lstrip-colon [s] (if (str/starts-with? s ":") (subs s 1) s))

(defn- last-seg [id] (last (str/split id #"\.")))

(defn- get* [m k d] (if (contains? m k) (get m k) d))

;; ── parsers ─────────────────────────────────────────────────────────────────

(defn- dedup-latest
  "Keep one fact per id (EDGAR repeats a concept across filings) — last wins,
  iteration order preserved (matches Python dict insertion semantics)."
  [facts]
  (let [seen (reduce (fn [m f] (assoc m (get f ":fin.fact/id") f)) (array-map) facts)]
    (vec (vals seen))))

(defn parse-edgar-companyfacts
  "SEC EDGAR companyfacts → [filings facts] (:authoritative).
  obj shape: obj['facts']['us-gaap'][Element]['units'][unit][ {end val fy fp form ...} ].
  Picks annual (fp == 'FY', form 10-K/20-F); one fact per (concept, fy)."
  ([obj org-id] (parse-edgar-companyfacts obj org-id nil))
  ([obj org-id want-fy]
   (let [gaap (get-in obj ["facts" "us-gaap"] {})
         [filings facts]
         (reduce
          (fn [[filings facts] [element body]]
            (let [canon (cmap/canonical element "usgaap")]
              (if-not canon
                [filings facts]
                (reduce
                 (fn [[filings facts] [unit points]]
                   (reduce
                    (fn [[filings facts] p]
                      (if (or (not= (get p "fp") "FY")
                              (not (#{"10-K" "20-F"} (get p "form"))))
                        [filings facts]
                        (let [fy (get p "fy")]
                          (if (and want-fy (not= fy want-fy))
                            [filings facts]
                            (let [end (get* p "end" "")
                                  accession (get* p "accn" "")
                                  fid (str "fil.us.edgar." (last-seg org-id) "." fy)
                                  filings (if (contains? filings fid)
                                            filings
                                            (assoc filings fid
                                                   {":fin.filing/id" fid ":fin.filing/company" org-id
                                                    ":fin.filing/source" ":edgar"
                                                    ":fin.filing/form" (str ":" (get* p "form" "10-K"))
                                                    ":fin.filing/fiscal-year" fy
                                                    ":fin.filing/period-type" ":annual"
                                                    ":fin.filing/period-end" end
                                                    ":fin.filing/filed-date" (get* p "filed" "")
                                                    ":fin.filing/accession" accession
                                                    ":fin.filing/doc-cid" ""
                                                    ":fin.filing/currency" (str ":" (str/lower-case unit))
                                                    ":fin.filing/accounting" ":usgaap"
                                                    ":fin.filing/sourcing" ":authoritative"}))
                                  stmt (get* concept-stmt canon ":pl")
                                  fact {":fin.fact/id" (str "fact." org-id "." fy "." (lstrip-colon stmt) "." canon ".consolidated")
                                        ":fin.fact/filing" fid ":fin.fact/company" org-id
                                        ":fin.fact/statement" stmt ":fin.fact/concept" (str ":" canon)
                                        ":fin.fact/concept-raw" (str "us-gaap:" element)
                                        ":fin.fact/value" (/ (double (get p "val")) 1000000.0)
                                        ":fin.fact/unit" (str ":" (str/lower-case unit))
                                        ":fin.fact/scale" ":millions"
                                        ":fin.fact/context" ":consolidated" ":fin.fact/period-end" end
                                        ":fin.fact/sourcing" ":authoritative"}]
                              [filings (conj facts fact)])))))
                    [filings facts] points))
                 [filings facts] (get body "units" {})))))
          [(array-map) []] gaap)]
     [(vec (vals filings)) (dedup-latest facts)])))

(defn parse-edinet-elements
  "R0 EDINET adapter: pre-extracted element list → [filings facts] (jgaap/ifrs)."
  [obj org-id]
  (let [std (get* obj "accounting" "jgaap")
        fy (get obj "fiscalYear")
        cur (get* obj "currency" "jpy")
        end (get* obj "periodEnd" "")
        fid (str "fil.jp.edinet." (last-seg org-id) "." fy)
        filing {":fin.filing/id" fid ":fin.filing/company" org-id ":fin.filing/source" ":edinet"
                ":fin.filing/form" ":yuho" ":fin.filing/fiscal-year" fy ":fin.filing/period-type" ":annual"
                ":fin.filing/period-end" end ":fin.filing/filed-date" (get* obj "filedDate" "")
                ":fin.filing/accession" (get* obj "docID" "") ":fin.filing/doc-cid" ""
                ":fin.filing/currency" (str ":" cur) ":fin.filing/accounting" (str ":" std)
                ":fin.filing/sourcing" ":authoritative"}
        facts (reduce
               (fn [facts el]
                 (let [canon (cmap/canonical (get el "element")
                                             (if (= std "ifrs") "ifrs" "jgaap"))]
                   (if-not canon
                     facts
                     (let [stmt (get* concept-stmt canon ":pl")
                           ctx (get* el "context" "consolidated")]
                       (conj facts
                             {":fin.fact/id" (str "fact." org-id "." fy "." (lstrip-colon stmt) "." canon "." ctx)
                              ":fin.fact/filing" fid ":fin.fact/company" org-id ":fin.fact/statement" stmt
                              ":fin.fact/concept" (str ":" canon) ":fin.fact/concept-raw" (get el "element")
                              ":fin.fact/value" (double (get el "value")) ":fin.fact/unit" (str ":" cur)
                              ":fin.fact/scale" (str ":" (get* el "scale" "millions")) ":fin.fact/context" (str ":" ctx)
                              ":fin.fact/period-end" end ":fin.fact/sourcing" ":authoritative"})))))
               [] (get* obj "elements" []))]
    [[filing] facts]))

;; ── seed-merge (authoritative wins) ──────────────────────────────────────────

(def ^:private sourcing-rank {":authoritative" 2 ":representative" 1 ":synthesized" 0})

(defn merge-with-seed
  "Merge `seed` facts with newly-ingested facts from any number of sources (e.g.
  EDGAR + EDINET) keyed on :fin.fact/id — the more-authoritative :fin.fact/sourcing
  wins a collision (never the reverse); ids unique to either side pass through
  unchanged. Mirrors merge_with_seed(seed, *sources)."
  [seed & ingested-fact-lists]
  (let [rank #(get sourcing-rank (get % ":fin.fact/sourcing") -1)
        by-id (reduce (fn [m f] (assoc m (get f ":fin.fact/id") f)) (array-map) seed)
        by-id (reduce
               (fn [m f]
                 (let [id (get f ":fin.fact/id")
                       cur (get m id)]
                   (if (or (nil? cur) (> (rank f) (rank cur)))
                     (assoc m id f)
                     m)))
               by-id
               (apply concat ingested-fact-lists))]
    (vec (vals by-id))))

;; ── G7-gated live fetch (JVM edge) ──────────────────────────────────────────

#?(:clj
   (defn fetch-edgar
     "LIVE EDGAR companyfacts fetch — G7-gated, single polite request.
     Refuses (throws) unless KANJO_OPERATOR_GATE=1 (mirrors the Python sys.exit guard
     whose message the invariant test matches on 'G7'/'gate'/'refus')."
     [cik]
     (when (not= (System/getenv "KANJO_OPERATOR_GATE") "1")
       (throw (ex-info (str "refused: live fetch requires KANJO_OPERATOR_GATE=1 "
                            "(G7 Council+operator gate). Offline mode reads data/ingest/*.json.")
                       {:kanjo/gate "G7"})))
     (let [cik (if (< (count cik) 10) (str (apply str (repeat (- 10 (count cik)) "0")) cik) cik)
           url (str "https://data.sec.gov/api/xbrl/companyfacts/CIK" cik ".json")
           org (get edgar-cik->org cik (str "org.corp.us.cik" cik))
           parse-json (requiring-resolve 'clojure.data.json/read-str)
           conn (doto (.openConnection (java.net.URL. url))
                  (.setRequestProperty "User-Agent" "etzhayyim-kanjo research jun@etzhayyim.group")
                  (.setConnectTimeout 30000)
                  (.setReadTimeout 30000))]
       (with-open [r (io/reader (.getInputStream conn))]
         (parse-edgar-companyfacts (parse-json (slurp r)) org)))))
