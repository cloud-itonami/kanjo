(ns kanjo.methods.atproto
  "kanjō 勘定 — atproto cell. Composes the actor's AT Protocol surface from the
  disclosed-fact graph: a PROFILE (app.bsky.actor.profile), structured DISCLOSURE
  records (com.etzhayyim.kanjo.disclosure), and human-readable SOCIAL POSTS
  (app.bsky.feed.post) ready to publish to the actor's PDS (pds.aozora.app,
  did:web:etzhayyim.github.io:com-etzhayyim-kanjo / at://kanjo.etzhayyim.com).

  CONSTITUTIONAL BY CONSTRUCTION:
   - G1 primary-disclosure only: a post can only carry a fact already in the graph,
     and every fact's :source is an enum of EDINET/EDGAR/Companies House/EU OAM.
   - G2 non-adjudicating / G4 no advice: `assert-clean` REJECTS any composed text
     containing rating/valuation/forecast/buy-sell language (JP + EN). A post is a
     transparency statement of a disclosed number, never a verdict or recommendation.
   - G5 sourcing honesty: every record carries :sourcing verbatim from the fact.
   - G8 provenance: every record carries the source filing's IPFS doc-cid.

  Convention parity (analyze.cljc / kanjo-edn): graph rows are maps with STRING
  `\":fin.…/…\"` keys; keyword values stay `\":foo\"` strings. The pure builders are
  deterministic — `createdAt` is passed IN (no clock in the pure path), and the
  record key is a CONTENT hash of the record, so the same fact always yields the
  same rkey (resume-safe, like autorun). File I/O sits at the JVM edge."
  (:require [kotoba.lang.text :as str]
            [kanjo.methods.kanjo-edn :as kanjo-edn]
            #?(:clj [clojure.java.io :as io])))

(def actor-did  "did:web:etzhayyim.github.io:com-etzhayyim-kanjo")
(def actor-handle "kanjo.etzhayyim.com")

;; Company display names — fallback only; kabuto's :company graph (org.corp.*) is SSoT.
(def company-name
  {"org.corp.jp.toyota"    "Toyota Motor"
   "org.corp.jp.sony"      "Sony Group"
   "org.corp.jp.nintendo"  "Nintendo"
   "org.corp.us.apple"     "Apple"
   "org.corp.us.microsoft" "Microsoft"})

(defn name-of [company]
  (or (company-name company)
      (-> (or company "") (str/split #"\.") last (str/replace "-" " ") str/capitalize)))

(def ccy-sym {":jpy" "¥" ":usd" "$" ":eur" "€" ":gbp" "£"
              ":krw" "₩" ":cny" "元" ":sek" "kr" ":twd" "NT$"})

;; ── G2/G4 guard — a composed string may not carry advice/valuation/forecast ──

;; English advice/valuation/forecast terms — matched on WORD BOUNDARIES so a
;; disclosed concept like "ope-rating income" never trips "rating" (substring
;; matching is wrong for English; "operating" must not hit "rating").
(def ^:private forbidden-en
  ["buy" "sell" "hold" "overweight" "underweight" "outperform" "underperform"
   "price target" "undervalued" "overvalued" "cheap" "expensive" "bullish" "bearish"
   "recommend" "rating" "ratings" "upgrade" "downgrade" "forecast" "guidance"])
;; Japanese terms have no word boundaries → substring is the correct test here.
(def ^:private forbidden-ja
  ["投資判断" "買い推奨" "売り推奨" "推奨" "割安" "割高" "目標株価" "格付" "業績予想" "見通し"])

(def ^:private forbidden-en-re
  (re-pattern (str "(?i)\\b(?:" (str/join "|" (map #(str/replace % " " "\\s+") forbidden-en)) ")\\b")))

(defn assert-clean
  "Throw if `text` carries advice / valuation / forecast language (G2/G4). Returns
  the text unchanged when clean. The single guard every machine-composed outward
  string passes. English = word-boundary regex; Japanese = substring."
  [text]
  (let [t (or text "")
        hit (or (some #(when (str/includes? t %) %) forbidden-ja)
                (when-let [m (re-find forbidden-en-re t)] m))]
    (when hit
      (throw (ex-info (str "G2/G4 violation: outward text carries forbidden term " (pr-str hit))
                      {:term hit :text text}))))
  text)

;; ── deterministic content-addressed record key (FNV-1a → hex) ────────────────

(defn- fnv1a [^String s]
  #?(:clj (let [bs (.getBytes s "UTF-8")]
            (loop [h (unchecked-long 0xcbf29ce484222325) i 0]
              (if (< i (alength bs))
                (recur (unchecked-multiply (bit-xor h (bit-and (aget bs i) 0xff))
                                           (unchecked-long 0x100000001b3))
                       (inc i))
                (bit-and h 0x7fffffffffffffff))))
     :cljs (loop [h 0x811c9dc5 i 0]
             (if (< i (count s))
               (recur (-> (bit-xor h (.charCodeAt s i)) (* 0x01000193) (bit-and 0xffffffff)) (inc i))
               (unsigned-bit-shift-right h 0)))))

(defn rkey [seed] (str "kanjo" (format "%015x" (fnv1a seed))))

;; ── minimal JSON writer (pure; record maps use clean string keys) ────────────

(defn- num->str [n]
  (if (and (number? n) (not (integer? n)))
    (-> (format "%.6f" (double n)) (str/replace #"0+$" "") (str/replace #"\.$" ""))
    (str n)))

(defn ->json
  "Serialize the subset used by atproto records: maps (string keys), vectors,
  strings, integers, doubles, booleans, nil. Stable key order = insertion order."
  [v]
  (cond
    (nil? v)      "null"
    (string? v)   (str \" (-> v (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")
                              (str/replace "\n" "\\n")) \")
    (boolean? v)  (if v "true" "false")
    (number? v)   (num->str v)
    (map? v)      (str "{" (str/join "," (for [[k val] v] (str (->json (name k)) ":" (->json val)))) "}")
    (sequential? v) (str "[" (str/join "," (map ->json v)) "]")
    :else (->json (str v))))

;; ── record builders (pure; createdAt passed in) ──────────────────────────────

(defn profile-record
  "app.bsky.actor.profile — the actor's public face. Non-adjudicating mission text."
  [now]
  {"$type" "app.bsky.actor.profile"
   "displayName" "kanjō 勘定"
   ;; NOTE: the mission statement is a FIXED, human-reviewed constant that NEGATES the
   ;; forbidden terms ("no ratings…"); it is deliberately NOT run through `assert-clean`,
   ;; whose job is to police per-company MACHINE-composed claims (post-text), not a
   ;; constant describing what kanjō refuses to do.
   "description" (str "World public-company financial-disclosure (決算) knowledge graph. "
                      "I register the numbers a listed company disclosed in its primary filing "
                      "(EDINET 有報 / SEC EDGAR 10-K / Companies House / EU OAM), normalized so "
                      "JP-GAAP·US-GAAP·IFRS land on one set of canonical concepts. "
                      "A transparency map — not an analyst. No ratings, no valuations, no advice.")
   "createdAt" now})

(defn disclosure-record
  "com.etzhayyim.kanjo.disclosure — one disclosed fact, joined to its filing for
  provenance. `fact` and `filing` are string-keyed graph rows."
  [fact filing now]
  (let [company (get fact ":fin.fact/company")]
    {"$type" "com.etzhayyim.kanjo.disclosure"
     "company"     company
     "companyName" (name-of company)
     "fiscalYear"  (long (or (get filing ":fin.filing/fiscal-year") 0))
     "statement"   (get fact ":fin.fact/statement")
     "concept"     (get fact ":fin.fact/concept")
     "conceptRaw"  (get fact ":fin.fact/concept-raw")
     "value"       (num->str (get fact ":fin.fact/value"))
     "unit"        (get fact ":fin.fact/unit")
     "scale"       (get fact ":fin.fact/scale")
     "context"     (get fact ":fin.fact/context")
     "accounting"  (get filing ":fin.filing/accounting")
     "source"      (get filing ":fin.filing/source")
     "form"        (get filing ":fin.filing/form")
     "periodEnd"   (get fact ":fin.fact/period-end")
     "docCid"      (or (not-empty (get filing ":fin.filing/doc-cid")) nil)
     "sourcing"    (get fact ":fin.fact/sourcing")
     "createdAt"   now}))

(defn fmt-value [fact]
  (let [sym (ccy-sym (get fact ":fin.fact/unit") "")
        scale (case (get fact ":fin.fact/scale") ":millions" "M" ":thousands" "K" "")
        v (get fact ":fin.fact/value")
        n (long (Math/round (double (or v 0))))]
    (str sym (->> (str n) reverse (partition-all 3) (map #(apply str (reverse %))) reverse (str/join ",")) scale)))

(defn post-text
  "Human-readable, G2/G4-clean transparency line for one fact."
  [fact filing]
  (let [company (name-of (get fact ":fin.fact/company"))
        fy (get filing ":fin.filing/fiscal-year")
        concept (str/replace (str/replace-first (get fact ":fin.fact/concept") #"^:" "") "-" " ")
        std (str/upper (str/replace-first (str (get filing ":fin.filing/accounting")) #"^:" ""))
        src (str/upper (str/replace-first (str (get filing ":fin.filing/source")) #"^:" ""))
        ctx (str/replace-first (str (get fact ":fin.fact/context")) #"^:" "")]
    (assert-clean
     (str company " disclosed FY" fy " " concept " " (fmt-value fact)
          " (" std ", " ctx ") — source: " src ". #決算 #disclosure"))))

(defn feed-post
  "app.bsky.feed.post — the social post. Text is G2/G4-guarded; the structured
  fact travels in an embedded disclosure record reference."
  [fact filing disclosure-uri now]
  (cond-> {"$type" "app.bsky.feed.post"
           "text"  (post-text fact filing)
           "langs" ["en" "ja"]
           "createdAt" now}
    disclosure-uri (assoc "embed"
                          {"$type" "app.bsky.embed.record"
                           "record" {"uri" disclosure-uri}})))

;; ── compose the full surface from a graph ────────────────────────────────────

(defn- post-worthy?
  "Only headline P&L / balance-sheet lines make social posts (avoid flooding)."
  [fact]
  (contains? #{":revenue" ":net-income" ":operating-income" ":total-assets"}
             (get fact ":fin.fact/concept")))

(defn compose
  "Build the whole atproto surface from filings+facts. Returns
  {:profile … :disclosures [{:rkey :record}] :posts [{:rkey :record}]}. Pure."
  [filings facts now]
  (let [by-id (into {} (map (juxt #(get % ":fin.filing/id") identity) filings))
        disclosures (for [f facts
                          :let [filing (get by-id (get f ":fin.fact/filing"))]
                          :when filing]
                      (let [rec (disclosure-record f filing now)
                            rk (rkey (str (get f ":fin.fact/id")))]
                        {:rkey rk :fact f :filing filing :record rec}))
        dz (vec disclosures)
        posts (for [{:keys [fact filing rkey]} dz
                    :when (post-worthy? fact)]
                (let [uri (str "at://" actor-did "/com.etzhayyim.kanjo.disclosure/" rkey)
                      rec (feed-post fact filing uri now)]
                  {:rkey (str "post-" rkey) :record rec}))]
    {:profile (profile-record now)
     :disclosures (mapv #(select-keys % [:rkey :record]) dz)
     :posts (vec posts)}))

;; ── main (file I/O at the edge) ──────────────────────────────────────────────

#?(:clj
   (def ^:private here (-> (io/file *file*) .getParentFile .getParentFile .getParentFile .getParentFile .getAbsolutePath)))

#?(:clj
   (defn -main [& args]
     (let [src (or (first args) (str here "/data/seed-financial-facts.kotoba.edn"))
           now (str (java.time.Instant/now))
           rows (kanjo-edn/read-file src)
           filings (filter #(get % ":fin.filing/id") rows)
           facts   (filter #(get % ":fin.fact/id") rows)
           {:keys [profile disclosures posts]} (compose filings facts now)
           outdir (io/file here "out" "atproto")]
       (.mkdirs outdir)
       (spit (io/file outdir "profile.json") (str (->json profile) "\n"))
       (spit (io/file outdir "disclosures.jsonl")
             (str/join "\n" (map #(->json (assoc (:record %) "rkey" (:rkey %))) disclosures)))
       (spit (io/file outdir "posts.jsonl")
             (str/join "\n" (map #(->json (assoc (:record %) "rkey" (:rkey %))) posts)))
       (spit (io/file outdir "publish-manifest.json")
             (->json {"$type" "com.etzhayyim.kanjo.publishManifest"
                      "actor" actor-did "handle" actor-handle
                      "pds" "https://pds.aozora.app"
                      "profile" "profile.json"
                      "disclosures" (count disclosures)
                      "posts" (count posts)
                      "createdAt" now}))
       (println (str "kanjō atproto: profile + " (count disclosures) " disclosure records + "
                     (count posts) " social posts → out/atproto/"))
       (doseq [p (take 3 posts)] (println "  · " (get (:record p) "text"))))))
