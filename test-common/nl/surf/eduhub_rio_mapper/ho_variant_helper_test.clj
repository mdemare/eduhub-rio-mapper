;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2026 SURFnet B.V.
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU Affero General Public License
;; as published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful, but
;; WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
;; Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public
;; License along with this program.  If not, see
;; <https://www.gnu.org/licenses/>.

(ns nl.surf.eduhub-rio-mapper.ho-variant-helper-test
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.ho-variant-helper :as variant]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote]
            [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml]
            [nl.surf.eduhub-rio-mapper.vcr-helper :as vcr]))

(def parent-code "1001O3338")
(def parent-key "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
(def child-key "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
(def offered-key "cccccccc-cccc-4ccc-8ccc-cccccccccccc")

(defn dom [body]
  (.getDocumentElement
   (xml/str->dom (str "<ns2:response xmlns:ns2=\"" loader/schema "\">"
                      "<ns2:requestGoedgekeurd>true</ns2:requestGoedgekeurd>" body "</ns2:response>"))))

(defn characteristic [name value]
  (str "<ns2:kenmerken><ns2:kenmerknaam>" name "</ns2:kenmerknaam>"
       "<ns2:kenmerkwaardeTekst>" value "</ns2:kenmerkwaardeTekst></ns2:kenmerken>"))

(defn parent-dom [key]
  (dom (str "<ns2:hoOpleiding><ns2:opleidingseenheidcode>" parent-code "</ns2:opleidingseenheidcode>"
            "<ns2:begindatum>2002-09-01</ns2:begindatum>"
            (characteristic "soort" "OPLEIDING")
            (when key (characteristic "eigenOpleidingseenheidSleutel" key)) "</ns2:hoOpleiding>")))

(def managed {:opleidingseenheidcodes #{parent-code "1010O6466"}
              :valid-from "2016-12-16" :valid-to nil})

(defn relations-dom [managed? accredited?]
  (dom (str "<ns2:samenhangOpleidingseenheid><ns2:opleidingseenheidcode>" parent-code "</ns2:opleidingseenheidcode>"
            (apply str (for [[type code] (concat (when managed? [["VARIANT_VAN" "1010O6466"]])
                                                (when accredited? [["AFGELEID_VAN" "1000O0001"]]))]
                         (str "<ns2:gerelateerdeOpleidingseenheid><ns2:opleidingseenheidcode>" code "</ns2:opleidingseenheidcode>"
                              "<ns2:opleidingsrelatiesoort>" type "</ns2:opleidingsrelatiesoort>"
                              "<ns2:opleidingsrelatieBegindatum>2016-12-16</ns2:opleidingsrelatieBegindatum>"
                              "</ns2:gerelateerdeOpleidingseenheid>")))
            "</ns2:samenhangOpleidingseenheid>")))

(defn preparation-system [initial-key]
  (let [key (atom initial-key) managed? (atom true) calls (atom [])
        boundary (fn [call result]
                   (http-utils/*vcr* (fn [_] (swap! calls conj call) result) {}))]
    {:key key :managed? managed? :calls calls
     :handlers {:rio-config {}
                :getter (fn [request]
                          (let [relations? (= "opleidingsrelatiesBijOpleidingseenheid" (::rio/type request))
                                response (if relations? (relations-dom @managed? true) (parent-dom @key))]
                            (boundary [:get request]
                                      (if (= :dom (:response-type request)) response
                                          (#'loader/handle-response-for-type nil (::rio/type request) response)))))
                :resolver (fn [type id oin] (boundary [:resolve type id oin] (when (= @key id) parent-code)))
                :link! (fn [request]
                         (reset! key (::ooapi/id request))
                         (boundary [:link request] {:success true}))}}))

(defn mutation [_ institution relation] {:institution institution :relation relation})

(deftest prepare-parent-branches-and-boundary
  (doseq [initial-key [parent-key nil]]
    (testing (if initial-key "preserves existing key" "links only when key is absent")
      (let [{:keys [handlers calls key managed?]} (preparation-system initial-key)
            deleted (atom [])]
        (with-redefs [mutator/mutate! (fn [request _]
                                       (swap! deleted conj (:relation request))
                                       (reset! managed? false)
                                       {:goedgekeurd true})]
          (binding [http-utils/*vcr* (fn [& _] (throw (ex-info "Preflight must not use recorder" {})))]
            (let [parent (variant/prepare-parent! handlers "oin" parent-code mutation :attempts 2 :delay-ms 0)]
              (is (= {:code parent-code :key @key :valid-from "2002-09-01" :valid-to nil} parent))
              (is (= [managed] @deleted) "AFGELEID_VAN is parsed by the real getter and preserved")
              (is (= (if initial-key 0 1) (count (filter #(= :link (first %)) @calls))))
              (when initial-key (is (= initial-key @key))))))))))

(deftest preparation-fails-before-scenario
  (let [{:keys [handlers calls]} (preparation-system parent-key)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HO_VARIANT_PARENT_CODE"
                          (variant/prepare-parent! handlers "oin" nil mutation)))
    (is (empty? @calls))
    (testing "wrong resolution is bounded and does not delete relations"
      (with-redefs [mutator/mutate! (fn [& _] (throw (Exception. "must not mutate")))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not resolve"
                              (variant/prepare-parent! (assoc handlers :resolver (constantly "wrong"))
                                                       "oin" parent-code mutation :attempts 2 :delay-ms 0)))))
    (testing "non-accredited or wrong entity is rejected before linking"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be an accredited"
                            (variant/prepare-parent! (assoc handlers :getter (constantly (dom "")))
                                                     "oin" parent-code mutation))))
    (testing "existing non-UUID identity is preserved by refusing this parent"
      (let [{:keys [handlers]} (preparation-system "legacy-key")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"existing key must be"
                              (variant/prepare-parent! handlers "oin" parent-code mutation)))))))

(deftest relation-removal-timeout
  (let [{:keys [handlers]} (preparation-system parent-key)
        mutations (atom 0)]
    (with-redefs [mutator/mutate! (fn [& _] (swap! mutations inc) {:goedgekeurd true})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mapper relations remain"
                            (variant/prepare-parent! handlers "oin" parent-code mutation :attempts 2 :delay-ms 0)))
      (is (= 1 @mutations)))))

(deftest playback-skips-all-live-setup
  (with-redefs [vcr/vcr-mode :playback
                remote/remote-entities-fixture (fn [& _] (throw (Exception. "no upload")))
                variant/prepare-parent! (fn [& _] (throw (Exception. "no preflight")))]
    (let [ran (atom false)]
      (variant/fixture "v5" "parent" #(throw (Exception. "no live config")) mutation
                       #(do (reset! ran true)
                            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Network forbidden" (http/request {})))))
      (is @ran))))

(deftest failed-preflight-never-uploads-or-starts-scenario
  (with-redefs [vcr/vcr-mode :record
                remote/remote-entities-fixture (fn [& _] (throw (Exception. "no upload")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"HO_VARIANT_PARENT_CODE"
                          (variant/fixture "v5" "parent" (constantly {:handlers {}}) mutation
                                           #(throw (Exception. "no scenario")))))))

(deftest fixture-identities-are-resolved-before-save-and-render
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "oe216" (make-array java.nio.file.attribute.FileAttribute 0)))
        parent-file (io/file dir "programmes/parent.json")
        child-file (io/file dir "programmes/child.json")
        mapping-file (io/file dir "mapping.edn")]
    (try
      (io/make-parents parent-file)
      (spit parent-file "{\"programmeId\":\"{{programmes/parent}}\"}")
      (spit child-file "{\"consumer\":{\"variantOf\":\"{{programmes/parent}}\"}}")
      (with-redefs [remote/OOAPI-VERSION "v6"]
        (with-redefs-fn {#'remote/entities-dir (constantly dir)
                        #'remote/input-files (fn [& _] [parent-file child-file])}
          #(binding [remote/*identity-overrides* {"programmes/parent" parent-key}
                     remote/*mapping-file* (str mapping-file)]
             (let [session (remote/make-session)
                   objects (remote/remote-objects session)
                   parent (first (filter (fn [o] (= (str "programmes/" parent-key) (:path o))) objects))
                   child (first (filter (fn [o] (= (str "programmes/" (get session "programmes/child")) (:path o))) objects))]
               (is (= parent-key (get session "programmes/parent")))
               (is (= parent-key (get (edn/read-string (slurp mapping-file)) "programmes/parent")))
               (is (= parent-key (get (json/read-str (:body parent)) "programmeId")))
               (is (some (fn [o] (= parent-key (get-in (json/read-str (:body o)) ["consumer" "variantOf"]))) objects))
               (is (some? child))))))
      (finally (doseq [f (reverse (file-seq dir))] (io/delete-file f))))))

(deftest both-version-fixtures-reference-variant
  (doseq [[version parent child offered id-field parent-ref offered-ref]
          [["v5" "education-specifications/ho-variant-parent"
            "education-specifications/ho-variant-child" "programs/ho-variant-offered"
            "educationSpecificationId" ["parent"] ["educationSpecification"]]
           ["v6" "programmes/specification-ho-variant-parent"
            "programmes/specification-ho-variant-child" "programmes/ho-variant-offered"
            "programmeId" ["consumer" "variantOf"] ["consumer" "specificationId"]]]]
    (with-redefs-fn {#'remote/entities-dir (constantly (io/file (str "test-" version "/fixtures/remote-entities")))}
      #(binding [remote/*identity-overrides* {parent parent-key}]
         (let [session (remote/make-session)
               objects (into {} (map (juxt :path (comp json/read-str :body))) (remote/remote-objects session))
               object (fn [name] (get objects (str (first (str/split name #"/")) "/" (get session name))))]
           (is (= parent-key (get (object parent) id-field)))
           (is (= parent-key (get-in (object child) parent-ref)))
           (is (= (str (get session child)) (get-in (object offered) offered-ref)))
           (is (= "variant" (if (= "v5" version)
                              (get (first (filter (fn [c] (= "rio" (get c "consumerKey")))
                                                  (get (object child) "consumers"))) "educationSpecificationSubType")
                              (get-in (object child) ["consumer" "specificationType"])))))))))

(deftest scenario-only-mutates-variant-and-offered
  (let [jobs (atom [])
        steps (atom [])
        parent {:code parent-code :key parent-key :valid-from "2002-09-01" :valid-to nil}
        handlers {:getter (fn [request]
                            (case (::rio/type request)
                              "opleidingseenheid" (dom (str "<ns2:hoOpleiding><ns2:opleidingseenheidcode>1010O6466</ns2:opleidingseenheidcode>" (characteristic "soort" "VARIANT") "</ns2:hoOpleiding>"))
                              "aangebodenOpleiding" (dom (str "<ns2:aangebodenHoOpleiding><ns2:aangebodenOpleidingCode>" offered-key "</ns2:aangebodenOpleidingCode><ns2:opleidingseenheidcode>1010O6466</ns2:opleidingseenheidcode></ns2:aangebodenHoOpleiding>"))
                              "opleidingsrelatiesBijOpleidingseenheid" [(assoc managed :valid-to "2025-04-25")]))
                  :resolver (fn [type id _]
                              (is (= (if (= type :oe) child-key offered-key) id))
                              (if (= type :oe) "1010O6466" offered-key))}]
    (with-redefs [variant/scenario-data (constantly {:parent parent :mapping {"parent" parent-key "child" child-key "offered" offered-key}})
                  loader/find-eigen-opleidingseenheid-sleutel (fn [& _] child-key)
                  vcr/make-vcr (fn [] (fn [_ idx _] (swap! steps conj idx) (fn [handler request] (handler request))))]
      (variant/run-scenario! "v5" handlers "oin"
                             (fn [type id action]
                               (swap! jobs conj [type id action])
                               {:goedgekeurd true :opleidingscode "1010O6466"})
                             "parent" "child" "offered" :eduspec :program)
      (is (= [[:eduspec child-key "upsert"] [:program offered-key "upsert"]
              [:program offered-key "delete"] [:eduspec child-key "delete"]] @jobs))
      (is (= [1 2 3 4 5 6] @steps)))))

(deftest bounded-wait-covers-delayed-visibility
  (let [calls (atom 0)]
    (is (true? (variant/await! "eventual state" #(when (= 3 (swap! calls inc)) true) 3 0)))
    (is (= 3 @calls))
    (reset! calls 0)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"still missing"
                          (variant/await! "still missing" #(do (swap! calls inc) false) 3 0)))
    (is (= 3 @calls))))

(deftest changed-accreditation-aborts-preparation
  (let [{:keys [handlers managed?]} (preparation-system parent-key)
        getter (:getter handlers)
        accreditation-reads (atom 0)]
    (with-redefs [mutator/mutate! (fn [& _] (reset! managed? false) {:goedgekeurd true})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"AFGELEID_VAN relations changed"
           (variant/prepare-parent!
            (assoc handlers :getter (fn [request]
                                      (if (and (= "opleidingsrelatiesBijOpleidingseenheid" (::rio/type request))
                                               (= :dom (:response-type request))
                                               (<= 2 (swap! accreditation-reads inc)))
                                        (relations-dom false false)
                                        (getter request))))
            "oin" parent-code mutation :attempts 2 :delay-ms 0))))))

(deftest failed-verification-still-deletes-created-variant
  (let [jobs (atom [])]
    (with-redefs [variant/scenario-data (constantly {:parent {:key parent-key}
                                                    :mapping {"parent" parent-key "child" child-key "offered" offered-key}})
                  vcr/make-vcr (fn [] (fn [& _] (fn [handler request] (handler request))))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"verification failed"
           (variant/run-scenario!
            "v6" {:resolver (constantly "1010O6466")
                  :getter (fn [& _] (throw (ex-info "verification failed" {})))}
            "oin" (fn [type id action]
                    (swap! jobs conj [type id action])
                    {:goedgekeurd true :opleidingscode "1010O6466"})
            "parent" "child" "offered" :programme :programme)))
      (is (= [[:programme child-key "upsert"] [:programme child-key "delete"]] @jobs)))))

(deftest record-fixture-injects-prepared-key-and-dates-before-upload
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory "oe216-fixture" (make-array java.nio.file.attribute.FileAttribute 0)))
        scenario-dir (io/file dir "ho-variant")
        legacy-file (io/file dir "mapping.edn")
        prepared {:key parent-key :code parent-code :valid-from "2002-09-01" :valid-to nil}
        order (atom [])]
    (try
      (.mkdirs scenario-dir)
      (spit (io/file scenario-dir "stale.edn") "old recording")
      (spit legacy-file "unrelated legacy identities")
      (with-redefs [vcr/vcr-mode :record
                    variant/root (constantly (str scenario-dir))
                    variant/prepare-parent! (fn [& _] (swap! order conj :prepare) prepared)
                    remote/remote-entities-fixture
                    (fn [test-fn]
                      (swap! order conj :upload)
                      (is (= {"programmes/parent" parent-key} remote/*identity-overrides*))
                      (is (= (str (io/file scenario-dir "mapping.edn")) remote/*mapping-file*))
                      (is (= prepared (edn/read-string (slurp (io/file scenario-dir "parent.edn")))))
                      (is (not (.exists (io/file scenario-dir "stale.edn"))))
                      (let [object (remote/*object-transform*
                                    {:path (str "programmes/" parent-key)
                                     :body "{\"validFrom\":\"1950-01-01\",\"validTo\":\"2060-01-01\"}"})]
                        (is (= {"validFrom" "2002-09-01"} (json/read-str (:body object)))))
                      (test-fn))]
        (variant/fixture "v6" "programmes/parent" (constantly {}) mutation
                         #(do (swap! order conj :scenario) (is (= prepared variant/*parent*)))))
      (is (= [:prepare :upload :scenario] @order))
      (is (= "unrelated legacy identities" (slurp legacy-file)))
      (finally (doseq [f (reverse (file-seq dir))] (io/delete-file f))))))
