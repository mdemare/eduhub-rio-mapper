(ns nl.surf.eduhub-rio-mapper.v5.utils.ooapi
  (:require [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.v5.rio.helper :as rio-helper-v5])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn level-sector-map-to-rio?
  "True if we can map the given level and sector to RIO."
  [{:keys [level sector educationSpecificationType]}]
  (or (= "privateProgram" educationSpecificationType)
      (some? (rio-helper-v5/level-sector-mapping level sector))))

(defn get-localized-value-exclusive
  "Get localized value from LanguageTypedString.

  The provided locales are tried in order. There is no fallback"
  [attr & [locales]]
  (->> locales
       (keep (fn [locale]
               (some #(when (str/starts-with? (% :language) locale)
                        (% :value))
                     attr)))
       first))

(defn get-localized-value
  "Get localized value from LanguageTypedString.

  The provided locales are tried in order. If none found, fall back to
  English (international).  If still none found take the first."
  [attr & [locales]]
  (or
    (get-localized-value-exclusive attr (concat locales ["en"]))
    (-> attr first :value)))

(defn ooapi-to-periods [{:keys [timelineOverrides] :as ooapi} entity-key]
  (as-> timelineOverrides $
        (map
          #(assoc (entity-key %)
             :validFrom (:validFrom %)
             :validTo (:validTo %))
          $)
        (conj $ ooapi)))

(defn current-period [periods attr-key]
  (let [current-date (.format date-format (LocalDate/now))]
    (->> periods
         (filter #(neg? (compare (attr-key %) current-date)))
         (sort-by attr-key)
         last)))

(defn extract-rio-consumer
  "Find the first consumer with a consumerKey equal to 'rio' or return nil."
  [consumers]
  (some->> consumers
           (filter #(= (:consumerKey %) "rio"))
           first))

(defn valid-date? [date]
  (and (string? date)
       (try (let [d (LocalDate/parse date date-format)]
              ;; XSD schema does not accept "Year zero".
              (not (zero? (.getYear d))))
            (catch DateTimeParseException _ false))))

(defn has-mode-of-delivery? [x]
  (or (:modeOfDelivery x)
      (some #(and (:modeOfDelivery %)
                  (= "rio" (:consumerKey %)))
            (:consumers x))))

(defn has-registration-status? [x]
  (some #(and (#{"open" "closed"} (:registrationStatus %))
              (= "rio" (:consumerKey %)))
        (:consumers x)))

(defn valid-type-and-subtype?
  "EducationSpecification should only have subType if type is 'program'."
  [{:keys [educationSpecificationType consumers]}]
  (let [{:keys [educationSpecificationSubType] :as rio-consumer} (extract-rio-consumer consumers)]
    (or (and (= educationSpecificationType "program")
             (= educationSpecificationSubType "variant"))
        (not (contains? rio-consumer :educationSpecificationSubType)))))

(defn not-equal-to-parent?
  [{:keys [educationSpecificationId parent]}]
  (not= educationSpecificationId parent))
