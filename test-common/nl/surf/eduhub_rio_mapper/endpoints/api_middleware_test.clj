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
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [ring.mock.request :refer [request]]))

(deftest wrap-server-status-test
  (with-redefs [api-middleware/server-stopping    (atom false)
                api-middleware/nr-active-requests (atom 0)]
    (let [active (atom nil)
          app    (api-middleware/wrap-server-status
                  (fn [_]
                    (reset! active @api-middleware/nr-active-requests)
                    {:status http-status/ok}))]
      (is (= {:status http-status/ok} (app {})))
      (is (= 1 @active) "request is counted while active")
      (is (= 0 @api-middleware/nr-active-requests) "request is uncounted when done")

      (testing "active requests are uncounted when the handler throws"
        (is (thrown? Exception ((api-middleware/wrap-server-status (fn [_] (throw (Exception. "boom")))) {})))
        (is (= 0 @api-middleware/nr-active-requests)))

      (testing "after shutdown"
        (api-middleware/shutdown-handler)
        (is (= {:status http-status/service-unavailable :body "Server stopping"}
               (app {})))))))

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
