#!/usr/bin/env bb
;; Working Clojure port of methods/kanjo_edn.py (the EDN reader collapses to clojure.edn).
(ns kanjo.methods.kanjo-edn
  "kanjō 勘定 — EDN reader (ADR-2606032000). The Python source hand-rolls a small EDN parser;
  Clojure reads EDN natively. read-file returns the first top-level vector (the dataset)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn read-all
  "Read every top-level form from `text`; return the first vector found (the dataset)."
  [text]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. text))
        eof (Object.)]
    (loop [forms []]
      (let [v (edn/read {:eof eof} rdr)]
        (if (= v eof)
          (or (first (filter vector? forms)) (first forms) [])
          (recur (conj forms v)))))))

(defn read-file [path] (read-all (slurp (io/file path))))
