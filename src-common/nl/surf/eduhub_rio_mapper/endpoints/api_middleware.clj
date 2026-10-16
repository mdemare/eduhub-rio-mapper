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

(ns nl.surf.eduhub-rio-mapper.endpoints.api-middleware
  "Version-independent part of the HTTP API.

  The v5 and v6 `endpoints.api` namespaces only define their job
  routes (types, actions and how a request becomes a job) and pass
  them, together with their version options, to `routes`, `make-app`
  and `serve-api`."
  (:require [clojure.data.json :as json]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compojure.core :refer [GET]]
            [compojure.route :as route]
            [nl.jomco.http-status-codes :as http-status]
            [nl.jomco.ring-trace-context :refer [wrap-trace-context]]
            [nl.surf.eduhub-rio-mapper.clients-info :refer [wrap-client-info]]
            [nl.surf.eduhub-rio-mapper.endpoints.app-server :as app-server]
            [nl.surf.eduhub-rio-mapper.endpoints.health :as health]
            [nl.surf.eduhub-rio-mapper.endpoints.ring-middleware-json :refer [wrap-json-response]]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.authentication :as authentication]
            [nl.surf.eduhub-rio-mapper.utils.logging :refer [with-mdc wrap-logging]]
            [nl.surf.eduhub-rio-mapper.worker :as worker]
            [ring.middleware.defaults :as defaults]
            [ring.util.response :as response])
  (:import [java.net MalformedURLException URL]
           [java.time Instant]
           java.util.UUID))

(defn make-lifecycle
  "Create request accounting and drain state for one API instance."
  []
  {:server-stopping (atom false)
   :nr-active-requests (atom 0)})

(defn wrap-server-status [app {:keys [server-stopping nr-active-requests]}]
  (fn [req]
    (if @server-stopping
      {:status http-status/service-unavailable :body "Server stopping"}
      (try
        (swap! nr-active-requests inc)
        (app req)
        (finally
          (swap! nr-active-requests dec))))))

(defn- valid-uuid? [uuid]
  (try (UUID/fromString uuid)
       true
       (catch IllegalArgumentException _ false)))

(defn- prepare-job-for-enqueuement [res token suppress-header]
  (assoc (:job res)
         :token token
         :created-at (str (Instant/now)) ; store created-at in job itself as soon as it is created
         :suppress-http-messages (= "true" suppress-header)))

(defn- enqueue-job [enqueue-fn enqueued-status req res]
  (let [token (UUID/randomUUID)
        suppress-header (get-in req [:headers "x-suppress-http-messages"])]
    (with-mdc {:token token}
      (-> res
          (prepare-job-for-enqueuement token suppress-header)
          enqueue-fn))
    (assoc res
           :status enqueued-status
           :body {:token token})))

(defn wrap-job-enqueuer
  "Enqueue the job in the response, if any, and return its token.

  The response gets `enqueued-status`, which differs per API version:
  v5 responds 200 OK, v6 201 Created."
  [app enqueue-fn enqueued-status]
  (fn job-enqueuer [req]
    (let [res (app req)]
      (cond->> res
        (:job res) (enqueue-job enqueue-fn enqueued-status req)))))

(defn- valid-url? [url]
  (try
    (URL. url)
    (str/starts-with? url "http")                           ; Reject non-http protocols like file://
    (catch MalformedURLException _
      false)))

(defn wrap-callback-extractor
  "Store the x-callback header in the job under `callback-url-key`.

  `callback-url-key` is the version's `::job/callback-url` keyword."
  [app callback-url-key]
  (fn callback-extractor [req]
    (let [callback-url          (get-in req [:headers "x-callback"])
          {:keys [job] :as res} (app req)]
      (if (or (nil? job)
              (nil? callback-url))
        res
        (if (valid-url? callback-url)
          (update res :job assoc callback-url-key callback-url)
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
              (valid-uuid? uuid))
        (app req)
        {:status http-status/bad-request :body "Invalid UUID"}))))

(defn wrap-code-validator [app]
  (fn code-validator [req]
    (let [res (app req)
          ao-code (get-in res [:job ::rio/aangeboden-opleiding-code])
          invalid-ao-code (and (some? ao-code) (not (valid-uuid? ao-code)))
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

(defn- private-routes [job-routes {:keys [enqueuer-fn enqueued-status callback-url-key]}]
  (-> job-routes
      (compojure.core/wrap-routes wrap-callback-extractor callback-url-key)
      ;; Response validation must finish before enqueueing: rejected requests
      ;; must enqueue zero jobs and create no job status.
      (compojure.core/wrap-routes wrap-code-validator)
      (compojure.core/wrap-routes wrap-job-enqueuer enqueuer-fn enqueued-status)
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

(defn routes
  "Return all API routes, with the version's `job-routes` behind the job middleware.

  `job-routes` return `{:job ...}` responses. `config` contains the `:enqueuer-fn` and `:status-getter-fn`, and
  the version options `:enqueued-status` (see `wrap-job-enqueuer`) and
  `:callback-url-key` (see `wrap-callback-extractor`)."
  [job-routes config]
  (compojure.core/routes
   public-routes
   (private-routes job-routes config)
   (read-only-routes config)
   (route/not-found nil)))

(defn make-app
  "Build the ring app.

  `routes-fn` takes a map with the `:enqueuer-fn` and
  `:status-getter-fn` and returns the routes. Creates a fresh lifecycle unless
  one is supplied explicitly for use with `shutdown-handler`."
  ([routes-fn config]
   (make-app routes-fn config (make-lifecycle)))
  ([routes-fn {:keys [auth-config clients] :as config} lifecycle]
   (let [token-authenticator (-> (authentication/make-token-authenticator auth-config)
                                 (authentication/cache-token-authenticator {:ttl-minutes 10}))]
     (-> (routes-fn {:enqueuer-fn      (partial worker/enqueue! config)
                    :status-getter-fn (partial status/rget config)})
         (health/wrap-health config)
         (wrap-client-info clients)
         (authentication/wrap-authentication token-authenticator)
         (wrap-logging)
         (wrap-json-response)
         (wrap-trace-context)
         (defaults/wrap-defaults defaults/api-defaults)
         (wrap-server-status lifecycle)))))

(defn shutdown-handler
  "Reject new requests and wait for this instance's active requests to finish."
  [{:keys [server-stopping nr-active-requests]}]
  ;; All subsequent requests will get a 503 error
  (reset! server-stopping true)
  ;; Wait until all pending requests have been completed
  (loop []
    (when (< 0 @nr-active-requests)
      (Thread/sleep 500)
      (recur))))

(defn- add-shutdown-hook! [drain]
  (.addShutdownHook (Runtime/getRuntime) (new Thread ^Runnable drain)))

(defn serve-api
  "Start the API server with its own lifecycle.

  `make-app` takes `config` and the lifecycle shared with the shutdown hook."
  [make-app {{:keys [^Integer port host]} :api-config :as config} options]
  (let [lifecycle (make-lifecycle)]
    (add-shutdown-hook! #(shutdown-handler lifecycle))
    (app-server/run-jetty (make-app config lifecycle) (assoc options
                                                         :host host
                                                         :port port))))
