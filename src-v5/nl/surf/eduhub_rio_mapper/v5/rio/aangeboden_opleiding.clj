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

(ns nl.surf.eduhub-rio-mapper.v5.rio.aangeboden-opleiding
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.duration :as duration]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.v5.rio.helper :as rio-helper-v5]
            [nl.surf.eduhub-rio-mapper.v5.utils.ooapi :as ooapi-utils]))

(def education-specification-type-mapping
  {"course"         "aangebodenHOOpleidingsonderdeel"
   "cluster"        "aangebodenHOOpleidingsonderdeel"
   "program"        "aangebodenHOOpleiding"
   "privateProgram" "aangebodenParticuliereOpleiding"})

(def ^:private mapping-course-program->aangeboden-opleiding
  {:buitenlandsePartner [:foreignPartners true]
   :eersteInstroomDatum [:firstStartDate false]
   :onderwijsaanbiedercode [:educationOffererCode true]
   :onderwijslocatiecode [:educationLocationCode true]})

(def ^:private mapping-offering->cohort
  {:beginAanmeldperiode :enrollStartDate
   :deelnemersplaatsen :maxNumberStudents
   :einddatum :endDate
   :eindeAanmeldperiode :enrollEndDate
   :toelichtingVereisteToestemming :explanationRequiredPermission})

(defn- course-program-timeline-override-adapter
  [{:keys [name description validFrom abbreviation link consumers] :as _periode}]
  (let [{:keys [acceleratedRoute deficiency foreignPartners jointPartnerCodes propaedeuticPhase
                requirementsActivities studyChoiceCheck]} (ooapi-utils/extract-rio-consumer consumers)]
    (fn [pk]
      (case pk
        :begindatum validFrom
        :buitenlandsePartner foreignPartners
        :deficientie (rio-helper-v5/ooapi-mapping "deficientie" deficiency)
        :eigenNaamAangebodenOpleiding (ooapi-utils/get-localized-value name ["nl-NL" "nl"])
        :eigenNaamInternationaal (ooapi-utils/get-localized-value-exclusive name ["en"])
        :eigenNaamKort abbreviation
        :eigenOmschrijving (ooapi-utils/get-localized-value description ["nl-NL" "nl"])
        :eisenWerkzaamheden (rio-helper-v5/ooapi-mapping "eisenWerkzaamheden" requirementsActivities)
        :internationaleNaamDuits (ooapi-utils/get-localized-value-exclusive name ["de"])
        :propedeutischeFase (rio-helper-v5/ooapi-mapping "propedeutischeFase" propaedeuticPhase)
        :samenwerkendeOnderwijsaanbiedercode jointPartnerCodes
        :studiekeuzecheck (rio-helper-v5/ooapi-mapping "studiekeuzecheck" studyChoiceCheck)
        :versneldTraject (rio-helper-v5/ooapi-mapping "versneldTraject" acceleratedRoute)
        :website link))))

;; Non-standard mapping for modeOfDelivery
;; See also https://github.com/open-education-api/specification/issues/295
(def consumer-modeOfDelivery-mapping
  {"online" "ONLINE"
   "hybrid" "KLASSIKAAL_EN_ONLINE"
   "situated" "KLASSIKAAL"
   "lecture" "LEZING"
   "self-study" "ZELFSTUDIE"
   "coaching" "COACHING"})

;; modeOfDelivery in rio-consumer of the offering has precedence over the one in the offering itself.
(defn- extract-opleidingsvorm [modeOfDelivery rio-consumer]
  (let [consumer-modeOfDelivery (:modeOfDelivery rio-consumer)
        mapped-values (if consumer-modeOfDelivery
                        (map consumer-modeOfDelivery-mapping consumer-modeOfDelivery)
                        (map #(rio-helper-v5/ooapi-mapping "opleidingsvorm" %) modeOfDelivery))]
    (first (filter seq mapped-values))))

(defn- course-program-offering-adapter
  [{:keys [consumers startDate modeOfDelivery priceInformation
           flexibleEntryPeriodStart flexibleEntryPeriodEnd] :as offering}]
  (let [{:keys [registrationStatus requiredPermissionRegistration]
         :as   rio-consumer} (ooapi-utils/extract-rio-consumer consumers)]
    (fn [ck]
      (if-let [translation (mapping-offering->cohort ck)]
        (translation offering)
        (case ck
          :bedrijfsopleiding nil    ; ignored
          :cohortcode (-> offering :primaryCode :code)
          :cohortstatus (rio-helper-v5/ooapi-mapping "cohortStatus" registrationStatus)
          :flexibeleInstroom (and flexibleEntryPeriodStart {:beginInstroomperiode flexibleEntryPeriodStart
                                                  :eindeInstroomperiode flexibleEntryPeriodEnd})
          :opleidingsvorm (extract-opleidingsvorm modeOfDelivery rio-consumer)
          :prijs (mapv (fn [h] {:soort (rio-helper-v5/ooapi-mapping "soort" (:costType h)) :bedrag (:amount h)})
             priceInformation)
          :toestemmingVereistVoorAanmelding (rio-helper-v5/ooapi-mapping "toestemmingVereistVoorAanmelding"
                                                                      requiredPermissionRegistration)
          :vastInstroommoment (when (nil? flexibleEntryPeriodStart) {:instroommoment startDate}))))))

(defn- course-program-adapter
  "Given a course or program, a rio-consumer object and an id, return a function.
   This function, given a attribute name from the RIO namespace, returns the corresponding value from the course or program,
   translated if necessary to the RIO domain."
  [{:keys [rioCode validFrom validTo offerings level modeOfStudy fieldsOfStudy consumers teachingLanguage timelineOverrides] :as course-program}
   opleidingscode
   ooapi-type]
  (let [rio-consumer (ooapi-utils/extract-rio-consumer consumers)
        sector       (:sector rio-consumer)
        duration-map (some-> rio-consumer :duration duration/parse-duration)
        id           ((if (= :course ooapi-type) :courseId :programId) course-program)
        periods      (map #(assoc (ooapi-type %)
                             :validFrom (:validFrom %)
                             :validTo   (:validTo %))
                          timelineOverrides)]
    (fn [k] {:pre [(keyword? k)]}
      (if-let [[translation consumer] (mapping-course-program->aangeboden-opleiding k)]
        (if (rio-helper-v5/ooapi-mapping? (name k))
          (rio-helper-v5/ooapi-mapping (name k) (translation (if consumer rio-consumer course-program)))
          (translation (if consumer rio-consumer course-program)))
        (case k
          :opleidingseenheidSleutel opleidingscode
          ;; Required field. If found in the resolve phase, will be added to the entity under the rioCode key,
          ;; otherwise use the eigen sleutel value (an UUID).
          :aangebodenOpleidingCode (or rioCode id)
          ;; See opleidingseenheid for explanation of timelineOverrides and periods.
          :begindatum (first (sort (conj (map :validFrom timelineOverrides) validFrom)))
          :einddatum (last (sort (conj (map :validTo timelineOverrides) validTo)))
          :ISCED (rio-helper/narrow-isced fieldsOfStudy)
          :afwijkendeOpleidingsduur (when duration-map {:opleidingsduurEenheid (:eenheid duration-map)
                                                        :opleidingsduurOmvang  (:omvang duration-map)})
          :niveau (rio-helper-v5/level-sector-mapping level sector)
          :vorm (rio-helper-v5/ooapi-mapping "vorm" modeOfStudy)
          :voertaal (rio-helper-v5/ooapi-mapping
                      "voertaal"
                      (or (:teachingLanguages rio-consumer)
                          teachingLanguage))

          :cohorten (mapv #(course-program-offering-adapter %)
                          offerings)

          ;; See opleidingseenheid for explanation of timelineOverrides and periods.
          :periodes (->> (conj periods course-program)
                         (mapv #(course-program-timeline-override-adapter %)))

          ;; These are in the xsd but ignored by us
          :eigenAangebodenOpleidingSleutel (some-> id str/lower-case) ;; resolve to the ooapi id
          :laatsteInstroomdatum (:lastStartDate rio-consumer)
          :opleidingserkenningSleutel nil
          :voVakerkenningSleutel nil)))))

(defn ->aangeboden-opleiding
  "Converts a program or course into the right kind of AangebodenOpleiding."
  [course-program ooapi-type opleidingscode education-specification-type]
  {:pre [(string? education-specification-type)]}
  (-> (course-program-adapter course-program opleidingscode ooapi-type)
      rio-helper/wrapper-periodes-cohorten
      (rio-helper/->xml (education-specification-type-mapping education-specification-type))))
