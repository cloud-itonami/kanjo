(ns kanjo.methods.kanjo-edn
  "kanjō 勘定 — minimal EDN-subset reader. Clojure port of methods/kanjo_edn.py (ADR-2606032000).

  Parses the subset of EDN the kanjō seed / output files use: a top-level vector
  of maps whose values are strings, doubles, longs, keywords, nil/true/false, and
  nested vectors. No tagged literals, no sets, no symbols.

  Convention parity (root CLAUDE.md / bond.cljc): every keyword in the source —
  whether a map KEY or a VALUE — is kept as its `\":foo\"` STRING, exactly like the
  Python reader returns (keyword keys as `\":foo\"` strings, keyword values as
  `\":foo\"` strings). So a map reads back as a Clojure map with STRING keys, and
  `analyze`/`ingest` index it with literal `\":fin.fact/…\"` strings — byte-for-byte
  the Python shape. Maps→map, vectors→vector, nil/true/false as-is.

  I/O is at the edges (clojure.java.io); the parser itself is pure."
  (:require [clojure.string :as str]
            #?(:clj [clojure.java.io :as io])))

;; ── reader state is a tiny mutable cursor over the string (volatile index) ──

(defn- mk [s] {:s s :n (count s) :i (volatile! 0)})

(defn- peek-ch [{:keys [s n i]}]
  (let [k @i] (if (< k n) (.charAt ^String s k) nil)))

(def ^:private eof ::eof)

(declare rd)

(defn- skip-ws [{:keys [s n i] :as r}]
  (loop []
    (when (< @i n)
      (let [c (.charAt ^String s @i)]
        (cond
          (= c \;) (do (loop [] (when (and (< @i n) (not= (.charAt ^String s @i) \newline))
                                  (vswap! i inc) (recur)))
                       (recur))
          (or (= c \space) (= c \tab) (= c \return) (= c \newline) (= c \,))
          (do (vswap! i inc) (recur))
          :else nil)))))

(defn- rd-vec [{:keys [n i] :as r} close]
  (vswap! i inc) ;; consume [
  (loop [out []]
    (skip-ws r)
    (if (= (peek-ch r) close)
      (do (vswap! i inc) out)
      (let [v (rd r)]
        (when (= v eof) (throw (ex-info "unterminated vector" {})))
        (recur (conj out v))))))

(defn- rd-map [{:keys [n i] :as r}]
  (vswap! i inc) ;; consume {
  (loop [out {}]
    (skip-ws r)
    (if (= (peek-ch r) \})
      (do (vswap! i inc) out)
      (let [k (rd r)
            v (rd r)]
        (recur (assoc out k v))))))

(defn- rd-str [{:keys [s n i] :as r}]
  (vswap! i inc) ;; consume opening quote
  (let [sb (StringBuilder.)]
    (loop []
      (if (< @i n)
        (let [c (.charAt ^String s @i)]
          (cond
            (= c \\) (let [nxt (if (< (inc @i) n) (.charAt ^String s (inc @i)) \space)]
                       (vswap! i inc)
                       (.append sb (case nxt \n \newline \t \tab \r \return nxt))
                       (vswap! i inc)
                       (recur))
            (= c \") (do (vswap! i inc) (.toString sb))
            :else (do (.append sb c) (vswap! i inc) (recur))))
        (throw (ex-info "unterminated string" {}))))))

(def ^:private delim #{\space \tab \return \newline \, \[ \] \{ \} \"})

(defn- rd-kw [{:keys [s n i] :as r}]
  (let [j @i]
    (vswap! i inc)
    (loop []
      (when (and (< @i n) (not (delim (.charAt ^String s @i))))
        (vswap! i inc) (recur)))
    (subs s j @i)))   ;; keep leading ':'

(defn- rd-atom [{:keys [s n i] :as r}]
  (let [j @i]
    (loop []
      (when (and (< @i n) (not (delim (.charAt ^String s @i))))
        (vswap! i inc) (recur)))
    (let [tok (subs s j @i)]
      (cond
        (= tok "nil") nil
        (= tok "true") true
        (= tok "false") false
        :else
        (let [floaty? (and (some #(some #{%} ".eE") tok)
                           (re-matches #"[-+]?[0-9]*\.?[0-9]+([eE][-+]?[0-9]+)?" tok))]
          (cond
            floaty? #?(:clj (Double/parseDouble tok) :cljs (js/parseFloat tok))
            (re-matches #"[-+]?[0-9]+" tok) #?(:clj (Long/parseLong tok) :cljs (js/parseInt tok 10))
            :else tok))))))   ;; bare symbol — kept as string

(defn- rd [{:keys [i] :as r}]
  (skip-ws r)
  (if (nil? (peek-ch r))
    eof
    (let [c (peek-ch r)]
      (cond
        (= c \[) (rd-vec r \])
        (= c \{) (rd-map r)
        (= c \") (rd-str r)
        (= c \:) (rd-kw r)
        :else (rd-atom r)))))

(defn read-all
  "Read every top-level form; return the first vector found (the dataset)."
  [text]
  (let [r (mk text)
        forms (loop [acc []]
                (let [v (rd r)]
                  (if (= v eof) acc (recur (conj acc v)))))]
    (or (some #(when (vector? %) %) forms)
        (first forms)
        [])))

#?(:clj
   (defn read-file [path]
     (read-all (slurp (io/file path)))))
