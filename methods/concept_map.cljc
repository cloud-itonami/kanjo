(ns kanjo.methods.concept-map
  "kanjō 勘定 — canonical concept dictionary (the GAAP-normalization layer).
  Clojure port of methods/concept_map.py (ADR-2606032000).

  The single source of truth mapping a SOURCE XBRL taxonomy element (EDINET
  jppfs_cor / jpcrp_cor · US-GAAP us-gaap:* · IFRS ifrs-full:*) onto a kanjō
  CANONICAL concept keyword (\":revenue\", \":operating-income\", …) so JP-GAAP,
  US-GAAP and IFRS filings land in one comparable EAVT vocabulary. The G5
  :synthesized normalization layer — honest where two standards are NOT
  comparable (経常利益 / ordinary-income is JGAAP-only).

  Canonical concept names are plain strings (no leading colon), as in the Python
  port — `canonical` returns the bare name or nil; `:fin.concept/*` attrs add the
  colon at emit time. Convention parity: all `:*` attr/value tokens stay strings."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

;; ── canonical concept catalogue ──────────────────────────────────────────────
;; Each entry: [canonical-name [statement label jgaap usgaap ifrs note]] — ORDERED
;; (vector of pairs) to keep _index / _edn first-match-wins + emission order
;; byte-identical to the Python dict's insertion order.
(def concepts
  [["revenue"
    [":pl" "Revenue / 売上高"
     ["NetSales" "OperatingRevenue1" "Revenue" "NetSalesSummaryOfBusinessResults"]
     ["RevenueFromContractWithCustomerExcludingAssessedTax" "Revenues" "SalesRevenueNet"]
     ["Revenue"]
     ""]]
   ["gross-profit"
    [":pl" "Gross profit / 売上総利益"
     ["GrossProfit"] ["GrossProfit"] ["GrossProfit"] ""]]
   ["operating-income"
    [":pl" "Operating income / 営業利益"
     ["OperatingIncome" "OperatingProfitLoss"]
     ["OperatingIncomeLoss"]
     ["ProfitLossFromOperatingActivities"]
     ""]]
   ["ordinary-income"
    [":pl" "Ordinary income / 経常利益"
     ["OrdinaryIncome" "OrdinaryProfitLoss"]
     []
     []
     "JGAAP-only. No US-GAAP / IFRS equivalent — do NOT cross-compare across standards."]]
   ["pretax-income"
    [":pl" "Pre-tax income / 税引前当期純利益"
     ["IncomeBeforeIncomeTaxes" "ProfitLossBeforeTax"]
     ["IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"]
     ["ProfitLossBeforeTax"]
     ""]]
   ["net-income"
    [":pl" "Net income attributable to owners of parent / 親会社株主に帰属する当期純利益"
     ["ProfitLossAttributableToOwnersOfParent" "ProfitLoss" "NetIncome"]
     ["NetIncomeLoss"]
     ["ProfitLossAttributableToOwnersOfParent" "ProfitLoss"]
     ""]]
   ["total-assets"
    [":bs" "Total assets / 資産合計"
     ["Assets"] ["Assets"] ["Assets"] ""]]
   ["current-assets"
    [":bs" "Current assets / 流動資産"
     ["CurrentAssets"] ["AssetsCurrent"] ["CurrentAssets"] ""]]
   ["total-liabilities"
    [":bs" "Total liabilities / 負債合計"
     ["Liabilities"] ["Liabilities"] ["Liabilities"] ""]]
   ["current-liabilities"
    [":bs" "Current liabilities / 流動負債"
     ["CurrentLiabilities"] ["LiabilitiesCurrent"] ["CurrentLiabilities"] ""]]
   ["total-equity"
    [":bs" "Total equity / net assets / 純資産"
     ["NetAssets" "EquityAttributableToOwnersOfParent"]
     ["StockholdersEquity" "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"]
     ["Equity" "EquityAttributableToOwnersOfParent"]
     "JGAAP 純資産 (NetAssets) includes non-controlling interests; equity-ratio here uses it as-published."]]
   ["cash-and-equivalents"
    [":bs" "Cash and cash equivalents / 現金及び現金同等物"
     ["CashAndDeposits" "CashAndCashEquivalents"]
     ["CashAndCashEquivalentsAtCarryingValue"]
     ["CashAndCashEquivalents"]
     ""]]
   ["cfo"
    [":cf" "Operating cash flow / 営業活動によるCF"
     ["NetCashProvidedByUsedInOperatingActivities"]
     ["NetCashProvidedByUsedInOperatingActivities"]
     ["CashFlowsFromUsedInOperatingActivities"]
     ""]]
   ["cfi"
    [":cf" "Investing cash flow / 投資活動によるCF"
     ["NetCashProvidedByUsedInInvestmentActivities" "NetCashProvidedByUsedInInvestingActivities"]
     ["NetCashProvidedByUsedInInvestingActivities"]
     ["CashFlowsFromUsedInInvestingActivities"]
     ""]]
   ["cff"
    [":cf" "Financing cash flow / 財務活動によるCF"
     ["NetCashProvidedByUsedInFinancingActivities"]
     ["NetCashProvidedByUsedInFinancingActivities"]
     ["CashFlowsFromUsedInFinancingActivities"]
     ""]]
   ["capex"
    [":cf" "Capital expenditure / 設備投資 (有形固定資産の取得)"
     ["PurchaseOfPropertyPlantAndEquipment"]
     ["PaymentsToAcquirePropertyPlantAndEquipment"]
     ["PurchaseOfPropertyPlantAndEquipment"]
     "Sign as-published (a cash OUTFLOW; typically negative in the CF statement)."]]
   ["eps"
    [":eps" "Basic earnings per share / 1株当たり当期純利益"
     ["BasicEarningsLossPerShare" "BasicEarningsPerShare"]
     ["EarningsPerShareBasic"]
     ["BasicEarningsLossPerShare"]
     ""]]])

;; lookup map (canonical-name → entry tuple) — for CONCEPTS[...] style access
(def CONCEPTS (into {} concepts))

;; reverse index: source element local-name -> canonical name (per standard).
;; first match wins (setdefault) — relies on the ordered `concepts` vector.
(defn- build-index []
  (reduce
   (fn [idx [canon [_stmt _label jg us ifrs _note]]]
     (-> idx
         (update "jgaap" #(reduce (fn [m e] (if (contains? m e) m (assoc m e canon))) % jg))
         (update "usgaap" #(reduce (fn [m e] (if (contains? m e) m (assoc m e canon))) % us))
         (update "ifrs" #(reduce (fn [m e] (if (contains? m e) m (assoc m e canon))) % ifrs))))
   {"jgaap" {} "usgaap" {} "ifrs" {}}
   concepts))

(def ^:private idx (build-index))

(defn canonical
  "Map a source taxonomy element (local-name, prefix optional) onto a canonical
  concept name (without leading ':'), or nil if unmapped.
  standard ∈ {\"jgaap\",\"usgaap\",\"ifrs\"}. Accepts \"us-gaap:Revenues\",
  \"jppfs_cor:NetSales\", \"ifrs-full:Revenue\" or bare \"NetSales\"."
  [element standard]
  (let [local (last (str/split element #":"))]
    (get-in idx [standard local])))

(defn metric-inputs
  "Which canonical concepts each derived :fin.metric depends on (used by analyze).
  Ordered (array-map) so iteration order matches the Python dict (G5 determinism)."
  []
  (array-map
   "operating-margin" ["operating-income" "revenue"]
   "net-margin" ["net-income" "revenue"]
   "gross-margin" ["gross-profit" "revenue"]
   "roe" ["net-income" "total-equity"]
   "roa" ["net-income" "total-assets"]
   "equity-ratio" ["total-equity" "total-assets"]
   "current-ratio" ["current-assets" "current-liabilities"]))

;; ── EDN dictionary emit (concept_map.py __main__ path) ──────────────────────

(defn- s-quote [x]
  (str "\"" (-> x (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn edn-dump []
  (let [lines (concat
               [";; kanjō 勘定 — canonical concept dictionary (GAAP-normalization map)"
                ";; GENERATED by methods/concept_map.cljc — ADR-2606032000. :synthesized normalization layer (G5)."
                ";; vocabulary: corporate-financials-ontology.kotoba.edn (:fin.concept/*)"
                "["]
               (for [[canon [stmt label jg us ifrs note]] concepts]
                 (let [parts (cond-> [(str ":fin.concept/id :" canon)
                                      (str ":fin.concept/statement " stmt)
                                      (str ":fin.concept/label " (s-quote label))
                                      (str ":fin.concept/jgaap " (s-quote (str/join "," jg)))
                                      (str ":fin.concept/usgaap " (s-quote (str/join "," us)))
                                      (str ":fin.concept/ifrs " (s-quote (str/join "," ifrs)))]
                               (seq note) (conj (str ":fin.concept/note " (s-quote note))))]
                   (str " {" (str/join " " parts) "}")))
               ["]"])]
    (str (str/join "\n" lines) "\n")))
