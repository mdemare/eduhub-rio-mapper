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

(ns nl.surf.eduhub-rio-mapper.redis-test-helper
  "Redis connection settings shared by the tests tagged `^:redis`."
  (:import [java.util UUID]))

(def redis-conn
  {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}})

;; Tests purge their queues, locks and statuses, so each test run gets
;; its own key prefix.  This keeps concurrent runs (other worktrees, CI
;; jobs) on the same Redis from purging or stealing each other's jobs.
(def redis-key-prefix
  (str "eduhub-rio-mapper-test-" (UUID/randomUUID)))
