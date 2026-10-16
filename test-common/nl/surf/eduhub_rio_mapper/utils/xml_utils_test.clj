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

(ns nl.surf.eduhub-rio-mapper.utils.xml-utils-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import [java.io File]
           [javax.xml.parsers DocumentBuilder DocumentBuilderFactory]
           [org.xml.sax EntityResolver SAXException]))

(deftest namespace-aware-parsing-test
  (let [doc (xml-utils/str->dom
             "<?xml version='1.0'?><s:Envelope xmlns:s='urn:soap'><s:Body><result xmlns='urn:rio' code='ok'>A &amp; B &#233;</result></s:Body></s:Envelope>")
        root (.getDocumentElement doc)
        result (xml-utils/get-in-dom root "urn:rio" ["result"])]
    (is (= "urn:soap" (.getNamespaceURI root)))
    (is (= "Envelope" (.getLocalName root)))
    (is (= "ok" (.getAttribute result "code")))
    (is (= "A & B é" (.getTextContent result)))))

(deftest existing-soap-rio-fixtures-test
  ;; Explicit paths exercise both versions, even in the common-only suite.
  (doseq [version ["v5" "v6"]
          file (file-seq (io/file (str "test-" version "/fixtures/rio")))
          :when (.endsWith (.getName file) ".xml")]
    (testing (str file)
      (let [root (.getDocumentElement (xml-utils/str->dom (slurp file)))]
        (is (= "Envelope" (.getLocalName root)))
        (is (= "http://schemas.xmlsoap.org/soap/envelope/" (.getNamespaceURI root)))
        (is (some? (xml-utils/get-in-dom root
                                       "http://schemas.xmlsoap.org/soap/envelope/"
                                       ["Body"])))))))

(deftest doctype-rejected-before-resolution-test
  (let [target (File/createTempFile "rio-xml-entity-" ".txt")
        make-builder @#'xml-utils/document-builder
        resolutions (atom [])]
    (try
      (spit target "<!ENTITY harmless 'OFFLINE_MARKER'>")
      ;; Observe resolution attempts on the actual configured parser, including
      ;; file URIs. The spy throws before any filesystem or network access.
      (with-redefs [xml-utils/document-builder
                    (fn []
                      (doto ^DocumentBuilder (make-builder)
                        (.setEntityResolver
                         (reify EntityResolver
                           (resolveEntity [_ public-id system-id]
                             (swap! resolutions conj [public-id system-id])
                             (throw (SAXException. "Unexpected resolution")))))))]
        (doseq [uri [(str (.toURI target)) "http://127.0.0.1:9/forbidden.dtd"]
                [description xml]
                [["external general entity"
                  (str "<!DOCTYPE root [<!ENTITY external SYSTEM '" uri "'>]><root>&external;</root>")]
                 ["external DTD"
                  (str "<!DOCTYPE root SYSTEM '" uri "'><root/>")]
                 ["external parameter entity"
                  (str "<!DOCTYPE root [<!ENTITY % external SYSTEM '" uri "'>%external;]><root/>")]]]
          (testing (str description " " uri)
            (reset! resolutions [])
            (is (thrown-with-msg? SAXException #"DOCTYPE" (xml-utils/str->dom xml)))
            (is (empty? @resolutions))))
        (doseq [xml ["<!DOCTYPE root [<!ENTITY internal 'marker'>]><root>&internal;</root>"
                     "<!DOCTYPE root><root/>"]]
          (testing "even self-contained DOCTYPEs are forbidden"
            (is (thrown-with-msg? SAXException #"DOCTYPE" (xml-utils/str->dom xml)))
            (is (empty? @resolutions)))))
      (finally
        (io/delete-file target)))))

(deftest rejecting-resolver-test
  ;; Exercise the resolver independently of the factory's DOCTYPE prohibition.
  (let [target (File/createTempFile "rio-xml-resolver-" ".txt")]
    (try
      (spit target "OFFLINE_MARKER")
      (with-redefs [xml-utils/db-factory #(DocumentBuilderFactory/newInstance)]
        (is (thrown-with-msg?
             SAXException #"External XML entities are forbidden"
             (xml-utils/str->dom
              (str "<!DOCTYPE root [<!ENTITY external SYSTEM '" (.toURI target)
                   "'>]><root>&external;</root>")))))
      (finally
        (io/delete-file target)))))
