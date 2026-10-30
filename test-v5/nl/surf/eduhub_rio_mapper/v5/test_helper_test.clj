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

(ns nl.surf.eduhub-rio-mapper.v5.test-helper-test
  (:require [clojure.test :refer [deftest is]]
            [environ.core :as environ]
            [nl.surf.eduhub-rio-mapper.v5.test-helper :as helper]))

(deftest playback-config-ignores-environment
  (let [empty-config (with-redefs [environ/env {}] (helper/make-test-config))
        polluted-config (with-redefs [environ/env {:clients-info-path "missing-live-clients.json"
                                                 :keystore "missing-live-keystore.jks"
                                                 :gateway-password-v5-file "missing-live-secret"
                                                 :rio-read-url "https://live.example/"
                                                 :surf-conext-client-secret "live-secret"}]
                          (helper/make-test-config))]
    (doseq [config [empty-config polluted-config]]
      (is (= "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0"
             (get-in config [:rio-config :read-url])))
      (is (= "https://vt-webservice.duo.nl:6977/RIO/services/beheren4.0"
             (get-in config [:rio-config :update-url])))
      (is (= "https://gateway.test.surfeduhub.nl/" (:gateway-root-url config)))
      (is (= {:username "dummy" :password "dummy"} (:gateway-credentials config)))
      (is (= "dummy" (get-in config [:auth-config :client-secret])))
      (is (seq (:clients config)))
      (is (some? (get-in config [:rio-config :credentials :certificate]))))))
