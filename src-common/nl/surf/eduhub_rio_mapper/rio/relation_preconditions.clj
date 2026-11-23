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

(ns nl.surf.eduhub-rio-mapper.rio.relation-preconditions
  (:require [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]))

(defn- unavailable! [parent-id institution-oin reason retryable? cause]
  (throw (ex-info (str "Cannot reconcile variant relations: parent " parent-id
                       " is unavailable (" (name reason) ")")
                  {:error :relation-parent-unavailable :reason reason
                   :parent-id parent-id :institution-oin institution-oin
                   :retryable? retryable?}
                  cause)))

(defn- parent-read [parent-id institution-oin stage f]
  (try
    (f)
    (catch Exception ex
      (if (= :relation-parent-unavailable (:error (ex-data ex)))
        (throw ex)
        (let [status (:status (ex-data ex))]
          (unavailable! parent-id institution-oin
                        (if (and (= :ooapi stage) (= 404 status)) :missing-ooapi-parent :parent-read-failed)
                        (and (not (#{401 403 404} status))
                             (not= false (:retryable? (ex-data ex))))
                        ex))))))

(defn parent-reference! [parent-id institution-oin]
  (when-not parent-id
    (unavailable! parent-id institution-oin :unresolved-parent false nil))
  parent-id)

(defn resolve-parent! [parent-id institution-oin resolver]
  (parent-reference! parent-id institution-oin)
  (parent-read parent-id institution-oin :resolve
               #(let [code (resolver :oe parent-id institution-oin)]
                  (when-not code
                    (unavailable! parent-id institution-oin :unresolved-parent false nil))
                  (when-not (and (string? code) (seq code))
                    (unavailable! parent-id institution-oin :parent-read-failed true nil))
                  code)))

(defn require-parent! [parent-id institution-oin primary-key load-parent add-rio-code]
  (let [entity (parent-read parent-id institution-oin :ooapi #(load-parent parent-id))]
    (when-not entity
      (unavailable! parent-id institution-oin :missing-ooapi-parent false nil))
    (when-not (and (map? entity) (= parent-id (primary-key entity)) (string? (:validFrom entity)))
      (unavailable! parent-id institution-oin :parent-read-failed true nil))
    (let [parent (parent-read parent-id institution-oin :resolve #(add-rio-code entity))
          code (::rio/opleidingscode parent)]
      (when-not code
        (unavailable! parent-id institution-oin :unresolved-parent false nil))
      (when-not (and (string? code) (seq code))
        (unavailable! parent-id institution-oin :parent-read-failed true nil))
      parent)))
