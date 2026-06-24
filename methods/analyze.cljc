(ns kanjo.methods.analyze
  "kanjō 勘定 — analyze cell. Clojure port of methods/analyze.py (ADR-2606032000).

  Reads the disclosed-fact graph (data/seed-financial-facts.kotoba.edn or an
  ingested merge) and emits AGGREGATE-FIRST observations:
    - per-company per-fiscal-year derived ratios (:fin.metric — :synthesized, G5)
    - year-over-year growth where ≥2 fiscal years are present (as-of history)
    - sector / currency aggregates (:fin.agg — coverage-honest, never a market total)
    - intel-report.md  +  financial-metrics.kotoba.edn

  NON-ADJUDICATING (G2) / NO ADVICE (G4): every number is either a figure the
  company disclosed or a transparent ratio of two disclosed figures. kanjō reports;
  it never rates 'good/bad', values a company, or recommends an action — preserved
  1:1 from the Python (the report wording the invariant suite asserts on is verbatim).

  Convention parity (root CLAUDE.md / bond.cljc): graph rows are maps with STRING
  `\":fin.…/…\"` keys; keyword values stay `\":foo\"` strings. Pure transforms; file
  I/O sits at the JVM edge (kanjo-edn/read-file + spit)."
  (:require [clojure.string :as str]
            [kanjo.methods.kanjo-edn :as kanjo-edn]
            [kanjo.methods.concept-map :as cmap]
            #?(:clj [clojure.java.io :as io])))

;; Fallback company meta (name / sector / country), used only when kabuto's
;; :company graph is unavailable. kabuto (org.corp.* id space) is the SSoT.
(def company-meta
  {"org.corp.jp.toyota"    ["Toyota Motor"  ":automotive"  "JP"]
   "org.corp.jp.sony"      ["Sony Group"    ":electronics" "JP"]
   "org.corp.jp.nintendo"  ["Nintendo"      ":consumer"    "JP"]
   "org.corp.us.apple"     ["Apple"         ":electronics" "US"]
   "org.corp.us.microsoft" ["Microsoft"     ":software"    "US"]})

(def ccy-sym {":jpy" "¥" ":usd" "$" ":eur" "€" ":gbp" "£"})

#?(:clj
   (def ^:private here
     ;; methods/ → actor root (one level up from this file's dir, like os.path.dirname×2)
     (-> (io/file *file*) .getParentFile .getParentFile .getAbsolutePath)))

#?(:clj
   (def ^:private kabuto-seed
     (str here "/../kabuto/data/seed-public-companies.kotoba.edn")))

(defn load-company-meta
  "Join kabuto's :company graph (SSoT) for name/sector/country; fall back to inlined
  meta. Returns {company-id [name sector-keyword country]}. kabuto wins where present.
  The optional `path` is the kabuto seed (I/O at the edge); absent/unreadable → fallback."
  ([] (load-company-meta nil))
  ([path]
   #?(:clj
      (let [p (or path kabuto-seed)]
        (if (and p (.exists (io/file p)))
          (try
            (reduce (fn [meta r]
                      (let [cid (get r ":company/id")]
                        (if-not cid
                          meta
                          (assoc meta cid
                                 [(get r ":company/name" (get-in meta [cid 0] cid))
                                  (get r ":company/sector" (get-in meta [cid 1] ":unknown"))
                                  (get r ":company/country" (get-in meta [cid 2] "?"))]))))
                    company-meta
                    (kanjo-edn/read-file p))
            (catch Exception _ company-meta)) ;; unreadable → inlined fallback
          company-meta))
      :cljs company-meta)))

(def meta-table (load-company-meta))

;; ── load + reshape ───────────────────────────────────────────────────────────

(defn load
  "Read a graph EDN file → [filings facts]. filings = {id row}; facts = rows with id."
  [path]
  (let [rows (kanjo-edn/read-file path)
        filings (reduce (fn [acc r]
                          (if (contains? r ":fin.filing/id")
                            (assoc acc (get r ":fin.filing/id") r)
                            acc))
                        {} rows)
        facts (filterv #(contains? % ":fin.fact/id") rows)]
    [filings facts]))

(defn by-company-year
  "{company {fy {concept(no colon) [value unit scale]}}}. fy = int(period-end[:4]).
  Consolidated context only."
  [facts]
  (reduce
   (fn [out f]
     (if (not= (get f ":fin.fact/context") ":consolidated")
       out
       (let [co (get f ":fin.fact/company")
             fy #?(:clj (Long/parseLong (subs (get f ":fin.fact/period-end") 0 4))
                   :cljs (js/parseInt (subs (get f ":fin.fact/period-end") 0 4) 10))
             concept (str/replace-first (get f ":fin.fact/concept") #"^:+" "")
             entry [(double (get f ":fin.fact/value"))
                    (get f ":fin.fact/unit") (get f ":fin.fact/scale")]]
         (assoc-in out [co fy concept] entry))))
   {} facts))

;; ── derive metrics ───────────────────────────────────────────────────────────

(defn- round4 [x]
  ;; Python round(x, 4) — banker's rounding; HALF_EVEN to 4 places.
  #?(:clj (.doubleValue (.setScale (bigdec x) 4 java.math.RoundingMode/HALF_EVEN))
     :cljs (/ (js/Math.round (* x 10000)) 10000)))

(defn- mk-metric [co fy kind value basis]
  {":fin.metric/id" (str "metric." co "." fy "." kind)
   ":fin.metric/company" co
   ":fin.metric/fiscal-year" fy
   ":fin.metric/kind" (str ":" kind)
   ":fin.metric/value" (round4 value)
   ":fin.metric/basis" basis
   ":fin.metric/sourcing" ":synthesized"})

(defn derive-metrics
  "→ vector of :fin.metric maps (all :synthesized). Ratios from metric-inputs +
  YoY vs the immediately prior fiscal year, if present."
  [cy]
  (let [mi (cmap/metric-inputs)]
    (vec
     (mapcat
      (fn [[co years]]
        (mapcat
         (fn [[fy concepts]]
           (let [vals (into {} (map (fn [[k v]] [k (nth v 0)]) concepts))
                 ratios (keep
                         (fn [[kind [num den]]]
                           (when (and (contains? vals num) (contains? vals den)
                                      (not (zero? (get vals den))))
                             (mk-metric co fy kind (/ (get vals num) (get vals den))
                                        (str num "/" den))))
                         mi)
                 prev (get years (dec fy))
                 yoy (when prev
                       (let [prev-vals (into {} (map (fn [[k v]] [k (nth v 0)]) prev))]
                         (keep
                          (fn [[kind concept]]
                            (when (and (contains? vals concept) (contains? prev-vals concept))
                              (let [p (get prev-vals concept)]
                                (when-not (zero? p)
                                  (mk-metric co fy kind (/ (- (get vals concept) p) p)
                                             (str concept "[" fy "] vs " concept "[" (dec fy) "]"))))))
                          [["revenue-yoy" "revenue"]
                           ["operating-income-yoy" "operating-income"]
                           ["net-income-yoy" "net-income"]])))]
             (concat ratios yoy)))
         years))
      cy))))

;; ── aggregates ───────────────────────────────────────────────────────────────

(defn aggregates
  "Σ revenue per (sector, currency) — coverage-honest; NEVER cross-currency summed."
  [cy]
  (let [aggs (reduce
              (fn [aggs [co years]]
                (let [sector (get-in meta-table [co 1] ":unknown")]
                  (reduce
                   (fn [aggs [fy concepts]]
                     (if-not (contains? concepts "revenue")
                       aggs
                       (let [[val unit _scale] (get concepts "revenue")
                             k [sector unit fy]
                             a (get aggs k {"sum" 0.0 "n" 0})]
                         (assoc aggs k {"sum" (+ (get a "sum") val) "n" (inc (get a "n"))}))))
                   aggs years)))
              {} cy)]
    (vec
     (for [[[sector unit fy] a] (sort-by key aggs)]
       {":fin.agg/id" (str "agg.sector." (str/replace-first sector #"^:+" "") "."
                           (str/replace-first unit #"^:+" "") "." fy ".revenue")
        ":fin.agg/dimension" ":sector"
        ":fin.agg/key" (str/replace-first sector #"^:+" "")
        ":fin.agg/fiscal-year" fy
        ":fin.agg/concept" ":revenue"
        ":fin.agg/sum" (get a "sum")
        ":fin.agg/n" (get a "n")
        ":fin.agg/sourcing" ":synthesized"}))))

;; ── formatting ───────────────────────────────────────────────────────────────

(defn- fmt-float [pattern x] #?(:clj (format pattern x) :cljs (str x)))

(defn fmt-money
  ([v unit] (fmt-money v unit ":millions"))
  ([v unit scale]
   (let [sym (get ccy-sym unit (str (str/upper-case (str/replace-first unit #"^:+" "")) " "))]
     (if (= scale ":millions")
       (cond
         (>= (Math/abs (double v)) 1000000) (str sym (fmt-float "%.2f" (/ v 1000000.0)) "tn")
         (>= (Math/abs (double v)) 1000) (str sym (fmt-float "%.1f" (/ v 1000.0)) "bn")
         :else (str sym (fmt-float "%,.0f" (double v)) "m"))
       (str sym (fmt-float "%,.0f" (double v)) "m")))))

(defn pct [x] (str (fmt-float "%.1f" (* x 100)) "%"))

;; sort companies/keys the way Python sorted() does over strings/tuples
(defn- max-key-num [m] (apply max (keys m)))

(defn report
  "Aggregate-first markdown intel report. Wording is verbatim from the Python so the
  G2/G4 invariant suite (non-adjudicating / no-advice / 'does not forecast') holds 1:1."
  [filings facts cy metrics aggs]
  (let [L (atom [])
        A (fn [s] (swap! L conj s))
        sources (sort (distinct (map #(get % ":fin.filing/source") (vals filings))))
        companies (sort (keys cy))]
    (A "# kanjō 勘定 — 決算 (financial-disclosure) intel report")
    (A "")
    (A "> Aggregate-first, **non-adjudicating** (G2), **no investment advice** (G4). Every figure is")
    (A "> either disclosed by the company in a primary filing (EDINET / EDGAR) or a transparent ratio")
    (A "> of two disclosed figures (`:synthesized`, G5). This is a transparency map, never a verdict")
    (A "> or a recommendation. Seed cohort is `:representative` — see honesty note.")
    (A "")
    (A "## Coverage")
    (A "")
    (A (str "- **Filings**: " (count filings) " · **Facts**: " (count facts) " · **Companies**: "
            (count companies) " · " "**Metrics derived**: " (count metrics)))
    (A (str "- **Primary-disclosure sources**: "
            (str/join ", " (map #(str/replace-first % #"^:+" "") sources))
            " (all Tier-A per ADR-2605263800 §2 — NO 四季報 / no paid terminal, G1)"))
    (A (str "- **Sourcing**: every fact `:representative` in this seed (headline figures, rounded). "
            "Authoritative line-item XBRL = G7 operator-gated (`ingest.py`)."))
    (A "")
    (A "## Per-company FY2024 (as disclosed + derived ratios)")
    (A "")
    (A "| Company | Ctry | Revenue | Op income | Net income | Op margin | Net margin | ROE | Equity ratio |")
    (A "|---|---|--:|--:|--:|--:|--:|--:|--:|")
    (doseq [co companies]
      (let [years (get cy co)
            fy (max-key-num years)
            c (get years fy)
            [name _sector ctry] (get meta-table co [co "" "?"])
            unit (nth (get c "revenue" [0 ":usd" ":millions"]) 1)
            rev (if (contains? c "revenue") (fmt-money (nth (get c "revenue") 0) unit) "—")
            opi (if (contains? c "operating-income") (fmt-money (nth (get c "operating-income") 0) unit) "—")
            ni (if (contains? c "net-income") (fmt-money (nth (get c "net-income") 0) unit) "—")
            mm (into {} (for [m metrics
                              :when (and (= (get m ":fin.metric/company") co)
                                         (= (get m ":fin.metric/fiscal-year") fy))]
                          [(get m ":fin.metric/kind") (get m ":fin.metric/value")]))
            opm (if (contains? mm ":operating-margin") (pct (get mm ":operating-margin")) "—")
            nm (if (contains? mm ":net-margin") (pct (get mm ":net-margin")) "—")
            roe (if (contains? mm ":roe") (pct (get mm ":roe")) "—")
            eq (if (contains? mm ":equity-ratio") (pct (get mm ":equity-ratio")) "—")]
        (A (str "| " name " | " ctry " | " rev " | " opi " | " ni " | " opm " | " nm " | " roe " | " eq " |"))))
    (A "")
    (A "_Margins/ROE are `:synthesized` ratios of disclosed figures. Revenue shown in the filing's own")
    (A "currency — kanjō does NOT FX-convert in R0, so figures across currencies are NOT comparable as-is._")
    (A "")
    (let [yoy (filter #(str/ends-with? (get % ":fin.metric/kind") "-yoy") metrics)]
      (when (seq yoy)
        (A "## Year-over-year (as-of history — 非終末論, prior facts retained)")
        (A "")
        (A "| Company | FY | Revenue YoY | Op income YoY | Net income YoY |")
        (A "|---|--:|--:|--:|--:|")
        (let [byco (reduce (fn [m mt]
                             (assoc-in m [[(get mt ":fin.metric/company") (get mt ":fin.metric/fiscal-year")]
                                          (get mt ":fin.metric/kind")]
                                       (get mt ":fin.metric/value")))
                           {} yoy)]
          (doseq [[[co fy] kinds] (sort-by key byco)]
            (let [name (get-in meta-table [co 0] co)
                  r (if (contains? kinds ":revenue-yoy") (pct (get kinds ":revenue-yoy")) "—")
                  o (if (contains? kinds ":operating-income-yoy") (pct (get kinds ":operating-income-yoy")) "—")
                  n (if (contains? kinds ":net-income-yoy") (pct (get kinds ":net-income-yoy")) "—")]
              (A (str "| " name " | " fy " | " r " | " o " | " n " |")))))
        (A "")))
    (A "## Sector aggregates (coverage-honest — read against `n`, never a market total; G3/G5)")
    (A "")
    (A "| Sector | Currency | FY | Σ revenue | n companies |")
    (A "|---|---|--:|--:|--:|")
    (doseq [a aggs]
      (let [unit (str ":" (nth (str/split (get a ":fin.agg/id") #"\.") (- (count (str/split (get a ":fin.agg/id") #"\.")) 3)))]
        (A (str "| " (get a ":fin.agg/key") " | "
                (str/upper-case (str/replace-first unit #"^:+" "")) " | "
                (get a ":fin.agg/fiscal-year") " | "
                (fmt-money (get a ":fin.agg/sum") unit) " | " (get a ":fin.agg/n") " |"))))
    (A "")
    (A "> Σ is bounded by what is ingested — it is NOT the sector's market total. Cross-currency sums")
    (A "> are deliberately NOT computed (no FX layer in R0). Absence of a company ≠ zero.")
    (A "")
    (A "## Honesty (R0)")
    (A "")
    (A "- Bounded `:representative` seed of a few real filers (JP EDINET + US EDGAR). \"Register ALL")
    (A "  companies' 決算\" is the **R1** goal — full EDINET/EDGAR-universe XBRL parse is **G7** Council +")
    (A "  operator gated (`ingest.py`).")
    (A "- Figures are publicly-documented HEADLINE numbers, rounded — not the authoritative line-item XBRL.")
    (A "- 経常利益 (`:ordinary-income`) is JGAAP-only; it is recorded where filed (Nintendo) but is NOT")
    (A "  cross-compared to US-GAAP / IFRS filers (concept_map note).")
    (A "- kanjō does not forecast (no 業績予想 — that is exactly what the prohibited 四季報 adds), does not")
    (A "  rate, value, or advise. It records what was disclosed and the arithmetic of it.")
    (str (str/join "\n" @L) "\n")))

;; ── EDN dump ─────────────────────────────────────────────────────────────────

(defn- v->edn [v]
  (cond
    (string? v) (if (str/starts-with? v ":") v (str "\"" (str/replace v "\"" "\\\"") "\""))
    (boolean? v) (if v "true" "false")
    (and (number? v) (not (integer? v))) (str v)   ;; double repr
    :else (str v)))

(defn edn-dump [metrics aggs]
  (let [L (atom [";; kanjō 勘定 — derived financial metrics + aggregates (GENERATED by analyze.cljc)"
                 ";; ADR-2606032000 · all :synthesized (G5) — NEVER re-ingested as disclosed facts."
                 "["])
        emit-row (fn [m] (swap! L conj (str " {" (str/join " " (map (fn [[k v]] (str k " " (v->edn v))) m)) "}")))]
    (doseq [m metrics] (emit-row m))
    (doseq [a aggs] (emit-row a))
    (swap! L conj "]")
    (str (str/join "\n" @L) "\n")))

;; ── main (file I/O at the edge) ──────────────────────────────────────────────

#?(:clj
   (defn -main [& args]
     (let [src (or (first args) (str here "/data/seed-financial-facts.kotoba.edn"))
           [filings facts] (load src)
           cy (by-company-year facts)
           metrics (derive-metrics cy)
           aggs (aggregates cy)
           outdir (io/file here "out")]
       (.mkdirs outdir)
       (spit (io/file outdir "intel-report.md") (report filings facts cy metrics aggs))
       (spit (io/file outdir "financial-metrics.kotoba.edn") (edn-dump metrics aggs))
       (println (str "kanjō analyze: " (count filings) " filings · " (count facts) " facts · "
                     (count cy) " companies · " (count metrics) " metrics · " (count aggs) " aggregates"))
       (println "  → out/intel-report.md")
       (println "  → out/financial-metrics.kotoba.edn"))))
