(ns kanjo.methods.depgraph
  "kanjō 勘定 — depgraph cell (ADR-0003). The SUPPLY-CHAIN / WORLD-ECONOMY DEPENDENCY
  face: joins kabuto 兜's first-class supply edges (supplier→customer, org.corp.*
  shared id space) with kanjō's DISCLOSED financials so the global dependency graph
  is anchored to the numbers companies actually filed.

  Emits three things from the join:
   - dependency records (com.etzhayyim.kanjo.dependency, ADR-0002 lexicon) — one per
     supply edge, carrying the FILER-disclosed criticality + provenance;
   - a Graphviz DOT VISUALIZATION (可視化) of the dependency graph — nodes sized by
     disclosed revenue (kanjō) or market-cap (kabuto) fallback, coloured by sector,
     edges weighted by disclosed criticality;
   - a coverage-honest report ranking the most depended-upon SUPPLIERS (systemic
     chokepoints) — a RESILIENCE + TRANSPARENCY map so buyers can diversify.

  CONSTITUTIONAL (inherits kanjō G-gates + kabuto G2): non-adjudicating — out-criticality
  is an OBSERVATION of disclosed/representative supply intensity, NOT a target list, a
  rating, or a verdict. Every node/edge carries :sourcing (G5); kabuto supply edges are
  :representative (inferred from public disclosure, NOT a bill of materials). Pure
  transforms; file I/O at the JVM edge.

  Convention parity (analyze.cljc / kanjo-edn): graph rows are maps with STRING keys."
  (:require [kotoba.lang.text :as str]
            [kanjo.methods.kanjo-edn :as kanjo-edn]
            #?(:clj [clojure.java.io :as io])))

;; ── load: kabuto supply graph + kanjō disclosed revenue ──────────────────────

(defn companies
  "kabuto :company nodes → {id {:name :sector :mcap}}."
  [rows]
  (into {} (for [r rows :when (get r ":company/id")]
             [(get r ":company/id")
              {:name (get r ":company/name")
               :sector (get r ":company/sector")
               :mcap (get r ":company/market-cap-busd")}])))

(defn supply-edges
  "kabuto :supply.edge rows → [{:from :to :tier :commodity :criticality :sourcing}]."
  [rows]
  (for [r rows :when (get r ":supply.edge/id")]
    {:from (get r ":supply.edge/from")
     :to (get r ":supply.edge/to")
     :tier (get r ":supply.edge/tier")
     :commodity (get r ":supply.edge/commodity")
     :criticality (get r ":supply.edge/criticality")
     :sourcing (get r ":supply.edge/sourcing")}))

(defn disclosed-revenue
  "kanjō facts → {company-id {:revenue value :unit u :fy n}} for the LATEST fiscal
  year disclosed per company. Joins the financials onto the supply graph."
  [facts filings]
  (let [fy-of (into {} (map (juxt #(get % ":fin.filing/id")
                                  #(get % ":fin.filing/fiscal-year")) filings))]
    (reduce (fn [m f]
              (if (= ":revenue" (get f ":fin.fact/concept"))
                (let [id (get f ":fin.fact/company")
                      fy (or (fy-of (get f ":fin.fact/filing")) 0)
                      cur (get m id)]
                  (if (or (nil? cur) (> fy (:fy cur)))
                    (assoc m id {:revenue (get f ":fin.fact/value")
                                 :unit (get f ":fin.fact/unit") :fy fy})
                    m))
                m))
            {} facts)))

;; ── analysis: dependency metrics (依存関係 分析) ──────────────────────────────

(defn node-metrics
  "Per-company structural dependency metrics over the supply graph:
   :out-crit  Σ criticality of edges where the node is the SUPPLIER (how much the
              world depends ON it — systemic-chokepoint intensity);
   :in-crit   Σ criticality of edges where the node is the CUSTOMER (how dependent
              it is on others);
   :customers / :suppliers — raw degree. Observations, not verdicts."
  [edges]
  (reduce (fn [m {:keys [from to criticality]}]
            (let [c (double (or criticality 0))]
              (-> m
                  (update-in [from :out-crit] (fnil + 0.0) c)
                  (update-in [from :customers] (fnil inc 0))
                  (update-in [to :in-crit] (fnil + 0.0) c)
                  (update-in [to :suppliers] (fnil inc 0)))))
          {} edges))

;; ── dependency records (com.etzhayyim.kanjo.dependency) ──────────────────────

(defn dependency-records
  "One com.etzhayyim.kanjo.dependency edge per supply edge, joined to company names
  + (where kanjō covers the customer) the disclosed-revenue basis. `now` passed in."
  [edges comps rev now]
  (for [{:keys [from to criticality commodity sourcing]} edges]
    {":dep/id" (str "dep." from "->" to)
     ":dep/from" from
     ":dep/from-name" (:name (comps from))
     ":dep/to" to
     ":dep/to-name" (:name (comps to))
     ":dep/relation" ":supplier"
     ":dep/commodity" commodity
     ":dep/share" criticality
     ":dep/customer-disclosed?" (boolean (rev to))
     ":dep/sourcing" (or sourcing ":representative")
     ":dep/created-at" now}))

;; ── visualization (可視化): Graphviz DOT ──────────────────────────────────────

(def ^:private sector-color
  {":semiconductors" "#e74c3c" ":software" "#3498db" ":electronics" "#9b59b6"
   ":automotive" "#e67e22" ":consumer" "#16a085" ":industrials" "#7f8c8d"
   ":materials" "#795548" ":energy" "#f39c12" ":financials" "#2c3e50"
   ":healthcare" "#27ae60" ":telecom" "#2980b9" ":utilities" "#95a5a6"})

(defn- node-id [id] (str "n_" (str/replace id #"[^A-Za-z0-9]" "_")))

(defn- short-name [nm id]
  (let [s (or nm (last (str/split (or id "") #"\.")))]
    (if (> (count s) 22) (str (subs s 0 20) "…") s)))

(defn- rev-label [rev unit]
  (when rev
    (let [sym ({":jpy" "¥" ":usd" "$" ":eur" "€" ":gbp" "£"
                ":krw" "₩" ":cny" "元" ":sek" "kr" ":twd" "NT$"} unit "")
          v (double rev)]                    ;; value is in :millions of `unit`
      (if (>= v 1.0e6)
        (str sym (format "%.1f" (/ v 1.0e6)) "tn")     ;; ≥ 1 trillion
        (str sym (format "%.0f" (/ v 1.0e3)) "bn")))))  ;; otherwise billions

(defn ->dot
  "Render the supply-dependency graph as Graphviz DOT. Only nodes incident to an edge
  are drawn. Disclosed (kanjō-covered) nodes get a bold border + revenue in the label."
  [edges comps rev metrics]
  (let [incident (into #{} (mapcat (juxt :from :to) edges))
        L (atom ["digraph kanjo_world_supply {"
                 "  rankdir=LR; bgcolor=\"#0b0e14\"; concentrate=true;"
                 "  node [style=\"filled,rounded\" shape=box fontname=\"Helvetica\" fontsize=10 fontcolor=\"#0b0e14\" color=\"#0b0e14\" penwidth=1];"
                 "  edge [color=\"#5a6472\" arrowsize=0.6];"
                 "  labelloc=t; fontcolor=\"#cdd6e0\"; fontsize=18;"
                 "  label=\"kanjō×kabuto — world supply-chain dependency (disclosed financials anchored · resilience map, not a target list · G2)\";"])
        A #(swap! L conj %)]
    (doseq [id (sort incident)]
      (let [c (comps id)
            m (metrics id)
            rv (rev id)
            disclosed? (boolean rv)
            fill (sector-color (:sector c) "#bdc3c7")
            lbl (str (short-name (:name c) id)
                     (when-let [r (rev-label (:revenue rv) (:unit rv))] (str "\\n" r))
                     (when-let [oc (:out-crit m)] (when (pos? oc) (str "\\nΣdep " (format "%.1f" oc)))))]
        (A (str "  " (node-id id) " [label=\"" lbl "\""
                " fillcolor=\"" fill "\""
                (when disclosed? " color=\"#f1c40f\" penwidth=3")
                "];"))))
    (doseq [{:keys [from to criticality]} edges]
      (let [w (+ 0.5 (* 4.0 (double (or criticality 0))))]
        (A (str "  " (node-id from) " -> " (node-id to)
                " [penwidth=" (format "%.2f" w) "];"))))
    (A "}")
    (str (str/join "\n" @L) "\n")))

(defn core-edges
  "Subgraph for a READABLE headline viz: keep only edges incident to one of the
  top-K most-depended-upon suppliers (by Σ out-criticality). Coverage is logged."
  [edges metrics k]
  (let [top (->> metrics
                 (filter (fn [[_ m]] (pos? (or (:out-crit m) 0))))
                 (sort-by (fn [[_ m]] (- (:out-crit m))))
                 (take k) (map first) set)]
    (vec (filter #(or (top (:from %)) (top (:to %))) edges))))

;; ── report (世界経済の分析) ──────────────────────────────────────────────────

(defn report [edges comps rev metrics]
  (let [incident (into #{} (mapcat (juxt :from :to) edges))
        covered (count (filter rev incident))
        chokepoints (->> metrics
                         (filter (fn [[_ m]] (pos? (or (:out-crit m) 0))))
                         (sort-by (fn [[_ m]] (- (:out-crit m))))
                         (take 15))
        L (atom [])
        A #(swap! L conj %)]
    (A "# kanjō×kabuto — world supply-chain dependency map")
    (A "")
    (A (str "Supply edges: **" (count edges) "** · incident companies: **" (count incident)
            "** · of which kanjō-disclosed financials: **" covered "**."))
    (A "")
    (A "> Non-adjudicating (G2): `Σdep` (out-criticality) is the disclosed/representative intensity")
    (A "> with which OTHER companies depend on this supplier — a **resilience signal so buyers can")
    (A "> diversify**, never a ranking of \"importance\", a rating, or a target list. kabuto supply")
    (A "> edges are `:representative` (inferred from public disclosure, NOT a bill of materials).")
    (A "> Absence of a company = not yet ingested. Coverage-bounded — NOT the world total.")
    (A "")
    (A "## Most depended-upon suppliers (systemic chokepoints, by Σ disclosed criticality)")
    (A "")
    (A "| # | supplier | sector | downstream customers | Σdep | disclosed revenue |")
    (A "|--:|---|---|--:|--:|--:|")
    (doseq [[i [id m]] (map-indexed vector chokepoints)]
      (let [c (comps id) rv (rev id)]
        (A (str "| " (inc i) " | " (short-name (:name c) id)
                " | " (str/replace-first (str (:sector c)) #"^:" "")
                " | " (or (:customers m) 0)
                " | " (format "%.2f" (or (:out-crit m) 0.0))
                " | " (or (rev-label (:revenue rv) (:unit rv)) "—") " |"))))
    (A "")
    (A "_Visualization: `out/depgraph/world-supply.svg` (Graphviz). Gold-bordered nodes are")
    (A "kanjō-disclosed; edge width = disclosed supply criticality._")
    (str (str/join "\n" @L) "\n")))

;; ── EDN dump ─────────────────────────────────────────────────────────────────

(defn- v->edn [v]
  (cond (nil? v) "nil"
        (string? v) (if (str/starts-with? v ":") v (str \" (str/replace v "\"" "\\\"") \"))
        (boolean? v) (str v)
        (and (number? v) (not (integer? v))) (str v)
        :else (str v)))

(defn edn-dump [deps]
  (str ";; kanjō 勘定 — supply-chain dependency edges (GENERATED by depgraph.cljc, ADR-0003).\n"
       ";; com.etzhayyim.kanjo.dependency shape · kabuto supply join · :representative (G5) — observations, not verdicts.\n"
       "[\n"
       (str/join "\n" (for [d deps]
                        (str " {" (str/join " " (map (fn [[k v]] (str k " " (v->edn v))) d)) "}")))
       "\n]\n"))

;; ── main (file I/O at the edge) ──────────────────────────────────────────────

#?(:clj
   (def ^:private here (-> (io/file *file*) .getParentFile .getParentFile .getParentFile .getParentFile .getAbsolutePath)))

#?(:clj
   (defn -main [& args]
     (let [kabuto (or (first args)
                      (str here "/../com-etzhayyim-kabuto/data/seed-public-companies.kotoba.edn"))
           kanjo-facts (or (second args) (str here "/data/facts.merged.kotoba.edn"))
           now (str (java.time.Instant/now))
           krows (kanjo-edn/read-file kabuto)
           comps (companies krows)
           edges (vec (supply-edges krows))
           ;; Base facts (live EDGAR merge, EDGAR/US-heavy) PLUS the :representative
           ;; supply-chain chokepoint seed (TSMC/ASML/Arm/JP semi-cluster) that anchors
           ;; the non-US nodes the dependency graph surfaced. Both join by org.corp.* key.
           chokepoints (str here "/data/seed-supply-chokepoints.kotoba.edn")
           frows (concat
                  (if (.exists (io/file kanjo-facts))
                    (kanjo-edn/read-file kanjo-facts)
                    (kanjo-edn/read-file (str here "/data/seed-financial-facts.kotoba.edn")))
                  (when (.exists (io/file chokepoints)) (kanjo-edn/read-file chokepoints)))
           filings (filter #(get % ":fin.filing/id") frows)
           facts (filter #(get % ":fin.fact/id") frows)
           rev (disclosed-revenue facts filings)
           metrics (node-metrics edges)
           deps (dependency-records edges comps rev now)
           outdir (io/file here "out" "depgraph")]
       (.mkdirs outdir)
       (spit (io/file outdir "world-supply.dot") (->dot edges comps rev metrics))
       (spit (io/file outdir "world-supply-core.dot")
             (->dot (core-edges edges metrics 20) comps rev metrics))
       (spit (io/file outdir "dependencies.kotoba.edn") (edn-dump deps))
       (spit (io/file outdir "depgraph-report.md") (report edges comps rev metrics))
       (let [incident (into #{} (mapcat (juxt :from :to) edges))]
         (println (str "kanjō depgraph: " (count edges) " supply edges · " (count incident)
                       " incident companies · " (count (filter rev incident))
                       " with disclosed financials · " (count comps) " companies in kabuto graph"))
         (println "  → out/depgraph/world-supply.dot  (render: dot -Tsvg)")
         (println "  → out/depgraph/dependencies.kotoba.edn")
         (println "  → out/depgraph/depgraph-report.md")))))
