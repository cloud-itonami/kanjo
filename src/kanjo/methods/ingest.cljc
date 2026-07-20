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
  EDGAR fetch is G7-gated and requires an explicitly injected host capability;
  file/network I/O stays outside this portable namespace."
  (:require [clojure.string :as str]
            [kanjo.methods.concept-map :as cmap]))

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

;; ── duration guard (annual vs quarterly-mislabeled-as-annual) ───────────────
;; SEC EDGAR's companyfacts feed can carry a QUARTERLY-duration data point (e.g. a
;; "selected quarterly financial data" Q4 footnote figure embedded IN the 10-K) tagged
;; with the SAME fy / fp:"FY" / form:"10-K" metadata as the true full-year figure —
;; those three fields describe the SOURCE FILING, not the duration of any one data
;; point within it. Without checking the point's own [start,end] window, such a
;; quarterly-scale point passes the fp/form/fy filter unnoticed and can silently
;; clobber the true annual value under dedup-latest's "last wins". Confirmed real
;; instances: Apple/Microsoft/Texas Instruments FY2020, Costco FY2017, Nvidia
;; FY2016-17, P&G FY2020 revenue all stored as Q4-scale figures pre-fix.
(def MIN-ANNUAL-DURATION-DAYS
  "Shortest [start,end] span (inclusive, days) accepted as a full fiscal year. A true
  annual duration is ~365 days; 350 gives slack for short fiscal years / leap-year
  edges without admitting a ~90-day quarterly point."
  350)

(def MAX-ANNUAL-DURATION-DAYS
  "Longest [start,end] span (inclusive, days) accepted as a full fiscal year. 380 gives
  slack for a 53-week fiscal year (common in retail, e.g. Costco) without admitting a
  ~2-year cumulative duration point."
  380)

(defn- parse-int [s] #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn- ymd->epoch-day
  "Days since a fixed epoch for an ISO \"YYYY-MM-DD\" date string. Portable
  civil-to-days conversion (Howard Hinnant's days_from_civil, proleptic Gregorian) —
  no java.time / js Date dependency needed to keep this .cljc usable from clj, cljs,
  and nbb alike."
  [s]
  (let [[y m d] (map parse-int (str/split s #"-"))
        y (if (<= m 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) d -1)
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- duration-days [start end] (- (ymd->epoch-day end) (ymd->epoch-day start)))

(defn- annual-duration?
  "True if XBRL point `p` is either (a) an INSTANT fact with no \"start\" key at all
  (e.g. total-assets — unaffected by this check, exactly as before this guard existed),
  or (b) a DURATION/flow fact whose [start,end] window falls within
  [MIN-ANNUAL-DURATION-DAYS, MAX-ANNUAL-DURATION-DAYS] — i.e. actually spans a full
  fiscal year rather than a quarter mislabeled with filing-level fp:\"FY\"/form:\"10-K\"
  metadata. Never throws — a malformed/unparseable start or end on one point is
  rejected (skipped), not allowed to crash the whole ingest."
  [p]
  (if-not (contains? p "start")
    true
    (try
      (let [days (duration-days (get p "start") (get p "end"))]
        (<= MIN-ANNUAL-DURATION-DAYS days MAX-ANNUAL-DURATION-DAYS))
      (catch #?(:clj Exception :cljs :default) _ false))))

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
  Picks annual (fp == 'FY', form 10-K/20-F) AND, for duration/flow points (those
  carrying a \"start\" key), verifies [start,end] actually spans ~1 year
  (annual-duration?/MIN-ANNUAL-DURATION-DAYS..MAX-ANNUAL-DURATION-DAYS) — fp/form/fy
  describe the SOURCE FILING, not any one data point's own duration, so a quarterly
  footnote point embedded in the 10-K can otherwise pass this filter mislabeled as
  annual. Instant points (no \"start\", e.g. total-assets) are unaffected. One fact per
  (concept, fy)."
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
                              (not (#{"10-K" "20-F"} (get p "form")))
                              (not (annual-duration? p)))
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

;; ── G7-gated live fetch (explicit host edge) ────────────────────────────────

#?(:clj
   (defn fetch-edgar
     "Parse one LIVE EDGAR response supplied by an explicit host capability.

     Refuses (throws) unless KANJO_OPERATOR_GATE=1 (mirrors the Python sys.exit guard
     whose message the invariant test matches on 'G7'/'gate'/'refus'). The
     capability receives a data-only request map and must return a decoded JSON
     map; this namespace never resolves a codec or opens a network connection."
     ([cik]
      (fetch-edgar nil cik))
     ([fetch-json cik]
      (when (not= (System/getenv "KANJO_OPERATOR_GATE") "1")
        (throw (ex-info (str "refused: live fetch requires KANJO_OPERATOR_GATE=1 "
                             "(G7 Council+operator gate). Offline mode reads data/ingest/*.json.")
                        {:kanjo/gate "G7"})))
      (when-not (fn? fetch-json)
        (throw (ex-info "refused: live fetch requires an explicit fetch-json capability"
                        {:kanjo/gate "G7" :kanjo/capability :fetch-json})))
      (let [cik (if (< (count cik) 10) (str (apply str (repeat (- 10 (count cik)) "0")) cik) cik)
            url (str "https://data.sec.gov/api/xbrl/companyfacts/CIK" cik ".json")
            org (get edgar-cik->org cik (str "org.corp.us.cik" cik))
            request {:url url
                     :headers {"User-Agent" "etzhayyim-kanjo research jun@etzhayyim.group"}
                     :connect-timeout-ms 30000
                     :read-timeout-ms 30000}
            response (fetch-json request)]
        (when-not (map? response)
          (throw (ex-info "fetch-json capability must return a decoded JSON map"
                          {:kanjo/capability :fetch-json
                           :kanjo/response-type (type response)})))
        (parse-edgar-companyfacts response org)))))
