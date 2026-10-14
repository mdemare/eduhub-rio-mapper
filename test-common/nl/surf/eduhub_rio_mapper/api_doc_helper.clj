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
(ns nl.surf.eduhub-rio-mapper.api-doc-helper
  "Extract examples from API.md, so they can be checked against the code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- code-block-lines
  "Returns the lines inside fenced code blocks in API.md.

  Each line is a map with `:block` (index of the code block),
  `:version` (`:v5`, `:v6` or nil when the line applies to both
  versions) and `:line`.

  A line starting with `# v5` or `# v6` inside a code block marks the
  following lines in that block as version specific."
  []
  (loop [[line & lines] (str/split-lines (slurp (io/file "API.md")))
         block          nil
         n              0
         version        nil
         result         []]
    (cond
      (nil? line)
      result

      (str/starts-with? line "```")
      (if block
        (recur lines nil n nil result)
        (recur lines n (inc n) nil result))

      (nil? block)
      (recur lines block n version result)

      :else
      (if-let [[_ v] (re-matches #"# (v\d+)\b.*" line)]
        (recur lines block n (keyword v) result)
        (recur lines block n version (conj result {:block block :version version :line line}))))))

(defn- for-version? [version {v :version}]
  (or (nil? v) (= version v)))

(defn documented-requests
  "Returns `[method path]` for documented requests for `version`.

  Only `/job` and `/status` requests are included."
  [version]
  (->> (code-block-lines)
       (filter (partial for-version? version))
       (keep #(re-matches #"(GET|POST) (/(?:job|status)/\S+)" (:line %)))
       (map (fn [[_ method path]] [(keyword (str/lower-case method)) path]))))

(defn documented-job-response-status
  "Returns the HTTP status code documented for job creation for `version`."
  [version]
  (let [lines (code-block-lines)
        block (:block (first (filter #(str/includes? (:line %) "{\"token\"") lines)))]
    (->> lines
         (filter #(= block (:block %)))
         (filter (partial for-version? version))
         (keep #(re-matches #"(\d{3}) .*" (:line %)))
         (map (comp parse-long second))
         first)))
