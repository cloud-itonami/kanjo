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
  (:require [kotoba.lang.text :as str]
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

;; ── element-priority tie-break (same canonical concept, same company+fy) ────
;; concept_map.cljc maps MULTIPLE distinct source elements onto the SAME
;; canonical concept (e.g. revenue's usgaap list is
;; ["RevenueFromContractWithCustomerExcludingAssessedTax" "Revenues" "SalesRevenueNet"];
;; total-equity's is ["StockholdersEquity"
;; "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"]).
;; Because :fin.fact/id is built from (org, fy, canon) alone — no element/tag
;; component — facts from DIFFERENT elements for the SAME company+fy collide on the
;; SAME id. The list order in concept_map.cljc was never written as a priority
;; ranking (verified: for revenue, the FIRST-listed element
;; "RevenueFromContractWithCustomerExcludingAssessedTax" is the one that turns out to
;; need the LOWEST priority — see below), so "keep whichever the raw JSON iterates
;; last" (the previous `dedup-latest`, order-arbitrary w.r.t. Clojure hash-map
;; iteration of the parsed JSON) silently picked the wrong element for real
;; companies: Oracle FY2010/2011 landed on SalesRevenueNet (a narrower,
;; merchandise/product-only tag) instead of Revenues (the real total, ~10x larger);
;; Costco FY2017 landed on SalesRevenueNet ($126,172M, net merchandise sales only)
;; instead of Revenues ($129,025M, includes membership-fee income).
;;
;; Evidence trail (2026-07-20 live re-fetch + comparison across all 45 EDGAR-sourced
;; companies in data/facts.merged.kotoba.edn, every real (canon, company, fy)
;; collision found, not just the 2 companies above):
;;
;;  - "revenue" × {Revenues, SalesRevenueNet} — 19 real (non-tied) collisions
;;    checked (Oracle FY2010/2011, Costco FY2010-2017, Caterpillar FY2009-2017,
;;    GE FY2016/2017, Walmart FY2009-2018, P&G FY2014). Revenues is the larger
;;    value AND the correct total in 18/19 (SalesRevenueNet is a narrower
;;    product/segment-sales-only line in every one, confirmed against each
;;    company's real reported total revenue). The ONE exception, P&G FY2014, is
;;    the opposite: P&G's OWN "Revenues" XBRL tag for that period (accn
;;    0000080424-14-000057) carries $29.4B — nowhere near P&G's real ~$83.1B
;;    FY2014 total (a company-side XBRL tagging defect, not a narrow/broad
;;    distinction) — while SalesRevenueNet correctly carries $83.062B. In BOTH
;;    directions the LARGER value is the correct one, so plain
;;    value-magnitude ("larger wins") resolves this pair correctly in all 19/19
;;    cases checked, including the anomaly. No named-tag override needed here.
;;
;;  - "revenue" × {Revenues, RevenueFromContractWithCustomerExcludingAssessedTax}
;;    — 50 real collisions checked. In 47/50, Revenues >= RevenueFromContract...
;;    and Revenues is confirmed correct (e.g. Chevron: "Revenues" = the income
;;    statement's "Total revenues and other income" subtotal;
;;    RevenueFromContract... = the narrower "Sales and other operating revenues"
;;    subtotal beneath it. Same pattern independently confirmed for Walmart,
;;    Pfizer, GE). BUT in 3/50 (Mastercard FY2019/2020/2021), the LARGER value
;;    (RevenueFromContract..., $23-30B) is WRONG — Mastercard's real reported
;;    net revenue for those years ($16.883B/$15.301B/$18.884B, matching public
;;    10-K figures) is the SMALLER "Revenues" tag; the larger
;;    RevenueFromContract... point is a genuine same-duration, same-fy/fp/form
;;    XBRL point in Mastercard's own companyfacts feed that does not correspond
;;    to the company's total (most likely a gross/pre-rebate revenue-disclosure
;;    row reusing the element without a distinguishing dimension in the
;;    non-dimensional companyfacts API — a source-side XBRL-tagging ambiguity,
;;    not something inferable from magnitude alone). A pure "larger wins" rule
;;    would regress these 3 already-correct values. Revenues is correct 50/50
;;    times this pair was checked (identical or the deliberately larger figure
;;    in 47, and the deliberately SMALLER-but-correct figure in the 3 Mastercard
;;    cases) — so this ONE pair gets an explicit NAME-based override instead of
;;    magnitude (see `element-priority`).
;;
;;  - "total-equity" × {StockholdersEquity,
;;    StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest} —
;;    303 real collisions checked. The …IncludingNCI tag is larger (hence
;;    magnitude-correct) in 291/303; in the other 12 (Boeing FY2024, Cisco
;;    FY2016, PepsiCo FY2012-2017, Qualcomm FY2013-2016) …IncludingNCI is
;;    SMALLER by a small amount that exactly equals each company's own
;;    (negative) noncontrolling-interest balance for that year — i.e. those
;;    companies have a small NCI deficit, so the total INCLUDING it is
;;    genuinely less than the parent-only figure. …IncludingNCI is still the
;;    definitionally correct "total equity" in every one of those 12 cases too
;;    (ASC 810 requires consolidated total equity to include NCI; excluding a
;;    negative NCI overstates the true total) — so a pure magnitude rule would
;;    be wrong in exactly those 12/303, and this pair ALSO gets a NAME-based
;;    override (…IncludingNCI always wins), verified correct in 303/303.
;;
;;  - No other canonical concept in concept_map.cljc's `usgaap` element lists has
;;    more than one element (gross-profit / operating-income / pretax-income /
;;    net-income / total-assets / current-assets / total-liabilities /
;;    current-liabilities / cash-and-equivalents / cfo / cfi / cff / capex / eps
;;    each map exactly one usgaap tag) — so no other concept can produce this
;;    class of :fin.fact/id collision from parse-edgar-companyfacts today. If a
;;    future concept_map.cljc edit adds a second usgaap element to one of those,
;;    it falls through to the magnitude default below (undocumented, but no
;;    worse than the pre-fix "arbitrary JSON order" behavior) until it is
;;    checked against real data and (if needed) added to `element-priority`.
(def element-priority
  "Per-canonical-concept, per-source-element-pair NAME-based override — ONLY for
  pairs explicitly verified (see the evidence trail above) against every real
  co-occurrence found in a full live re-fetch of the 45 EDGAR-sourced companies in
  data/facts.merged.kotoba.edn (2026-07-20). NOT a blanket 'prefer list-order' or
  'prefer a specific tag globally' table — e.g. Revenues beats
  RevenueFromContractWithCustomerExcludingAssessedTax here, but NOT
  SalesRevenueNet (that pair uses the magnitude default instead, because the
  P&G FY2014 evidence above shows a fixed 'prefer Revenues' rule would be wrong
  there). {canon {raw-element-local-name rank}} — lower rank wins a collision
  against any OTHER element also present in this canon's map; a collision
  candidate whose element is NOT listed for this canon falls through to
  magnitude (`resolve-fact-collisions`)."
  {"revenue" {"Revenues" 0
              "RevenueFromContractWithCustomerExcludingAssessedTax" 1}
   "total-equity" {"StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest" 0
                   "StockholdersEquity" 1}})

(defn- raw-element
  "\"us-gaap:Revenues\" -> \"Revenues\" (the bare source-taxonomy local name, as
  stored on :fin.fact/concept-raw)."
  [fact]
  (last (str/split (get fact ":fin.fact/concept-raw" "") #":")))

(defn- collision-winner
  "Pick the surviving fact among 2+ facts colliding on the same :fin.fact/id (same
  canonical concept, company, fy — different source elements). Uses the verified
  NAME-based `element-priority` override when EVERY candidate's raw element is
  listed for this canon; otherwise falls back to the magnitude default (the
  LARGER :fin.fact/value wins — a broader/more-inclusive disclosure line is never
  less than a narrower one it subsumes, for every real case checked that isn't
  already carved out into `element-priority`). Deterministic on an exact-value tie
  (keeps whichever appears first in `group`)."
  [canon group]
  (let [ranked (get element-priority canon)]
    (if (and ranked (every? #(contains? ranked (raw-element %)) group))
      (apply min-key #(get ranked (raw-element %)) group)
      (reduce (fn [a b] (if (>= (get a ":fin.fact/value") (get b ":fin.fact/value")) a b)) group))))

(defn- resolve-fact-collisions
  "Group `facts` by :fin.fact/id; ids with exactly one fact pass through unchanged.
  Two DIFFERENT collision shapes land on the same :fin.fact/id (no element/tag
  component), and only one of them is the bug this fix targets:
   1. the SAME source element repeated across multiple accessions/filings (EDGAR
      re-discloses each fy as a prior-year comparative in every later 10-K,
      sometimes restated) — pre-existing, NOT part of this fix's scope, so this
      keeps the ORIGINAL `dedup-latest` behavior (last-in-source-order wins,
      i.e. the most-recently-filed comparative) for a single element's own
      repeats. 'Largest wins' would be WRONG here in general — a discontinued-
      operations reclassification typically makes a LATER restated comparative
      SMALLER (the divested unit's revenue is removed from continuing
      operations), so magnitude is not a safe tie-break within one element.
   2. DIFFERENT source elements mapped to the same canonical concept for the same
      company+fy (the actual bug — see the evidence trail above
      `element-priority`) — resolved via `collision-winner`, one candidate per
      element (itself reduced via rule 1 first).
  Never silently drops the losing candidate: returns [kept-facts rejected], where
  `rejected` records id/kept/rejected element+value for every CROSS-element
  collision (audit trail only — G11 restatement-as-history ethos: a rejected
  disclosure is logged, not erased, even though only one row per id lands in the
  graph itself; `rejected` is never merged back into filings/facts). Same-element
  repeats are NOT logged to `rejected` (unchanged pre-existing behavior, not a new
  tie-break decision)."
  [facts]
  (let [groups (vals (reduce (fn [m f] (update m (get f ":fin.fact/id") (fnil conj []) f))
                              (array-map) facts))]
    (reduce
     (fn [[kept rejected] group]
       (let [per-element (vals (reduce (fn [m f] (update m (raw-element f) (fnil conj []) f))
                                        (array-map) group))
             ;; rule 1: within one element, last-in-source-order wins (unchanged
             ;; pre-existing dedup-latest semantic; not logged as a tie-break).
             candidates (mapv last per-element)]
         (if (= 1 (count candidates))
           [(conj kept (first candidates)) rejected]
           ;; rule 2: 2+ DIFFERENT elements collide on this id -- the actual bug.
           (let [canon (lstrip-colon (get (first candidates) ":fin.fact/concept" ""))
                 winner (collision-winner canon candidates)
                 losers (remove #(identical? % winner) candidates)]
             [(conj kept winner)
              (into rejected
                    (map (fn [l]
                           {":fin.fact/id" (get l ":fin.fact/id")
                            ":fin.fact/company" (get l ":fin.fact/company")
                            ":kept-concept-raw" (get winner ":fin.fact/concept-raw")
                            ":kept-value" (get winner ":fin.fact/value")
                            ":rejected-concept-raw" (get l ":fin.fact/concept-raw")
                            ":rejected-value" (get l ":fin.fact/value")})
                         losers))]))))
     [[] []]
     groups)))

(defn parse-edgar-companyfacts
  "SEC EDGAR companyfacts → [filings facts rejected] (:authoritative).
  obj shape: obj['facts']['us-gaap'][Element]['units'][unit][ {end val fy fp form ...} ].
  Picks annual (fp == 'FY', form 10-K/20-F) AND, for duration/flow points (those
  carrying a \"start\" key), verifies [start,end] actually spans ~1 year
  (annual-duration?/MIN-ANNUAL-DURATION-DAYS..MAX-ANNUAL-DURATION-DAYS) — fp/form/fy
  describe the SOURCE FILING, not any one data point's own duration, so a quarterly
  footnote point embedded in the 10-K can otherwise pass this filter mislabeled as
  annual. Instant points (no \"start\", e.g. total-assets) are unaffected. One fact
  per (concept, fy) — when multiple SOURCE ELEMENTS map to the same canonical
  concept for the same company+fy, `resolve-fact-collisions`/`collision-winner`
  picks the surviving one (see the evidence trail above `element-priority`);
  `rejected` (3rd return value) is the audit trail of what lost and why, never
  merged into `filings`/`facts`."
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
                                                    ":fin.filing/currency" (str ":" (str/lower unit))
                                                    ":fin.filing/accounting" ":usgaap"
                                                    ":fin.filing/sourcing" ":authoritative"}))
                                  stmt (get* concept-stmt canon ":pl")
                                  fact {":fin.fact/id" (str "fact." org-id "." fy "." (lstrip-colon stmt) "." canon ".consolidated")
                                        ":fin.fact/filing" fid ":fin.fact/company" org-id
                                        ":fin.fact/statement" stmt ":fin.fact/concept" (str ":" canon)
                                        ":fin.fact/concept-raw" (str "us-gaap:" element)
                                        ":fin.fact/value" (/ (double (get p "val")) 1000000.0)
                                        ":fin.fact/unit" (str ":" (str/lower unit))
                                        ":fin.fact/scale" ":millions"
                                        ":fin.fact/context" ":consolidated" ":fin.fact/period-end" end
                                        ":fin.fact/sourcing" ":authoritative"}]
                              [filings (conj facts fact)])))))
                    [filings facts] points))
                 [filings facts] (get body "units" {})))))
          [(array-map) []] gaap)
         [kept rejected] (resolve-fact-collisions facts)]
     [(vec (vals filings)) kept rejected])))

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
