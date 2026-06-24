#!/usr/bin/env bb
;; Working Clojure port of methods/kotoba.py — the local content-addressed Datom-log writer.
(ns kanjo.methods.kotoba
  "kanjō 勘定 — kotoba Datom-log writer (local, content-addressed).
  ADR-2606032000 + ADR-2605262130 + ADR-2605312345.

  The local autonomous-loop write path. Disclosed primary-filing facts only (G1); derived
  :fin.metric (ratios/YoY) + :fin.agg (sector/currency aggregates) carry :sourcing :synthesized
  and are NEVER re-ingested as disclosed facts, NEVER a rating/valuation/forecast (G2/G4/G5).

    graph-datoms   → EAVT for filings / facts / concepts (E = entity id)
    derived-datoms → EAVT for derived :fin.metric + :fin.agg (already :synthesized)
    make-tx / append-tx / read-log / head-cid / verify-chain — content-addressed commit-DAG

  :db/add only (append-only, 非終末論). Deterministic: caller supplies tx-id + as-of; the derived
  path uses no hash-set iteration → CID reproducible / resume-safe."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def ^:private this-file *file*)
(defn log-default []
  (-> this-file io/file .getAbsoluteFile .getParentFile .getParentFile
      (io/file "data" "kanjo.datoms.kotoba.edn")))

(def id-keys #{:fin.filing/id :fin.fact/id :fin.concept/id :fin.metric/id :fin.agg/id})
(defn- add-datom [e a v] [:db/add e a v])

(defn- rows->datoms [rows]
  (vec (mapcat
        (fn [r]
          (when (map? r)
            (when-let [e (some #(get r %) id-keys)]
              (for [[k v] r :when (not (id-keys k))
                    item (if (sequential? v) v [v])]
                (add-datom e k item)))))
        rows)))

(defn graph-datoms [rows] (rows->datoms rows))
(defn derived-datoms [metrics aggs] (rows->datoms (concat metrics aggs)))

(defn- sha256-hex [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes s "UTF-8"))))))
(defn- canonical [datoms prev] (str "{:datoms " (pr-str datoms) " :prev " (pr-str prev) "}"))
(defn tx-cid
  ([datoms] (tx-cid datoms ""))
  ([datoms prev] (str "b" (sha256-hex (canonical datoms prev)))))

(defn make-tx [datoms & {:keys [tx-id as-of prev-cid] :or {prev-cid ""}}]
  {:tx/id tx-id :tx/as-of as-of :tx/prev prev-cid
   :tx/cid (tx-cid datoms prev-cid) :tx/count (count datoms) :tx/datoms datoms})

(defn append-tx
  ([tx] (append-tx tx (log-default)))
  ([tx log-path]
   (let [f (io/file log-path)]
     (.mkdirs (.getParentFile (.getAbsoluteFile f)))
     (when-not (.exists f)
       (spit f (str ";; kanjō kotoba Datom log — append-only EAVT transactions (content-addressed DAG). "
                    "Disclosed facts + :synthesized ratios; non-adjudicating, no forecast (G2/G4/G5). "
                    "DO NOT hand-edit. ADR-2606032000.\n")))
     (spit f (str (pr-str tx) "\n") :append true)
     (:tx/cid tx))))

(defn read-log
  ([] (read-log (log-default)))
  ([log-path]
   (let [f (io/file log-path)]
     (if-not (.exists f) []
       (->> (str/split-lines (slurp f)) (map str/trim)
            (remove #(or (empty? %) (str/starts-with? % ";"))) (mapv edn/read-string))))))

(defn head-cid
  ([] (head-cid (log-default)))
  ([log-path] (let [txs (read-log log-path)] (if (seq txs) (:tx/cid (last txs)) ""))))

(defn verify-chain
  ([] (verify-chain (log-default)))
  ([log-path]
   (let [txs (read-log log-path)]
     (loop [i 0 prev "" xs txs]
       (if (empty? xs)
         {:ok true :length (count txs) :broken-at -1}
         (let [tx (first xs) expect (tx-cid (:tx/datoms tx []) prev)]
           (if (or (not= (:tx/cid tx) expect) (not= (:tx/prev tx) prev))
             {:ok false :length (count txs) :broken-at i}
             (recur (inc i) (:tx/cid tx) (rest xs)))))))))
