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

(ns nl.surf.eduhub-rio-mapper.utils.create-truststore-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore])
  (:import [java.security KeyStore]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "create-truststore-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- load-truststore
  ^KeyStore [file]
  (with-open [in (io/input-stream file)]
    (doto (KeyStore/getInstance "JKS")
      ;; same password as keystore/credentials
      (.load in (char-array "")))))

(defn- trusted-certs [^KeyStore ks]
  (into {}
        (map (fn [alias] [alias (vec (.getEncoded (.getCertificate ks alias)))]))
        (enumeration-seq (.aliases ks))))

;; Runs a copy of dev/create-truststore.sh in a scratch tree laid out
;; like the repo, so the committed resources/truststore.jks is never
;; touched.
(deftest create-truststore-reproduces-committed-file
  (let [root (temp-dir)
        dest (io/file root "resources" "truststore.jks")]
    (doseq [f (.listFiles (io/file "dev"))
            :when (re-find #"^create-truststore\.sh$|\.pem$" (.getName f))]
      (io/make-parents (io/file root "dev" (.getName f)))
      (io/copy f (io/file root "dev" (.getName f))))
    ;; an existing store must be replaced, not imported into
    (io/make-parents dest)
    (spit dest "not a keystore")
    (let [{:keys [exit out err]} (shell/sh "sh" (str (io/file root "dev" "create-truststore.sh"))
                                           :in "")]
      (is (zero? exit) (str out err)))
    (is (= (trusted-certs (keystore/keystore-resource "truststore.jks" ""))
           (trusted-certs (load-truststore dest))))))

(deftest committed-truststore-is-classpath-resource
  (is (= (.getCanonicalFile (io/file "resources/truststore.jks"))
         (.getCanonicalFile (io/file (io/resource "truststore.jks"))))))
