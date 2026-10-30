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

(ns nl.surf.eduhub-rio-mapper.v6.ho-opleiding-variant-test
  (:require [clojure.test :refer [deftest use-fixtures]]
            [environ.core :refer [env]]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.ho-variant-helper :as variant]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
            [nl.surf.eduhub-rio-mapper.v6.commands.processing :as processing]
            [nl.surf.eduhub-rio-mapper.v6.config :as config]
            [nl.surf.eduhub-rio-mapper.v6.job :as job]
            [nl.surf.eduhub-rio-mapper.v6.rio.relation-handler :as relation-handler]
            [nl.surf.eduhub-rio-mapper.vcr-helper :as vcr]))

(use-fixtures :once
  (partial variant/fixture "v6" "programmes/specification-ho-variant-parent"
           (fn []
             (let [config (config/make-config env)
                   client (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")]
               {:handlers (processing/make-handlers config)
                :institution-oin (:institution-oin client)
                :parent-code (:ho-variant-parent-code env)}))
           relation-handler/relation-mutation))

(deftest ^:ho-opleiding-variant accredited-parent-variant-test
  (let [config (config/make-config (if (= :record vcr/vcr-mode) env variant/playback-environment))
        client (clients-info/client-info (:clients config) "rio-mapper-dev.jomco.nl")
        handlers (processing/make-handlers config)
        run-job (fn [type id action]
                  (job/run! handlers
                            (merge client
                                   {::ooapi/id id ::ooapi/type type
                                    ::ooapi/root-url (:gateway-root-url config)
                                    :gateway-credentials (:gateway-credentials config)
                                    :action action})
                            false))]
    (variant/run-scenario! "v6" handlers (:institution-oin client) run-job
                           "programmes/specification-ho-variant-parent" "programmes/specification-ho-variant-child" "programmes/ho-variant-offered"
                           "programme" "programme")))
