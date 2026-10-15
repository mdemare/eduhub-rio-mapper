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

(ns nl.surf.eduhub-rio-mapper.v5.main
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.v5.cli-commands :as cli-commands]
            [nl.surf.eduhub-rio-mapper.v5.config :as config])
  (:gen-class))

(def commands
  #{"upsert" "delete" "delete-by-code" "get" "show" "resolve" "serve-api" "worker" "help" "dry-run-upsert" "link"
    "document-env-vars" "test-rio"})

(defn -main
  [command & args]
  (when (not (commands command))
    (.println *err* (str "Invalid command '" command "'."))
    (.println *err* (str "Valid commands are: " (str/join ", " commands)))
    (System/exit 1))

  (when (= command "help")
    (println (str "Available commands: " (str/join ", " commands) "."))
    (println "Configuration settings via environment:\n")
    (println (config/help))
    (System/exit 0))

  (let [result (cli-commands/process-command command args (if (= command "worker")
                                                            (config/make-config-and-handlers-worker)
                                                            (config/make-config-and-handlers-web)))]
    (case command
      ("serve-api" "worker" "show" "test-rio")
      nil

      "get"
      (if (string? result) (println result)
                           (pprint/pprint result))

      ("dry-run-upsert" "link")
      (pprint/pprint result)

      ("resolve" "document-env-vars")
      (println result)

      ("upsert" "delete" "delete-by-code")
      (if (status/errors? result)
        (binding [*out* *err*]
          (prn result))
        (-> result json/write-str println)))))
