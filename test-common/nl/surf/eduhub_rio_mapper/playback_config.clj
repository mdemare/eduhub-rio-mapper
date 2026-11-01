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

(def environment
  "Public fixture settings for offline playback, independent of live secrets."
  {:clients-info-path "test/test-clients.json"
   :keystore "test/keystore.jks"
   :keystore-password "xxxxxx"
   :keystore-alias "test-surf"
   :rio-read-url "https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0"
   :rio-update-url "https://vt-webservice.duo.nl:6977/RIO/services/beheren4.0"
   :rio-recipient-oin "00000000000000000000"
   :gateway-root-url-v5 "https://gateway.test.surfeduhub.nl/"
   :gateway-root-url-v6 "https://gateway-v6-sandbox.dev.surfeduhub.nl/"
   :gateway-user-v5 "playback"
   :gateway-password-v5 "playback"
   :gateway-user-v6 "playback"
   :gateway-password-v6 "playback"
   :surf-conext-introspection-endpoint "https://example.invalid/introspection"
   :surf-conext-client-id "playback"
   :surf-conext-client-secret "playback"})
