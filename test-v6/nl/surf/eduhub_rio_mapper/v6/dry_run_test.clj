;; This file is part of eduhub-rio-mapper
;;
;; Copyright (C) 2022 SURFnet B.V.
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

(ns nl.surf.eduhub-rio-mapper.v6.dry-run-test
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
   [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
   [nl.surf.eduhub-rio-mapper.v6.commands.dry-run :as dry-run]
   [nl.surf.eduhub-rio-mapper.v6.commands.processing :as processing]
   [nl.surf.eduhub-rio-mapper.v6.test-helper :as helper]
   [nl.surf.eduhub-rio-mapper.vcr-helper :as vcr.helper]))

;;; TODO dryrun-test should allow recording, but that means inserting entities into RIO first.
;;; For the time being, we'll use playback-only.

(deftest ^:vcr dryrun-test
  (let [vcr    (vcr.helper/make-vcr)
        config (helper/make-test-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        handlers (processing/make-handlers config)
        dry-run! (:dry-run! handlers)]

    (testing "education-specifications"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/opleenh-dryrun" 1 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "afb435cc-5352-f55f-a548-41c9dfd60002"
                                      ::ooapi/type "programme"
                                      :rio-type :oe))]
          (is (some? result))
          (is (= {:begindatum {:diff false},
                  :eigenOpleidingseenheidSleutel {:diff false},
                  :omschrijving {:diff false},
                  :naamLang {:diff false},
                  :naamKort {:diff false},
                  :internationaleNaam {:diff false},
                  :status "found",
                  :opleidingseenheidcode "1010O8815"}
                 (:dry-run result))))))

    (testing "education-specifications"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/opleenh-dryrun" 2 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "9338214e-3978-484c-676d-427303a92748"
                                      ::ooapi/type "programme"
                                      :rio-type :oe))]
          (is (some? result))
          (is (= {:begindatum {:diff false},
                  :eigenOpleidingseenheidSleutel {:diff false},
                  :omschrijving {:diff false},
                  :naamLang {:diff false},
                  :naamKort {:diff false},
                  :internationaleNaam {:diff false},
                  :status "found",
                  :opleidingseenheidcode "1011O3434"}
                 (:dry-run result))))))

    (testing "education-specifications with timelineOverride"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/opleenh-dryrun" 3 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "cdecdecd-5352-f55f-a548-41c9dfd60002"
                                      ::ooapi/type "programme"
                                      :rio-type :oe))]
          (is (some? result))
          (is (= {:begindatum {:diff false},
                  :eigenOpleidingseenheidSleutel {:diff false},
                  :omschrijving {:diff false},
                  :naamLang {:diff false},
                  :naamKort {:diff false},
                  :internationaleNaam {:diff true,
                                       :current "OVERRIDE Bachelor Chemical technology",
                                       :proposed "OVERRIDE X Bachelor Chemical technology"},
                  :status "found",
                  :opleidingseenheidcode "1011O3469"}
                 (:dry-run result))))))

    (testing "course with timelineOverrides"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-dryrun" 5 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "3c358c84-dfc3-4a30-874e-0b70db15638b"
                                      ::ooapi/type "course"))]
          (is (= {:begindatum {:diff false}
                  :eigenNaamInternationaal {:diff true,
                                            :current "OVERRIDE EN TRANSLATION: Micro Biotechnologie",
                                            :proposed
                                            "OVERRIDE X EN TRANSLATION: Micro Biotechnologie"},
                  :eigenNaamAangebodenOpleiding {:diff true,
                                                 :current "OVERRIDE NL VERTALING: Micro Biotechnologie",
                                                 :proposed "OVERRIDE X NL VERTALING: Micro Biotechnologie"},
                  :cohorten {:diff false},
                  :eigenOmschrijving {:diff false},
                  :onderwijsaanbiedercode {:diff false},
                  :onderwijslocatiecode {:diff false},
                  :aangebodenOpleidingCode
                  "3c358c84-dfc3-4a30-874e-0b70db15638b",
                  :status "found"}
                 (:dry-run result))))))

    (testing "courses with a timeline override"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-dryrun" 5 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "3c358c84-dfc3-4a30-874e-0b70db15638b"
                                      ::ooapi/type "course"))]
          (is (= {:begindatum {:diff false},
                  :eigenNaamInternationaal {:diff true,
                                            :current "OVERRIDE EN TRANSLATION: Micro Biotechnologie",
                                            :proposed
                                            "OVERRIDE X EN TRANSLATION: Micro Biotechnologie"},
                  :eigenNaamAangebodenOpleiding {:diff true,
                                                 :current "OVERRIDE NL VERTALING: Micro Biotechnologie",
                                                 :proposed "OVERRIDE X NL VERTALING: Micro Biotechnologie"},
                  :cohorten {:diff false},
                  :eigenOmschrijving {:diff false},
                  :onderwijsaanbiedercode {:diff false},
                  :onderwijslocatiecode {:diff false},
                  :aangebodenOpleidingCode
                  "3c358c84-dfc3-4a30-874e-0b70db15638b",
                  :status "found"}
                 (:dry-run result))))))

    (testing "course not in ooapi"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-dryrun" 2 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "44444444-dfc3-4a30-874e-0b70db15638a"
                                      ::ooapi/type "course"))]
          (is (= {:dry-run {:status "error"}}
                 result)))))

    (testing "courses"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-dryrun" 3 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "4c358c84-dfc3-4a30-874e-0b70db15638b"
                                      ::ooapi/type "course"))]
          (is (= {:begindatum {:diff false},
                  :eigenNaamInternationaal {:diff false},
                  :eigenNaamAangebodenOpleiding {:diff false},
                  :cohorten
                  {:diff true,
                   :current [],
                   :proposed
                   [{:cohortcode "aeb74fae-0dbe-9611-addd-32be49f47d81",
                     :beginAanmeldperiode "2018-09-05",
                     :eindeAanmeldperiode "2019-01-30"}
                    {:cohortcode "ea7d7413-f342-9007-2832-69d2d58932a6",
                     :beginAanmeldperiode "2019-09-05",
                     :eindeAanmeldperiode "2020-08-30"}]},
                  :eigenOmschrijving {:diff false},
                  :onderwijsaanbiedercode {:diff false},
                  :onderwijslocatiecode {:diff false},
                  :aangebodenOpleidingCode
                  "4c358c84-dfc3-4a30-874e-0b70db15638b",
                  :status "found"}
                 (:dry-run result))))))

    (testing "course not in RIO"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-dryrun" 4 "finder")]
        (let [result (dry-run! (assoc client-info
                                      ::ooapi/id "4c358c84-dfc3-4a30-874e-0b70db15638b"
                                      ::ooapi/type "course"))]
          (is (= {:begindatum
                  {:diff true, :current nil, :proposed "2017-01-01"},
                  :eigenNaamInternationaal
                  {:diff true,
                   :current nil,
                   :proposed "EN TRANSLATION: Micro Biotechnologie"},
                  :eigenNaamAangebodenOpleiding
                  {:diff true,
                   :current nil,
                   :proposed "NL VERTALING: Micro Biotechnologie"},
                  :cohorten
                  {:diff true,
                   :current nil,
                   :proposed
                   [{:cohortcode "aeb74fae-0dbe-9611-addd-32be49f47d81",
                     :beginAanmeldperiode "2018-09-05",
                     :eindeAanmeldperiode "2019-01-30"}
                    {:cohortcode "ea7d7413-f342-9007-2832-69d2d58932a6",
                     :beginAanmeldperiode "2019-09-05",
                     :eindeAanmeldperiode "2020-08-30"}]},
                  :eigenOmschrijving
                  {:diff true,
                   :current nil,
                   :proposed
                   "NL VERTALING: . ridge, lasso, elastic net) and Bayesian statistics. In many practical aspects of physiology in to planning and design approaches (amongst which are discussed in this industry, it’s important to evaluate alternative processes.Working group sessions:Design and evaluation of sustainability are approached both in scope and scale, quantization of energies, driving forces, change and rural development processes."},
                  :onderwijsaanbiedercode
                  {:diff true, :current nil, :proposed "110A133"},
                  :onderwijslocatiecode
                  {:diff true, :current nil, :proposed "107X215"},
                  :aangebodenOpleidingCode nil,
                  :status "not-found"}
                 (:dry-run result))))))))

(deftest generate-diff-ooapi-rio-test
  (testing "normal case"
    (let [prgspec-id    "fddec347-8ca1-c991-8d39-9a85d09c0001"
          rio-summary   {:begindatum                    "1950-09-20",
                         :naamLang                      "NL VERTALING: Toetsdeskundige",
                         :naamKort                      "1T",
                         :internationaleNaam            "EN VERTALING: Toetsdeskundige",
                         :omschrijving                  "NL VERTALING: There is a 12 credits course which offers student the opportunity to experience in the domain will be paid to the depletion of fossil resources to biomass resources for energy, raw materials and tree form. The course addresses the question if and how this relates to material struggles over natural resources and its relationship to economic and technological domains;- solving optimization problems is climate change. Within a theoretical stance to support making conscious study and career choices.",
                         :eigenOpleidingseenheidSleutel prgspec-id}
          prgspec       (-> "fixtures/ooapi/education-specification-diff.json"
                            io/resource
                            slurp
                            (json/read-str :key-fn keyword))
          ooapi-summary (dry-run/summarize-prgspec prgspec)
          diff          (dry-run/generate-diff-ooapi-rio {:rio-summary rio-summary :ooapi-summary ooapi-summary})]
      (is (= {:begindatum                    {:diff true, :current "1950-09-20", :proposed "2019-08-24"},
              :eigenOpleidingseenheidSleutel {:diff false},
              :status                        "found",
              :omschrijving                  {:diff false},
              :naamLang                      {:diff true, :current "NL VERTALING: Toetsdeskundige", :proposed "Bachelor Chemische technologie"},
              :naamKort                      {:diff true, :current "1T", :proposed "B Scheikundige Technologie"},
              :internationaleNaam            {:diff true, :current "EN VERTALING: Toetsdeskundige", :proposed "Bachelor Chemical technology"}}
             (merge diff {:status "found"})))))
  (testing "no rio object"
    (let [rio-summary   nil
          prgspec       (-> "fixtures/ooapi/education-specification-diff.json"
                            io/resource
                            slurp
                            (json/read-str :key-fn keyword))
          ooapi-summary (dry-run/summarize-prgspec prgspec)
          diff          (dry-run/generate-diff-ooapi-rio {:rio-summary rio-summary :ooapi-summary ooapi-summary})]
      (is (= {:begindatum                    {:diff true, :current nil, :proposed "2019-08-24"},
              :eigenOpleidingseenheidSleutel {:diff true, :current nil, :proposed "fddec347-8ca1-c991-8d39-9a85d09c0001"},
              :omschrijving                  {:diff true, :current nil, :proposed "NL VERTALING: There is a 12 credits course which offers student the opportunity to experience in the domain will be paid to the depletion of fossil resources to biomass resources for energy, raw materials and tree form. The course addresses the question if and how this relates to material struggles over natural resources and its relationship to economic and technological domains;- solving optimization problems is climate change. Within a theoretical stance to support making conscious study and career choices."},
              :naamLang                      {:diff true, :current nil, :proposed "Bachelor Chemische technologie"},
              :naamKort                      {:diff true, :current nil, :proposed "B Scheikundige Technologie"},
              :internationaleNaam            {:diff true, :current nil, :proposed "Bachelor Chemical technology"}}
             diff)))))
