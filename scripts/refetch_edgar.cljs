#!/usr/bin/env nbb
(ns refetch-edgar
  "kanjō 勘定 — G7-authorised live SEC EDGAR re-fetch + re-parse of every EDGAR-sourced
  company already in data/facts.merged.kotoba.edn, using the FIXED
  kanjo.methods.ingest/parse-edgar-companyfacts. Two fixes now live in that function:
  (1, 2026-07-20 duration-guard fix) rejects a quarterly-duration point that silently
  passes the fp:\"FY\"/form:\"10-K\" filter (fp/form/fy describe the SOURCE FILING,
  not any one data point's own duration); (2, 2026-07-20 follow-up,
  element-priority fix) when MULTIPLE source elements map onto the same canonical
  concept for the same company+fy (e.g. revenue's Revenues vs SalesRevenueNet —
  see `element-priority` / the evidence trail above it in ingest.cljc), resolves
  the collision by value-magnitude or a verified name-based override instead of
  the previous 'last wins, arbitrary w.r.t. JSON iteration order' behavior.
  Corrects previously-wrong :authoritative facts from both causes.

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
    nbb --classpath src scripts/refetch_edgar.cljs [--dry-run] [--data PATH] [--summary-path PATH]

  --dry-run       resolve + print the CIK table only; no network fetch, no file write.
  --data PATH     override data/facts.merged.kotoba.edn (mainly for tests).
  --summary-path PATH
                  ALSO write a clean, git-diffable Markdown run summary to PATH
                  (facts/filings before -> after, element-priority tie-break /
                  rejected-candidate count, and which company+fiscal-year facts
                  actually changed :fin.fact/value vs the prior commit) — this
                  is the SAME data already printed to stdout above, reshaped for
                  a CI PR body (see .github/workflows/edgar-refresh.yml) rather
                  than re-derived. Not written on --dry-run, on REFUSED runs, or
                  when nothing actually changed (see the no-write short-circuit
                  in -main — a CI workflow's `git diff` should then be empty and
                  correctly skip opening a PR)."
  (:require [kanjo.methods.ingest :as ing]
            [kanjo.methods.kanjo-edn :as kedn]
            [kotoba.lang.text :as str]
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
    (str/upper (get org-suffix->ticker-override suffix suffix))))

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
           acc {:filings [] :facts [] :errors [] :per-company [] :rejected []}]
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
          (let [[filings facts rejected] (ing/parse-edgar-companyfacts (:obj outcome) org-id)]
            (println (str "    +" (count filings) " filings / +" (count facts) " facts"))
            ;; element-priority collision audit trail (ingest.cljc resolve-fact-collisions) --
            ;; not written to data-path, printed here so a same-canon/company/fy tie-break is
            ;; never silent (G11 restatement-as-history ethos: log what lost, and why).
            (doseq [r rejected]
              (println (str "    tie-break " (get r ":fin.fact/id") ": kept "
                            (get r ":kept-concept-raw") "=" (get r ":kept-value")
                            ", rejected " (get r ":rejected-concept-raw") "=" (get r ":rejected-value"))))
            (p/recur (rest remaining)
                     (-> acc
                         (update :filings into filings)
                         (update :facts into facts)
                         (update :rejected into rejected)
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

;; ── changed-value diff (old commit vs this run's merge) ─────────────────────
;; company+fiscal-year is parsed from the fact id's own convention
;; ("fact.<org-id>.<fy>.<statement>.<concept>.<context>", see row->edn-line /
;; the seed generator this mirrors) rather than a fabricated join — a fact id
;; that doesn't match this shape is skipped from the report (never guessed).
(defn- fact-id->company+fy [id]
  (let [segs (str/split id #"\.")]
    ;; "fact" "org" "corp" "us" "<ticker>" "<fy>" ... -> company = "org.corp.us.<ticker>"
    (when (and (= (first segs) "fact") (>= (count segs) 6))
      {:company (str/join "." (subvec (vec segs) 1 5)) :fiscal-year (nth segs 5)})))

(defn- changed-value-facts
  "old-rows / merged-rows -> sorted [{:company :fiscal-year :old-value :new-value}]
  for every :fin.fact/id present in BOTH with a DIFFERENT :fin.fact/value —
  i.e. an existing fact whose value this run's re-parse corrected, not a
  newly-added fact (those are covered by the plain fact-count delta above)."
  [old-rows merged-rows]
  (let [old-by-id (into {} (keep (fn [r] (when-let [id (get r ":fin.fact/id")] [id r]))) old-rows)]
    (->> merged-rows
         (keep (fn [r]
                 (when-let [id (get r ":fin.fact/id")]
                   (when-let [old (get old-by-id id)]
                     (let [old-v (get old ":fin.fact/value") new-v (get r ":fin.fact/value")]
                       (when (not= old-v new-v)
                         (merge (fact-id->company+fy id) {:id id :old-value old-v :new-value new-v})))))))
         (sort-by (juxt :company :fiscal-year))
         vec)))

(defn- write-summary! [path {:keys [data-path old-org-count resolved-count skipped
                                     ok-count fetched-count errors rejected
                                     old-fact-count new-fact-count old-filing-count
                                     new-filing-count changed]}]
  (let [md (str
            "# kanjō EDGAR refresh — " (.toISOString (js/Date.)) "\n\n"
            "Re-fetched + re-parsed all EDGAR-sourced companies already in `" data-path
            "` (no scope expansion — G7 single-polite-request re-derivation).\n\n"
            "## Summary\n\n"
            "- companies: " old-org-count " distinct EDGAR-sourced companies\n"
            "- resolved to CIK: " resolved-count "/" old-org-count
            (when (seq skipped) (str " (" (count skipped) " skipped — could not confidently resolve, NOT fabricated)")) "\n"
            "- fetched OK: " fetched-count "/" ok-count
            (when (seq errors) (str "  (" (count errors) " error" (when (> (count errors) 1) "s") ")")) "\n"
            "- facts: " old-fact-count " -> " new-fact-count
            " (" (- new-fact-count old-fact-count) ")\n"
            "- filings: " old-filing-count " -> " new-filing-count
            " (" (- new-filing-count old-filing-count) ")\n"
            "- element-priority tie-breaks resolved this run (multiple source elements -> "
            "same canonical concept for the same company+fy — see docstring): " (count rejected) "\n"
            "- rejected candidates (the losing side of each tie-break above — same set, "
            "not double-counted): " (count rejected) "\n"
            "- existing facts whose value CHANGED vs the prior commit: " (count changed) "\n\n"
            (when (seq errors)
              (str "## Fetch errors\n\n"
                   (str/join "\n" (map #(str "- " (:org-id %) " (" (:ticker %) "): " (:error %)) errors))
                   "\n\n"))
            (when (seq rejected)
              (str "## Element-priority tie-breaks (kept vs rejected)\n\n"
                   (str/join "\n" (map (fn [r] (str "- " (get r ":fin.fact/id") ": kept "
                                                     (get r ":kept-concept-raw") "=" (get r ":kept-value")
                                                     ", rejected " (get r ":rejected-concept-raw") "="
                                                     (get r ":rejected-value")))
                                        rejected))
                   "\n\n"))
            (if (seq changed)
              (str "## Companies/fiscal-years whose facts changed value\n\n"
                   "| company | fiscal-year | old value | new value | fact id |\n"
                   "|---|---|---|---|---|\n"
                   (str/join "\n" (map (fn [{:keys [company fiscal-year old-value new-value id]}]
                                          (str "| " company " | " fiscal-year " | " old-value
                                               " | " new-value " | `" id "` |"))
                                        changed))
                   "\n")
              "## Companies/fiscal-years whose facts changed value\n\n_none — all corrections were additive (new facts only)._\n"))]
    (.writeFileSync fs path md "utf8")))

(defn -main [args]
  (let [dry-run? (some #{"--dry-run"} args)
        data-path (or (second (drop-while #(not= "--data" %) args)) DEFAULT-DATA-PATH)
        summary-path (second (drop-while #(not= "--summary-path" %) args))
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
                {:keys [filings facts errors per-company rejected]} (process-companies ok)]
          (println (str "\nFetched OK: " (count per-company) "/" (count ok)
                        (when (seq errors) (str "  (errors: " (count errors) ")"))))
          (doseq [e errors] (println (str "  ERROR " (:org-id e) " (" (:ticker e) "): " (:error e))))
          (println (str "\nelement-priority tie-breaks (same canon/company/fy, multiple source "
                        "elements): " (count rejected) " (detail printed per-company above)"))
          (let [merged (merge-rows old-rows (concat filings facts))
                new-fact-count (count (filter #(contains? % ":fin.fact/id") merged))
                new-filing-count (count (filter #(contains? % ":fin.filing/id") merged))]
            (println (str "\nfacts " old-fact-count " -> " new-fact-count
                          ", filings " old-filing-count " -> " new-filing-count))
            (cond
              (or (< new-fact-count old-fact-count) (< new-filing-count old-filing-count))
              (do (println "REFUSED: merge would shrink the dataset; leaving file untouched.")
                  (js/process.exit 1))

              ;; Row content is byte-for-byte identical to the prior commit (merge-rows'
              ;; hash-map iteration order is a pure function of the id set, so this holds
              ;; whenever no id's value changed AND no id was added/removed) — DON'T touch
              ;; the file at all, not even the header timestamp. A CI workflow's
              ;; `git diff --stat -- data-path` (see .github/workflows/edgar-refresh.yml)
              ;; depends on this: write-data!'s header always embeds the current
              ;; timestamp, so writing unconditionally would make every run look "changed"
              ;; and open a no-op PR every week even when EDGAR itself returned nothing new.
              (= merged old-rows)
              (println "\nno change: every id's value is identical to the prior commit; leaving file (and its timestamp header) untouched.")

              :else
              (let [changed (changed-value-facts old-rows merged)]
                (write-data! data-path merged)
                (println (str "wrote " data-path " (.bak backup of prior content written alongside)"))
                (println (str "\nexisting facts whose value changed vs the prior commit: " (count changed)))
                (when summary-path
                  (write-summary! summary-path
                                  {:data-path data-path :old-org-count (count org-ids)
                                   :resolved-count (count ok) :skipped skipped
                                   :ok-count (count ok) :fetched-count (count per-company)
                                   :errors errors :rejected rejected
                                   :old-fact-count old-fact-count :new-fact-count new-fact-count
                                   :old-filing-count old-filing-count :new-filing-count new-filing-count
                                   :changed changed})
                  (println (str "wrote " summary-path)))))))))))

(-main (vec *command-line-args*))
