;; This file is part of eduhub-mapper
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

(ns nl.surf.eduhub-rio-mapper.v5.config-test
  (:require [clojure.test :refer [deftest is]]
            [nl.surf.eduhub-rio-mapper.v5.config :as config])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]))

(def default-env {:surf-conext-introspection-endpoint "https://gateway.dev.surfnet.nl/",
                  :keystore-alias "default",
                  :gateway-password-v5 "default",
                  :keystore "test/keystore.jks",
                  :clients-info-path "test/test-clients.json",
                  :rio-recipient-oin "default",
                  :rio-update-url "default",
                  :gateway-user-v5 "default",
                  :surf-conext-client-id "default",
                  :surf-conext-client-secret "default",
                  :gateway-root-url-v5 "https://gateway.test.surfeduhub.nl/",
                  :rio-read-url "default"})

(def default-expected-value {:keystore-pass "repelsteeltje!",
                             :gateway-credentials
                             {:password "default", :username "default"},
                             :redis-conn {:spec {:uri "redis://localhost"}}})

(defn- test-env [env]
  (config/load-config-from-env (merge default-env env)))

(deftest missing-secret
  (is (= {:keystore-password "missing"}
         (last (test-env {})))))

(deftest only-value-secret
  (let [env {:keystore-password "repelsteeltje!"}]
    (is (= default-expected-value
           (-> env test-env first (select-keys [:keystore-pass :gateway-credentials :redis-conn]))))))

(deftest only-file-secret
  (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))
        env {:keystore-password-file path}]
    (spit path "repelsteeltje!")
    (is (= default-expected-value
           (-> env test-env first (select-keys [:keystore-pass :gateway-credentials :redis-conn]))))))

(deftest file-secret-for-env-with-default
  (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))
        env {:redis-uri-file path :keystore-password "repelsteeltje!"}]
    (spit path "redis://localhost:6381")
    (is (= (assoc-in default-expected-value [:redis-conn :spec :uri] "redis://localhost:6381")
           (-> env test-env first (select-keys [:keystore-pass :gateway-credentials :redis-conn]))))))

(deftest only-file-secret-file-missing
  (let [env {:keystore-password-file "missing-file"}]
    (is (thrown? ExceptionInfo (test-env env)))))

(deftest both-types-of-secret-specified
  (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))
        env {:keystore-password "repelsteeltje!"
             :keystore-password-file path}]
    (spit path "repelsteeltje!")
    (is (thrown? ExceptionInfo (test-env env)))))

(deftest configuration-errors-are-exceptions
  (let [error (try
                (config/make-config {})
                (catch ExceptionInfo e e))]
    (is (instance? ExceptionInfo error))
    (is (re-find #"Configuration error" (ex-message error)))
    (is (re-find #"CLIENTS_INFO_PATH" (ex-message error)))
    (is (= "missing" (:clients-info-path (:errors (ex-data error)))))))
