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

(ns nl.surf.eduhub-rio-mapper.v5.commands.processing
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [nl.jomco.http-status-codes :as http-status]
    [nl.surf.eduhub-rio-mapper.rio.helper :as rio.helper]
    [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
    [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
    [nl.surf.eduhub-rio-mapper.rio.relation-preconditions :as preconditions]
    [nl.surf.eduhub-rio-mapper.specs.mutation :as mutation]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
    [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
    [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
    [nl.surf.eduhub-rio-mapper.v5.commands.dry-run :as dry-run]
    [nl.surf.eduhub-rio-mapper.v5.commands.link :as link]
    [nl.surf.eduhub-rio-mapper.v5.ooapi.base :as ooapi-base]
    [nl.surf.eduhub-rio-mapper.v5.ooapi.loader :as ooapi.loader]
    [nl.surf.eduhub-rio-mapper.v5.rio.relation-handler :as relation-handler]
    [nl.surf.eduhub-rio-mapper.v5.rio.updated-handler :as updated-handler]
    [nl.surf.eduhub-rio-mapper.v5.utils.ooapi :as ooapi-utils]))

(defn- extract-eduspec-from-result [result]
  (let [entity (:ooapi result)]
    (when (= "aanleveren_opleidingseenheid" (:action result))
      entity)))

(defn- make-updater-load-ooapi-phase [{:keys [ooapi-loader]}]
  (let [validating-loader (ooapi.loader/validating-loader ooapi-loader)]
    (fn load-ooapi-phase [{::ooapi/keys [type id] :as request}]
      (logging/with-mdc
        {:ooapi-type type :ooapi-id id}
        (ooapi.loader/load-entities validating-loader request)))))

;; If resolve not successful, nil is returned or exception thrown.

;; returns function that takes request
;; and returns request with ::rio/opleidingscode or ::rio/aangeboden-opleiding-code
(defn- make-updater-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{:keys [institution-oin action]
                      ::ooapi/keys [type id entity]
                      ::rio/keys [opleidingscode] :as request}]
    {:pre [institution-oin]}
    (let [rio-consumer    (ooapi-utils/extract-rio-consumer (:consumers entity))
          resolve-eduspec (= type "education-specification")
          ooapi-id        (if resolve-eduspec
                            id
                            (ooapi-base/education-specification-id entity))
          oe-code         (or opleidingscode
                              (when ooapi-id
                                (resolver :oe ooapi-id institution-oin)))
          ao-code         (when-not resolve-eduspec (resolver :ao id institution-oin))]
      ;; Inserting a course or program while the education
      ;; specification has not been added to RIO will throw an error.
      ;; Also throw an error when trying to delete an education specification
      ;; that cannot be resolved.
      (when (or (and (nil? oe-code) (not resolve-eduspec) (= "upsert" action))
                (and (nil? oe-code) resolve-eduspec (= "delete" action)))
        (throw (ex-info (str "No 'opleidingseenheid' found in RIO with eigensleutel: " ooapi-id)
                        {:code       oe-code
                         :type       type
                         :action     action
                         :retryable? false})))
      ;; Resolve failures retain their cause and explicit retryability.
      (when (and (= type "education-specification")
                 (= "variant" (:educationSpecificationSubType rio-consumer)))
        (preconditions/resolve-parent! (:parent entity) institution-oin resolver))
      (cond-> request
              oe-code (assoc ::rio/opleidingscode oe-code)
              ao-code (assoc ::rio/aangeboden-opleiding-code ao-code)))))

;; returns function that takes request with ::rio/opleidingscode or ::rio/aangeboden-opleiding-code
;; and returns request with :rio-relations
(defn- make-load-relations-phase [{:keys [getter]}]
  (fn load-relations-phase [{::rio/keys [opleidingscode]
                             ::ooapi/keys [type]
                             :keys [institution-oin] :as request}]
    {:pre [(or (nil? opleidingscode) (string? opleidingscode))]}
    (cond-> request
      (and opleidingscode
           (= type "education-specification"))
      ;; Format: vector of relations, each relation is a map with:
      ;; {:opleidingseenheidcodes #{string...}  ; set of opleidingseenheid codes
      ;;  :valid-from LocalDate                 ; start date
      ;;  :valid-to LocalDate}                  ; optional end date
      (assoc :rio-relations
             (relation-handler/load-relation-data getter opleidingscode institution-oin)))))

(defn- valid-date-range?
  "Returns whether the date range of the relation is valid.

   A valid range fits completely within the range of the date range of the eduspec."
  [eduspec {:keys [valid-from valid-to] :as _relation}]
  (let [eduspec-valid-from (:validFrom eduspec)
        eduspec-valid-to   (:validTo eduspec)
        valid-from-check (if (and valid-from eduspec-valid-from)
                           (<= 0 (compare valid-from eduspec-valid-from))
                           (nil? eduspec-valid-from))
        valid-to-check   (if (and valid-to eduspec-valid-to)
                           (>= 0 (compare valid-to eduspec-valid-to))
                           (nil? eduspec-valid-to))
        is-valid (and valid-from-check valid-to-check)]
    is-valid))

(defn- make-prune-relations-phase [{:keys [getter] :as handlers} rio-config]
  (fn prune-relations-phase [{:keys [rio-relations institution-oin]
                              ::ooapi/keys [entity]
                              ::rio/keys [opleidingscode] :as request}]
    (if-not rio-relations
      request
      (let [eduspec entity
            {valid-relations true
             invalid-relations false} (group-by (partial valid-date-range? eduspec)
                                                rio-relations)
            parent-id (when (= "variant" (:educationSpecificationSubType
                                           (ooapi-utils/extract-rio-consumer (:consumers entity))))
                        (:parent entity))
            request (if (and (seq invalid-relations) parent-id)
                      (assoc request ::relation-handler/parent
                             (relation-handler/load-required-parent! parent-id request handlers))
                      request)]
        ;; Delete invalid relations from RIO system
        (when (and (seq invalid-relations)
                   opleidingscode)
          (doseq [invalid-rel invalid-relations]
            (-> (relation-handler/relation-mutation :delete institution-oin invalid-rel)
                (mutator/mutate! rio-config)))
          (rio.helper/blocking-retry #(empty? (relation-handler/load-relation-data getter opleidingscode institution-oin))
                                     rio-config
                                     "Ensure delete relation is processed by RIO"))

        ;; Return request with only valid relations
        (assoc request :rio-relations (vec valid-relations))))))

(defn- make-updater-soap-phase
  "Phase definition for creating the soap document.

   Returns function that takes request, and returns map with keys
   :job (request), :result (::Mutation/mutation-response) and :eduspec (education specification)
  "
  []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/update-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-deleter-prune-relations-phase [handlers]
  (fn [{::ooapi/keys [type] ::rio/keys [opleidingscode] :keys [institution-oin] :as request}]
    (when (and opleidingscode (= type "education-specification"))
      (relation-handler/delete-relations opleidingscode type institution-oin handlers))
    request))

(defn- make-deleter-soap-phase []
  (fn soap-phase [{:keys [institution-oin] :as job}]
    {:pre [institution-oin (job :institution-schac-home)]}
    (let [result  (updated-handler/deletion-mutation job)
          eduspec (extract-eduspec-from-result result)]
      {:job job :result result :eduspec eduspec})))

(defn- make-updater-mutate-rio-phase [{:keys [rio-config]}]
  (fn mutate-rio-phase [{:keys [job result eduspec]}]
    {:pre [(s/valid? ::mutation/mutation-response result)]}
    (logging/with-mdc {:soap-action (:action result) :ooapi-id (::ooapi/id job)}
      {:job job :eduspec eduspec :mutate-result (mutator/mutate! result rio-config)})))

(defn- make-deleter-confirm-rio-phase [{:keys [resolver]} rio-config]
  (fn confirm-rio-phase [{:keys [job] :as result}]
    (let [{::ooapi/keys [id type]
           :keys        [institution-oin]} job
          rio-type      (if (= "education-specification" type) :oe :ao)]
      (if (rio.helper/blocking-retry (complement #(resolver rio-type id institution-oin))
                                     rio-config
                                     "Ensure delete is processed by RIO")
        result
        (throw (ex-info (str "Processing this job takes longer than expected. Our developers have been informed and will contact DUO. Please try again in a few hours."
                             ": " type " " id) {:rio-queue-status :down}))))))

(defn- make-updater-confirm-rio-phase [{:keys [resolver]} rio-config]
  (fn confirm-rio-phase [{:keys [job] :as result}]
    (let [{::ooapi/keys [id type]
           :keys        [institution-oin]} job
          rio-type      (if (= "education-specification" type) :oe :ao)
          rio-code (rio.helper/blocking-retry #(resolver rio-type id institution-oin)
                                              rio-config
                                              "Ensure upsert is processed by RIO")]
      (if rio-code
        (let [path (if (= type "education-specification")
                     [:eduspec ::rio/opleidingscode]
                     [:job ::rio/aangeboden-opleiding-code])]
          (assoc-in result path rio-code))
        (throw (ex-info (str "Processing this job takes longer than expected. Our developers have been informed and will contact DUO. Please try again in a few hours."
                             ": " type " " id) {:rio-queue-status :down}))))))

(defn- make-updater-sync-relations-phase
  "Calculates which relations exist in ooapi, which relations exist in RIO, and synchronizes them.

  Only relations between education-specifications are considered; specifically, relations with type program,
  one with no subtype and one with subtype variant.
  To perform synchronization, relations are added and deleted in RIO."
  [{:keys [getter] :as handlers} config]
  (fn sync-relations-phase [{:keys [job eduspec] :as request}]
    (if (nil? eduspec)
      request
      (let [{:keys [missing superfluous] :as diff} (relation-handler/relation-mutations eduspec job handlers)
            {:keys [institution-oin]} job
            {::rio/keys [opleidingscode]} eduspec]

        (relation-handler/mutate-relations! diff job handlers)
        (rio.helper/blocking-retry (fn in-sync? []
                                     (let [rio-relations (relation-handler/load-relation-data getter opleidingscode institution-oin)
                                           rio-relations-set (set rio-relations)
                                           missing-now-present? (every? rio-relations-set missing)
                                           superfluous-now-gone? (empty? (set/intersection rio-relations-set superfluous))
                                           synced? (and missing-now-present? superfluous-now-gone?)]
                                       synced?))
                                   config
                                   "Ensure update relation is processed by RIO")
        request))))

(defn- wrap-phase [[phase f]]
  (fn [req]
    (try
      (f req)
      (catch Exception ex
        (throw (ex-info (ex-message ex)
                        (assoc (ex-data ex) :phase phase)
                        ex))))))

(defn- make-insert [handlers rio-config]
  (let [fs [[:preparing      (make-updater-soap-phase)]
            [:upserting      (make-updater-mutate-rio-phase handlers)]
            [:confirming     (make-updater-confirm-rio-phase handlers rio-config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)
             (::ooapi/entity request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (:mutate-result $)))))

(defn- make-update [handlers rio-config]
  (let [fs [[:fetching-ooapi  (make-updater-load-ooapi-phase handlers)]
            [:resolving       (make-updater-resolve-phase handlers)]
            [:load-relations  (make-load-relations-phase handlers)]
            [:prune-relations (make-prune-relations-phase handlers rio-config)]
            [:preparing       (make-updater-soap-phase)]
            [:upserting       (make-updater-mutate-rio-phase handlers)]
            [:confirming      (make-updater-confirm-rio-phase handlers rio-config)]
            [:associating     (make-updater-sync-relations-phase handlers rio-config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
            (reduce (fn [req f] (f req)) $ wrapped-fs)
            (merge (:mutate-result $) (select-keys (:job $) [::rio/aangeboden-opleiding-code]))))))

(defn- make-deleter [{:keys [rio-config] :as handlers}]
  {:pre [rio-config]}
  (let [fs [[:resolving  (make-updater-resolve-phase handlers)]
            [:deleting   (make-deleter-prune-relations-phase handlers)]
            [:preparing  (make-deleter-soap-phase)]
            [:deleting   (make-updater-mutate-rio-phase handlers)]
            [:confirming (make-deleter-confirm-rio-phase handlers rio-config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
        (reduce (fn [req f] (f req)) $ wrapped-fs)
        (:mutate-result $)))))

(defn- dry-run-status [rio-summary ooapi-summary]
  {:status (if ooapi-summary (if rio-summary "found" "not-found") "error")})

(defn- eduspec-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin]} {:keys [resolver getter]}]
  (let [rio-code      (resolver :oe id institution-oin)
        response      (when rio-code
                        (getter (rio.loader/rio-entity-request :oe rio-code institution-oin :raw-dom)))
        rio-obj       (when response
                        (some #(xml-utils/get-in-dom response rio.loader/schema [(name %)])
                              rio.loader/opleidingseenheid-namen))
        rio-summary   (dry-run/summarize-opleidingseenheid rio-obj)
        ooapi-summary (dry-run/summarize-eduspec ooapi-entity)
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :opleidingseenheidcode rio-code))]
    (merge output (dry-run-status rio-summary ooapi-summary))))

(defn- course-program-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin] :as request} {:keys [getter ooapi-loader]}]
  (let [response    (getter (rio.loader/rio-entity-request :ao id institution-oin :raw-dom))
        rio-obj     (some #(xml-utils/get-in-dom response rio.loader/schema [(name %)])
                          rio.loader/aangeboden-opleiding-namen)
        rio-summary (dry-run/summarize-aangeboden-opleiding rio-obj)
        offering-summary (->> (ooapi.loader/load-offerings ooapi-loader request)
                              (map dry-run/summarize-offering)
                              (sort-by :cohortcode)
                              vec)
        ooapi-summary (dry-run/summarize-course-program (assoc ooapi-entity :offerings offering-summary))
        rio-code (when-let [element (xml-utils/get-in-dom rio-obj rio.loader/schema ["aangebodenOpleidingCode"])]
                   (when (.hasChildNodes element)
                     (.getTextContent element)))
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :aangebodenOpleidingCode rio-code))]
    (merge output (dry-run-status rio-summary ooapi-summary))))

(defn- not-found? [obj]
  (= http-status/not-found (:status obj)))

(defn- safe-loader [f]
  (try
    (f)
    (catch Exception ex
      (if (not-found? (ex-data ex))
        (ex-data ex)
        (throw ex)))))

(defn- make-dry-runner [{:keys [rio-config ooapi-loader] :as handlers}]
  {:pre [rio-config]}
  (fn [{::ooapi/keys [type] :as request}]
    {:pre [(:institution-oin request)]}
    (let [ooapi-entity (safe-loader (fn [] (ooapi-loader request)))
          value (if (not-found? ooapi-entity)
                  {:status "error"}
                  (let [handler (case type "education-specification" eduspec-dry-run-handler
                                           ("course" "program") course-program-dry-run-handler)]
                    (handler ooapi-entity request handlers)))]
      {:dry-run value})))

(defn make-handlers
  [{:keys [rio-config
           gateway-root-url
           gateway-credentials]}]
  {:pre [(:recipient-oin rio-config)]}
  (let [resolver     (rio.loader/make-resolver rio-config)
        getter       (rio.loader/make-getter rio-config)
        ooapi-loader (ooapi.loader/make-ooapi-http-loader gateway-root-url
                                                          gateway-credentials
                                                          rio-config)
        handlers     {:ooapi-loader ooapi-loader
                      :rio-config   rio-config
                      :getter       getter
                      :resolver     resolver}
        update!      (make-update handlers rio-config)
        delete!      (make-deleter handlers)
        insert!      (make-insert handlers rio-config)
        dry-run!     (make-dry-runner handlers)
        link!        (link/make-linker rio-config getter)]
    (assoc handlers :update! update!, :delete! delete!, :insert! insert!, :dry-run! dry-run!, :link! link!)))
