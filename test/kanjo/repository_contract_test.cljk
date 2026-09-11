(ns kanjo.repository-contract-test
  (:require [clojure.edn :as edn] [clojure.test :refer [deftest is]]))
(deftest canonical-edn
  (doseq [p ["manifest.edn" "schema.edn" "actor.edn" "identity.edn"
             "dependencies.edn" "repository-contracts.edn" "migration.edn"]]
    (is (some? (edn/read-string (slurp p))) p)))
(deftest boundary
  (let [c (edn/read-string (slurp "repository-contracts.edn"))]
    (is (= :edn (:canonical-data c)))
    (is (= "wire" (get-in c [:external-formats :root])))))
