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

;; Comparison with e2e tests:
;; e2e tests contact the RIO test environment (KIT) for each RIO call.
;; e2e tests use the entire stack, including web server and worker.
;; vcr tests only contact RIO during record mode.
;; This leads to some important differences:
;; - vcr playback tests are much faster (about 50x)
;; - vcr playback tests work when RIO is offline (after 17 and during weekends) or down
;; - vcr tests (both modes) show the actual exceptions; e2e tests access the api and that's a black box from their view
;; - vcr tests cannot be run in record mode from github actions
;; - vcr tests do not test the worker or the api
;; - e2e tests are somewhat more resilient - vcr tests expect an exact sequence of http calls.
;; - vcr tests need to be re-recorded regularly (make record)

(ns nl.surf.eduhub-rio-mapper.vcr-helper
  (:require
   [clojure.data.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [nl.surf.eduhub-rio-mapper.rio.helper :as rio.helper])
  (:import
   [java.io PushbackReader]
   [java.net URI]))

(def vcr-mode (if (= "true" (System/getenv "VCR_RECORD"))
                :record
                :playback))

;; Code-only Raadplegen tests do not need legacy OOAPI ID mappings.
(def vcr-mapping
  (delay
    (let [fixture-dir #(rio.helper/edn-read-file (str "test-" (name %) "/fixtures" "/vcr/mapping.edn"))]
      {:v5 (fixture-dir :v5)
       :v6 (fixture-dir :v6)})))

(defn- entity-id
  "Get UUID of entity from remote-entities session."
  [name]
  ;; Loading this namespace in record mode requires OOAPI_VERSION. Keep it
  ;; confined to legacy tests that actually use the ObjectStore session.
  (get @(requiring-resolve 'nl.surf.eduhub-rio-mapper.remote-entities-helper/*session*) name))

(defn entity-name-to-id [n version]
  {:post [(not (empty? %))]}
  (let [id (if (= vcr-mode :record)
             (some-> n entity-id str)
             (get (version @vcr-mapping) n))]
    (when (nil? id)
      (let [err (if (= vcr-mode :record)
                  "Entity name not present in remote entities"
                  "Entity name not present in vcr/mapping.edn")]
        (throw (ex-info err {:name n}))))
    id))

(defn- ls [dir-name]
  (map #(.getName %) (.listFiles (io/file dir-name))))

(defn only-one-if-any [list]
  (assert (< (count list) 2) (prn-str list))
  (first list))

(defn- numbered-dir [basedir nr]
  {:post [(some? %)]}
  (let [dirname (->> basedir
                      (ls)
                      (filter #(.startsWith % (str nr "-")))
                      (only-one-if-any))]
    (when-not dirname (throw (ex-info (format "No recorded request found for dir %s nr %d" basedir nr) {})))
    (str basedir "/" dirname)))

(defn- numbered-file [basedir nr]
  {:post [(some? %)]}
  (let [filename (->> basedir
                      (ls)
                      (filter #(.startsWith % (str nr "-")))
                      (filter #(.endsWith % ".edn"))
                      (only-one-if-any))]
    (when-not filename (throw (ex-info (format "No recorded request found for dir %s nr %d" basedir nr) {})))
    (str basedir "/" filename)))

(defn path-for [url]
  (-> url
      (URI.)
      (.getPath)
      (str/replace-first #"^/" "")))

(defn req-name [request]
  (let [action (get-in request [:headers "SOAPAction"])]
    (if action
      (last (str/split action #"/"))
      (-> (:url request)
          path-for
          (str/replace \/ \-)
        (str/replace-first #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" "ooapi-id")))))

(defn- wrap-json-body [body]
  (if (string? body)
    (try
      (assoc {} :JSONSTRING (json/read-str body))
      (catch Exception _
        body))
    body))

(defn- unwrap-json-body [body]
  (if (and (map? body) (contains? body :JSONSTRING))
    (json/write-str (:JSONSTRING body))
    body))

(defn- make-playbacker [root idx _]
  (let [count-atom (atom 0)
        dir        (numbered-dir root idx)]
    (fn [_ actual-request]
      (let [i                (swap! count-atom inc)
            fname            (numbered-file dir i)
            recording        (with-open [r (io/reader fname)] (edn/read (PushbackReader. r)))
            recorded-request (:request recording)]
        (doseq [property-path [[:url] [:method] [:headers "SOAPAction"]]]
          (let [expected (get-in recorded-request property-path)
                actual   (get-in actual-request property-path)]
            (is (= expected actual)
                (str "Unexpected property " (last property-path)))))
        (update (:response recording) :body unwrap-json-body)))))

(defn- make-recorder [root idx desc]
  (let [mycounter (atom 0)]
    (fn [handler request]
      (let [response     (handler request)
            content-type (get (:headers response) "Content-Type")
            counter      (swap! mycounter inc)
            file-name    (str root "/" idx "-" desc "/" counter "-" (req-name request) ".edn")
            headers      (select-keys (:headers request) ["SOAPAction" "X-Route"])]
        (io/make-parents file-name)
        (with-open [w (io/writer file-name)]
          (pprint {:request  (-> request
                                (select-keys [:method :url :body])
                                (assoc :headers headers)
                                (update :body wrap-json-body))
                   :response (-> response
                                (select-keys [:status :body])
                                (assoc :headers {"content-type" content-type})
                                (update :body wrap-json-body))}
                  w))
        response))))


(defn make-vcr []
  (case vcr-mode
    :playback make-playbacker
    :record   make-recorder))
