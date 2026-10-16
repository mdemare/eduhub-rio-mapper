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

(ns nl.surf.eduhub-rio-mapper.v6.endpoints.api
  (:require [clojure.string :as str]
            [compojure.core :refer [POST]]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.endpoints.api-middleware :as api-middleware]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi-specs]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.v6.job :as job]))

(def types {"courses"    "course"
            "programmes" "programme"})

;; Actions accepted by the generic `/job/:action/:type/:id` route.  A
;; link job always needs a rio-code, so it is only available through
;; the link and unlink routes.
(def actions #{"upsert" "delete" "dry-run-upsert"})

;; Route param constraints, so that requests with an unknown type or
;; action don't fall through to a more generic route (and fail UUID
;; validation there) but end up as 404 Not Found.
(defn- alternatives-re [ss]
  (re-pattern (str/join "|" (sort ss))))

(def ^:private type-re (alternatives-re (keys types)))

(def ^:private action-re (alternatives-re actions))

(defn- make-job [request action type id]
  {:job (-> request
            (select-keys [:institution-schac-home
                          :institution-name
                          :institution-oin
                          :trace-context])
            (assoc :action      action
                   ::ooapi-specs/type type
                   ::ooapi-specs/id   id))})

(defn job-route [{{:keys [action type id]} :params :as request}]
  (let [type   (types type)
        action (actions action)]
    (when (and type action)
      (make-job request action type id))))

(defn link-route [{{:keys [rio-code type id]} :params :as request}]
  (let [result   (when-let [type (types type)]
                   (make-job request "link" type id))
        codename (if (re-matches #"\d{4}O\d{4}" rio-code) ::rio/opleidingscode ::rio/aangeboden-opleiding-code)]
    (when result
      (assoc-in result [:job codename] rio-code))))

(def ^:private job-routes
  (compojure.core/routes
   ;; Unlink is link to `nil`
   (POST ["/job/unlink/:rio-code/:type" :type type-re] request
     (link-route request))

   (POST ["/job/dry-run/upsert/:type/:id" :type type-re] request
     (job-route (assoc-in request [:params :action] "dry-run-upsert")))

   (POST ["/job/link/:rio-code/:type/:id" :type type-re] request
     (link-route request))

   (POST ["/job/:action/:type/:id" :action action-re :type type-re] request
     (job-route request))))

(def version-options
  "Options for the version-independent middleware.

  See `api-middleware/routes`."
  {:enqueued-status  http-status/created
   :callback-url-key ::job/callback-url})

(defn routes [config]
  (api-middleware/routes job-routes (merge config version-options)))

(defn make-app
  ([config]
   (api-middleware/make-app routes config))
  ([config lifecycle]
   (api-middleware/make-app routes config lifecycle)))

(defn serve-api [config options]
  (api-middleware/serve-api make-app config options))
