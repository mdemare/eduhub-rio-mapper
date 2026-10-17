(ns nl.surf.eduhub-rio-mapper.v5.rio.duration-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.rio.duration :as duration]
            [nl.surf.eduhub-rio-mapper.rio.duration-test :refer [cases]]
            [nl.surf.eduhub-rio-mapper.v5.rio.aangeboden-opleiding :as aangeboden])
  (:import [java.time.format DateTimeParseException]))

(defn duration-xml [value ooapi-type]
  (->> (aangeboden/->aangeboden-opleiding
        {:consumers [{:consumerKey "rio" :duration value}]} ooapi-type "1234O1234" "course")
       (filter #(and (vector? %) (= :duo:afwijkendeOpleidingsduur (first %))))
       first))

(deftest duration-conversion
  (doseq [[value expected] cases]
    (testing (pr-str value)
      (doseq [ooapi-type [:course :program]]
        (is (= (when expected
                 [:duo:afwijkendeOpleidingsduur
                  [:duo:opleidingsduurEenheid (:eenheid expected)]
                  [:duo:opleidingsduurOmvang (:omvang expected)]])
               (duration-xml value ooapi-type))))))
  (doseq [value ["" "invalid" "PTbad" "P1.5Y"]]
    (is (thrown? DateTimeParseException (duration-xml value :course)))))

(deftest adapter-uses-shared-duration-conversion
  (let [calls (atom [])]
    (with-redefs [duration/parse-duration (fn [value]
                                          (swap! calls conj value)
                                          {:eenheid "U" :omvang 7})]
      (is (= [:duo:afwijkendeOpleidingsduur
              [:duo:opleidingsduurEenheid "U"]
              [:duo:opleidingsduurOmvang 7]]
             (duration-xml "PT2H45M" :course)))
      (is (= ["PT2H45M"] @calls)))))
