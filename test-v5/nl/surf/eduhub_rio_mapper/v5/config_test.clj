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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nl.surf.eduhub-rio-mapper.config :as common-config]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities-helper]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis]
            [nl.surf.eduhub-rio-mapper.v5.config :as config]))

;; The shared configuration is tested in nl.surf.eduhub-rio-mapper.config-test;
;; this only checks that the v5 wrapper selects the v5 gateway settings.

(deftest wrapper-uses-v5-settings
  (is (= (common-config/make-opts-spec "v5") config/opts-spec))
  (is (= :gateway-password-v5 (first config/env-keys-with-alternate-file-secret)))
  (is (= (common-config/help "v5") (config/help)))
  (is (str/includes? (config/help) "GATEWAY_ROOT_URL_V5"))
  (is (= "v5" (:version config/version-fns))))

(deftest set-status-fn-reads-persisted-callback-url-key
  ;; Queued jobs are stored in Redis with this exact keyword; renaming it
  ;; would drop callbacks for jobs in flight during a deploy.
  (let [callback-urls (atom [])]
    (with-redefs [redis/set                (fn [& _])
                  status/do-async-callback (fn [_config url _job]
                                             (swap! callback-urls conj url))]
      (((:make-set-status-fn config/version-fns) {:status-ttl-sec 60})
       {:token                                         "test"
        :nl.surf.eduhub-rio-mapper.v5.job/callback-url "https://example.com/hook"}
       :done))
    (is (= ["https://example.com/hook"] @callback-urls))))

(defn- readme-env-vars
  "Environment variables listed in the README \"Configuration\" section."
  []
  (let [readme  (slurp "README.md")
        section (second (re-find #"(?s)## Configuration\n.*?```\n(.*?)```" readme))]
    (set (map second (re-seq #"(?m)^([A-Z][A-Z0-9_]+)\s" section)))))

(defn- env-var-name [k]
  (-> k name str/upper-case (str/replace "-" "_")))

(deftest readme-documents-env-vars
  (let [documented (readme-env-vars)
        spec       (set (map env-var-name (keys config/opts-spec)))]
    (is (empty? (remove documented spec))
        "all config options are documented in the README")
    (is (empty? (remove spec (map #(str/replace % #"_V6$" "_V5") documented)))
        "the README documents no unknown config options")))

(defn- e2e-doc-env-vars
  "Environment variables listed as prerequisites in doc/e2e.md."
  []
  (set (map second (re-seq #"(?m)^\s*- `([A-Z][A-Z0-9_]+)`" (slurp "doc/e2e.md")))))

(deftest e2e-doc-lists-only-used-env-vars
  (let [documented (map #(str/replace % #"_V6$" "_V5") (e2e-doc-env-vars))
        used       (set (concat (map env-var-name (keys config/opts-spec))
                                (map env-var-name (keys remote-entities-helper/opts-spec))
                                ;; read directly by e2e-helper to get a bearer token
                                ["CLIENT_ID" "CLIENT_SECRET" "TOKEN_ENDPOINT"]))]
    (is (empty? (remove used documented))
        "doc/e2e.md lists no unused environment variables")))
