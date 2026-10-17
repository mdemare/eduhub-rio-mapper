(ns nl.surf.eduhub-rio-mapper.rio.duration-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.rio.duration :as duration])
  (:import [java.time.format DateTimeParseException]))

(def cases
  [[nil nil]
   ["PT2H45M" {:eenheid "U" :omvang 2}]
   ["PT30M" {:eenheid "U" :omvang 0}]
   ["PT3600.5S" {:eenheid "U" :omvang 1}]
   ["P3D" {:eenheid "D" :omvang 3}]
   ["P2W" {:eenheid "D" :omvang 14}]
   ["P2Y" {:eenheid "J" :omvang 2}]
   ["P2Y3M" {:eenheid "M" :omvang 27}]
   ["P2M3D" {:eenheid "M" :omvang 2}]
   ["P2Y3D" {:eenheid "J" :omvang 2}]
   ["P12M" {:eenheid "M" :omvang 12}]
   ["P0D" {:eenheid "D" :omvang 0}]
   ["PT0S" {:eenheid "U" :omvang 0}]])

(deftest duration-conversion
  (doseq [[value expected] cases]
    (testing (pr-str value)
      (is (= expected (duration/parse-duration value)))))
  (doseq [value ["" "invalid" "PTbad" "P1.5Y"]]
    (is (thrown? DateTimeParseException (duration/parse-duration value)))))
