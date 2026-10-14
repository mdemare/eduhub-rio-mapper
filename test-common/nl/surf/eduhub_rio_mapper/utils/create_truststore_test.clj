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
            [clojure.string :as str]
            [clojure.test :refer :all]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "create-truststore-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

;; Runs dev/create-truststore.sh with a fake keytool that records the
;; -keystore argument, so we can check where the truststore ends up.
(deftest create-truststore-writes-classpath-resource
  (let [bin      (temp-dir)
        args-out (io/file bin "args")
        keytool  (io/file bin "keytool")]
    (spit keytool (str "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" args-out "'\n"))
    (.setExecutable keytool true)
    (let [{:keys [exit err]} (shell/sh "sh" "dev/create-truststore.sh"
                                       :env {"PATH" (str bin ":" (System/getenv "PATH"))})]
      (is (zero? exit) err))
    (let [args (str/split-lines (slurp args-out))
          dest (second (drop-while #(not= "-keystore" %) args))]
      (is (= (.getCanonicalFile (io/file "resources/truststore.jks"))
             (.getCanonicalFile (io/file dest))))
      ;; that is the file the application loads (see keystore/credentials)
      (is (= (.getCanonicalFile (io/file (io/resource "truststore.jks")))
             (.getCanonicalFile (io/file dest)))))))
