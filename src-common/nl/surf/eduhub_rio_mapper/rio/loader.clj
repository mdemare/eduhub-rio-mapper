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

(ns nl.surf.eduhub-rio-mapper.rio.loader
  "Gets the RIO opleidingscode given an OOAPI entity ID."
  (:require
   [clojure.data.json :as json]
   [clojure.data.xml :as clj-xml]
   [clojure.spec.alpha :as s]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
   [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
   [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
   [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
   [nl.surf.eduhub-rio-mapper.utils.rio-utils :as rio-utils]
   [nl.surf.eduhub-rio-mapper.utils.soap :as soap]
   [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
   [nl.surf.eduhub-rio-mapper.utils.xml-validator :as xml-validator])
  (:import (org.w3c.dom Element NodeList)))

(def aangeboden-opleiding-type "aangebodenOpleiding")
(def aangeboden-opleidingen-van-organisatie-type "aangebodenOpleidingenVanOrganisatie")
(def opleidingseenheid-type "opleidingseenheid")
(def opleidingseenheden-van-organisatie-type "opleidingseenhedenVanOrganisatie")
(def opleidingsrelaties-bij-opleidingseenheid-type "opleidingsrelatiesBijOpleidingseenheid")

(def opleidingseenheid-namen
  #{:hoOpleiding :particuliereOpleiding :hoOnderwijseenhedencluster :hoOnderwijseenheid})

(def aangeboden-opleiding-namen
  #{:aangebodenHOOpleidingsonderdeel :aangebodenHOOpleiding :aangebodenParticuliereOpleiding})

;; NOTE: aangeboden opleidingen are referenced by OOAPI UID
(def aangeboden-opleiding-types #{aangeboden-opleiding-type
                                  aangeboden-opleidingen-van-organisatie-type})

(def valid-get-types (into aangeboden-opleiding-types
                           #{opleidingseenheid-type
                             opleidingseenheden-van-organisatie-type
                             opleidingsrelaties-bij-opleidingseenheid-type}))

(def schema "http://duo.nl/schema/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def contract "http://duo.nl/contract/DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4")
(def validator  (xml-validator/create-validation-fn "DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.xsd"))

;; De externe identificatie komt niet voor in RIO
;; Handled separately because this is an expected outcome, and handling it is part of the normal program flow.
(def missing-entity "A01161")

(defn- handle-resolver-success [element]
  {:post [(string? %)]}
  ;; TODO: this is ugly, but we don't know at this stage what entity we tried to resolve.
  (let [code (or (xml-utils/single-xml-unwrapper element "ns2:opleidingseenheidcode")
                 (xml-utils/single-xml-unwrapper element "ns2:aangebodenOpleidingCode"))]
    (rio-utils/log-rio-action-response (str "SUCCESSFUL RESOLVE:" code) element)
    code))

(defn- handle-resolver-error [element]
  {:post [(nil? %)]}
  (let [foutmelding (xml-utils/get-in-dom element ["ns2:foutmelding"])
        id          (some-> foutmelding
                            (xml-utils/get-in-dom ["ns2:sleutelgegeven" "ns2:sleutelwaarde"])
                            (.getFirstChild)
                            (.getTextContent))
        foutcode    (xml-utils/single-xml-unwrapper foutmelding "ns2:foutcode")
        error-msg   (if (= missing-entity foutcode)
                      (str "Object with id (" id ") not found in RIO via resolve")
                      (str "Resolve of object " id " failed with error code " foutcode))]
    (rio-utils/log-rio-action-response error-msg element)
    (when-not (= missing-entity foutcode)
      (throw (ex-info error-msg {:retryable? false})))))

(defn- rio-relation-getter-response [^Element element]
  (when-not (rio-utils/goedgekeurd? element)
    (throw (ex-info "Cannot reconcile relations: RIO rejected the relation read"
                    {:error :relation-read-failed :retryable? true})))
  (when (rio-utils/goedgekeurd? element)
    (when-let [samenhang (xml-utils/get-in-dom element ["ns2:samenhangOpleidingseenheid"])]
      (let [code (xml-utils/single-xml-unwrapper samenhang "ns2:opleidingseenheidcode")
            ^NodeList related-opl-eenheden (.getElementsByTagName samenhang "ns2:gerelateerdeOpleidingseenheid")]
        (s/assert ::rio/opleidingscode code)
        (when (pos? (.getLength related-opl-eenheden))
          (->> (range (.getLength related-opl-eenheden))
               (map #(.item related-opl-eenheden %))
               ;; Accredited HoOpleidingen have a AFGELEID_VAN relation which is not relevant for the edumapper
               ;; and should be ignored.
               (remove #(= (xml-utils/single-xml-unwrapper % "ns2:opleidingsrelatiesoort") "AFGELEID_VAN"))
               (mapv (fn [related]
                       (let [related-code (xml-utils/single-xml-unwrapper related "ns2:opleidingseenheidcode")]
                         (s/assert ::rio/opleidingscode related-code)
                         {:valid-from             (xml-utils/single-xml-unwrapper related "ns2:opleidingsrelatieBegindatum")
                          :valid-to               (xml-utils/single-xml-unwrapper related "ns2:opleidingsrelatieEinddatum")
                          :opleidingseenheidcodes #{code related-code}})))))))))

(defn make-datamap
  [sender-oin recipient-oin]
  {:schema        schema
   :contract      contract
   :validator     validator
   :sender-oin    sender-oin
   :recipient-oin recipient-oin
   :to-url        (str "https://duo.nl/RIO/services/raadplegen4.0?oin=" recipient-oin)
   :from-url      (str "http://www.w3.org/2005/08/addressing/anonymous?oin=" sender-oin)})

(defn guard-getter-response
  [{:keys [body]} type tag]
  {:pre [tag body]}
  (when-not (re-find (re-pattern (str "<" tag "[^>]*>")) body)
    (throw (ex-info (str "Unexpected response, it does not contain tag: " tag)
                    {:type type, :body body})))
  body)

(defn- extract-body-element [response tag]
  (-> response
      xml-utils/str->dom
      .getDocumentElement
      (xml-utils/get-in-dom ["SOAP-ENV:Body" tag])))

(defn make-resolver
  "Return a RIO resolver.

  The resolver takes an `id` and an `institution-oin` and returns a
  map with errors, or the corresponding RIO opleidingscode."
  [{:keys [read-url credentials recipient-oin connection-timeout-millis]}]
  {:pre [read-url]}
  (fn resolver
    [rio-type id institution-oin]
    {:pre [institution-oin
           rio-type
           (some? id)
           (string? id)
           (#{:oe :ao} rio-type)]}
    (logging/with-mdc
      {:soap-action "opvragen_rioIdentificatiecode" :ooapi-id id}
      (let [xml (soap/prepare-soap-call "opvragen_rioIdentificatiecode"
                                        [[(case rio-type
                                            :oe :duo:eigenOpleidingseenheidSleutel
                                            :ao :duo:eigenAangebodenOpleidingSleutel)
                                          id]]
                                        (make-datamap institution-oin recipient-oin)
                                        credentials)
            request {:url read-url
                     :method :post
                     :body xml
                     :headers {"SOAPAction" (str contract "/opvragen_rioIdentificatiecode")}
                     :connection-timeout connection-timeout-millis
                     :content-type :xml}
            tag "ns2:opvragen_rioIdentificatiecode_response"
            element (-> (http-utils/send-http-request (merge credentials request))
                        (guard-getter-response "rioIdentificatiecode" tag)
                        (extract-body-element tag))]
        (if (rio-utils/goedgekeurd? element)
          (let [code (handle-resolver-success element)]
            code)
          (handle-resolver-error element))))))

(defn- valid-onderwijsbestuurcode? [code]
  {:pre [code]}
  (re-matches #"\d\d\dB\d\d\d" code))

(defn rio-entity-request [rio-type rio-code institution-oin response-type]
  (let [[code-name rio-code rio-type-name] (if (= rio-type :oe)
                                             [::rio/opleidingscode rio-code opleidingseenheid-type]
                                             [::rio/aangeboden-opleiding-code rio-code aangeboden-opleiding-type])]
    {::rio/type       rio-type-name
     code-name        rio-code
     :institution-oin institution-oin
     :response-type   response-type}))

(defn opleidingeenheid-exists?
  "Return whether the getter response contains an opleidingseenheid."
  [rio-code getter institution-oin]
  {:pre [rio-code]}
  (getter (rio-entity-request :oe rio-code institution-oin :exists?)))

(defn aangeboden-opleiding-exists?
  "Return whether the getter response contains an aangeboden opleiding."
  [rio-code getter institution-oin]
  {:pre [rio-code]}
  (getter (rio-entity-request :ao rio-code institution-oin :exists?)))

;; Only used in the "test-rio" CLI command
(defn find-eigen-opleidingseenheid-sleutel
  "Fetch an opleidingseenheid and return its eigenOpleidingseenheidSleutel value, or nil if absent."
  [rio-code getter institution-oin]
  {:pre [rio-code]}
  (let [^Element element (getter (rio-entity-request :oe rio-code institution-oin :dom))
        ^NodeList kenmerken (.getElementsByTagName element "ns2:kenmerken")
        kenmerk (->> (range (.getLength kenmerken))
                     (map #(.item kenmerken %))
                     (filter #(= "eigenOpleidingseenheidSleutel"
                                 (xml-utils/single-xml-unwrapper % "ns2:kenmerknaam")))
                     first)]
    (xml-utils/single-xml-unwrapper kenmerk "ns2:kenmerkwaardeTekst")))

(defn- rio-xml-getter-response [^Element element]
  (assert (rio-utils/goedgekeurd? element))
  (-> element xml-utils/dom->str))

(defn- rio-json-getter-response [^Element element]
  (assert (rio-utils/goedgekeurd? element))
  (-> element xml-utils/dom->str clj-xml/parse-str xml-utils/xml-event-tree->edn json/write-str))

(defn- generate-rio-sexp-request [{::ooapi/keys [id]
                                   ::rio/keys   [type opleidingscode aangeboden-opleiding-code code]
                                   :keys        [pagina]
                                   :or          {pagina 0}}]
  (condp = type
    ;; Command line only.
    opleidingseenheden-van-organisatie-type
    [[:duo:onderwijsbestuurcode code]
     [:duo:pagina pagina]]

    ;; Command line only.
    aangeboden-opleidingen-van-organisatie-type
    [[:duo:onderwijsaanbiedercode id]
     [:duo:pagina pagina]]

    opleidingsrelaties-bij-opleidingseenheid-type
    [[:duo:opleidingseenheidcode opleidingscode]]

    aangeboden-opleiding-type
    [[:duo:aangebodenOpleidingCode aangeboden-opleiding-code]]

    opleidingseenheid-type
    [[:duo:opleidingseenheidcode opleidingscode]]))

(defn- handle-response-for-type [response-type rio-entity-type resp-obj]
  (cond
    (= :literal response-type)
    resp-obj

    ;; Return the parsed response even when RIO rejects the request (e.g. not found).
    (= :raw-dom response-type)
    resp-obj

    (= :exists? response-type)
    (boolean
     (some #(xml-utils/get-in-dom resp-obj [(str "ns2:" (name %))])
           (case rio-entity-type
             "opleidingseenheid" opleidingseenheid-namen
             "aangebodenOpleiding" aangeboden-opleiding-namen)))

    (= :dom response-type)
    (do
      (when-not (rio-utils/goedgekeurd? resp-obj)
        (throw (ex-info "Request not goedgekeurd" {:resp-obj resp-obj})))
      resp-obj)

    ;; CLI only
    (= :json response-type)
    (rio-json-getter-response resp-obj)

    ;; CLI only
    (= :xml response-type)
    (rio-xml-getter-response resp-obj)

    ;; If response-type is unspecified, use edn for relations and json for everything else
    (= rio-entity-type opleidingsrelaties-bij-opleidingseenheid-type)
    (rio-relation-getter-response resp-obj)

    ;; CLI only
    :else
    (rio-json-getter-response resp-obj)))

;; response-type can be optionally specified. Valid values: :literal, :dom, :raw-dom, :exists?, :xml, :json
;; :dom requires requestGoedgekeurd; :raw-dom leaves that check to the caller.
(defn- rio-get [{::ooapi/keys [id]
                 ::rio/keys   [type opleidingscode aangeboden-opleiding-code code]
                 :keys        [response-type]
                 :as          rio-get-data}
                request-template
                soap-call-fn]
  ;; preconditions
  {:pre [(or (aangeboden-opleiding-types type)
             opleidingscode
             code
             (throw (ex-info "first precondition" rio-get-data)))
         (or (not= type aangeboden-opleidingen-van-organisatie-type)
             id)
         ;; if the type is aangebodenOpleiding, there must be an aangeboden-opleiding-code
         (or (not= type aangeboden-opleiding-type)
             aangeboden-opleiding-code
             (throw (ex-info "third precondition - aangeboden-opleiding-code required for type aangebodenOpleiding" rio-get-data)))]}

  ;; assertions
  (when-not (valid-get-types type)
    (throw (ex-info (str "Unexpected type: " type)
                    {:id             id
                     :opleidingscode opleidingscode
                     :retryable?     false})))

  (when (and (= type opleidingseenheden-van-organisatie-type)
             (not (valid-onderwijsbestuurcode? code)))
    (throw (ex-info (str "Type 'onderwijsbestuurcode' has ID invalid format: " code)
                    {:type       type
                     :retryable? false})))

  ;; function body
  (logging/with-mdc {:soap-action (str "opvragen_" type)}
    (let [tag              (str "ns2:opvragen_" type "_response")
          response-body    (-> request-template
                               (assoc
                                :method       :post
                                :body         (soap-call-fn (generate-rio-sexp-request rio-get-data))
                                :content-type :xml)
                               http-utils/send-http-request
                               (guard-getter-response type tag))
          body-element     (extract-body-element response-body tag)
          resp-obj         (if (= :literal response-type)
                             response-body
                             body-element)]
      (rio-utils/log-rio-action-response type body-element)
      (handle-response-for-type response-type type resp-obj))))

(defn make-getter
  "Return a function that looks up an 'aangeboden opleiding' by id.

  The getter takes an program or course id and returns a map of
  data with the RIO attributes, or errors."
  [{:keys [read-url credentials recipient-oin]}]
  {:pre [read-url]}
  (fn [{::rio/keys [type]
        :keys      [institution-oin]
        :as        rio-get-data}]
    (let [soap-action          (str "opvragen_" type)
          request-template     (assoc credentials
                                      :url read-url
                                      :headers {"SOAPAction" (str contract "/" soap-action)})
          prepare-soap-call-fn (fn [rio-sexp]
                                 (soap/prepare-soap-call soap-action
                                                         rio-sexp
                                                         (make-datamap institution-oin recipient-oin)
                                                         credentials))]
      (rio-get rio-get-data request-template prepare-soap-call-fn))))
