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

(ns nl.surf.eduhub-rio-mapper.v6.test-helper-test
  (:require [clojure.test :refer [deftest is testing]]
            [environ.core :as environ]
            [nl.surf.eduhub-rio-mapper.v6.config :as config]
            [nl.surf.eduhub-rio-mapper.v6.test-helper :as helper]))

(deftest playback-ignores-live-environment
  (doseq [environment [{} {:gateway-root-url-v6 "https://live.invalid/"
                          :clients-info-path "missing-clients.json"
                          :keystore-password-file "missing-secret"
                          :rio-read-url "https://live.invalid/rio"}]]
    (testing (str environment)
      ;; Capture input so missing options cannot terminate the regression JVM.
      (let [settings (with-redefs [environ/env environment
                                  config/make-config identity]
                       (helper/make-test-config))]
        (is (= "test/test-clients.json" (:clients-info-path settings)))
        (is (= "https://gateway-v6-sandbox.dev.surfeduhub.nl/" (:gateway-root-url-v6 settings)))
        (is (= "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0"
               (:rio-read-url settings)))
        (is (nil? (:keystore-password-file settings))))
      (let [result (with-redefs [environ/env environment]
                     (helper/make-test-config))]
        (is (some? (get-in result [:rio-config :credentials :certificate])))
        (is (= "playback" (get-in result [:gateway-credentials :password])))
        (is (some #(= "rio-mapper-dev.jomco.nl" (:client-id %))
                  (:clients result)))))))
