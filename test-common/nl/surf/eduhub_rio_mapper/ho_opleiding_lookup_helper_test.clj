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

(ns nl.surf.eduhub-rio-mapper.ho-opleiding-lookup-helper-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.ho-opleiding-lookup-helper :as lookup]
            [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
            [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml]
            [nl.surf.eduhub-rio-mapper.ho-opleiding-lookup-vcr-helper :as vcr])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest record-requires-explicit-live-settings
  (try
    (lookup/lookup-config true {})
    (is false "Missing live settings must fail before HTTP")
    (catch clojure.lang.ExceptionInfo e
      (is (= #{"KEYSTORE" "KEYSTORE_PASSWORD" "KEYSTORE_ALIAS"
               "RIO_READ_URL" "RIO_RECIPIENT_OIN" "CLIENTS_INFO_PATH"}
             (set (:missing (ex-data e))))))))

(deftest outgoing-soap-semantics
  (let [config (lookup/lookup-config false {"KEYSTORE" "nonexistent"})
        captured (atom nil)
        getter (loader/make-getter (:rio-config config))]
    ;; Capture the real getter's signed request, without fabricating a RIO response.
    (binding [http-utils/*vcr* (fn [_ request]
                                (reset! captured request)
                                (throw (ex-info "captured" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"captured"
                           (getter {::rio/type "opleidingseenheid"
                                    ::rio/opleidingscode lookup/rio-code
                                    :institution-oin (:institution-oin config)
                                    :response-type :dom}))))
    (is (nil? (lookup/assert-request! @captured config)))
    (doseq [[before after] [["1001O3338" "1001O3339"]
                            [(:institution-oin config) "00000000000000000000"]
                            ["opvragen_opleidingseenheid_request" "opvragen_rioIdentificatiecode_request"]]]
      (testing (str "Reject altered SOAP body: " before)
        (is (thrown? AssertionError
                     (lookup/assert-request!
                      (update @captured :body str/replace before after) config)))))
    (doseq [change [{:method :get}
                    {:url "https://example.invalid/ooapi"}
                    {:headers {"SOAPAction" "aanleveren_opleidingseenheid"}}]]
      (is (thrown? AssertionError (lookup/assert-request! (merge @captured change) config))))))

(deftest direct-child-dates
  ;; Small parser unit input, never used as a VCR fixture or live-recording substitute.
  (let [entity (.getDocumentElement
                (xml/str->dom "<hoOpleiding><begindatum>2002-09-01</begindatum><hoOpleidingPeriode><begindatum>2009-09-01</begindatum><einddatum>2010-01-01</einddatum></hoOpleidingPeriode></hoOpleiding>"))]
    (is (= "2002-09-01" (lookup/text entity "begindatum")))
    (is (nil? (lookup/text entity "einddatum")))
    (is (= "2009-09-01" (lookup/text (first (lookup/children entity "hoOpleidingPeriode")) "begindatum")))))

(deftest missing-cassette-never-calls-transport
  (let [directory (.toFile (Files/createTempDirectory "ho-lookup-vcr" (make-array FileAttribute 0)))
        calls (atom 0)]
    (try
      (with-redefs [vcr/vcr-mode :playback]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No recorded request found"
                             (((vcr/make-vcr) (.getPath directory) 1 "chemie")
                              (fn [_] (swap! calls inc)) {})))
        (let [cassette-dir (io/file directory "1-chemie")]
          (.mkdir cassette-dir)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No recorded request found"
                               (((vcr/make-vcr) (.getPath directory) 1 "chemie")
                                (fn [_] (swap! calls inc)) {})))))
      (is (zero? @calls))
      (finally
        (.delete (io/file directory "1-chemie"))
        (.delete directory)))))
