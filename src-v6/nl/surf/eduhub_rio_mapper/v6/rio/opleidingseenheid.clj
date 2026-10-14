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

(ns nl.surf.eduhub-rio-mapper.v6.rio.opleidingseenheid
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.rio.helper :as rio-helper]
            [nl.surf.eduhub-rio-mapper.v6.rio.helper :as rio-helper-v6]
            [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]
            [nl.surf.eduhub-rio-mapper.v6.utils.ooapi :as ooapi-utils]))

(defn- soort-mapping [{:keys [consumer]}]
  (get-in ooapi-v6/specification-types [(:specificationType consumer) :soort]))

(defn- programme-specification-timeline-override-adapter
  [{:keys [abbreviation description formalDocument name studyLoad validFrom] :as prgspec}]
  (fn [pk]
    (case pk
      :begindatum (rio-helper/datetime->date validFrom)
      :internationaleNaam (ooapi-utils/get-localized-value-exclusive name ["en"])
      :naamKort abbreviation
      :naamLang (ooapi-utils/get-localized-value name ["nl-NL" "nl"])
      :omschrijving (ooapi-utils/get-localized-value description ["nl-NL" "nl"])
      :studielast (if (= "VARIANT" (soort-mapping prgspec)) nil (:value (first studyLoad)))
      :studielasteenheid (rio-helper-v6/ooapi-mapping "studielasteenheid" (:studyLoadUnit (first studyLoad)))
      :waardedocumentsoort (rio-helper-v6/ooapi-mapping "waardedocumentsoort" formalDocument))))

(def ^:private mapping-progspec->opleidingseenheid
  {:eigenOpleidingseenheidSleutel #(some-> % :programmeId str/lower-case)
   :opleidingseenheidcode         :rioCode})

(defn- programme-specification-adapter
  [{:keys [validFrom validTo formalDocument level levelOfQualification fieldsOfStudy timelineOverrides] :as progspec}
   {:keys [category sector] :as rio-consumer}]
  (fn [opl-eenh-attr-name]
    (let [programme-type (:specificationType rio-consumer)
          periods     (ooapi-utils/ooapi-to-periods progspec :programme)
          translation (mapping-progspec->opleidingseenheid opl-eenh-attr-name)
          ;; As of November 1st, 2025, RIO no longer returns NLQF/EQF fields for Particuliere Opleidingen
          is-private-program? (= programme-type "private")]
      (if translation
        (translation progspec)
        (case opl-eenh-attr-name
          ;; The main education specification object represents the current situation, while the timelineOverrides
          ;; specify past and future states. However, in RIO's opleidingseenheid, the main object's begindatum and
          ;; einddatum represent the entire lifespan of an opleidingseenheid, while its periodes represent each
          ;; temporary state. Therefore, we calculate the lifespan of an opleidingseenheid below.
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
          :categorie (rio-helper-v6/ooapi-mapping "categorie" category)
          ;; NLQF/EQF fields are not used for Particuliere Opleidingen (private programs)
          :eqf (when-not is-private-program? (rio-helper-v6/ooapi-mapping "eqf" levelOfQualification))
          :niveau (rio-helper-v6/level-sector-mapping level sector)
          :nlqf (when-not is-private-program? (rio-helper-v6/ooapi-mapping "nlqf" levelOfQualification))
          ;; progspec itself is used to represent the main object without adaptations from timelineOverrides.
          :periodes (mapv programme-specification-timeline-override-adapter periods)
          :soort (soort-mapping progspec)
          :waardedocumentsoort (rio-helper-v6/ooapi-mapping "waardedocumentsoort" formalDocument))))))

(defn education-specification->opleidingseenheid
  "Converts a programme specification into the right kind of Opleidingseenheid."
  [{:keys [consumer] :as progspec}]
  (when (nil? (:specificationType consumer))
    (throw (ex-info "No specificationType in rio-consumer" {:rio-consumer consumer})))
  (-> (programme-specification-adapter progspec consumer)
      rio-helper/wrapper-periodes-cohorten
      (rio-helper/->xml (get-in ooapi-v6/specification-types [(:specificationType consumer) :opleidingseenheid]))))
