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

(ns nl.surf.eduhub-rio-mapper.v5.job-test
  (:require
    [clj-http.client :as client]
    [clojure.data.json :as json]
    [clojure.test :refer :all]
    [nl.jomco.http-status-codes :as http-status]
    [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
    [nl.surf.eduhub-rio-mapper.rio.mutation-test-helper :as mutation-helper]
    [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
    [nl.surf.eduhub-rio-mapper.v5.job :as job]
    [nl.surf.eduhub-rio-mapper.v5.test-helper :as helper])
  (:refer-clojure :exclude [run!]))

(def dummy-handlers {:delete! identity, :update! identity, :dry-run! identity, :link! identity})

(def dummy-job {::ooapi/id "0", ::ooapi/type "0", :action "delete", :institution-schac-home 0, :institution-oin 0})

(def config
  {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
   :redis-key-prefix "eduhub-rio-mapper-test"
   :status-ttl-sec   10
   :worker           {:nap-ms        10
                      :retry-wait-ms 10
                      :queues        ["foo" "bar"]
                      :queue-fn      :queue
                      :retryable-fn  (constantly false)
                      :error-fn      (constantly false)
                      :set-status-fn (fn [_ _ & [_]] (comment "nop"))}})

(deftest run!
  (testing "throwing an exception"
    (let [msg      "boom"
          handlers (assoc dummy-handlers :delete! (fn [_] (throw (ex-info msg {}))))]
      (is (status/retryable? (job/run! handlers dummy-job false))
          "throwing an exception results a retryable error")
      (is (= msg (-> (job/run! handlers dummy-job false) :errors :message))
          "throwing an exception results a retryable error"))))

(deftest ^:redis webhook
  (testing "webhook"
    (let [last-seen-request-atom (atom nil)
          set-status-fn          (status/make-set-status-fn config ::job/callback-url)
          job                    {::job/callback-url "https://github.com/"
                                  :action            "delete"
                                  ::ooapi/type       "course"
                                  ::ooapi/id         "123123"
                                  :created-at        "2024-08-30T08:41:34.929378Z"
                                  :started-at        "2024-08-30T08:41:34.929378Z"
                                  :token             "12345"}
          mock-webhook           (fn mock-webhook [req]
                                   (reset! last-seen-request-atom req)
                                   {:status http-status/ok
                                    :body   {:active    true
                                             :client_id "institution_client_id"}})]
      (binding [client/request mock-webhook]
        (set-status-fn job :done (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" "123"))
        (helper/wait-while-predicate nil? last-seen-request-atom 1)
        (let [req @last-seen-request-atom]
          (is (= {:status        "done"
                  :action        "delete"
                  :resource      "course/123123"
                  :attributes    {:opleidingseenheidcode "123"}
                  :created-at    "2024-08-30T08:41:34.929378Z"
                  :started-at    "2024-08-30T08:41:34.929378Z"
                  :token         "12345"}
                 (dissoc (json/read-str (:body req) {:key-fn keyword}) :finished-at)))
          (is (= (:url req) "https://github.com/"))))))

  (testing "webhook with http-messages"
    (let [config-with-http-logs  (assoc config :store-http-requests true)
          last-seen-request-atom (atom nil)
          set-status-fn          (status/make-set-status-fn config-with-http-logs ::job/callback-url)
          http-messages          [{:req {:url "https://example.com/"} :res {:status 200}}]
          job                    {::job/callback-url "https://github.com/"
                                  :action            "delete"
                                  ::ooapi/type       "course"
                                  ::ooapi/id         "123123"
                                  :created-at        "2024-08-30T08:41:34.929378Z"
                                  :started-at        "2024-08-30T08:41:34.929378Z"
                                  :token             "67890"}
          mock-webhook           (fn mock-webhook [req]
                                   (reset! last-seen-request-atom req)
                                   {:status http-status/ok
                                    :body   {:active    true
                                             :client_id "institution_client_id"}})
          setup-test             (fn setup-test [job]
                                   (reset! last-seen-request-atom nil)
                                   (set-status-fn job :done
                                                  (assoc (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" "1234O4321")
                                                         :http-messages http-messages))
                                   (helper/wait-while-predicate nil? last-seen-request-atom 1)
                                   (json/read-str (:body @last-seen-request-atom) {:key-fn keyword}))]
      (binding [client/request mock-webhook]
        (testing "without suppress-http-messages flag"
          (let [body (setup-test job)]
            (is (some? (:http-messages body))
                "http-messages should be included in callback when suppress flag is not set")
            (is (= http-messages (:http-messages body)))))

        (testing "with suppress-http-messages flag set to true"
          (let [body (setup-test (assoc job :suppress-http-messages true :token "11111"))]
            (is (nil? (:http-messages body))
                "http-messages should NOT be included in callback when suppress flag is true")))

        (testing "with suppress-http-messages flag set to false"
          (let [body (setup-test (assoc job :suppress-http-messages false :token "22222"))]
            (is (some? (:http-messages body))
                "http-messages should be included in callback when suppress flag is false")
            (is (= http-messages (:http-messages body)))))))))
