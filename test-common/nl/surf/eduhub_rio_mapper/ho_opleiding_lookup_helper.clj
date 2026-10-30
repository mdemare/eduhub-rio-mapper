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

(ns nl.surf.eduhub-rio-mapper.ho-opleiding-lookup-helper
  (:require
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.test :refer [is]]
   [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
   [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
   [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]
   [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
   [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore]
   [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml]
   [nl.surf.eduhub-rio-mapper.ho-opleiding-lookup-vcr-helper :as vcr])
  (:import [org.w3c.dom Element Node]))

(def rio-code "1001O3338")
(def read-url "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0")
(def recipient-oin "00000001800866472000")

(defn lookup-config
  "Only Raadplegen configuration: no gateway, authentication server or ObjectStore.
  Playback deliberately ignores the environment and uses the public test keystore."
  [record? environment]
  (let [required ["KEYSTORE" "KEYSTORE_PASSWORD" "KEYSTORE_ALIAS"
                  "RIO_READ_URL" "RIO_RECIPIENT_OIN" "CLIENTS_INFO_PATH"]
        _ (when record?
            (when-let [missing (seq (filter #(str/blank? (get environment %)) required))]
              (throw (ex-info "Missing live RIO lookup configuration" {:missing missing}))))
        settings (if record?
                   environment
                   {"KEYSTORE" "test/keystore.jks"
                    "KEYSTORE_PASSWORD" "xxxxxx"
                    "KEYSTORE_ALIAS" "test-surf"
                    "RIO_READ_URL" read-url
                    "RIO_RECIPIENT_OIN" recipient-oin
                    "CLIENTS_INFO_PATH" "test/test-clients.json"})
        client (clients-info/client-info
                (clients-info/read-clients-data {:path (get settings "CLIENTS_INFO_PATH")})
                "rio-mapper-dev.jomco.nl")]
    (when-not (:institution-oin client)
      (throw (ex-info "Missing test client institution OIN" {})))
    {:institution-oin (:institution-oin client)
     :rio-config {:read-url (get settings "RIO_READ_URL")
                  :recipient-oin (get settings "RIO_RECIPIENT_OIN")
                  :credentials (keystore/credentials (get settings "KEYSTORE")
                                                     (get settings "KEYSTORE_PASSWORD")
                                                     (get settings "KEYSTORE_ALIAS"))}}))

(defn children
  "Direct children, so entity dates cannot be confused with period dates."
  [^Node node tag]
  (let [nodes (.getChildNodes node)]
    (filterv #(and (= Node/ELEMENT_NODE (.getNodeType ^Node %))
                   (= tag (.getLocalName ^Node %)))
             (map #(.item nodes %) (range (.getLength nodes))))))

(defn text [node tag]
  (some-> ^Node (first (children node tag)) .getTextContent))

(defn assert-request!
  [request {:keys [institution-oin rio-config]}]
  (let [envelope (.getDocumentElement (xml/str->dom (:body request)))
        header (first (children envelope "Header"))
        body (first (children envelope "Body"))
        operation (first (children body "opvragen_opleidingseenheid_request"))]
    ;; Fail before sending any unexpected operation, including resolves or mutations.
    (assert (= :post (:method request)))
    (assert (= (:read-url rio-config) (:url request)))
    (assert (= (str loader/contract "/opvragen_opleidingseenheid")
               (get-in request [:headers "SOAPAction"])))
    (assert operation)
    (assert (= rio-code (text operation "opleidingseenheidcode")))
    (assert (= institution-oin (text operation "verzendendeInstantie")))
    (assert (= (:recipient-oin rio-config) (text operation "ontvangendeInstantie")))
    (assert (= (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" institution-oin)
               (text (first (children header "From")) "Address")))
    (assert (empty? (children operation "eigenOpleidingseenheidSleutel")))))

(defn assert-entity! [response]
  (let [entities (children response "hoOpleiding")]
    (is (= 1 (count entities)) "Returned entity is a hoOpleiding")
    (when-let [^Element entity (first entities)]
      (let [periods (children entity "hoOpleidingPeriode")
            by-start (into {} (map (juxt #(text % "begindatum") identity) periods))
            kenmerken (.getElementsByTagNameNS entity loader/schema "kenmerknaam")]
        (is (= rio-code (text entity "opleidingseenheidcode")))
        (is (= "2002-09-01" (text entity "begindatum")) "Entity start")
        (is (empty? (children entity "einddatum")))
        (is (= 2 (count periods)))
        (is (= #{"2002-09-01" "2009-09-01"} (set (keys by-start))) "Period starts")
        (doseq [period periods]
          (is (= "Chemie" (text period "naamLang"))))
        (when-let [later (get by-start "2009-09-01")]
          (is (= "Chemistry" (text later "internationaleNaam"))))
        (is (not-any? #(= "eigenOpleidingseenheidSleutel"
                          (.getTextContent ^Node (.item kenmerken %)))
                      (range (.getLength kenmerken)))
            "RIO code lookup needs no external key")))))

(defn run-lookup! [version]
  (let [record? (= :record vcr/vcr-mode)
        config (lookup-config record? (System/getenv))
        getter (loader/make-getter (:rio-config config))
        cassette ((vcr/make-vcr) (str "test-" version "/fixtures/ho-opleiding-lookup")
                  1 "chemie")
        calls (atom 0)
        transport http/request]
    (with-redefs [http/request (if record? transport
                                (fn [& _] (throw (ex-info "Network forbidden during playback" {}))))]
      (binding [http-utils/*vcr* (fn [handler request]
                                  (assert-request! request config)
                                  (assert (= 1 (swap! calls inc)) "Exactly one Raadplegen request")
                                  (cassette handler request))]
        (assert-entity!
         (getter {::rio/type "opleidingseenheid"
                  ::rio/opleidingscode rio-code
                  :institution-oin (:institution-oin config)
                  :response-type :dom}))))
    (is (= 1 @calls))))
