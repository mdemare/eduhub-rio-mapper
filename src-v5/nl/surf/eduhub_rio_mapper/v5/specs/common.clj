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

(ns nl.surf.eduhub-rio-mapper.v5.specs.common
  "Common specs for use in the ooapi namespaces."
  (:require [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.re-spec :refer [looks-like-html? re-spec text-spec]]
            [nl.surf.eduhub-rio-mapper.utils.uuid :refer [valid-uuid?]]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.common.LongLanguageTypedString :as-alias LongLanguageTypedString]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.enums :as enums]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.LanguageTypedString :as-alias LanguageTypedString]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.LanguageTypedStringEN :as-alias LanguageTypedStringEN]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.LanguageTypedStringNL :as-alias LanguageTypedStringNL]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.LearningOutcome :as-alias LearningOutcome]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.rio-consumer :as-alias rio-consumer]
            [nl.surf.eduhub-rio-mapper.v5.ooapi.StudyLoadDescriptor :as-alias StudyLoadDescriptor]
            [nl.surf.eduhub-rio-mapper.v5.utils.ooapi :as ooapi-utils])
  (:import [java.net URI]))

(s/def ::root-url #(instance? URI %))
(s/def ::type string?)
(s/def ::id string?)
(s/def ::institution-schac-home string?)
(s/def ::gateway-credentials (s/keys :req-un []))

(s/def ::root-url
  (re-spec #"http(s?)://.*"))

(s/fdef education-specification-id
  :args (s/cat :entity ::entity)
  :ret ::id)

(s/def ::date
  (s/and (re-spec #"\d\d\d\d-[01]\d-[0123]\d")
         ooapi-utils/valid-date?))

(s/def ::duration
  (re-spec #"^P(\d+Y)?(\d+M)?(\d+W)?(\d+D)?(T(\d+H)?(\d+M)?(\d+S)?)?$"))

(s/def ::uuid
  (s/and (re-spec #"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
         valid-uuid?))

(s/def ::id
  ::uuid)

;; Common types

(s/def ::educationOffererCode
  (re-spec #"\d\d\dA\d\d\d"))

(s/def ::LanguageTypedString/language
  (re-spec #"^[a-z]{2,4}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$"))

(s/def ::LanguageTypedString/value
  (text-spec 1 1000))

(s/def ::LanguageTypedString
  (s/keys :req-un [::LanguageTypedString/language
                   ::LanguageTypedString/value]))

(s/def ::LanguageTypedStringNL/language
  #{"nl-NL" "nl-BE"})

(s/def ::LanguageTypedStringEN/language
  #{"en-AU"
    "en-BZ"
    "en-CA"
    "en-CB"
    "en-GB"
    "en-IE"
    "en-JM"
    "en-NZ"
    "en-PH"
    "en-TT"
    "en-US"
    "en-ZA"
    "en-ZW"})

(s/def ::nlLanguageTypedString
  (s/keys :req-un [::LanguageTypedStringNL/language
                   ::LanguageTypedString/value]))

(s/def ::enLanguageTypedString
  (s/keys :req-un [::LanguageTypedStringEN/language
                   ::LanguageTypedString/value]))

;; A collection of language typed strings with any set of languages
(s/def ::LanguageTypedStrings
  (s/coll-of ::LanguageTypedString))

(s/def ::LongLanguageTypedString/language
  (re-spec #"^[a-z]{2,4}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$"))

(s/def ::LongLanguageTypedString/value
  (text-spec 1 3000))

(s/def ::LongLanguageTypedString
  (s/keys :req-un [::LongLanguageTypedString/language
                   ::LongLanguageTypedString/value]))

;; A collection of language typed strings with any set of languages
(s/def ::LongLanguageTypedStrings
  (s/coll-of ::LongLanguageTypedString))

;; A collection of language typed strings with at least one dutch or
;; english entry
(s/def ::nlOrEnLanguageTypedStrings
  (s/cat :head (s/* ::LanguageTypedString)
         :nlOrEn (s/or :nl ::nlLanguageTypedString
                       :en ::enLanguageTypedString)
         :tail (s/* ::LanguageTypedString)))

(s/def ::codeType
  (s/or :predefined enums/codeTypes
        :custom (re-spec #"x-[\w.]+")))
(s/def ::code string?)
(s/def ::codeTuple
  (s/keys :req-un [::codeType ::code]))
(s/def ::otherCodes (s/coll-of ::codeTuple))

(s/def ::StudyLoadDescriptor/value number?)
(s/def ::StudyLoadDescriptor/studyLoadUnit enums/studyLoadUnits)
(s/def ::studyLoad
  (s/keys :req-un
          [::StudyLoadDescriptor/studyLoadUnit
           ::StudyLoadDescriptor/value]))

;; XSD says 0-999 for ISCED, so broad/narrow fields.  OOAPI spec def
;; is 4 digits, so it accepts detailed fields. See also
;; `nl.surf.eduhub-rio-mapper.rio/narrow-isced`
(s/def ::fieldsOfStudy (re-spec #"\d{1,4}"))
;; Learning outcomes have no maximum length, otherwise same rules as
;; LanguageTypedString.
(s/def ::LearningOutcome/value
  (s/and string?
         seq
         #(not (looks-like-html? %))))

(s/def ::LearningOutcome
  (s/keys :req-un [::LanguageTypedString/language
                   ::LearningOutcome/value]))

(s/def ::learningOutcomes (s/coll-of (s/coll-of ::LearningOutcome)))
(s/def ::level enums/levels)
(s/def ::levelOfQualification #{"1" "2" "3" "4" "4+" "5" "6" "7" "8"})

(s/def ::modeOfDelivery
   (s/and (s/coll-of enums/modesOfDelivery)
         ;; for RIO at least one of the below is required
         #(some #{"online" "hybrid" "situated" "distance-learning" "on campus"} %)))

(s/def ::sector enums/sectors)

;; Address
(s/def ::additional any?)
(s/def ::addressType #{"postal" "visit" "deliveries" "billing" "teaching"})
(s/def ::city (text-spec 1 40))
(s/def ::countryCode (re-spec #"[a-zA-Z]{2}"))
(s/def ::geolocation (s/keys :req-un [::latitude ::longitude]))
(s/def ::latitude number?)
(s/def ::longitude number?)
;; Dutch postcode format, since that's what RIO accepts. Note that in
;; rio, the postcalCode should not contain any whitespace.
(s/def ::postalCode (re-spec #"[1-9]\d{3}\s*[A-Z]{2}"))

;; note that the `street` address field is never used in RIO, so not
;; specced for the mapper

;; The streetNumber in OOAPI is a number + "huisletter"
;; of "nummertoevoeging". In RIO huisnummers can be 1 - 99999 and
;; toevoegingen are 6 chars, separated by dash or space.

(s/def ::streetNumber
  (s/or :num int?
        :str (s/and (re-spec #"[1-9]\d{0,3}((-| )\S{1,6})?")
                    #(not (looks-like-html? %)))))

(s/def ::address (s/keys :req-un [::addressType]
                         :opt-un [::additional
                                  ::city
                                  ::countryCode
                                  ::geolocation
                                  ::postalCode
                                  ::streetNumber]))
(s/def ::addresses (s/coll-of ::address))

;; Consumers have at least a consumerKey entry

(s/def ::consumerKey
  string?)

;; A generic consumer
(s/def ::consumer
  (s/keys :req-un [::consumerKey]))

(s/def ::rio-consumer/consumerKey
  #{"rio"})

;; A consumer with consumerKey "rio"
(s/def ::rio-consumer
  (s/keys :req-un [::rio-consumer/consumerKey]))

(defn extract-req-attrs [spec]
  (let [spec-desc (s/describe spec)]
    (assert (= :req-un (nth spec-desc 1)))
    (nth spec-desc 2)))

(defn extract-opt-attrs [spec]
  (let [spec-desc (s/describe spec)]
    (assert (= :opt-un (nth spec-desc 3)))
    (nth spec-desc 4)))
