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

(ns nl.surf.eduhub-rio-mapper.v6.link-test
  (:require
   [clojure.test :refer :all]
   [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
   [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
   [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
   [nl.surf.eduhub-rio-mapper.v6.commands.processing :as processing]
   [nl.surf.eduhub-rio-mapper.v6.test-helper :as helper]
   [nl.surf.eduhub-rio-mapper.vcr-helper :as vcr.helper])
  (:import [clojure.lang ExceptionInfo]))

;;; TODO link-test should allow recording, but that means inserting two specifications into RIO first.
;;; For the time being, we'll use playback-only.

(deftest ^:vcr link-test
  (let [vcr    (vcr.helper/make-vcr)
        config (helper/make-test-config)
        client-info (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        rio-config (:rio-config config)
        handlers (processing/make-handlers {:rio-config rio-config
                                            :gateway-root-url (:gateway-root-url config)
                                            :gateway-credentials (:gateway-credentials config)})
        link! (:link! handlers)]

    (testing "education-specifications"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/opleenh-link" 1 "linker")]
        (let [result (link! (assoc client-info
                                   ::ooapi/id "11111112-dfc3-4a30-874e-000000000001"
                                   ::ooapi/type "programme"
                                   :rio-type :oe
                                   ::rio/opleidingscode "1010O6466"))]
          (is (= [:duo:opleidingseenheidcode "1010O6466"]
                 (get-in result [:rio-sexp 0 1])))
          (is (= {:eigenOpleidingseenheidSleutel {:diff true, :old-id "11111111-dfc3-4a30-874e-000000000001", :new-id "11111112-dfc3-4a30-874e-000000000001"}}
                 (:link result))))))

    (testing "education-specifications without opleidingseenheidsleutel"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/opleenh-link" 2 "linker")]
        (let [{:keys [link]}
              (link! (assoc client-info
                            ::ooapi/id "11111112-dfc3-4a30-874e-000000000001"
                            ::ooapi/type "programme"
                            :rio-type :oe
                            ::rio/opleidingscode "1010O6466"))]
          (is (= {:eigenOpleidingseenheidSleutel
                  {:diff true,
                   :old-id nil,
                   :new-id "11111112-dfc3-4a30-874e-000000000001"}}
                 link)))))

    (testing "unlink education-specifications without opleidingseenheidsleutel"
        ;; failing, needs step 4, opvragen
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/opleenh-link" 3 "linker")]
        (let [{:keys [link rio-sexp]}
              (link! (assoc client-info
                            ::ooapi/type "programme"
                            :rio-type :oe
                            ::rio/opleidingscode "1011O3504"))]
          (is (empty? (filter #(and (sequential? %) (= :duo:kenmerken (first %)))
                              (first rio-sexp))))
          (is (= {:eigenOpleidingseenheidSleutel
                  {:diff true,
                   :old-id "b1091a8c-5352-f55f-a548-41c9dfd60001",
                   :new-id nil}}
                 link)))))

    (testing "courses"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-link" 1 "linker")]
        (let [result (link! (assoc client-info
                                   ::ooapi/id "11111111-dfc3-4a30-874e-000000000001"
                                   ::ooapi/type "course"
                                   :rio-type :ao
                                   ::rio/aangeboden-opleiding-code "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672"))]
          (is (= {:link {:eigenAangebodenOpleidingSleutel {:diff true, :old-id nil, :new-id "11111111-dfc3-4a30-874e-000000000001"}}}
                 (select-keys result [:link]))))))

    (testing "programme"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-link" 2 "linker")]
        (let [result (link! (assoc client-info
                                   ::ooapi/id "11111111-dfc3-4a30-874e-000000000002"
                                   ::ooapi/type "programme"
                                   :rio-type :ao
                                   ::rio/aangeboden-opleiding-code "ab7431c0-f985-4742-aa68-42060570b17e"))]
          (is (= {:link {:eigenAangebodenOpleidingSleutel {:diff true, :old-id nil, :new-id "11111111-dfc3-4a30-874e-000000000002"}}}
                 (select-keys result [:link]))))))

    (testing "missing programme"
      (binding [http-utils/*vcr* (vcr "test-v6/fixtures/aangebodenopl-link" 3 "linker")]
        (let [request (assoc client-info
                             ::ooapi/id "11111111-dfc3-4a30-874e-000000000002"
                             ::ooapi/type "programme"
                             :rio-type :ao
                             ::rio/aangeboden-opleiding-code "00000000-d8e8-4868-b451-157180ab0001")]
          (is (thrown-with-msg? ExceptionInfo #"404 Not Found" (link! request))))))))
