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

(ns nl.surf.eduhub-rio-mapper.ho-variant-helper
  "Record-only accredited parent preparation and the shared variant scenario."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [is]]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote]
            [nl.surf.eduhub-rio-mapper.rio.loader :as loader]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.vcr-helper :as vcr])
  (:import [java.util UUID]
           [org.w3c.dom Node]))

(def playback-environment
  "Fixed cassette endpoints and dummy credentials, independent of live settings."
  {:clients-info-path "test/test-clients.json"
   :keystore "test/keystore.jks" :keystore-password "xxxxxx" :keystore-alias "test-surf"
   :rio-read-url "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0"
   :rio-update-url "https://vt-webservice.duo.nl:6977/RIO/services/beheren4.0"
   :rio-recipient-oin "00000001800866472000"
   :gateway-root-url-v5 "https://gateway.test.surfeduhub.nl/"
   :gateway-root-url-v6 "https://gateway-v6-sandbox.dev.surfeduhub.nl/"
   :gateway-user-v5 "playback" :gateway-password-v5 "playback"
   :gateway-user-v6 "playback" :gateway-password-v6 "playback"
   :surf-conext-introspection-endpoint "https://playback.invalid/introspect"
   :surf-conext-client-id "playback" :surf-conext-client-secret "playback"
   :rio-retry-attempts-seconds "0,0,0,0"})

(defn children [^Node node tag]
  (when node
    (let [nodes (.getChildNodes node)]
      (filterv #(= tag (.getLocalName ^Node %))
               (map #(.item nodes %) (range (.getLength nodes)))))))

(defn text [node tag]
  (some-> ^Node (first (children node tag)) .getTextContent))

(defn- elements [^Node node tag]
  (let [nodes (.getElementsByTagNameNS ^org.w3c.dom.Element node loader/schema tag)]
    (mapv #(.item nodes %) (range (.getLength nodes)))))

(defn- characteristic [entity name]
  (some (fn [c]
          (when (= name (text c "kenmerknaam"))
            (or (text c "kenmerkwaardeTekst")
                (text c "kenmerkwaardeEnumeratiewaarde"))))
        (elements entity "kenmerken")))

(defn relations [getter institution-oin code]
  (getter {::rio/type "opleidingsrelatiesBijOpleidingseenheid"
           ::rio/opleidingscode code :institution-oin institution-oin}))

(defn- relation-snapshot [getter institution-oin code]
  ;; :dom rejects denied reads, so an error cannot be mistaken for no relations.
  (let [response (getter {::rio/type "opleidingsrelatiesBijOpleidingseenheid"
                          ::rio/opleidingscode code :institution-oin institution-oin
                          :response-type :dom})
        rows (elements response "gerelateerdeOpleidingseenheid")
        row (fn [node] (mapv (partial text node)
                             ["opleidingseenheidcode" "opleidingsrelatieBegindatum" "opleidingsrelatieEinddatum"]))
        {accredited true managed false} (group-by #(= "AFGELEID_VAN" (text % "opleidingsrelatiesoort")) rows)]
    {:accredited (set (map row accredited)) :managed (set (map row managed))}))

(defn await! [description predicate attempts delay-ms]
  (loop [remaining attempts]
    (if-let [result (predicate)]
      result
      (if (<= remaining 1)
        (throw (ex-info (str "HoOpleiding prerequisite unmet: " description) {}))
        (do (Thread/sleep delay-ms) (recur (dec remaining)))))))

(defn prepare-parent!
  "Prepare a configured accredited parent, without recording any preflight HTTP.
  A successful resolution under the test institution verifies key access."
  [{:keys [getter resolver link! rio-config]} institution-oin code relation-mutation
   & {:keys [attempts delay-ms] :or {attempts 20 delay-ms 1000}}]
  (when (str/blank? code)
    (throw (ex-info "HoOpleiding prerequisite unmet: set HO_VARIANT_PARENT_CODE to an accredited RIO test parent" {})))
  (binding [http-utils/*vcr* (fn [handler request] (handler request))]
    (let [response (getter (loader/rio-entity-request :oe code institution-oin :dom))
          parent (first (children response "hoOpleiding"))
          accredited (:accredited (relation-snapshot getter institution-oin code))]
      (when-not (and parent (= code (text parent "opleidingseenheidcode"))
                     (= "OPLEIDING" (characteristic parent "soort"))
                     (seq accredited) (text parent "begindatum"))
        (throw (ex-info "HoOpleiding prerequisite unmet: selected code must be an accredited, non-variant HoOpleiding accessible to the test institution"
                        {:parent-code code})))
      (let [existing (loader/find-eigen-opleidingseenheid-sleutel code getter institution-oin)
            key (or existing (str (UUID/randomUUID)))]
        ;; OOAPI identities must be UUIDs; fail instead of replacing an existing key.
        (when-not (and (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" key)
                       (= key (str/lower-case key)))
          (throw (ex-info "HoOpleiding prerequisite unmet: existing key must be a lowercase OOAPI UUID; select another parent"
                          {:parent-code code :key key})))
        (when-not existing
          (let [result (link! {::ooapi/type "education-specification" ::ooapi/id key
                              ::rio/opleidingscode code :institution-oin institution-oin})]
            (when-not (:success result)
              (throw (ex-info "HoOpleiding prerequisite unmet: linking the parent failed" {:result result})))))
        (await! "external key does not resolve to selected parent"
                #(and (= key (loader/find-eigen-opleidingseenheid-sleutel code getter institution-oin))
                      (= code (resolver :oe key institution-oin))) attempts delay-ms)
        ;; The production getter excludes AFGELEID_VAN, so only mapper relations are removed.
        (doseq [relation (relations getter institution-oin code)]
          (when-not (:goedgekeurd (mutator/mutate! (relation-mutation :delete institution-oin relation) rio-config))
            (throw (ex-info "HoOpleiding prerequisite unmet: deleting a mapper relation failed" {:relation relation}))))
        (await! "mapper relations remain on selected parent"
                #(empty? (:managed (relation-snapshot getter institution-oin code))) attempts delay-ms)
        (when-not (= accredited (:accredited (relation-snapshot getter institution-oin code)))
          (throw (ex-info "HoOpleiding prerequisite unmet: AFGELEID_VAN relations changed" {:parent-code code})))
        {:code code :key key :valid-from (text parent "begindatum")
         :valid-to (text parent "einddatum")}))))

(def ^:dynamic *parent* nil)

(defn root [version] (str "test-" version "/fixtures/vcr/ho-variant"))

(defn fixture
  "Playback never invokes live config, preflight or ObjectStore setup."
  [version parent-name make-live-context relation-mutation test-fn]
  (if (= :record vcr/vcr-mode)
    (let [{:keys [handlers institution-oin parent-code]} (make-live-context)
          parent (prepare-parent! handlers institution-oin parent-code relation-mutation)
          parent-path (str (first (str/split parent-name #"/")) "/" (:key parent))]
      (binding [*parent* parent
                remote/*identity-overrides* {parent-name (:key parent)}
                remote/*mapping-file* (str (root version) "/mapping.edn")
                remote/*object-transform*
                (fn [object]
                  (if (= parent-path (:path object))
                    (let [body (-> (:body object) (json/read-str :key-fn keyword)
                                   (assoc :validFrom (:valid-from parent))
                                   (dissoc :validTo))
                          body (cond-> body (:valid-to parent) (assoc :validTo (:valid-to parent)))]
                      (assoc object :body (json/write-str body)))
                    object))]
        ;; Clear only this scenario, after preflight succeeds. Polling counts may
        ;; differ between recordings, so old numbered files must not survive.
        (doseq [file (reverse (file-seq (io/file (root version))))
                :when (.exists file)]
          (io/delete-file file))
        (io/make-parents (str (root version) "/parent.edn"))
        (spit (str (root version) "/parent.edn") (prn-str parent))
        (remote/remote-entities-fixture test-fn)))
    ;; All playback HTTP, including accidental calls outside the cassette, is forbidden.
    (with-redefs [http/request (fn [& _] (throw (ex-info "Network forbidden during Ho variant playback" {})))]
      (test-fn))))

(defn scenario-data [version]
  (if (= :record vcr/vcr-mode)
    {:parent *parent* :mapping remote/*session*}
    (let [read! (fn [file]
                  (when-not (.isFile (io/file file))
                    (throw (ex-info "Ho variant recordings missing: record with live prerequisites; see doc/ho-variant.md"
                                    {:file file})))
                  (edn/read-string (slurp file)))]
      {:parent (read! (str (root version) "/parent.edn"))
       :mapping (read! (str (root version) "/mapping.edn"))})))

(defn run-scenario!
  [version {:keys [getter resolver]} institution-oin run-job parent-name child-name offered-name oe-type ao-type]
  (let [{:keys [parent mapping]} (scenario-data version)
        parent-id (str (get mapping parent-name))
        child-id (str (get mapping child-name))
        offered-id (str (get mapping offered-name))
        vcr (vcr/make-vcr)
        created (atom #{})
        job! (fn [type id action]
               (let [result (run-job type id action)]
                 (is (:goedgekeurd result) (pr-str result))
                 (when-not (:goedgekeurd result)
                   (throw (ex-info "Ho variant scenario job failed" {:result result :id id :action action})))
                 (swap! created (if (= "upsert" action) conj disj) id)
                 result))
        step (fn [idx name f]
               (binding [http-utils/*vcr* (vcr (root version) idx name)] (f)))
        load! (fn [type code] (getter (loader/rio-entity-request type code institution-oin :dom)))]
    (assert (= (:key parent) parent-id) "Parent identity must match prepared key")
    (assert (every? seq [parent-id child-id offered-id]))
    (assert (= 3 (count (set [parent-id child-id offered-id]))) "Scenario identities must be distinct")
    (try
      (step 1 "insert-variant" #(is (:opleidingscode (job! oe-type child-id "upsert"))))
      (let [variant-code
            (step 2 "assert-variant"
                  #(let [code (resolver :oe child-id institution-oin)
                         response (load! :oe code)
                         entity (first (children response "hoOpleiding"))]
                     (is (some? entity))
                     (is (= code (text entity "opleidingseenheidcode")))
                     (is (= "VARIANT" (characteristic entity "soort")))
                     (is (= child-id (loader/find-eigen-opleidingseenheid-sleutel code getter institution-oin)))
                     (is (= [{:opleidingseenheidcodes #{(:code parent) code}
                              :valid-from (last (sort [(:valid-from parent) "2016-12-16"]))
                              :valid-to (first (sort (keep identity [(:valid-to parent) "2025-04-25"])))}]
                            (relations getter institution-oin code)))
                     code))]
        (step 3 "insert-offered" #(job! ao-type offered-id "upsert"))
        (step 4 "assert-offered"
              #(let [code (resolver :ao offered-id institution-oin)
                     entity (first (children (load! :ao code) "aangebodenHoOpleiding"))]
                 (is (some? entity))
                 (is (= code (text entity "aangebodenOpleidingCode")))
                 (is (= variant-code (text entity "opleidingseenheidcode"))))))
      (finally
        ;; Never clean up the accredited parent, including after partial failure.
        (try
          (when (@created offered-id)
            (step 5 "delete-offered" #(job! ao-type offered-id "delete")))
          (finally
            (when (@created child-id)
              (step 6 "delete-variant" #(job! oe-type child-id "delete")))))))))
