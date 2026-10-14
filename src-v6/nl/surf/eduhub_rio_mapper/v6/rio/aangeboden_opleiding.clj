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

(ns nl.surf.eduhub-rio-mapper.v6.rio.aangeboden-opleiding
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.v6.rio.helper :as rio-helper-v6]
            [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]
            [nl.surf.eduhub-rio-mapper.v6.utils.ooapi :as ooapi-utils])
  (:import [java.time Period Duration]))

(defn- parse-duration [duration]
  (when duration
    (if (str/includes? duration "T")
      ;; If it contains a T, we treat it as a time period, and count in hours.
      (let [d (Duration/parse duration)]
        {:eenheid "U" :omvang (.toHours d)})
      (let [p (Period/parse duration)
            months (.getMonths p)]
        (cond
          ;; When less than a month, use days.
          (zero? (.toTotalMonths p))
          {:eenheid "D" :omvang (.getDays p)}

          ;; Whole number of years, use years.
          (zero? months)
          {:eenheid "J" :omvang (.getYears p)}

          ;; Otherwise use months.
          :else
          {:eenheid "M" :omvang (.toTotalMonths p)})))))

;; keys are ::ooapi-v6/specification-type
(def specification-type-mapping
  (update-vals ooapi-v6/specification-types :aangeboden-opleiding))

(def ^:private mapping-course-program->aangeboden-opleiding
  {:buitenlandsePartner [:foreignPartners true]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode true]})

(def ^:private mapping-offering->cohort
  {:deelnemersplaatsen :maxNumberStudents
   :toelichtingVereisteToestemming :explanationRequiredPermission})

(defn- course-program-timeline-override-adapter
  [{:keys [name description validFrom abbreviation link consumer] :as _periode}]
  (let [{:keys [acceleratedRoute deficiency foreignPartners jointPartnerCodes propaedeuticPhase
                requirementsActivities studyChoiceCheck]} consumer]
    (fn [pk]
      (case pk
        :begindatum (rio-helper/datetime->date validFrom)
        :buitenlandsePartner foreignPartners
        :deficientie (rio-helper-v6/ooapi-mapping "deficientie" deficiency)
        :eigenNaamAangebodenOpleiding (ooapi-utils/get-localized-value name ["nl-NL" "nl"])
        :eigenNaamInternationaal (ooapi-utils/get-localized-value-exclusive name ["en"])
        :eigenNaamKort abbreviation
        :eigenOmschrijving (ooapi-utils/get-localized-value description ["nl-NL" "nl"])
        :eisenWerkzaamheden (rio-helper-v6/ooapi-mapping "eisenWerkzaamheden" requirementsActivities)
        :internationaleNaamDuits (ooapi-utils/get-localized-value-exclusive name ["de"])
        :propedeutischeFase (rio-helper-v6/ooapi-mapping "propedeutischeFase" propaedeuticPhase)
        :samenwerkendeOnderwijsaanbiedercode jointPartnerCodes
        :studiekeuzecheck (rio-helper-v6/ooapi-mapping "studiekeuzecheck" studyChoiceCheck)
        :versneldTraject (rio-helper-v6/ooapi-mapping "versneldTraject" acceleratedRoute)
        :website link))))

;; Non-standard mapping for modesOfDelivery
;; See also https://github.com/open-education-api/specification/issues/295
(def modesOfDelivery-mapping
  {"online" "ONLINE"
   "hybrid" "KLASSIKAAL_EN_ONLINE"
   "blended" "KLASSIKAAL_EN_ONLINE"
   "presential" "KLASSIKAAL"
   "lecture" "LEZING"
   "self_study" "ZELFSTUDIE"
   "coaching" "COACHING"})

(defn- lookup-mode-of-delivery [mode-of-delivery]
  (let [opleidingsvorm (get modesOfDelivery-mapping mode-of-delivery)]
    (when-not opleidingsvorm
      (throw (ex-info "modesOfDelivery cannot be mapped to RIO" {:modesOfDelivery mode-of-delivery})))
    opleidingsvorm))

;; modesOfDelivery in rio-consumer of the offering has precedence over the one in the offering itself.
(defn- extract-opleidingsvorm [modesOfDelivery rio-consumer]
  (let [consumer-modesOfDelivery (:modesOfDelivery rio-consumer)
        mapped-values (map lookup-mode-of-delivery
                           (or consumer-modesOfDelivery modesOfDelivery))]
    (first (filter seq mapped-values))))

(defn- validate-max
  "Returns first item of property. If present in consumer, consumer has precedence. Expect a single item, reject otherwise."
  [max key value required?]
  (cond
    (= 0 (count value))
    (when required? (throw (ex-info "Key is required" {:key key})))

    (> (count value) max)
    (throw (ex-info (str "RIO expects at most " max " item(s) for property " key) {:value value}))

    :else
    value))

(defn- consumer-backed
  "Returns property. If present in consumer, consumer has precedence. Properties may or may not be required."
  [entity consumer key {:keys [required? max] :as _opts}]
  (if-let [value (or (key consumer) (key entity))]
    (validate-max max key value required?)
    (when required?
      (throw (ex-info (str "RIO requires the " key " property") {})))))

(defn- course-program-offering-adapter
  [{:keys [consumer startDateTime endDateTime modesOfDelivery priceInformation
           flexibleEntryPeriodStartDateTime flexibleEntryPeriodEndDateTime] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]} consumer
        period (first (consumer-backed offering consumer :enrolmentPeriods {:required true, :max 1}))
        flexstart (rio-helper/datetime->date flexibleEntryPeriodStartDateTime)
        flexend (rio-helper/datetime->date flexibleEntryPeriodEndDateTime)]
    (fn [ck]
      (if-let [translation (mapping-offering->cohort ck)]
        (translation offering)
        (case ck
          :einddatum (rio-helper/datetime->date endDateTime)
          :bedrijfsopleiding nil    ; ignored
          :beginAanmeldperiode (-> (:startDateTime period) rio-helper/datetime->date)
          :eindeAanmeldperiode (-> (:endDateTime period) rio-helper/datetime->date)
          :cohortcode (-> offering :primaryCode :code)
          :cohortstatus (rio-helper-v6/ooapi-mapping "cohortStatus" registrationStatus)
          :flexibeleInstroom (and flexibleEntryPeriodStartDateTime {:beginInstroomperiode flexstart
                                                                    :eindeInstroomperiode flexend})
          :opleidingsvorm (extract-opleidingsvorm modesOfDelivery consumer)
          :prijs (mapv (fn [h] {:soort (rio-helper-v6/ooapi-mapping "soort" (:costType h)) :bedrag (:amount h)})
                       priceInformation)
          :toestemmingVereistVoorAanmelding (rio-helper-v6/ooapi-mapping "toestemmingVereistVoorAanmelding"
                                                                      requiredPermissionRegistration)
          :vastInstroommoment (when (nil? flexibleEntryPeriodStartDateTime) {:instroommoment (rio-helper/datetime->date startDateTime)}))))))

(defn- course-program-adapter
  "Given a course or program, a rio-consumer object and an id, return a function.
   This function, given a attribute name from the RIO namespace, returns the corresponding value from the course or program,
   translated if necessary to the RIO domain."
  [{:keys [rioCode validFrom validTo offerings level modeOfStudy sector fieldsOfStudy consumer timelineOverrides firstStartDateTime] :as course-program}
   opleidingscode
   ooapi-type]
  (let [duration-map (some-> consumer :duration parse-duration)
        id           ((if (= :course ooapi-type) :courseId :programmeId) course-program)
        periods      (map #(assoc (ooapi-type %)
                                  :validFrom (:validFrom %)
                                  :validTo   (:validTo %))
                          timelineOverrides)]
    (fn [k] {:pre [(keyword? k)]}
      (if-let [[translation consumer?] (mapping-course-program->aangeboden-opleiding k)]
        (if (rio-helper-v6/ooapi-mapping? (name k))
          (rio-helper-v6/ooapi-mapping (name k) (translation (if consumer? consumer course-program)))
          (translation (if consumer? consumer course-program)))
        (case k
          :opleidingseenheidSleutel opleidingscode
          ;; Required field. If found in the resolve phase, will be added to the entity under the rioCode key,
          ;; otherwise use the eigen sleutel value (an UUID).
          :aangebodenOpleidingCode (or rioCode id)
          ;; See opleidingseenheid for explanation of timelineOverrides and periods.
          :begindatum (-> (map :validFrom timelineOverrides)
                          (conj validFrom)
                          sort
                          first
                          rio-helper/datetime->date)
          :einddatum (-> (map :validTo timelineOverrides)
                         (conj validTo)
                         sort
                         last
                         rio-helper/datetime->date)
          :ISCED (rio-helper/narrow-isced fieldsOfStudy)
          :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                        :opleidingsduurOmvang  (:omvang duration-map)})
          :niveau (rio-helper-v6/level-sector-mapping level sector)
          :vorm (rio-helper-v6/ooapi-mapping "vorm" modeOfStudy)
          :voertaal (rio-helper-v6/ooapi-mapping
                     "voertaal"
                     (consumer-backed course-program consumer :teachingLanguages {:required false, :max 3}))
          :eersteInstroomDatum (rio-helper/datetime->date firstStartDateTime)

          :cohorten (mapv #(course-program-offering-adapter %)
                          offerings)

          ;; See opleidingseenheid for explanation of timelineOverrides and periods.
          :periodes (->> (conj periods course-program)
                         (mapv #(course-program-timeline-override-adapter %)))

          ;; These are in the xsd but ignored by us
          :eigenAangebodenOpleidingSleutel (some-> id str/lower-case) ;; resolve to the ooapi id
          :laatsteInstroomdatum (:lastStartDate consumer)
          :opleidingserkenningSleutel nil
          :voVakerkenningSleutel nil)))))

(defn ->aangeboden-opleiding
  "Converts a program or course into the right kind of AangebodenOpleiding."
  [course-program ooapi-type opleidingscode {::ooapi-v6/keys [specification-type]}]
  {:pre [(string? specification-type)]}
  (-> (course-program-adapter course-program opleidingscode ooapi-type)
      rio-helper/wrapper-periodes-cohorten
      (rio-helper/->xml (specification-type-mapping specification-type))))
