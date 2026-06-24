#!/usr/bin/env bb
;; Working Clojure port of methods/ingest.py (pure bridge + merge; live EDGAR fetch is operator-gated).
(ns kanjo.methods.ingest
  "kanjō 勘定 — ingest cell: PRIMARY-disclosure → kotoba EAVT 決算 facts (ADR-2606032000).

  Bridges primary public disclosure (SEC EDGAR companyfacts JSON · JP EDINET pre-extracted element
  JSON) into :fin.filing/* + :fin.fact/* (:authoritative), normalizing every source taxonomy
  element onto a canonical concept via concept_map. NETWORK DISCIPLINE (G7): DEFAULT OFFLINE (reads
  data/ingest/*.json); LIVE EDGAR fetch requires KANJO_OPERATOR_GATE=1 + --fetch-edgar — the
  network leg is operator-only (kept out of the clj surface). Authoritative wins on id collision
  over the :representative seed.

  Run:  bb --classpath 20-actors 20-actors/kanjo/methods/ingest.clj"
  (:require [kanjo.methods.kanjo-edn :as ke]
            [kanjo.methods.concept-map :as cm]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private this-file *file*)
(defn- actor-root [] (-> this-file io/file .getAbsoluteFile .getParentFile .getParentFile))

(def edgar-cik->org
  {"0000320193" "org.corp.us.apple" "0000789019" "org.corp.us.microsoft"
   "0001045810" "org.corp.us.nvidia" "0001018724" "org.corp.us.amazon"
   "0001652044" "org.corp.us.alphabet" "0001326801" "org.corp.us.meta"
   "0001067983" "org.corp.us.berkshire" "0001730168" "org.corp.us.broadcom"
   "0001318605" "org.corp.us.tesla" "0000050863" "org.corp.us.intel"
   "0000002488" "org.corp.us.amd" "0000723125" "org.corp.us.micron"})

(def concept-stmt (into {} (map (fn [[c m]] [c (:statement m)]) cm/concepts)))
(defn- last-seg [s] (last (str/split (str s) #"\.")))
(defn- kw [s] (keyword (str/lower-case (str s))))

(defn- dedup-latest [facts]
  (vec (vals (reduce (fn [m f] (assoc m (:fin.fact/id f) f)) {} facts))))

(defn parse-edgar-companyfacts
  "SEC EDGAR companyfacts JSON (string-keyed) → [filings facts] (:authoritative). Annual FY
  10-K/20-F points only; one fact per (concept, fy)."
  [obj org-id & {:keys [want-fy]}]
  (let [gaap (get-in obj ["facts" "us-gaap"] {})
        rows (for [[element body] gaap
                   :let [canon (cm/canonical element "usgaap")]
                   :when canon
                   [unit points] (get body "units" {})
                   p points
                   :when (and (= (get p "fp") "FY") (#{"10-K" "20-F"} (get p "form"))
                              (or (nil? want-fy) (= (get p "fy") want-fy)))]
               (let [fy (get p "fy") end (get p "end" "")
                     fid (str "fil.us.edgar." (last-seg org-id) "." fy)
                     stmt (get concept-stmt canon :pl)]
                 {:filing {:fin.filing/id fid :fin.filing/company org-id :fin.filing/source :edgar
                           :fin.filing/form (keyword (get p "form" "10-K")) :fin.filing/fiscal-year fy
                           :fin.filing/period-type :annual :fin.filing/period-end end
                           :fin.filing/filed-date (get p "filed" "") :fin.filing/accession (get p "accn" "")
                           :fin.filing/doc-cid "" :fin.filing/currency (kw unit)
                           :fin.filing/accounting :usgaap :fin.filing/sourcing :authoritative}
                  :fact {:fin.fact/id (str "fact." org-id "." fy "." (name stmt) "." canon ".consolidated")
                         :fin.fact/filing fid :fin.fact/company org-id :fin.fact/statement stmt
                         :fin.fact/concept (keyword canon) :fin.fact/concept-raw (str "us-gaap:" element)
                         :fin.fact/value (/ (double (get p "val")) 1000000.0)
                         :fin.fact/unit (kw unit) :fin.fact/scale :millions
                         :fin.fact/context :consolidated :fin.fact/period-end end
                         :fin.fact/sourcing :authoritative}}))
        filings (reduce (fn [m r] (let [fid (:fin.filing/id (:filing r))]
                                    (if (contains? m fid) m (assoc m fid (:filing r))))) {} rows)]
    [(vec (vals filings)) (dedup-latest (map :fact rows))]))

(defn parse-edinet-elements
  "R0 EDINET adapter: pre-extracted element list → [filing facts] (jgaap/ifrs)."
  [obj org-id]
  (let [std (get obj "accounting" "jgaap")
        fy (get obj "fiscalYear") cur (get obj "currency" "jpy") end (get obj "periodEnd" "")
        fid (str "fil.jp.edinet." (last-seg org-id) "." fy)
        filing {:fin.filing/id fid :fin.filing/company org-id :fin.filing/source :edinet
                :fin.filing/form :yuho :fin.filing/fiscal-year fy :fin.filing/period-type :annual
                :fin.filing/period-end end :fin.filing/filed-date (get obj "filedDate" "")
                :fin.filing/accession (get obj "docID" "") :fin.filing/doc-cid ""
                :fin.filing/currency (kw cur) :fin.filing/accounting (kw std)
                :fin.filing/sourcing :authoritative}
        facts (for [el (get obj "elements" [])
                    :let [canon (cm/canonical (get el "element") (if (= std "ifrs") "ifrs" "jgaap"))]
                    :when canon
                    :let [stmt (get concept-stmt canon :pl) ctx (get el "context" "consolidated")]]
                {:fin.fact/id (str "fact." org-id "." fy "." (name stmt) "." canon "." ctx)
                 :fin.fact/filing fid :fin.fact/company org-id :fin.fact/statement stmt
                 :fin.fact/concept (keyword canon) :fin.fact/concept-raw (get el "element")
                 :fin.fact/value (double (get el "value")) :fin.fact/unit (kw cur)
                 :fin.fact/scale (kw (get el "scale" "millions")) :fin.fact/context (kw ctx)
                 :fin.fact/period-end end :fin.fact/sourcing :authoritative})]
    [[filing] (vec facts)]))

(def ^:private rank {:representative 0 :synthesized 0 :authoritative 1})
(defn- row-id [r] (or (:fin.filing/id r) (:fin.fact/id r)))
(defn- row-src [r] (or (:fin.fact/sourcing r) (:fin.filing/sourcing r)))

(defn merge-with-seed
  "Merge ingested (:authoritative) over the :representative seed rows; authoritative wins."
  [seed filings facts]
  (let [by-id (reduce (fn [m r] (assoc m (row-id r) r)) {} seed)]
    (vec (vals (reduce (fn [m r]
                         (let [rid (row-id r) old (get m rid)]
                           (if (or (nil? old) (>= (get rank (row-src r) 0) (get rank (row-src old) 0)))
                             (assoc m rid r) m)))
                       by-id (concat filings facts))))))

(defn offline-ingest []
  (let [dir (io/file (actor-root) "data" "ingest")]
    (if-not (.isDirectory dir)
      [[] []]
      (reduce (fn [[fl fa] f]
                (let [obj (json/parse-string (slurp f))]
                  (if (and (contains? obj "facts") (contains? obj "cik"))
                    (let [org (get edgar-cik->org (format "%010d" (Long/parseLong (str (get obj "cik"))))
                                   (str "org.corp.us.cik" (get obj "cik")))
                          [f2 a2] (parse-edgar-companyfacts obj org)]
                      [(concat fl f2) (concat fa a2)])
                    (let [[f2 a2] (parse-edinet-elements obj (get obj "company"))]
                      [(concat fl f2) (concat fa a2)]))))
              [[] []]
              (sort (filter #(str/ends-with? (.getName %) ".json") (.listFiles dir)))))))

(defn main [& _]
  (let [[fl fa] (offline-ingest)
        seed (ke/read-file (io/file (actor-root) "data" "seed-financial-facts.kotoba.edn"))
        merged (merge-with-seed seed fl fa)]
    (println (format "kanjō ingest (offline): bridged %d filings · %d facts → %d merged rows (authoritative wins)"
                     (count fl) (count fa) (count merged)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply main *command-line-args*))
