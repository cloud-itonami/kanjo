;; mesh.clj — kanjō 勘定 KOTOBA Mesh entry component (Clojure / kotoba-clj).
;;
;; The mesh-hosting face of actor:kanjō (public-company financial-disclosure KG).
;; Compiled by kotoba-clj into a kotoba:kais WASM component, placed by the KOTOBA
;; Mesh lattice. Kotoba-native slice: observe filer→metric disclosure edges as
;; Datom assertions, derive the disclosure coverage via Datalog. The full
;; BS/PL/CF cross-GAAP normalization stays in the actor's existing methods.
;;
;; Posture: DISCLOSED facts from primary disclosure only (EDINET/EDGAR/CH/OAM);
;; non-adjudicating, NO advice / NO forecast; paid terminals prohibited.
;; host-imports: kqe-assert! / kqe-query → kotoba:kais/kqe (needs cap/kqe)
(ns kanjo)

(defn run [ctx]
  ;; observe — disclosed financial facts (filer discloses metric; primary source).
  (kqe-assert! "kanjo" "filer-a" "discloses" "balance-sheet")
  (kqe-assert! "kanjo" "filer-b" "discloses" "income-statement")
  (kqe-assert! "kanjo" "filer-c" "discloses" "cash-flow")
  ;; derive — disclosure coverage (a fact map, never advice/forecast) (Datalog).
  (kqe-query "disclosure(?m) :- discloses(?m)."))

(defn on-kse [topic payload]
  ;; KSE-topic trigger (observatory on-kse pattern, ADR-2606230001 §4).
  (kqe-query "disclosure(?m) :- discloses(?m)."))
