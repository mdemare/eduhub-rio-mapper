(ns nl.surf.eduhub-rio-mapper.test-suites-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(deftest fast-suites-defer-only-recorded-interactions
  (let [config (edn/read-string {:readers {'kaocha/v1 identity}} (slurp "tests.edn"))
        suites (into {} (map (juxt :id identity)) (:tests config))]
    (doseq [[full-id fast-id vcr-id] [[:v5 :v5-fast :v5-vcr]
                                      [:v6 :v6-fast :v6-vcr]]]
      (testing (name fast-id)
        (let [full (suites full-id)
              fast (suites fast-id)]
          (is (= (:test-paths full) (:test-paths fast)))
          (is (= (conj (set (:kaocha.filter/skip-meta full)) :vcr)
                 (set (:kaocha.filter/skip-meta fast))))
          (is (= [:vcr] (:kaocha.filter/focus-meta (suites vcr-id))))
          (is (= (:test-paths full) (:test-paths (suites vcr-id)))))))))
