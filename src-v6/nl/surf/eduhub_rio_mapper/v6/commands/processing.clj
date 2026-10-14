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

(ns nl.surf.eduhub-rio-mapper.v6.commands.processing
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [nl.jomco.http-status-codes :as http-status]
   [nl.surf.eduhub-rio-mapper.rio.helper :as rio.helper]
   [nl.surf.eduhub-rio-mapper.rio.loader :as rio.loader]
   [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
   [nl.surf.eduhub-rio-mapper.specs.mutation :as mutation]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
   [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
   [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
   [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
   [nl.surf.eduhub-rio-mapper.v6.commands.dry-run :as dry-run]
   [nl.surf.eduhub-rio-mapper.v6.commands.link :as link]
   [nl.surf.eduhub-rio-mapper.v6.ooapi.loader :as ooapi.loader]
   [nl.surf.eduhub-rio-mapper.v6.rio.relation-handler :as relation-handler]
   [nl.surf.eduhub-rio-mapper.v6.rio.updated-handler :as updated-handler]
   [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6])
  (:import [clojure.lang ExceptionInfo]))

(defn- extract-prgspec-from-result [result]
  (let [entity (:ooapi result)]
    (when (= "aanleveren_opleidingseenheid" (:action result))
      entity)))

;; This function wraps the resolver but returns nil if entity not found.
(defn- resolve-safe [resolver rio-type ooapi-id institution-oin]
  (try
    (resolver rio-type ooapi-id institution-oin)
    (catch Exception _ex
      nil)))

;; If resolve not successful, nil is returned or exception thrown.
(defn- can-resolve? [resolver rio-type ooapi-id institution-oin]
  (some? (resolve-safe resolver rio-type ooapi-id institution-oin)))

(defn- quick-validate-specification [{:keys [consumer] :as entity}]
  (when (nil? (:specificationType consumer))
    (let [error-msg "Invalid programme specification: specificationType required"]
      (throw (ex-info error-msg
                      {:entity     entity
                       :error      error-msg
                       :retryable? false}))))
  (when-not (s/valid? ::ooapi-v6/specification-type (:specificationType consumer))
    (let [error-msg (str "Invalid programme specification: specificationType value '" (:specificationType consumer) "' not allowed")]
      (throw (ex-info error-msg
                      {:entity     entity
                       :error      error-msg
                       :retryable? false})))))

(defn- quick-validate [{:keys [programmeType] :as entity} type]
  (when (and (#{"programme"} type)
             (= "specification" programmeType))
    (quick-validate-specification entity)))

;; This function wraps ooapi.loader/ooapi-http-loader but returns nil if entity not found.
(defn- ooapi-http-load-safe [request]
  (try
    (ooapi.loader/ooapi-http-loader request)
    (catch ExceptionInfo ex
      (when (not= http-status/not-found (:status (ex-data ex)))
        (throw ex)))))

;; Only function of this phase is to load programmes in order to distinguish
;; programmes from programme-specifications, which are different object in RIO
(defn- load-ooapi-phase-for-delete [{::ooapi/keys [type id] :as request}]
  {:pre  [(#{"programme" "course"} type)]
   :post [(:rio-type %)]}
  (if (= type "course")
    (assoc request :rio-type :ao)
    (logging/with-mdc
      {:ooapi-type type :ooapi-id id}
      (assoc request :rio-type
             (if-let [entity (ooapi-http-load-safe request)]
               (if (and (= "programme" type)
                        (= "specification" (:programmeType entity)))
                 :oe
                 :ao)
               :unknown)))))

;; in:  {::ooapi/keys [type id] :keys [action institution-name institution-oin institution-schac-home]}
;; diff out: {:keys [rio-type] ::ooapi/keys [entity]}
(defn- load-ooapi-phase-for-update [{::ooapi/keys [type id] :as request}]
  {:pre  [(#{"programme" "course"} type)]
   :post [(:rio-type %)]}
  (logging/with-mdc
    {:ooapi-type type :ooapi-id id}
    (let [{::ooapi/keys [entity] :as result} (ooapi.loader/load-entities request)]
      (quick-validate entity type)
      (assoc result :rio-type (if (and (= "programme" type)
                                       (= "specification" (:programmeType entity)))
                                :oe
                                :ao)))))

;; in:  {::ooapi/keys [type id entity] :keys [action rio-type institution-name institution-oin institution-schac-home]}
;; diff out: {::rio/keys [opleidingscode aangeboden-opleiding-code]}
(defn- make-updater-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{:keys [institution-oin action rio-type]
                      ::ooapi/keys [type id entity]
                      ::rio/keys [opleidingscode] :as request}]
    {:pre [institution-oin id rio-type]}
    (let [consumer        (:consumer entity)
          ooapi-id        (if (= :oe rio-type)
                            id
                            (-> entity :consumer :specificationId))
          oe-code         (or opleidingscode
                              (when ooapi-id
                                (resolver :oe ooapi-id institution-oin)))
          ao-code         (when-not (= :oe rio-type) (resolver rio-type id institution-oin))]
      ;; Inserting a course or program while the programme specification has not been added to RIO will throw an error.
      (when (and (nil? oe-code) (= :ao rio-type))
        (throw (ex-info (str "No 'opleidingseenheid' found in RIO with eigensleutel: " ooapi-id)
                        {:code       oe-code
                         :type       type
                         :action     action
                         :retryable? false})))
      ;; For variants, we need to check if the eduspec this is a variant of (the parent) actually exists in RIO - if not, abort now
      (when (and
             (= rio-type :oe)
             (some? (:variantOf consumer))
             (not (can-resolve? resolver :oe (:variantOf consumer) institution-oin)))
        (throw (ex-info (str "No 'opleidingseenheid' found in RIO for the parent of this variant with eigensleutel: " (:variantOf consumer))
                        {:code       oe-code
                         :type       type
                         :action     action
                         :retryable? false})))

      (cond-> request
        oe-code (assoc ::rio/opleidingscode oe-code)
        ao-code (assoc ::rio/aangeboden-opleiding-code ao-code)))))

;; in:  {::ooapi/keys [type id entity] :keys [action rio-type institution-name institution-oin institution-schac-home]}
;; diff out: {::rio/keys [opleidingscode aangeboden-opleiding-code]}
(defn- make-deleter-resolve-phase [{:keys [resolver]}]
  (fn resolve-phase [{:keys [institution-oin action rio-type]
                      ::ooapi/keys [type id entity]
                      ::rio/keys [opleidingscode] :as request}]
    {:pre [institution-oin id rio-type]}
    (if (= :unknown rio-type)
      ;; Special case for deleting unknown rio-type
      ;; if rio-type is :unknown, resolve first :oe, and if not found, :ao
      ;; as soon as either is found, continue with that one
      ;; if none is found, abort
      (let [resolve-for-rio-type (fn [rt] (resolve-safe resolver rt id institution-oin))]
        (if-let [rio-code (resolve-for-rio-type :oe)]
          (assoc request ::rio/opleidingscode rio-code :rio-type :oe)
          (when-let [rio-code (resolve-for-rio-type :ao)]
            (assoc request ::rio/aangeboden-opleiding-code rio-code :rio-type :ao))))

      ;; Normal case
      (let [ooapi-id        (if (= :oe rio-type)
                              id
                              (-> entity :consumer :specificationId))
            oe-code         (or opleidingscode
                                (when ooapi-id
                                  (resolver :oe ooapi-id institution-oin)))
            ao-code         (when-not (= :oe rio-type) (resolver rio-type id institution-oin))]
        ;; Throw an error when trying to delete an education specification
        ;; that cannot be resolved.
        (when (and (nil? oe-code) (= :oe rio-type))
          (throw (ex-info (str "No 'opleidingseenheid' found in RIO with eigensleutel: " ooapi-id)
                          {:code       oe-code
                           :type       type
                           :action     action
                           :retryable? false})))

        (cond-> request
          oe-code (assoc ::rio/opleidingscode oe-code)
          ao-code (assoc ::rio/aangeboden-opleiding-code ao-code))))))

;; in:  {::ooapi/keys [type id entity] :keys [action rio-type institution-name institution-oin institution-schac-home] ::rio/keys [opleidingscode aangeboden-opleiding-code]}
;; diff out: {:keys [rio-relations]}
(defn- make-load-relations-phase [{:keys [getter]}]
  (fn load-relations-phase [{::rio/keys [opleidingscode]
                             :keys [institution-oin rio-type] :as request}]
    {:pre [(or (nil? opleidingscode) (string? opleidingscode))]}
    (cond-> request
      (and opleidingscode
           (= rio-type :oe))
      ;; Format: vector of relations, each relation is a map with:
      ;; {:opleidingseenheidcodes #{string...}  ; set of opleidingseenheid codes
      ;;  :valid-from LocalDate                 ; start date
      ;;  :valid-to LocalDate}                  ; optional end date
      (assoc :rio-relations
             (relation-handler/load-relation-data getter opleidingscode institution-oin)))))

(defn- valid-date-range?
  "Returns whether the date range of the relation is valid.

   A valid range fits completely within the range of the date range of the prgspec."
  [prgspec {:keys [valid-from valid-to] :as _relation}]
  (let [prgspec-valid-from (:validFrom prgspec)
        prgspec-valid-to   (:validTo prgspec)
        valid-from-check (if (and valid-from prgspec-valid-from)
                           (<= 0 (compare valid-from prgspec-valid-from))
                           (nil? prgspec-valid-from))
        valid-to-check   (if (and valid-to prgspec-valid-to)
                           (>= 0 (compare valid-to prgspec-valid-to))
                           (nil? prgspec-valid-to))
        is-valid (and valid-from-check valid-to-check)]
    is-valid))

;; in:  {::ooapi/keys [type id entity] :keys [rio-relations action rio-type institution-name institution-oin institution-schac-home] ::rio/keys [opleidingscode aangeboden-opleiding-code]}
;; diff out: {}
(defn- make-prune-relations-phase [{:keys [getter] :as _handlers} rio-config]
  (fn prune-relations-phase [{:keys [rio-relations institution-oin]
                              ::ooapi/keys [entity]
                              ::rio/keys [opleidingscode] :as request}]
    (if-not rio-relations
      request
      (let [prgspec entity
            {valid-relations true
             invalid-relations false} (group-by (partial valid-date-range? prgspec)
                                                rio-relations)]
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

;; in:  {::ooapi/keys [type id entity] :keys [rio-relations action rio-type institution-name institution-oin institution-schac-home] ::rio/keys [opleidingscode aangeboden-opleiding-code]}
;; out {:job job :result result :prgspec prgspec}
(defn- soap-phase-for-update [{:keys [institution-oin] :as job}]
  {:pre [institution-oin (job :institution-schac-home)]}
  (let [result  (updated-handler/update-mutation job)
        prgspec (extract-prgspec-from-result result)]
    {:job job :result result :prgspec prgspec}))

(defn- make-deleter-prune-relations-phase [handlers rio-config]
  (fn [{::rio/keys [opleidingscode] :keys [institution-oin rio-type] :as request}]
    {:pre [rio-type]}
    (when (and opleidingscode (= :oe rio-type))
      (relation-handler/delete-relations opleidingscode rio-type institution-oin (:getter handlers) rio-config))
    request))

(defn- soap-phase-for-delete [{:keys [institution-oin] :as job}]
  {:pre [institution-oin (job :institution-schac-home)]}
  (let [result  (updated-handler/deletion-mutation job)
        prgspec (extract-prgspec-from-result result)]
    {:job job :result result :prgspec prgspec}))

;; in:  {:job job :result result :prgspec prgspec}
;; out: {:job job :mutate-result result :prgspec prgspec}
(defn- make-updater-mutate-rio-phase [rio-config]
  (fn mutate-rio-phase [{:keys [job result prgspec]}]
    {:pre [(s/valid? ::mutation/mutation-response result)]}
    (logging/with-mdc {:soap-action (:action result) :ooapi-id (::ooapi/id job)}
      {:job job :prgspec prgspec :mutate-result (mutator/mutate! result rio-config)})))

(defn- make-deleter-confirm-rio-phase [{:keys [resolver]} rio-config]
  (fn confirm-rio-phase [{:keys [job] :as result}]
    (let [{::ooapi/keys [id type]
           :keys        [institution-oin programme-specification]} job
          rio-type (if programme-specification :oe :ao)]
      (if (rio.helper/blocking-retry (complement #(resolver rio-type id institution-oin))
                                     rio-config
                                     "Ensure delete is processed by RIO")
        result
        (throw (ex-info (str "Processing this job takes longer than expected. Our developers have been informed and will contact DUO. Please try again in a few hours."
                             ": " type " " id) {:rio-queue-status :down}))))))

(defn- make-updater-confirm-rio-phase [{:keys [resolver]} rio-config]
  (fn confirm-rio-phase [{:keys [job] :as result}]
    (let [{::ooapi/keys [id type]
           :keys        [institution-oin rio-type]} job
          rio-code (rio.helper/blocking-retry #(resolver rio-type id institution-oin)
                                              rio-config
                                              "Ensure upsert is processed by RIO")]
      (if rio-code
        (let [path (if (= :oe rio-type)
                     [:prgspec ::rio/opleidingscode]
                     [:job ::rio/aangeboden-opleiding-code])]
          (assoc-in result path rio-code))
        (throw (ex-info (str "Processing this job takes longer than expected. Our developers have been informed and will contact DUO. Please try again in a few hours."
                             ": " type " " id) {:rio-queue-status :down}))))))

;; we loaded the relation data in the make-load-relations-phase phase with the opleidingscode - why isn't the opleidingscode present now?

(defn- make-updater-sync-relations-phase
  "Calculates which relations exist in ooapi, which relations exist in RIO, and synchronizes them.

  Only relations between education-specifications are considered; specifically, relations with type program,
  one with no subtype and one with subtype variant.
  To perform synchronization, relations are added and deleted in RIO."
  [{:keys [getter] :as handlers} config]

  (fn sync-relations-phase [{:keys [job prgspec] :as request}]
    (if (nil? prgspec)
      request
      (let [{:keys [missing superfluous] :as diff} (relation-handler/relation-mutations prgspec job handlers config)
            {:keys [institution-oin]} job
            {::rio/keys [opleidingscode]} prgspec]

        (relation-handler/mutate-relations! diff job (:rio-config config))
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
    (when req
      (try
        (f req)
        (catch Exception ex
          (throw (ex-info (ex-message ex)
                          (assoc (ex-data ex) :phase phase)
                          ex)))))))

(defn- make-insert [handlers rio-config]
  (let [fs [[:preparing      soap-phase-for-update]
            [:upserting      (make-updater-mutate-rio-phase rio-config)]
            [:confirming     (make-updater-confirm-rio-phase handlers rio-config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)
             (::ooapi/entity request)]}
      (as-> request $
        (reduce (fn [req f] (or (f req) (reduced nil))) $ wrapped-fs)
        (:mutate-result $)))))

(defn- make-update [handlers {:keys [rio-config] :as config}]
  (let [fs [[:fetching-ooapi  load-ooapi-phase-for-update]
            [:resolving       (make-updater-resolve-phase handlers)]
            [:load-relations  (make-load-relations-phase handlers)]
            [:prune-relations (make-prune-relations-phase handlers rio-config)]
            [:preparing       soap-phase-for-update]
            [:upserting       (make-updater-mutate-rio-phase rio-config)]
            [:confirming      (make-updater-confirm-rio-phase handlers rio-config)]
            [:associating     (make-updater-sync-relations-phase handlers config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
        (assoc $ :config config)
        (reduce (fn [req f] (or (f req) (reduced nil))) $ wrapped-fs)
        (merge (:mutate-result $) (select-keys (:job $) [::rio/aangeboden-opleiding-code]))))))

(defn- make-deleter [handlers
                     {:keys [rio-config] :as config}]
  {:pre [rio-config]}
  (let [fs [[:fetching-ooapi load-ooapi-phase-for-delete]
            [:resolving      (make-deleter-resolve-phase handlers)]
            [:deleting       (make-deleter-prune-relations-phase handlers rio-config)]
            [:preparing      soap-phase-for-delete]
            [:deleting       (make-updater-mutate-rio-phase rio-config)]
            [:confirming     (make-deleter-confirm-rio-phase handlers rio-config)]]
        wrapped-fs (map wrap-phase fs)]
    (fn [request]
      {:pre [(:institution-oin request)]}
      (as-> request $
        (assoc $ :config config)
        (reduce (fn [req f] (or (f req) (reduced nil))) $ wrapped-fs)
        (:mutate-result $)))))

(defn- dry-run-status [rio-summary ooapi-summary]
  {:status (if ooapi-summary (if rio-summary "found" "not-found") "error")})

(defn- prgspec-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin]} {:keys [resolver getter]}]
  (let [rio-code      (resolver :oe id institution-oin)
        response      (when rio-code
                        (getter (rio.loader/rio-entity-request :oe rio-code institution-oin :raw-dom)))
        rio-obj       (when response
                        (some #(xml-utils/get-in-dom response rio.loader/schema [(name %)])
                              rio.loader/opleidingseenheid-namen))
        rio-summary   (dry-run/summarize-opleidingseenheid rio-obj)
        ooapi-summary (dry-run/summarize-prgspec ooapi-entity)
        diff   (dry-run/generate-diff-ooapi-rio :rio-summary rio-summary :ooapi-summary ooapi-summary)
        output (if (nil? ooapi-summary) diff (assoc diff :opleidingseenheidcode rio-code))]
    (merge output (dry-run-status rio-summary ooapi-summary))))

(defn- course-program-dry-run-handler [ooapi-entity {::ooapi/keys [id] :keys [institution-oin] :as request} {:keys [getter]}]
  (let [response    (getter (rio.loader/rio-entity-request :ao id institution-oin :raw-dom))
        rio-obj     (some #(xml-utils/get-in-dom response rio.loader/schema [(name %)])
                          rio.loader/aangeboden-opleiding-namen)
        rio-summary (dry-run/summarize-aangeboden-opleiding rio-obj)
        offering-summary (->> (ooapi.loader/load-offerings request)
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

(defn- try-load-entity
  "Load an OOAPI entity, returning nil for 404 responses."
  [request]
  (try
    (ooapi.loader/ooapi-http-loader request)
    (catch Exception ex
      (when (not= http-status/not-found (:status (ex-data ex)))
        (throw ex)))))

(defn- make-dry-runner [handlers config]
  (fn [{::ooapi/keys [type] :as request}]
    {:pre [(:institution-oin request)]}
    (let [request (assoc request :config config)
          entity  (try-load-entity request)
          value   (if-not entity
                    {:status "error"}
                    (let [handler (if (and (= type "programme")
                                           (= "specification" (:programmeType entity)))
                                    prgspec-dry-run-handler
                                    course-program-dry-run-handler)]
                      (handler entity request handlers)))]
      {:dry-run value})))

(defn make-handlers
  [{:keys [rio-config] :as config}]
  {:pre [(:recipient-oin rio-config)]}
  (let [resolver     (rio.loader/make-resolver rio-config)
        getter       (rio.loader/make-getter rio-config)
        handlers     {:getter              getter
                      :resolver            resolver}
        update!      (make-update handlers config)
        delete!      (make-deleter handlers config)
        insert!      (make-insert handlers rio-config)
        dry-run!     (make-dry-runner handlers config)
        link!        (link/make-linker rio-config getter)]
    (assoc handlers :update! update!, :delete! delete!, :insert! insert!, :dry-run! dry-run!, :link! link!)))
