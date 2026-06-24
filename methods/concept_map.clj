#!/usr/bin/env bb
;; Working Clojure port of methods/concept_map.py (replaces the failed unit_refactor cljc stub).
(ns kanjo.methods.concept-map
  "kanjō 勘定 — canonical concept dictionary (the GAAP-normalization layer, ADR-2606032000).

  Maps a SOURCE XBRL taxonomy element (EDINET jppfs_cor/jpcrp_cor · US-GAAP us-gaap:* · IFRS
  ifrs-full:*) onto a kanjō CANONICAL concept keyword (:revenue, :operating-income, …) so JP-GAAP /
  US-GAAP / IFRS filings land in one comparable EAVT vocabulary. The G5 :synthesized normalization
  layer — honest where two standards are NOT comparable (経常利益 / ordinary-income is JGAAP-only)."
  (:require [clojure.string :as str]))

;; canonical-keyword → {:statement :label :jgaap [] :usgaap [] :ifrs [] :note}
(def concepts
  {"revenue" {:statement :pl :label "Revenue / 売上高"
              :jgaap ["NetSales" "OperatingRevenue1" "Revenue" "NetSalesSummaryOfBusinessResults"]
              :usgaap ["RevenueFromContractWithCustomerExcludingAssessedTax" "Revenues" "SalesRevenueNet"]
              :ifrs ["Revenue"] :note ""}
   "gross-profit" {:statement :pl :label "Gross profit / 売上総利益"
                   :jgaap ["GrossProfit"] :usgaap ["GrossProfit"] :ifrs ["GrossProfit"] :note ""}
   "operating-income" {:statement :pl :label "Operating income / 営業利益"
                       :jgaap ["OperatingIncome" "OperatingProfitLoss"] :usgaap ["OperatingIncomeLoss"]
                       :ifrs ["ProfitLossFromOperatingActivities"] :note ""}
   "ordinary-income" {:statement :pl :label "Ordinary income / 経常利益"
                      :jgaap ["OrdinaryIncome" "OrdinaryProfitLoss"] :usgaap [] :ifrs []
                      :note "JGAAP-only. No US-GAAP / IFRS equivalent — do NOT cross-compare across standards."}
   "pretax-income" {:statement :pl :label "Pre-tax income / 税引前当期純利益"
                    :jgaap ["IncomeBeforeIncomeTaxes" "ProfitLossBeforeTax"]
                    :usgaap ["IncomeLossFromContinuingOperationsBeforeIncomeTaxesMinorityInterestAndIncomeLossFromEquityMethodInvestments"]
                    :ifrs ["ProfitLossBeforeTax"] :note ""}
   "net-income" {:statement :pl :label "Net income attributable to owners of parent / 親会社株主に帰属する当期純利益"
                 :jgaap ["ProfitLossAttributableToOwnersOfParent" "ProfitLoss" "NetIncome"]
                 :usgaap ["NetIncomeLoss"]
                 :ifrs ["ProfitLossAttributableToOwnersOfParent" "ProfitLoss"] :note ""}
   "total-assets" {:statement :bs :label "Total assets / 資産合計"
                   :jgaap ["Assets"] :usgaap ["Assets"] :ifrs ["Assets"] :note ""}
   "current-assets" {:statement :bs :label "Current assets / 流動資産"
                     :jgaap ["CurrentAssets"] :usgaap ["AssetsCurrent"] :ifrs ["CurrentAssets"] :note ""}
   "total-liabilities" {:statement :bs :label "Total liabilities / 負債合計"
                        :jgaap ["Liabilities"] :usgaap ["Liabilities"] :ifrs ["Liabilities"] :note ""}
   "current-liabilities" {:statement :bs :label "Current liabilities / 流動負債"
                          :jgaap ["CurrentLiabilities"] :usgaap ["LiabilitiesCurrent"] :ifrs ["CurrentLiabilities"] :note ""}
   "total-equity" {:statement :bs :label "Total equity / net assets / 純資産"
                   :jgaap ["NetAssets" "EquityAttributableToOwnersOfParent"]
                   :usgaap ["StockholdersEquity" "StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"]
                   :ifrs ["Equity" "EquityAttributableToOwnersOfParent"]
                   :note "JGAAP 純資産 (NetAssets) includes non-controlling interests; equity-ratio here uses it as-published."}
   "cash-and-equivalents" {:statement :bs :label "Cash and cash equivalents / 現金及び現金同等物"
                           :jgaap ["CashAndDeposits" "CashAndCashEquivalents"]
                           :usgaap ["CashAndCashEquivalentsAtCarryingValue"] :ifrs ["CashAndCashEquivalents"] :note ""}
   "cfo" {:statement :cf :label "Operating cash flow / 営業活動によるCF"
          :jgaap ["NetCashProvidedByUsedInOperatingActivities"] :usgaap ["NetCashProvidedByUsedInOperatingActivities"]
          :ifrs ["CashFlowsFromUsedInOperatingActivities"] :note ""}
   "cfi" {:statement :cf :label "Investing cash flow / 投資活動によるCF"
          :jgaap ["NetCashProvidedByUsedInInvestmentActivities" "NetCashProvidedByUsedInInvestingActivities"]
          :usgaap ["NetCashProvidedByUsedInInvestingActivities"] :ifrs ["CashFlowsFromUsedInInvestingActivities"] :note ""}
   "cff" {:statement :cf :label "Financing cash flow / 財務活動によるCF"
          :jgaap ["NetCashProvidedByUsedInFinancingActivities"] :usgaap ["NetCashProvidedByUsedInFinancingActivities"]
          :ifrs ["CashFlowsFromUsedInFinancingActivities"] :note ""}
   "capex" {:statement :cf :label "Capital expenditure / 設備投資 (有形固定資産の取得)"
            :jgaap ["PurchaseOfPropertyPlantAndEquipment"] :usgaap ["PaymentsToAcquirePropertyPlantAndEquipment"]
            :ifrs ["PurchaseOfPropertyPlantAndEquipment"]
            :note "Sign as-published (a cash OUTFLOW; typically negative in the CF statement)."}
   "eps" {:statement :eps :label "Basic earnings per share / 1株当たり当期純利益"
          :jgaap ["BasicEarningsLossPerShare" "BasicEarningsPerShare"] :usgaap ["EarningsPerShareBasic"]
          :ifrs ["BasicEarningsLossPerShare"] :note ""}})

(def ^:private index
  (reduce (fn [idx [canon m]]
            (reduce (fn [idx std]
                      (reduce (fn [idx el] (update-in idx [std] #(if (contains? % el) % (assoc % el canon))))
                              idx (get m (case std "jgaap" :jgaap "usgaap" :usgaap "ifrs" :ifrs))))
                    idx ["jgaap" "usgaap" "ifrs"]))
          {"jgaap" {} "usgaap" {} "ifrs" {}}
          concepts))

(defn canonical
  "Map a source taxonomy element (local-name, prefix optional) → canonical concept keyword
  (without ':'), or nil if unmapped. standard ∈ {\"jgaap\" \"usgaap\" \"ifrs\"}."
  [element standard]
  (let [local (last (str/split (str element) #":"))]
    (get-in index [standard local])))

(defn metric-inputs []
  {"operating-margin" ["operating-income" "revenue"]
   "net-margin" ["net-income" "revenue"]
   "gross-margin" ["gross-profit" "revenue"]
   "roe" ["net-income" "total-equity"]
   "roa" ["net-income" "total-assets"]
   "equity-ratio" ["total-equity" "total-assets"]
   "current-ratio" ["current-assets" "current-liabilities"]})

(defn main [& _]
  (println (format "kanjō concept-map: %d canonical concepts; self-check %s"
                   (count concepts)
                   (if (and (= (canonical "jppfs_cor:NetSales" "jgaap") "revenue")
                            (= (canonical "us-gaap:NetIncomeLoss" "usgaap") "net-income")
                            (= (canonical "ifrs-full:Assets" "ifrs") "total-assets")
                            (nil? (canonical "OrdinaryIncome" "usgaap")))
                     "ok" "FAILED"))))

(when (= *file* (System/getProperty "babashka.file"))
  (main))
