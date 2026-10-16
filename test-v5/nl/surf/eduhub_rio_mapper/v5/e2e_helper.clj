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

(ns nl.surf.eduhub-rio-mapper.v5.e2e-helper
  "E2e test helpers bound to the v5 mapper; see `e2e-helper-common`."
  (:require [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.e2e-helper-common :as common]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities]
            [nl.surf.eduhub-rio-mapper.v5.config :as config]))

(defn ooapi-id
  "Get OOAPI UUID of automatically uploaded fixture."
  [type id]
  (let [name (str (name type) "/" id)]
    (get remote-entities/*session* name)))

(def ^:private helper
  (common/make-helper {:make-config     config/make-config
                       :ooapi-id        ooapi-id
                       :post-job-status http-status/ok
                       :posted-jobs-log "logs/e2e-v5-posted-jobs.log"
                       :mapper-alias    "-M:mapper"}))

(def config (:config helper))
(def base-url (:base-url helper))

(defmacro print-boxed
  "Print pretty box around output of evaluating `form`."
  [title & form]
  `(common/print-boxed ~title ~@form))

(def print-single-http-message common/print-single-http-message)
(def print-http-messages common/print-http-messages)

(def job-status-poll-sleep-msecs common/job-status-poll-sleep-msecs)
(def job-status-poll-total-msecs common/job-status-poll-total-msecs)

(def post-job (partial common/post-job helper))

;; The `test/assert-expr` methods for these are registered in
;; `e2e-helper-common`.
(def job-result common/job-result)
(def job-result-status common/job-result-status)
(def job-result-attributes common/job-result-attributes)
(def job-result-opleidingseenheidcode common/job-result-opleidingseenheidcode)
(def job-result-aangebodenopleidingcode common/job-result-aangebodenopleidingcode)
(def job-has-diffs? common/job-has-diffs?)
(def job-without-diffs? common/job-without-diffs?)
(def job-done? common/job-done?)
(def job-error? common/job-error?)
(def job-dry-run-found? common/job-dry-run-found?)
(def job-dry-run-not-found? common/job-dry-run-not-found?)

(def rio-resolve (partial common/rio-resolve helper))
(def rio-relations (partial common/rio-relations helper))
(def rio-with-relation? (partial common/rio-with-relation? helper))
(def rio-opleidingseenheid (partial common/rio-opleidingseenheid helper))
(def eigen-opleidingseenheid-sleutel (partial common/eigen-opleidingseenheid-sleutel helper))
(def rio-aangebodenopleiding (partial common/rio-aangebodenopleiding helper))
(def eigen-aangeboden-opleiding-sleutel (partial common/eigen-aangeboden-opleiding-sleutel helper))
(def element->edn common/element->edn)
(def kenmerken-values-aangeboden-opleiding common/kenmerken-values-aangeboden-opleiding)
(def get-in-xml common/get-in-xml)
(def get-all-in-xml common/get-all-in-xml)

(def services common/services)
(def start-services (partial common/start-services helper))
(def stop-services (partial common/stop-services helper))
(def wait-for-serve-api-sleep-msec common/wait-for-serve-api-sleep-msec)
(def wait-for-serve-api-total-msec common/wait-for-serve-api-total-msec)
(def with-running-mapper (partial common/with-running-mapper helper))
