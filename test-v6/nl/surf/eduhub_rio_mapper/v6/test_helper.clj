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

(ns nl.surf.eduhub-rio-mapper.v6.test-helper
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [nl.surf.eduhub-rio-mapper.playback-config :as playback-config]
   [nl.surf.eduhub-rio-mapper.specs.ooapi :as-alias ooapi]
   [nl.surf.eduhub-rio-mapper.specs.rio :as-alias rio]
   [nl.surf.eduhub-rio-mapper.v6.config :as config]
   [nl.surf.eduhub-rio-mapper.v6.ooapi.loader :as ooapi.loader]
   [nl.surf.eduhub-rio-mapper.v6.rio.updated-handler :as updated-handler]
   [nl.surf.eduhub-rio-mapper.v6.specs.ooapi :as ooapi-v6]))

(defn load-json [path]
  (some-> path
          io/resource
          slurp
          (json/read-str :key-fn keyword)))

(defn test-resolve-request [{:keys [rio-type] ::rio/keys [opleidingscode] :as request} resolver]
  (cond-> request
    (= :ao rio-type)
    (assoc ::rio/aangeboden-opleiding-code
           (resolver rio-type)
           ::ooapi-v6/specification-type
           "programme")

    :always
    (assoc ::rio/opleidingscode
           (or opleidingscode
               (resolver :oe)))))

(def test-client-info {:client-id              "rio-mapper-dev.jomco.nl"
                       :institution-schac-home "demo06.test.surfeduhub.nl"
                       :institution-oin        "0000000700025BE00000"})

(defmacro with-ooapi-loader
  "Temporarily overrides the OOAPI HTTP loader with the given function."
  [loader & body]
  `(let [loader-fn# ~loader]
     (with-redefs [ooapi.loader/ooapi-http-loader (fn [request#]
                                                 (loader-fn# request#))]
       ~@body)))

(defn test-handler
  "Loads ooapi fixtures from file and fakes resolver."
  [{::ooapi/keys [type] :as req} resolver]
  (-> (cond->> (merge req test-client-info)
        (not= "relation" type)
        (ooapi.loader/load-entities))
      (test-resolve-request resolver)
      updated-handler/update-mutation))

(defn wait-while-predicate [predicate val-atom max-sec]
  (loop [ttl (* max-sec 10)]
    (when (and (pos? ttl) (predicate @val-atom))
      (Thread/sleep 100)
      (recur (dec ttl)))))

(defn wait-for-expected [expected val-atom max-sec]
  (wait-while-predicate #(not= % expected) val-atom max-sec)
  (is (= expected @val-atom)))

(defn make-test-config
  "Deterministic offline configuration; live recording uses config/make-config."
  []
  (config/make-config playback-config/environment))
