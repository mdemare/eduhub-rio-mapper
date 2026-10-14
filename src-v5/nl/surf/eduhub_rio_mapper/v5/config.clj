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

(ns nl.surf.eduhub-rio-mapper.v5.config
  "Wrapper around `nl.surf.eduhub-rio-mapper.config` for OOAPI v5."
  (:require [environ.core :refer [env]]
            [nl.surf.eduhub-rio-mapper.config :as config]
            [nl.surf.eduhub-rio-mapper.v5.commands.processing :as processing]
            [nl.surf.eduhub-rio-mapper.v5.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.v5.job :as job]))

(def version "v5")

(def opts-spec (config/make-opts-spec version))

(def env-keys-with-alternate-file-secret
  (config/env-keys-with-alternate-file-secret version))

(defn help []
  (config/help version))

(defn load-config-from-env [env-map]
  (config/load-config-from-env version env-map))

(defn make-config
  ([]
   (make-config env))
  ([env]
   (config/make-config version env)))

(def version-fns
  {:version            version
   :make-handlers      processing/make-handlers
   :make-set-status-fn status/make-set-status-fn
   :run-job            job/run!
   :retryable?         status/retryable?
   :errors?            status/errors?})

(defn make-config-and-handlers-web []
  (config/make-config-and-handlers-web version-fns))

(defn make-config-and-handlers-worker []
  (config/make-config-and-handlers-worker version-fns))
