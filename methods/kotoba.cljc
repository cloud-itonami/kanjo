(ns kanjo.methods.kotoba
  "kotoba.py — kanjō kotoba Datom-log writer (local, content-addressed). ADR-2606032000
  + ADR-2605262130 + ADR-2605312345. 1:1 Clojure port of `methods/kotoba.py`.

  The local, autonomous-loop write path: a self-driving heartbeat appends content-addressed
  transactions to a local append-only EDN log with NO external I/O. Canonical state = the kotoba
  Datom log (content-addressed EAVT assertions, append-only — 非終末論).

    - graph-datoms(rows)            → EAVT assertions for every disclosed entity (filing / fact /
                                      concept). E = the entity's id; lists fan out.
    - derived-datoms(metrics, aggs) → EAVT assertions for the analyzer's derived :fin.metric +
                                      :fin.agg (each already carries :sourcing :synthesized, G5).
    - make-tx / append-tx / read-log / head-cid / verify-chain — content-addressed commit-DAG.

  EAVT = [op entity attribute value]; op is :db/add only (append-only — no :db/retract).
  Deterministic: the caller supplies tx-id + as-of (no wall clock) → resume-safe.

  House style (mirrors kanjo.methods.analyze / concept-map): map keys stay verbatim string keys,
  Python ':ns/name' keyword strings stay literal strings; pure fns; file I/O only behind #?(:clj …).
  SELF-CONTAINED for hashing (own sha-256 + canonical JSON), and read-back reuses the actor's own
  kanjo-edn reader (the same dependency the Python uses). The tx CID reproduces Python
  `'b' + hashlib.sha256(json.dumps({'prev':…,'datoms':…}, ensure_ascii=False, sort_keys=True,
  separators=(',',':')).encode('utf-8')).hexdigest()` byte-for-byte.
  (The Python `__main__` heartbeat printer is omitted — it is the autorun.cljc -main concern.)"
  (:require [clojure.string :as str]
            [kanjo.methods.kanjo-edn :as kanjo-edn]))

;; ── sha-256 host seam ─────────────────────────────────────────────────────────
(def ^:dynamic *sha256-hex*
  "String → lowercase hex sha-256 digest (UTF-8). Rebind on hosts without MessageDigest."
  #?(:clj (fn [^String s]
            (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                             (.getBytes s "UTF-8"))]
              (str/join (map #(let [h (Integer/toHexString (bit-and % 0xff))]
                                (if (= 1 (count h)) (str "0" h) h))
                             d))))
     :default (fn [_] (throw (ex-info "bind kanjo.methods.kotoba/*sha256-hex* on this host" {})))))

;; entity-id keys (an entity's :*/id wins in this order), mirrors ID_KEYS.
(def id-keys
  [":fin.filing/id" ":fin.fact/id" ":fin.concept/id"
   ":fin.metric/id" ":fin.agg/id"])

;; ── EAVT assertion ────────────────────────────────────────────────────────────
(defn add
  "One append-only EAVT assertion: [:db/add <entity> <attr> <value>]."
  [entity attr value]
  [":db/add" entity attr value])

(defn- rows->datoms
  "Flatten rows (maps) into append-only EAVT assertions. E = the first matching :*/id; lists fan
  out one datom per item. Map key iteration follows the EDN reader's insertion order — Python dict
  order."
  [rows]
  (reduce
   (fn [out r]
     (if-not (map? r)
       out
       (let [e (some #(when (contains? r %) (get r %)) id-keys)]
         (if (nil? e)
           out
           (reduce (fn [out k]
                     (if (some #{k} id-keys)
                       out
                       (let [v (get r k)
                             items (if (sequential? v) v [v])]
                         (reduce (fn [out item] (conj out (add e k item))) out items))))
                   out
                   (keys r))))))
   []
   rows))

(defn graph-datoms
  "Flatten the disclosed-fact graph (filings / facts / concepts) into append-only EAVT
  assertions. E = the entity's id. Disclosed primary-filing facts only (G1)."
  [rows]
  (rows->datoms rows))

(defn derived-datoms
  "Flatten the analyzer's derived :fin.metric (ratios / YoY) + :fin.agg (sector/currency
  aggregates) into append-only EAVT assertions. Each map already carries :sourcing :synthesized
  (G5) — transparent observations, NEVER re-ingested as disclosed facts, NEVER a
  rating/valuation/forecast (G2/G4)."
  [metrics aggs]
  (rows->datoms (concat (vec metrics) (vec aggs))))

;; ── canonical JSON for the CID preimage ──────────────────────────────────────
;; Mirrors _canonical's json.dumps({"prev":…,"datoms":…}, ensure_ascii=False, sort_keys=True,
;; separators=(",",":")). ensure_ascii=FALSE → non-ASCII emitted RAW, not \uXXXX.
(defn- json-escape-utf8 ^String [^String s]
  (str/escape s {\" "\\\"" \\ "\\\\"
                 \backspace "\\b" \tab "\\t" \newline "\\n" \formfeed "\\f" \return "\\r"}))

(defn- canonical-json-utf8 ^String [v]
  (cond
    (string? v)     (str "\"" (json-escape-utf8 v) "\"")
    (boolean? v)    (if v "true" "false")
    (nil? v)        "null"
    (integer? v)    (str v)
    (number? v)     (str v)
    (map? v)        (str "{" (str/join "," (map (fn [k] (str "\"" (json-escape-utf8 (str k)) "\":"
                                                             (canonical-json-utf8 (get v k))))
                                                (sort (keys v)))) "}")
    (sequential? v) (str "[" (str/join "," (map canonical-json-utf8 v)) "]")
    :else (throw (ex-info "canonical-json-utf8: unsupported value" {:value v}))))

(defn- canonical [datoms prev-cid]
  ;; sort_keys=True orders the top map's keys alphabetically: "datoms" < "prev".
  (canonical-json-utf8 {"prev" prev-cid "datoms" datoms}))

(defn tx-cid
  "Content address = sha256 over (prev-cid, datoms) → a commit-DAG."
  ([datoms] (tx-cid datoms ""))
  ([datoms prev-cid]
   (str "b" (*sha256-hex* (canonical datoms prev-cid)))))

(defn make-tx
  "Assemble one content-addressed transaction map (string :tx/* keys, mirrors Python)."
  [datoms & {:keys [tx-id as-of prev-cid] :or {prev-cid ""}}]
  {":tx/id"     tx-id
   ":tx/as-of"  as-of
   ":tx/prev"   prev-cid
   ":tx/cid"    (tx-cid datoms prev-cid)
   ":tx/count"  (count datoms)
   ":tx/datoms" datoms})

;; ── EDN value rendering (mirrors _edn_val) ───────────────────────────────────
(defn- json-dumps-str
  "json.dumps(s, ensure_ascii=False) — a double-quoted, escaped JSON string."
  [^String s]
  (str "\"" (json-escape-utf8 s) "\""))

(defn- edn-val ^String [v]
  ;; mirrors _edn_val: bool → true/false; int/float → repr; keyword-string passes through;
  ;; other string → json.dumps; list → bracketed; else str+dumps.
  (cond
    (boolean? v) (if v "true" "false")
    (integer? v) (str v)
    (number? v)  (str v)        ;; round4'd doubles → repr-identical to Python
    (string? v)  (if (str/starts-with? v ":") v (json-dumps-str v))
    (sequential? v) (str "[" (str/join " " (map edn-val v)) "]")
    :else (json-dumps-str (str v))))

(defn- tx-to-edn ^String [tx]
  (let [datoms (str/join " " (map (fn [d] (str "[" (str/join " " (map edn-val d)) "]"))
                                  (get tx ":tx/datoms")))]
    (str "{:tx/id " (get tx ":tx/id")
         " :tx/as-of " (get tx ":tx/as-of")
         " :tx/prev " (json-dumps-str (get tx ":tx/prev"))
         " :tx/cid " (json-dumps-str (get tx ":tx/cid"))
         " :tx/count " (get tx ":tx/count")
         " :tx/datoms [" datoms "]}")))

(def ^:private log-header
  (str ";; kanjo kotoba Datom log — append-only EAVT transactions "
       "(content-addressed DAG). Disclosed facts + :synthesized ratios; "
       "non-adjudicating, no advice/forecast. DO NOT hand-edit. ADR-2606032000.\n"))

#?(:clj
   (defn append-tx
     "Append ONE transaction to the append-only log (never rewrites). Returns the tx CID."
     [tx log-path]
     (let [f (clojure.java.io/file (str log-path))]
       (when-let [parent (.getParentFile f)] (.mkdirs parent))
       (when-not (.exists f) (spit f log-header))
       (spit f (str (tx-to-edn tx) "\n") :append true)
       (get tx ":tx/cid"))))

#?(:clj
   (defn read-log
     "Read the log back as a vector of transaction maps (uses the shared kanjo-edn reader).
     Each non-blank, non-comment line is ONE top-level tx map. Skips blank + ;-comment lines."
     [log-path]
     (let [f (clojure.java.io/file (str log-path))]
       (if-not (.exists f)
         []
         (->> (str/split-lines (slurp f))
              (map str/trim)
              (remove (fn [l] (or (str/blank? l) (str/starts-with? l ";"))))
              (keep (fn [l] (let [form (kanjo-edn/read-all l)]
                              (when (map? form) form))))
              vec)))))

#?(:clj
   (defn head-cid
     "The CID of the last tx in the log (\"\" if empty)."
     [log-path]
     (let [txs (read-log log-path)]
       (if (seq txs) (get (last txs) ":tx/cid") ""))))

#?(:clj
   (defn verify-chain
     "Recompute every CID from its datoms + prev; verify the DAG is intact. {ok length broken-at}."
     [log-path]
     (let [txs (read-log log-path)]
       (loop [i 0, prev "", ts txs]
         (if (empty? ts)
           {"ok" true "length" (count txs) "broken_at" -1}
           (let [tx (first ts)
                 expect (tx-cid (get tx ":tx/datoms" []) prev)]
             (if (or (not= (get tx ":tx/cid") expect) (not= (get tx ":tx/prev") prev))
               {"ok" false "length" (count txs) "broken_at" i}
               (recur (inc i) (get tx ":tx/cid") (rest ts)))))))))
