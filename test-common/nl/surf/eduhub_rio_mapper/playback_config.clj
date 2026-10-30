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

(ns nl.surf.eduhub-rio-mapper.playback-config)

;; These endpoints match the committed legacy cassettes. Playback must never
;; inherit live settings or secret-file overrides from the developer's environment.
(def environment
  {:clients-info-path "test/test-clients.json"
   :keystore "test/keystore.jks"
   :keystore-password "xxxxxx"
   :keystore-alias "test-surf"
   :gateway-root-url-v5 "https://gateway.test.surfeduhub.nl/"
   :gateway-root-url-v6 "https://gateway-v6-sandbox.dev.surfeduhub.nl/"
   :gateway-user-v5 "dummy"
   :gateway-password-v5 "dummy"
   :gateway-user-v6 "dummy"
   :gateway-password-v6 "dummy"
   :rio-read-url "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0"
   :rio-update-url "https://vt-webservice.duo.nl:6977/RIO/services/beheren4.0"
   :rio-recipient-oin "0000000700025BE00000"
   :surf-conext-introspection-endpoint "https://conext.example/introspect"
   :surf-conext-client-id "dummy"
   :surf-conext-client-secret "dummy"})
