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

(ns nl.surf.eduhub-rio-mapper.v6.specification-type-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.v6.commands.processing :as processing]
            [nl.surf.eduhub-rio-mapper.v6.ooapi.loader :as ooapi.loader]
            [nl.surf.eduhub-rio-mapper.v6.rio.aangeboden-opleiding :as aangeboden-opl]
            [nl.surf.eduhub-rio-mapper.v6.rio.opleidingseenheid :as opl-eenh]
            [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]))

(def expected
  {"course"    {:oe :duo:hoOnderwijseenheid         :soort nil         :ao :duo:aangebodenHOOpleidingsonderdeel}
   "programme" {:oe :duo:hoOpleiding                :soort "OPLEIDING" :ao :duo:aangebodenHOOpleiding}
   "variant"   {:oe :duo:hoOpleiding                :soort "VARIANT"   :ao :duo:aangebodenHOOpleiding}
   "private"   {:oe :duo:particuliereOpleiding      :soort nil         :ao :duo:aangebodenParticuliereOpleiding}
   "cluster"   {:oe :duo:hoOnderwijseenhedencluster :soort "HOEC"      :ao :duo:aangebodenHOOpleidingsonderdeel}})

(def ^:private quick-validate #'processing/quick-validate)

(defn- validation-error [specification-type]
  (try
    (quick-validate {:programmeType "specification"
                     :consumer      {:specificationType specification-type}}
                    "programme")
    nil
    (catch clojure.lang.ExceptionInfo ex
      (ex-message ex))))

(defn- progspec [specification-type]
  (-> {::ooapi/id "10010000-0000-0000-0000-000000000000" ::ooapi/type "programme"}
      ooapi.loader/ooapi-file-loader
      (assoc-in [:consumer :specificationType] specification-type)))

(defn- soort [oe-sexp]
  (some (fn [el]
          (when (and (vector? el)
                     (= :duo:kenmerken (first el))
                     (= [:duo:kenmerknaam "soort"] (second el)))
            (second (nth el 2))))
        oe-sexp))

(defn- course [] (ooapi.loader/ooapi-file-loader {::ooapi/id "30010000-0000-0000-0000-000000000000" ::ooapi/type "course"}))

(deftest specification-types-are-accepted-and-mapped
  (is (= (set (keys expected)) (set (keys ooapi-v6/specification-types))))
  (doseq [[specification-type {oe :oe expected-soort :soort ao :ao}] expected]
    (testing specification-type
      (is (s/valid? ::ooapi-v6/specification-type specification-type))
      (is (nil? (validation-error specification-type)))
      (let [oe-sexp (opl-eenh/education-specification->opleidingseenheid (progspec specification-type))]
        (is (= oe (first oe-sexp)))
        (is (= expected-soort (soort oe-sexp))))
      (is (= ao (first (aangeboden-opl/->aangeboden-opleiding (course) :course "1234O1234"
                                                              {::ooapi-v6/specification-type specification-type})))))))

(deftest unknown-specification-types-are-rejected
  (is (= "Invalid programme specification: specificationType required"
         (validation-error nil)))
  (is (= "Invalid programme specification: specificationType value 'foo' not allowed"
         (validation-error "foo")))
  (is (not (s/valid? ::ooapi-v6/specification-type "foo")))
  (is (nil? (quick-validate {:programmeType "program" :consumer {:specificationType "foo"}} "programme")))
  (is (nil? (quick-validate {:programmeType "specification" :consumer {:specificationType "foo"}} "course"))))

(deftest every-specification-type-has-a-complete-mapping
  (doseq [[specification-type mapping] ooapi-v6/specification-types]
    (testing specification-type
      (is (= #{:opleidingseenheid :soort :aangeboden-opleiding} (set (keys mapping))))
      (is (string? (:opleidingseenheid mapping)))
      (is (string? (:aangeboden-opleiding mapping))))))
