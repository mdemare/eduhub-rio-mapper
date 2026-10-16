(ns nl.surf.eduhub-rio-mapper.v6.utils.ooapi
  (:require [clojure.string :as str])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(def date-format (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

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

(defn valid-date? [date]
  (and (string? date)
       (try (let [d (LocalDate/parse date date-format)]
              ;; XSD schema does not accept "Year zero".
              (not (zero? (.getYear d))))
            (catch DateTimeParseException _ false))))
