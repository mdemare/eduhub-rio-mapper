(ns nl.surf.eduhub-rio-mapper.check-suites-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(deftest fast-and-full-suite-coverage
  (let [config (edn/read-string {:readers {'kaocha/v1 identity}} (slurp "tests.edn"))
        suites (into {} (map (juxt :id identity) (:tests config)))]
    (is (= [:slow] (:kaocha.filter/skip-meta (suites :common))))
    (is (= [:slow] (:kaocha.filter/focus-meta (suites :common-slow))))
    (is (= (:test-paths (suites :common)) (:test-paths (suites :common-slow))))
    (doseq [version ["v5" "v6"]]
      (testing version
        (let [fast (suites (keyword version))
              slow-tags #{:redis :vcr :ho-opleiding-lookup (keyword (str version "-e2e"))}]
          (is (= slow-tags (set (:kaocha.filter/skip-meta fast))))
          ;; Every category excluded from the fast suite has a full-check suite.
          (doseq [[suffix tag] [["redis" :redis]
                                ["vcr" :vcr]
                                ["e2e" (keyword (str version "-e2e"))]]]
            (let [suite (suites (keyword (str version "-" suffix)))]
              (is (= (:test-paths fast) (:test-paths suite)))
              (is (= [tag] (:kaocha.filter/focus-meta suite)))))
          (is (= (:test-paths fast)
                 (:test-paths (suites (keyword (str "ho-opleiding-" version)))))))))))
