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

(ns nl.surf.eduhub-rio-mapper.endpoints.api-middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [compojure.core :refer [POST]]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.endpoints.api-middleware :as api-middleware]
            [nl.surf.eduhub-rio-mapper.endpoints.app-server :as app-server]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [ring.mock.request :refer [request]]))

(deftest wrap-server-status-test
  (let [{:keys [nr-active-requests] :as lifecycle} (api-middleware/make-lifecycle)
        active (atom nil)
        app    (api-middleware/wrap-server-status
                (fn [_]
                  (reset! active @nr-active-requests)
                  {:status http-status/ok}) lifecycle)]
    (is (= {:status http-status/ok} (app {})))
    (is (= 1 @active) "request is counted while active")
    (is (= 0 @nr-active-requests) "request is uncounted when done")

    (testing "active requests are uncounted when the handler throws"
      (is (thrown? Exception ((api-middleware/wrap-server-status (fn [_] (throw (Exception. "boom"))) lifecycle) {})))
      (is (= 0 @nr-active-requests)))

    (testing "after shutdown"
      (api-middleware/shutdown-handler lifecycle)
      (is (= {:status http-status/service-unavailable :body "Server stopping"}
             (app {}))))))

(deftest shutdown-waits-for-own-request-test
  (let [{:keys [server-stopping] :as lifecycle} (api-middleware/make-lifecycle)
        entered (promise)
        release (promise)
        stopping (promise)
        app (api-middleware/wrap-server-status
             (fn [_] (deliver entered true) @release :ok) lifecycle)
        request-task (future (app {}))]
    (add-watch server-stopping ::stopping
               (fn [_ _ _ value] (when value (deliver stopping true))))
    (try
      (is (= true (deref entered 2000 :timeout)))
      (let [drain-task (future (api-middleware/shutdown-handler lifecycle))]
        (try
          (is (= true (deref stopping 2000 :timeout)))
          (is (= :pending (deref drain-task 100 :pending)))
          (is (= {:status http-status/service-unavailable :body "Server stopping"}
                 (app {})))
          (deliver release true)
          (is (= :ok (deref request-task 2000 :timeout)))
          (is (nil? (deref drain-task 2000 :timeout)))
          (finally (future-cancel drain-task))))
      (finally
        (deliver release true)
        (future-cancel request-task)
        (remove-watch server-stopping ::stopping)))))

(deftest independent-lifecycles-test
  (let [lifecycle-a (api-middleware/make-lifecycle)
        lifecycle-b (api-middleware/make-lifecycle)
        entered (promise)
        release (promise)
        app-a (api-middleware/wrap-server-status (constantly :ok) lifecycle-a)
        app-b (api-middleware/wrap-server-status
               (fn [req]
                 (when (:block? req)
                   (deliver entered true)
                   @release)
                 :ok)
               lifecycle-b)
        request-task (future (app-b {:block? true}))]
    (try
      (is (= true (deref entered 2000 :timeout)))
      (let [drain-task (future (api-middleware/shutdown-handler lifecycle-a))]
        (try
          (is (nil? (deref drain-task 2000 :timeout))
              "unrelated in-flight requests do not hold the drain open")
          (is (= {:status http-status/service-unavailable :body "Server stopping"}
                 (app-a {})))
          (is (= :ok (app-b {})) "the other instance still accepts requests")
          (is (= :ok ((api-middleware/wrap-server-status
                       (constantly :ok) (api-middleware/make-lifecycle)) {}))
              "a fresh instance accepts requests")
          (finally (future-cancel drain-task))))
      (finally
        (deliver release true)
        (is (= :ok (deref request-task 2000 :timeout)))
        (future-cancel request-task)))))

(deftest serve-api-lifecycle-test
  (let [hooks (atom [])
        apps (atom [])
        config {:api-config {:host "localhost" :port 8080}
                :auth-config {:introspection-endpoint "https://example.org/introspect"
                              :client-id "test" :client-secret "test"}}
        make-app (partial api-middleware/make-app
                          (constantly (constantly {:status 200 :body "ok"})))]
    (with-redefs [api-middleware/add-shutdown-hook! #(swap! hooks conj %)
                  app-server/run-jetty (fn [app options]
                                         (swap! apps conj app)
                                         (is (= {:host "localhost" :port 8080 :join? false} options))
                                         :server)]
      (dotimes [_ 2]
        (is (= :server (api-middleware/serve-api make-app config {:join? false})))))
    (is (= 2 (count @hooks)))
    ((first @hooks))
    (is (= {:status 503 :body "Server stopping"}
           ((first @apps) (request :get "/"))))
    (is (= 200 (:status ((second @apps) (request :get "/")))))
    (is (= 200 (:status ((make-app config) (request :get "/")))))
    ((second @hooks))
    (is (= {:status 503 :body "Server stopping"}
           ((second @apps) (request :get "/"))))))

(deftest wrap-access-control-test
  (let [private   (api-middleware/wrap-access-control-private (constantly :ok))
        read-only (api-middleware/wrap-access-control-read-only (constantly :ok))]
    (is (= :ok (private {:institution-oin "123"})))
    (is (= http-status/forbidden (:status (private {:client-id "client"}))))
    (is (= :ok (read-only {:client-id "client"})))
    (is (= http-status/forbidden (:status (read-only {:institution-oin "123"}))))))

(deftest wrap-uuid-validator-test
  (let [app (api-middleware/wrap-uuid-validator (constantly :ok))]
    (is (= :ok (app {:params {}})))
    (is (= :ok (app {:params {:id "12345678-1234-2345-3456-123456789abc"}})))
    (is (= :ok (app {:params {:token "12345678-1234-2345-3456-123456789abc"}})))
    (is (= {:status http-status/bad-request :body "Invalid UUID"}
           (app {:params {:id "not-a-uuid"}})))
    (is (= {:status http-status/bad-request :body "Invalid UUID"}
           (app {:params {:token "not-a-uuid"}})))))

(deftest wrap-job-enqueuer-test
  (doseq [enqueued-status [http-status/ok http-status/created]]
    (let [queue (atom [])
          app   (api-middleware/wrap-job-enqueuer identity #(swap! queue conj %) enqueued-status)]
      (is (= {:status http-status/ok} (app {:status http-status/ok}))
          "no job, nothing enqueued")
      (is (empty? @queue))
      (let [res (app {:status http-status/ok :job {:test "dummy"}})]
        (is (= enqueued-status (:status res)))
        (is (= {:token (:token (first @queue))} (:body res)))))))

(deftest wrap-callback-extractor-test
  (let [app (api-middleware/wrap-callback-extractor identity ::callback-url)]
    (is (= "https://surf.nl/"
           (-> (app {:job {} :headers {"x-callback" "https://surf.nl/"}}) :job ::callback-url)))
    (is (= {:status http-status/bad-request :body "Malformed callback url"}
           (app {:job {} :headers {"x-callback" "file:///etc/passwd"}})))))

(def ^:private job-routes
  (compojure.core/routes
   (POST "/job/:id" [id]
     {:job {:id id}})
   (POST "/link/:id/:code" [id code]
     {:job {:id id ::rio/aangeboden-opleiding-code code}})))

(deftest routes-test
  (let [last-job (atom nil)
        routes   (api-middleware/routes job-routes
                                        {:enqueuer-fn      #(reset! last-job %)
                                         :status-getter-fn {"12345678-1234-2345-3456-123456789abc" {:status :done}}
                                         :enqueued-status  http-status/created
                                         :callback-url-key ::callback-url})
        uuid     "12345678-1234-2345-3456-123456789abc"
        private  #(assoc % :institution-oin "123")
        reader   #(assoc % :client-id "client")]
    (is (:health (routes (request :get "/health"))))
    (is (= http-status/not-found (:status (routes (request :get "/unknown")))))

    (testing "job routes"
      (is (= http-status/forbidden (:status (routes (request :post (str "/job/" uuid))))))
      (is (= http-status/bad-request (:status (routes (private (request :post "/job/x"))))))
      (is (= http-status/bad-request
             (:status (routes (private (request :post (str "/link/" uuid "/x")))))))
      (is (nil? @last-job) "rejected requests must not enqueue")
      (let [res (routes (-> (request :post (str "/job/" uuid))
                            (assoc-in [:headers "x-callback"] "https://surf.nl/")
                            private))]
        (is (= http-status/created (:status res)))
        (is (= (:token @last-job) (get-in res [:body :token])))
        (is (= {:id uuid ::callback-url "https://surf.nl/"}
               (select-keys @last-job [:id ::callback-url])))))

    (testing "status route"
      (is (= http-status/forbidden (:status (routes (request :get (str "/status/" uuid))))))
      (is (= {:status http-status/ok :token uuid :body {:status :done}}
             (select-keys (routes (reader (request :get (str "/status/" uuid))))
                          [:status :token :body])))
      (is (= http-status/not-found
             (:status (routes (reader (request :get "/status/22345678-1234-2345-3456-123456789abc")))))))))
