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

(ns nl.surf.eduhub-rio-mapper.config
  "Configuration shared by the v5 and v6 mappers.

  Version-specific behaviour is passed in by the callers: the OOAPI
  version string (\"v5\" or \"v6\") selects the gateway options
  (GATEWAY_USER_V5 etc), and the handler / job functions are passed
  as a map to the `make-config-and-handlers-*` builders."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.endpoints.metrics :as metrics]
            [nl.surf.eduhub-rio-mapper.utils.keystore :as keystore]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(defn parse-int-list [s & _opts] [(mapv #(Integer/parseInt %) (str/split s #","))])

(defn- gateway-key
  "Option key for gateway setting `k` of OOAPI `version`, e.g. :gateway-user-v6."
  [k version]
  (keyword (str "gateway-" (name k) "-" version)))

(defn make-opts-spec
  "Returns the envopts spec for the given OOAPI `version` (\"v5\" or \"v6\")."
  [version]
  {:clients-info-path                  ["Clients info config file" :file
                                        :in [:clients-info-config :path]]
   :connection-timeout-millis          ["HTTP connection timeout in milliseconds" :int
                                        :default 10000
                                        :in [:rio-config :connection-timeout-millis]]
   (gateway-key :user version)         ["OOAPI Gateway Username" :str
                                        :in [:gateway-credentials :username]]
   (gateway-key :password version)     ["OOAPI Gateway Password" :str
                                        :in [:gateway-credentials :password]]
   (gateway-key :root-url version)     ["OOAPI Gateway Root URL" :http
                                        :in [:gateway-root-url]]
   :keystore                           ["Path to keystore" :file]
   :keystore-password                  ["Keystore password" :str
                                        :in [:keystore-pass]] ; name compatibility with clj-http
   :keystore-alias                     ["Key alias in keystore" :str]
   :rio-read-url                       ["RIO Services Read URL" :str
                                        :in [:rio-config :read-url]]
   :rio-update-url                     ["RIO Services Update URL" :str
                                        :in [:rio-config :update-url]]
   :rio-recipient-oin                  ["Recipient OIN for RIO SOAP calls" :str
                                        :in [:rio-config :recipient-oin]]
   :surf-conext-introspection-endpoint ["SurfCONEXT introspection endpoint" :http
                                        :in [:auth-config :introspection-endpoint]]
   :surf-conext-client-id              ["SurfCONEXT client id for Mapper service" :str
                                        :in [:auth-config :client-id]]
   :surf-conext-client-secret          ["SurfCONEXT client secret for Mapper service" :str
                                        :in [:auth-config :client-secret]]
   :api-port                           ["HTTP port for serving web API" :int
                                        :default 8080
                                        :in [:api-config :port]]
   :api-hostname                       ["Hostname for listing web API" :str
                                        :default "localhost"
                                        :in [:api-config :host]]
   :worker-api-port                    ["HTTP port for serving worker web API" :int
                                        :default 8080
                                        :in [:worker-api-config :port]]
   :worker-api-hostname                ["Hostname for listing worker web API" :str
                                        :default "localhost"
                                        :in [:worker-api-config :host]]
   :job-retry-wait-ms                  ["Number of milliseconds to wait before retrying a failed job" :int
                                        :default 5000
                                        :in [:worker :retry-wait-ms]]
   :job-max-retries                    ["Max number of retries of a failed job" :int
                                        :default 3
                                        :in [:worker :max-retries]]
   :redis-uri                          ["URI to redis" :str
                                        :default "redis://localhost"
                                        :in [:redis-conn :spec :uri]]
   :redis-key-prefix                   ["Prefix for redis keys" :str
                                        :default "eduhub-rio-mapper"
                                        :in [:redis-key-prefix]]
   :rio-retry-attempts-seconds         ["Number of seconds to wait for first, second, etc. retry of RIO command, comma separated" :int-list
                                        :default [5,30,120,600]
                                        :parser parse-int-list
                                        :in [:rio-config :rio-retry-attempts-seconds]]
   :status-ttl-sec                     ["Number of seconds to keep job status" :int
                                        :default (* 60 60 24 7) ;; one week
                                        :in [:status-ttl-sec]]
   :store-http-requests                ["Boolean; should all http traffic be logged? Defaults to true." :boolean
                                        :default true
                                        :in [:store-http-requests]]})

(defn help [version]
  (envopts/specs-description (make-opts-spec version)))

(defmethod envopts/parse :file
  [s _]
  (let [f (io/file s)]
    (if (.isFile f)
      [f]
      [nil (str "not a file: `" s "`")])))

(defn- file-secret-loader-reducer [env-map value-key]
  (let [file-key (keyword (str (name value-key) "-file"))
        path (file-key env-map)]
    (cond
      (nil? path)
      env-map

      (not (.exists (io/file path)))
      (throw (ex-info (str "ENV var contains filename that does not exist: " path)
                      {:filename path, :env-path file-key}))

      (value-key env-map)
      (throw (ex-info "ENV var contains secret both as file and as value"
                      {:env-path [value-key file-key]}))

      :else
      (-> env-map
          (assoc value-key (str/trim (slurp path)))
          (dissoc file-key)))))

;; These ENV keys may alternatively have a form in which the secret is contained in a file.
;; These ENV keys have a -file suffix, e.g.: gateway-basic-auth-pass-file
(defn env-keys-with-alternate-file-secret [version]
  [(gateway-key :password version) :keystore-password :surf-conext-client-secret :redis-uri])

(defn load-config-from-env [version env-map]
  (-> (reduce file-secret-loader-reducer env-map (env-keys-with-alternate-file-secret version))
      (envopts/opts (make-opts-spec version))))

(defn make-config
  ([version]
   (make-config version env))
  ([version env]
   {:post [(some? (-> % :rio-config :credentials :certificate))]}
   (let [[config errs] (load-config-from-env version env)]
     (when errs
       (.println *err* "Configuration error")
       (.println *err* (prn-str (keys env)))
       (.println *err* (envopts/errs-description errs))
       (System/exit 1))
     (let [{:keys [clients-info-config
                   keystore
                   keystore-pass
                   keystore-alias] :as cfg}
           config
           credentials
           (keystore/credentials keystore
                                 keystore-pass
                                 keystore-alias)]
       (-> cfg
           (update :gateway-root-url #(str (str/replace % #"/+$" "") "/")) ;; ensure 1 trailing slash
           (assoc-in [:rio-config :credentials] credentials)
           (assoc :clients (clients-info/read-clients-data clients-info-config)))))))

;; The builders below take a map of version-specific functions:
;;
;;   :version            OOAPI version string, "v5" or "v6"
;;   :make-handlers      processing/make-handlers
;;   :make-set-status-fn status/make-set-status-fn
;;   :run-job            job/run!, called as (run-job handlers job store-http-requests)
;;   :retryable?         status/retryable?
;;   :errors?            status/errors?

(defn make-config-and-handlers-web [{:keys [version make-handlers make-set-status-fn]}]
  (let [{:keys [clients] :as cfg} (make-config version)
        handlers (make-handlers cfg)
        config (update cfg :worker merge
                       {:queues        (clients-info/institution-schac-homes clients)
                        :set-status-fn (make-set-status-fn cfg)
                        :queue-fn      :institution-schac-home})]
    {:handlers handlers :config config}))

(defn make-config-and-handlers-worker [{:keys [version make-handlers make-set-status-fn run-job retryable? errors?]}]
  (let [{:keys [clients] :as cfg} (make-config version)
        handlers (make-handlers cfg)
        schac-home-to-name (reduce (fn [h c] (assoc h (:institution-schac-home c) (:institution-name c))) {} clients)
        institution-schac-homes (clients-info/institution-schac-homes clients)
        config (update cfg :worker merge
                       {:queues               (clients-info/institution-schac-homes clients)
                        :queue-fn             :institution-schac-home
                        :run-job-fn           (fn [job] (run-job handlers job (:store-http-requests cfg)))
                        :set-status-fn        (make-set-status-fn cfg)
                        :retryable-fn         retryable?
                        :error-fn             errors?
                        ;; The web-api doesn't need the job metrics
                        :jobs-counter-fn      (metrics/make-jobs-counter schac-home-to-name)
                        :queued-jobs-gauge-fn (metrics/make-queued-jobs-gauge #(worker/queue-counts-by-key % cfg) institution-schac-homes)})]
    {:handlers handlers :config config}))
