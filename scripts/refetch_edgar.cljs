#!/usr/bin/env nbb
(ns refetch-edgar
  "kanjō 勘定 — G7-authorised live SEC EDGAR re-fetch + re-parse of every EDGAR-sourced
  company already in data/facts.merged.kotoba.edn, using the FIXED
  kanjo.methods.ingest/parse-edgar-companyfacts (2026-07-20 duration-guard fix:
  ADR/commit — see src/kanjo/methods/ingest.cljc MIN-ANNUAL-DURATION-DAYS /
  MAX-ANNUAL-DURATION-DAYS). Corrects previously-wrong :authoritative facts caused by
  a quarterly-duration point silently passing the old fp:\"FY\"/form:\"10-K\" filter
  (fp/form/fy describe the SOURCE FILING, not any one data point's own duration).

  Requires (never duplicates) the same pure parse-edgar-companyfacts /
  kanjo.methods.kanjo-edn reader used by src/kanjo/methods/ingest.cljc, so the fix and
  this regeneration path can never drift apart. All network I/O (fetch, delay, file
  read/write) lives ONLY in this script — ingest.cljc stays a pure, portable .cljc.

  Merge convention mirrors 70-tools/scripts/coverage-publish/edgar_batch.py
  (etzhayyim/root) — the tool that ORIGINALLY produced this file: read the existing
  merged EDN, key every row (filing OR fact) by its own id
  (:fin.filing/id | :fin.fact/id), overlay newly-fetched rows keyed the same way,
  new row wins on a same-tier (:authoritative >= :authoritative) collision — i.e. a
  same-id correction REPLACES the old value in place, never just appended as a
  duplicate. (kanjo.methods.ingest/merge-with-seed itself only keys by :fin.fact/id
  with a strict '>' — it cannot express same-tier replacement and would silently
  collapse :fin.filing/* rows under a nil key, so this script reimplements the same
  authoritative-wins-and-corrects semantic edgar_batch.py already used, uniformly
  across both row kinds.) Never shrinks the dataset; refuses (exits nonzero) and
  leaves the file untouched if the new total row count would be smaller than the old.

  G7: live EDGAR ingest is Council-authorised (README \"Live ingest — Council-
  authorised (2026-06-16)\"). This run RE-DERIVES facts for companies already in the
  dataset (no scope expansion) — polite: one GET per company, a real identifying
  User-Agent (SEC requires this), REQUEST-DELAY-MS between requests, never
  organism-tick scraping.

  Company list is DERIVED from the existing data file's distinct EDGAR-sourced
  :fin.filing/company values (not hardcoded) — CIKs are resolved from SEC's public
  ticker/CIK map (company_tickers.json) via an explicit org-id-suffix → ticker table,
  verified by printing the matched company title for eyeballing. A company that
  cannot be confidently resolved is logged and SKIPPED, never fabricated.

  Usage:
    nbb --classpath src scripts/refetch_edgar.cljs [--dry-run] [--data PATH]

  --dry-run   resolve + print the CIK table only; no network fetch, no file write.
  --data PATH override data/facts.merged.kotoba.edn (mainly for tests)."
  (:require [kanjo.methods.ingest :as ing]
            [kanjo.methods.kanjo-edn :as kedn]
            [clojure.string :as str]
            [promesa.core :as p]
            ["fs" :as fs]))

(def USER-AGENT "etzhayyim-kanjo research jun@gftd.group")
(def REQUEST-DELAY-MS
  "Polite delay between successive SEC EDGAR GETs (G7 \"single polite request\"
  discipline) — SEC's fair-access guidance asks for <=10 req/s; this is far under
  that, single-threaded, sequential, one company at a time."
  400)
(def DEFAULT-DATA-PATH "data/facts.merged.kotoba.edn")

;; ── org.corp.us.<suffix> → SEC ticker overrides ─────────────────────────────
;; Only companies where the org-id suffix is NOT already the literal lowercase
;; ticker need an entry here — everything else defaults to (upper-case suffix).
;; Hand-verified against each company's real ticker.
(def org-suffix->ticker-override
  {"apple"     "AAPL"
   "alphabet"  "GOOGL"
   "amazon"    "AMZN"
   "broadcom"  "AVGO"
   "intel"     "INTC"
   "microsoft" "MSFT"
   "nvidia"    "NVDA"
   "tesla"     "TSLA"})

(defn- org-suffix [org-id] (last (str/split org-id #"\.")))

(defn- org-id->ticker [org-id]
  (let [suffix (org-suffix org-id)]
    (str/upper-case (get org-suffix->ticker-override suffix suffix))))

;; Explicit CIK overrides for cases where SEC's company_tickers.json ticker→CIK
;; entry does NOT point at the primary annual-report (10-K) filer — verified by hand
;; per company, never guessed. Checked before the ticker-map lookup.
(def org-id->cik-override
  {;; company_tickers.json maps ticker "XOM" to CIK 2115436, a registrant created in
   ;; 2026 for a shelf/fee-only filing (companyfacts for 2115436 has ONLY an "ffd"
   ;; (fee-data) taxonomy, no "us-gaap" at all — verified live 2026-07-20). The real
   ;; historical Exxon Mobil Corporation 10-K filer with full us-gaap facts is
   ;; CIK 34088 (entityName "Exxon Mobil Corporation", verified live 2026-07-20).
   "org.corp.us.xom" "0000034088"})

;; ── EDN row writer (mirrors kanjo.methods.analyze/v->edn; ingest.cljc's map
;;    convention keeps STRING keys/values whose value is a leading-":" string is
;;    written back as a bare EDN keyword token, exactly like the rest of this file) ─
(defn- v->edn [v]
  (cond
    (string? v) (if (str/starts-with? v ":") v (str "\"" (str/replace v "\"" "\\\"") "\""))
    (boolean? v) (if v "true" "false")
    (nil? v) "nil"
    :else (str v)))

(defn- row->edn-line [row]
  (str " {" (str/join " " (map (fn [[k v]] (str k " " (v->edn v))) row)) "}"))

(defn- row-id [row] (or (get row ":fin.filing/id") (get row ":fin.fact/id")))
(defn- row-sourcing [row] (or (get row ":fin.fact/sourcing") (get row ":fin.filing/sourcing")))

(def sourcing-tier
  "Mirrors edgar_batch.py's RANK — :representative and :synthesized are tier 0,
  :authoritative is tier 1. A new row wins a same-id collision when its tier is
  >= the old row's tier (so an :authoritative correction replaces a prior
  :authoritative value in place, not just a strictly-higher-tier source)."
  {":representative" 0 ":synthesized" 0 ":authoritative" 1})

(defn- tier [row] (get sourcing-tier (row-sourcing row) 0))

;; ── SEC HTTP ─────────────────────────────────────────────────────────────────

(defn- fetch-json [url]
  (p/let [resp (js/fetch url #js{:headers #js{"User-Agent" USER-AGENT}})]
    (if (.-ok resp)
      (p/let [json (.json resp)] (js->clj json))
      (throw (js/Error. (str "HTTP " (.-status resp) " " url))))))

(defn- pad-cik [cik-str]
  (let [s (str cik-str)]
    (str (apply str (repeat (max 0 (- 10 (count s))) "0")) s)))

(defn- delay-ms [ms v] (p/create (fn [resolve _] (js/setTimeout #(resolve v) ms))))

;; ── main pipeline ────────────────────────────────────────────────────────────

(defn- read-data [path]
  (kedn/read-all (.readFileSync fs path "utf8")))

(defn- edgar-companies [rows]
  (->> rows
       (filter #(= (get % ":fin.filing/source") ":edgar"))
       (map #(get % ":fin.filing/company"))
       distinct
       sort
       vec))

(defn- resolve-ciks [tickers-obj org-ids]
  ;; tickers-obj: string-keyed map "0".."N" -> {"cik_str" N "ticker" T "title" S}
  (let [by-ticker (into {} (for [[_ v] tickers-obj]
                              [(get v "ticker") v]))]
    (into []
          (for [org-id org-ids]
            (let [ticker (org-id->ticker org-id)
                  override-cik (get org-id->cik-override org-id)
                  hit (get by-ticker ticker)]
              (cond
                override-cik
                {:org-id org-id :ticker ticker :cik (pad-cik override-cik)
                 :title (str (get hit "title" "") " [CIK override — see org-id->cik-override]")
                 :resolved true}
                hit
                {:org-id org-id :ticker ticker :cik (pad-cik (get hit "cik_str"))
                 :title (get hit "title") :resolved true}
                :else
                {:org-id org-id :ticker ticker :resolved false
                 :reason "no matching ticker in SEC company_tickers.json"}))))))

(defn- process-companies [entries]
  (p/loop [remaining entries
           acc {:filings [] :facts [] :errors [] :per-company []}]
    (if (empty? remaining)
      acc
      (p/let [{:keys [org-id ticker cik]} (first remaining)
              _ (println (str "  fetching " ticker " (CIK " cik ", " org-id ")…"))
              outcome (p/catch
                       (p/let [obj (fetch-json (str "https://data.sec.gov/api/xbrl/companyfacts/CIK" cik ".json"))]
                         {:ok true :obj obj})
                       (fn [err] {:ok false :error (.-message err)}))
              _ (delay-ms REQUEST-DELAY-MS nil)]
        (if (:ok outcome)
          (let [[filings facts] (ing/parse-edgar-companyfacts (:obj outcome) org-id)]
            (println (str "    +" (count filings) " filings / +" (count facts) " facts"))
            (p/recur (rest remaining)
                     (-> acc
                         (update :filings into filings)
                         (update :facts into facts)
                         (update :per-company conj {:org-id org-id :ticker ticker :cik cik
                                                     :filings (count filings) :facts (count facts)}))))
          (do
            (println (str "    ERROR: " (:error outcome)))
            (p/recur (rest remaining)
                     (update acc :errors conj {:org-id org-id :ticker ticker :cik cik :error (:error outcome)}))))))))

(defn- merge-rows
  "Key every existing row by its own id; overlay new rows, new wins ties (>=)."
  [old-rows new-rows]
  (let [by-id (reduce (fn [m r] (assoc m (row-id r) r)) (array-map) old-rows)
        by-id (reduce (fn [m r]
                        (let [id (row-id r) cur (get m id)]
                          (if (or (nil? cur) (>= (tier r) (tier cur)))
                            (assoc m id r)
                            m)))
                      by-id new-rows)]
    (vec (vals by-id))))

(defn- write-data! [path rows]
  (let [header (str ";; kanjō — merged 決算 graph (seed ⊕ ingested; :authoritative wins). "
                     "GENERATED by scripts/refetch_edgar.cljs (duration-guard fix, "
                     (.toISOString (js/Date.)) ").\n[")
        body (str/join "\n" (map row->edn-line rows))]
    (.writeFileSync fs (str path ".bak") (.readFileSync fs path "utf8") "utf8")
    (.writeFileSync fs path (str header "\n" body "\n]\n") "utf8")))

(defn -main [args]
  (let [dry-run? (some #{"--dry-run"} args)
        data-path (or (second (drop-while #(not= "--data" %) args)) DEFAULT-DATA-PATH)
        old-rows (read-data data-path)
        old-fact-count (count (filter #(contains? % ":fin.fact/id") old-rows))
        old-filing-count (count (filter #(contains? % ":fin.filing/id") old-rows))
        org-ids (edgar-companies old-rows)]
    (println (str "kanjō refetch_edgar: " (count org-ids) " distinct EDGAR-sourced companies in "
                   data-path " (" old-filing-count " filings / " old-fact-count " facts total)"))
    (p/let [tickers (fetch-json "https://www.sec.gov/files/company_tickers.json")
            resolved (resolve-ciks tickers org-ids)
            ok (filter :resolved resolved)
            skipped (remove :resolved resolved)]
      (println (str "\nResolved " (count ok) "/" (count org-ids) " companies to CIKs:"))
      (doseq [{:keys [org-id ticker cik title]} ok]
        (println (str "  " org-id " -> " ticker " (CIK " cik ") — " title)))
      (when (seq skipped)
        (println (str "\nSKIPPED " (count skipped) " (could not confidently resolve — NOT fabricated):"))
        (doseq [{:keys [org-id ticker reason]} skipped]
          (println (str "  " org-id " (tried ticker " ticker "): " reason))))
      (if dry-run?
        (println "\n--dry-run: no fetch, no write.")
        (p/let [_ (println (str "\nFetching " (count ok) " companyfacts documents, "
                                 REQUEST-DELAY-MS "ms apart…"))
                {:keys [filings facts errors per-company]} (process-companies ok)]
          (println (str "\nFetched OK: " (count per-company) "/" (count ok)
                        (when (seq errors) (str "  (errors: " (count errors) ")"))))
          (doseq [e errors] (println (str "  ERROR " (:org-id e) " (" (:ticker e) "): " (:error e))))
          (let [merged (merge-rows old-rows (concat filings facts))
                new-fact-count (count (filter #(contains? % ":fin.fact/id") merged))
                new-filing-count (count (filter #(contains? % ":fin.filing/id") merged))]
            (println (str "\nfacts " old-fact-count " -> " new-fact-count
                          ", filings " old-filing-count " -> " new-filing-count))
            (if (or (< new-fact-count old-fact-count) (< new-filing-count old-filing-count))
              (do (println "REFUSED: merge would shrink the dataset; leaving file untouched.")
                  (js/process.exit 1))
              (do (write-data! data-path merged)
                  (println (str "wrote " data-path " (.bak backup of prior content written alongside)"))))))))))

(-main (vec (drop 2 (or js/process.argv []))))
