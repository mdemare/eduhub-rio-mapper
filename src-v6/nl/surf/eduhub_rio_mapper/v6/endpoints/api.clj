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

(ns nl.surf.eduhub-rio-mapper.v6.endpoints.api
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET POST]]
            [compojure.route :as route]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.clients-info :refer [wrap-client-info]]
            [nl.surf.eduhub-rio-mapper.endpoints.app-server :as app-server]
            [nl.surf.eduhub-rio-mapper.endpoints.health :as health]
            [nl.surf.eduhub-rio-mapper.endpoints.ring-middleware-json :refer [wrap-json-response]]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi-specs]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.authentication :as authentication]
            [nl.surf.eduhub-rio-mapper.utils.logging :refer [with-mdc wrap-logging]]
            [nl.surf.eduhub-rio-mapper.v6.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.v6.job :as job]
            [nl.surf.eduhub-rio-mapper.v6.utils.ooapi :as ooapi-utils]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [ring.middleware.defaults :as defaults]
            [ring.util.response :as response])
  (:import [java.net MalformedURLException URL]
           [java.time Instant]
           java.util.UUID))

(def server-stopping (atom false))

(def nr-active-requests (atom 0))

(defn wrap-server-status [app]
  (fn [req]
    (if @server-stopping
      {:status http-status/service-unavailable :body "Server stopping"}
      (try
        (swap! nr-active-requests inc)
        (app req)
        (finally
          (swap! nr-active-requests dec))))))

(defn- prepare-job-for-enqueuement [res token suppress-header]
  (assoc (:job res)
         :token token
         :created-at (str (Instant/now)) ; store created-at in job itself as soon as it is created
         :suppress-http-messages (= "true" suppress-header)))

(defn- enqueue-job [enqueue-fn req res]
  (let [token (UUID/randomUUID)
        suppress-header (get-in req [:headers "x-suppress-http-messages"])]
    (with-mdc {:token token}
      (-> res
          (prepare-job-for-enqueuement token suppress-header)
          enqueue-fn))
    (if (not-empty res)
      (assoc res
             :status http-status/created
             :body {:token token})
      res)))

(defn wrap-job-enqueuer
  [app enqueue-fn]
  (fn job-enqueuer [req]
    (let [res (app req)]
      (cond->> res
        (:job res) (enqueue-job enqueue-fn req)))))

(defn- valid-url? [url]
  (try
    (URL. url)
    (str/starts-with? url "http")                           ; Reject non-http protocols like file://
    (catch MalformedURLException _
      false)))

(defn wrap-callback-extractor [app]
  (fn callback-extractor [req]
    (let [callback-url          (get-in req [:headers "x-callback"])
          {:keys [job] :as res} (app req)]
      (if (or (nil? job)
              (nil? callback-url))
        res
        (if (valid-url? callback-url)
          (update res :job assoc ::job/callback-url callback-url)
          {:status http-status/bad-request :body "Malformed callback url"})))))

(defn json-request-headers? [headers]
  (let [accept (get headers "Accept")]
    (and accept
         (str/starts-with? accept "application/json"))))

(defn add-json-body [res add-json?]
  (if add-json?
    (try
      (assoc res :json-body (json/read-str (:body res) :key-fn keyword))
      (catch Exception ex
        ;; sometimes web servers return text instead of json for 4xx and 5xx status responses.
        (log/errorf ex "Not valid JSON: %s" (:body res))
        res))
    res))

;; For json requests (requests with a json Accept header) add a :json-body key to the response with the
;; same content as the response body, only with the json parsed, instead of as a raw string.
(defn add-single-parsed-json-response [{req :req :as all}]
  {:pre [req]}
  (update all :res #(add-json-body % (json-request-headers? (:headers req)))))

(defn wrap-status-getter
  "If the response contains a token, use it to load the status from redis and return it. Optionally, add the logs with all http traffic."
  [app {:keys [status-getter-fn]}]
  (fn status-getter [req]
    (let [res (app req)
          token (:token res)
          show-http-messages? (= "true" (get-in req [:params :http-messages] "false"))]
      (if (nil? token)
        res
        (let [job-status (status-getter-fn token)]
          (if (nil? job-status)
            {:status http-status/not-found
             :token  token
             :body   {:status :unknown}}
            {:status http-status/ok
             :token  token
             :body   (if show-http-messages?
                       (update job-status :http-messages #(map add-single-parsed-json-response %))
                       (dissoc job-status :http-messages))}))))))

(defn wrap-uuid-validator [app]
  (fn uuid-validator [req]
    (let [uuid (or (get-in req [:params :id])
                   (get-in req [:params :token]))]
      (if (or (nil? uuid)
              (ooapi-utils/valid-uuid? uuid))
        (app req)
        {:status http-status/bad-request :body "Invalid UUID"}))))

(defn wrap-code-validator [app]
  (fn code-validator [req]
    (let [res (app req)
          ao-code (get-in res [:job ::rio/aangeboden-opleiding-code])
          invalid-ao-code (and (some? ao-code) (not (ooapi-utils/valid-uuid? ao-code)))
          opl-code (get-in res [:job ::rio/opleidingscode])
          invalid-opleidingscode (and (some? opl-code) (not (s/valid? ::rio/OpleidingsEenheidID-v01 opl-code)))]
      (cond
        invalid-ao-code
        {:status http-status/bad-request :body (format "Invalid aangeboden opleidingcode '%s'" ao-code)}

        invalid-opleidingscode
        {:status http-status/bad-request :body (format "Invalid opleidingscode '%s'" opl-code)}

        :else
        res))))

(defn wrap-access-control-private [app]
  (fn access-control-private [{:keys [institution-oin] :as req}]
    (if institution-oin
      (app req)
      (response/status http-status/forbidden))))

(defn wrap-access-control-read-only [app]
  (fn [{:keys [client-id] :as req}]
    (if client-id
      (app req)
      (response/status http-status/forbidden))))

(def types {"courses"    "course"
            "programmes" "programme"})

(def actions #{"upsert" "delete" "dry-run-upsert" "link"})

(defn job-route [{{:keys [action type id]} :params :as request}]
  (let [type   (types type)
        action (actions action)]
    (when (and type action)
      {:job (-> request
                (select-keys [:institution-schac-home
                              :institution-name
                              :institution-oin
                              :trace-context])
                (assoc :action      action
                       ::ooapi-specs/type type
                       ::ooapi-specs/id   id))})))

(defn link-route [{{:keys [rio-code]} :params :as request}]
  (let [result   (job-route (assoc-in request [:params :action] "link"))
        codename (if (re-matches #"\d{4}O\d{4}" rio-code) ::rio/opleidingscode ::rio/aangeboden-opleiding-code)]
    (when result
      (assoc-in result [:job codename] rio-code))))

(defn private-routes [{:keys [enqueuer-fn]}]
  (-> (compojure.core/routes
        ;; Unlink is link to `nil`
       (POST "/job/unlink/:rio-code/:type" request
         (link-route request))

       (POST "/job/dry-run/upsert/:type/:id" request
         (job-route (assoc-in request [:params :action] "dry-run-upsert")))

       (POST "/job/link/:rio-code/:type/:id" request
         (link-route request))

       (POST "/job/:action/:type/:id" request
         (job-route request)))

      (compojure.core/wrap-routes wrap-callback-extractor)
      (compojure.core/wrap-routes wrap-code-validator)
      (compojure.core/wrap-routes wrap-job-enqueuer enqueuer-fn)
      (compojure.core/wrap-routes wrap-uuid-validator)
      (compojure.core/wrap-routes wrap-access-control-private)))

(def public-routes
  (compojure.core/routes
   (GET "/health" []
     {:health true})))

(defn read-only-routes [config]
  (-> (GET "/status/:token" [token] {:token token})
      (compojure.core/wrap-routes wrap-status-getter config)
      (compojure.core/wrap-routes wrap-uuid-validator)
      (compojure.core/wrap-routes wrap-access-control-read-only)))

(defn routes [config]
  (compojure.core/routes
   public-routes
   (private-routes config)
   (read-only-routes config)
   (route/not-found nil)))

(defn make-app [{:keys [auth-config clients] :as config}]
  (let [token-authenticator       (-> (authentication/make-token-authenticator auth-config)
                                      (authentication/cache-token-authenticator {:ttl-minutes 10}))]
    (-> (routes {:enqueuer-fn      (partial worker/enqueue! config)
                 :status-getter-fn (partial status/rget config)})
        (health/wrap-health config)
        (wrap-client-info clients)
        (authentication/wrap-authentication token-authenticator)
        (wrap-logging)
        (wrap-json-response)
        (wrap-trace-context)
        (defaults/wrap-defaults defaults/api-defaults)
        (wrap-server-status))))

(defn shutdown-handler []
  ;; All subsequent requests will get a 503 error
  (reset! server-stopping true)
  ;; Wait until all pending requests have been completed
  (loop []
    (when (< 0 @nr-active-requests)
      (Thread/sleep 500)
      (recur))))

(defn serve-api
  [{{:keys [^Integer port host]} :api-config :as config} options]
  (.addShutdownHook (Runtime/getRuntime) (new Thread ^Runnable shutdown-handler))
  (app-server/run-jetty (make-app config) (assoc options
                                                 :host host
                                                 :port port)))
