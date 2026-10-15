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

(ns nl.surf.eduhub-rio-mapper.v5.endpoints.api-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [clojure.walk :as walk]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.api-doc-helper :as api-doc]
            [nl.surf.eduhub-rio-mapper.endpoints.api-middleware :as api-middleware]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.rio.mutation-test-helper :as mutation-helper]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.v5.endpoints.api :as api]
            [nl.surf.eduhub-rio-mapper.v5.job :as job]
            [ring.mock.request :refer [request]]))

(defn authenticated-request [method path]
  (assoc (request method path)
    :client-id 123
    :institution-oin "123"))

(def config nil)
(def last-job (atom nil))
(def api-routes (api/routes {:enqueuer-fn      (fn [job] (reset! last-job job) nil)
                             :status-getter-fn (constantly {:test :dummy})}))

(deftest uuid-validation
  (is (= http-status/bad-request (:status (api-routes (authenticated-request :get "/status/invalid-token"))))))

(deftest routes
  (are [method path]
      (= http-status/bad-request (:status (api-routes (authenticated-request method path))))
    :get  "/status/1"
    :post "/job/upsert/courses/bleh"
    :post "/job/link/1234O4321/courses/abcdefgh-ijkl-mnop-qrst-uvwxyzabcdef")

  (are [method path]
      (is (= http-status/not-found (:status (api-routes (authenticated-request method path)))))
    :get  "/blerk"
    :get  "/job/upsert/courses/31415"
    :get  "/status"
    :post "/job/upsert/blerk/12345678-1234-2345-3456-123456789abc"
    :post "/job/blerk/courses/12345678-1234-2345-3456-123456789abc"
    :post "/job/link/1234O4321/blerk/12345678-1234-2345-3456-123456789abc"
    :post "/job/unlink/1234O4321/blerk"
    :post "/job/link/1234O4321/programmes/12345678-1234-2345-3456-123456789abc"
    :post "/job/unlink/1234O4321/programmes")

  (testing "link without rio-code is not accepted as a job"
    (are [path]
        (do (reset! last-job nil)
            (let [{:keys [status]} (api-routes (authenticated-request :post path))]
              (and (is (= http-status/not-found status))
                   (is (nil? @last-job)))))
      "/job/link/courses/12345678-1234-2345-3456-123456789abc"
      "/job/link/education-specifications/12345678-1234-2345-3456-123456789abc"))

  (are [expected-job path]
      (let [{:keys [job status]} (-> :post
                                     (authenticated-request path)
                                     (assoc :institution-schac-home "edu.nl")
                                     api-routes)]
        (is (= http-status/ok status))
        (is (= expected-job job)))

    {:action                 "upsert"
     ::ooapi/type            "course"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/upsert/courses/12345678-1234-2345-3456-123456789abc"

    {:action                 "upsert"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/upsert/education-specifications/12345678-1234-2345-3456-123456789abc"

    {:action                 "upsert"
     ::ooapi/type            "program"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/upsert/programs/12345678-1234-2345-3456-123456789abc"

    {:action                 "delete"
     ::ooapi/type            "course"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/delete/courses/12345678-1234-2345-3456-123456789abc"

    {:action                 "delete"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/delete/education-specifications/12345678-1234-2345-3456-123456789abc"

    {:action                 "link"
     ::ooapi/type            "education-specification"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     ::rio/opleidingscode    "1234O4321"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/link/1234O4321/education-specifications/12345678-1234-2345-3456-123456789abc"

    {:action                         "link"
     ::ooapi/type                    "course"
     ::ooapi/id                      "12345678-1234-2345-3456-123456789abc"
     ::rio/aangeboden-opleiding-code "00000000-0000-0000-0000-000000000000"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/link/00000000-0000-0000-0000-000000000000/courses/12345678-1234-2345-3456-123456789abc"

    {:action                         "link"
     ::ooapi/type                    "program"
     ::ooapi/id                      "12345678-1234-2345-3456-123456789abc"
     ::rio/aangeboden-opleiding-code "00000000-0000-0000-0000-000000000000"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/link/00000000-0000-0000-0000-000000000000/programs/12345678-1234-2345-3456-123456789abc"

    ;; Unlink is link to id with value `nil`
    {:action                 "link"
     ::ooapi/type            "education-specification"
     ::ooapi/id              nil
     ::rio/opleidingscode    "1234O4321"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/unlink/1234O4321/education-specifications"

    {:action                         "link"
     ::ooapi/type                    "course"
     ::ooapi/id                      nil
     ::rio/aangeboden-opleiding-code "00000000-0000-0000-0000-000000000000"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/unlink/00000000-0000-0000-0000-000000000000/courses"

    {:action                         "link"
     ::ooapi/type                    "program"
     ::ooapi/id                      nil
     ::rio/aangeboden-opleiding-code "00000000-0000-0000-0000-000000000000"
     :institution-schac-home         "edu.nl"
     :institution-oin                "123"}
    "/job/unlink/00000000-0000-0000-0000-000000000000/programs"

    {:action                 "delete"
     ::ooapi/type            "program"
     ::ooapi/id              "12345678-1234-2345-3456-123456789abc"
     :institution-schac-home "edu.nl"
     :institution-oin        "123"}
    "/job/delete/programs/12345678-1234-2345-3456-123456789abc")

  (is (= "12345678-1234-2345-3456-123456789abc"
         (-> (assoc (request :get "/status/12345678-1234-2345-3456-123456789abc")
               :client-id "123")
             (api-routes)
             :token))))

(deftest wrap-access-control
  (is (= http-status/forbidden
         (:status (api-routes (request :get "/status/12345678-1234-2345-3456-123456789abc"))))
      "no client, status request")

  (is (= http-status/ok
         (:status (api-routes (assoc (request :get "/status/12345678-1234-2345-3456-123456789abc") :client-id "ludwig"))))
      "read only client, status request")

  (is (= http-status/forbidden
         (:status (api-routes (assoc (request :post "/job/delete/courses/123") :client-id "ludwig"))))
      "read only client, mutation request")

  (is (= http-status/ok
         (:status (api-routes (assoc (request :get "/status/12345678-1234-2345-3456-123456789abc")
                                :client-id "wolfgang"
                                :institution-oin "123",
                                :institution-schac-home "uu.nl"))))
        "real client, status request")

  (is (= http-status/ok
         (:status (api-routes (assoc (request :post "/job/delete/courses/12345678-1234-2345-3456-123456789abc")
                                :client-id "wolfgang"
                                :institution-oin "123",
                                :institution-schac-home "uu.nl"))))
        "real client, mutation request"))

(deftest wrap-code-validator
  (let [app (api-middleware/wrap-code-validator identity)]

    (is (= {:status 400, :body "Invalid aangeboden opleidingcode 'foo'"}
           (app {:job {::rio/aangeboden-opleiding-code "foo"}}))
        "invalid aangeboden opleiding code")

    (let [res {:job {::rio/aangeboden-opleiding-code "12345678-1234-2345-3456-123456789abc"}}]
      (is (= res (app res))
          "valid aangeboden opleiding code"))

    (is (= {:status 400, :body "Invalid opleidingscode 'foo'"}
           (app {:job {::rio/opleidingscode "foo"}}))
        "invalid opleidingscode")

    (let [res {:job {::rio/opleidingscode "1234O4321"}}]
      (is (= res (app res))
          "valid opleidingscode"))))

(deftest wrap-job-queuer
  (let [queue-atom (atom [])
        app        (api-middleware/wrap-job-enqueuer identity #(swap! queue-atom conj %) (:enqueued-status api/version-options))]

    (is (= {} (app {}))
        "no job, do nothing")

    (let [res (app {:job {:test "dummy"}})]
      (is (-> res :body :token)
          "token returned")

      (let [job-result (-> @queue-atom first (dissoc :token))]
        (is (= [:test :created-at :suppress-http-messages] (keys job-result)))
        (is (= {:test "dummy"}
               (dissoc job-result :created-at :suppress-http-messages))
            "job queued"))

      (is (-> @queue-atom first :token)
          "job has token")

      (is (= (-> @queue-atom first :token)
             (-> res :body :token))
          "job token same as returned token"))

    (testing "suppress-http-messages header"
      (reset! queue-atom [])

      (testing "when header is absent"
        (app {:job {:test "dummy"}})
        (is (false? (:suppress-http-messages (first @queue-atom)))
            "suppress-http-messages should be false when header is absent"))

      (reset! queue-atom [])

      (testing "when header is 'true'"
        (app {:job {:test "dummy"} :headers {"x-suppress-http-messages" "true"}})
        (is (true? (:suppress-http-messages (first @queue-atom)))
            "suppress-http-messages should be true when header is 'true'"))

      (reset! queue-atom [])

      (testing "when header is 'false'"
        (app {:job {:test "dummy"} :headers {"x-suppress-http-messages" "false"}})
        (is (false? (:suppress-http-messages (first @queue-atom)))
            "suppress-http-messages should be false when header is 'false'"))

      (reset! queue-atom [])

      (testing "when header is any other value"
        (app {:job {:test "dummy"} :headers {"x-suppress-http-messages" "tomato"}})
        (is (false? (:suppress-http-messages (first @queue-atom)))
            "suppress-http-messages should be false when header is not 'true'")))))

(deftest wrap-callback-extractor
  (let [app        (api-middleware/wrap-callback-extractor identity (:callback-url-key api/version-options))]

    (is (= {} (app {}))
        "no callback, do nothing")

    (let [res (app {:job {} :headers {"x-callback" "dummy"}})]
      (is (= 400 (:status res))
          "illegal url"))

    (let [res (app {:job {} :headers {"x-callback" "https://surf.nl/"}})]
      (is (-> res :job ::job/callback-url)
          "valid url"))))

(deftest wrap-mocked-status-getter
  (let [http-messages-json [{:req {:headers {"Accept" "application/json"}} :res {:status {:finished true}, :body "{\"foo\": 123}"}}]
        http-messages-xml [{:req {:headers {"Accept" "application/xml"}} :res {:status {:finished true}, :body "{\"foo\": 123}"}}]
        status {:http-messages http-messages-json, :profile "rio"}
        f (fn mocked-wrap-status-getter [req status-getter]
            (let [f (api-middleware/wrap-status-getter
                      (fn [req] {:token (:token req)})
                      {:status-getter-fn status-getter})]
              (f req)))]
    (testing "Token is nil"
      (let [actual (f {:token nil} (constantly status))]
        (is (= {:token nil} actual))))
    (testing "Status is nil"
      (let [actual (f {:token "T"} (constantly nil))]
        (is (= {:status http-status/not-found, :token "T", :body {:status :unknown}} actual))))
    (testing "http-messages is false"
      (let [actual (f {:token "T"} (constantly status))]
        (is (= {:status http-status/ok, :token "T", :body {:profile "rio"}} actual))))
    (testing "Accept header is not json but xml"
      (let [actual (f {:token "T" :params {:http-messages "true"}} (constantly (assoc status :http-messages http-messages-xml)))]
        (is (= {:status http-status/ok,
                :token "T",
                :body {:profile "rio",
                       :http-messages [{:req {:headers {"Accept" "application/xml"}}, :res {:status {:finished true}, :body "{\"foo\": 123}"}}]}}
               actual))))
    (testing "Full functionality"
      (let [actual (f {:token "T" :params {:http-messages "true"}} (constantly status))]
        (is (= {:status http-status/ok,
                :token "T",
                :body
                {:profile "rio",
                 :http-messages
                 [{:req {:headers {"Accept" "application/json"}},
                   :res
                   {:status {:finished true},
                    :body "{\"foo\": 123}",
                    :json-body {:foo 123}}}]}}
               actual))))))

(deftest ^:redis wrap-status-getter
  (let [config      {:redis-conn       {:pool {} :spec {:uri (or (System/getenv "REDIS_URI") "redis://localhost")}}
                     :redis-key-prefix "eduhub-rio-mapper-test"
                     :status-ttl-sec   10}
        app         (api-middleware/wrap-status-getter identity {:status-getter-fn (partial status/rget config)})
        status-set! (status/make-set-status-fn config ::job/callback-url)]
    (status/purge! config)

    (status-set! {:token       "test-pending"
                  :action      "upsert"
                  :created-at  "2024-08-30T08:41:34.929378Z"
                  :started-at  nil
                  ::ooapi/type "test"
                  ::ooapi/id   "314"}
                 :pending)

    (status-set! {:token       "test-error"
                  :action      "link"
                  :created-at  "2024-08-30T08:41:34.929378Z"
                  :started-at  "2024-08-30T08:41:34.929378Z"
                  ::ooapi/type "test"
                  ::ooapi/id   "3141"}
                 :error
                 {:errors
                  {:random  "crap"
                   :message "error"
                   :phase   "middle"}})

    (status-set! {:token       "test-done"
                  :action      "delete"
                  :created-at  "2024-08-30T08:41:34.929378Z"
                  :started-at  "2024-08-30T08:41:34.929378Z"
                  ::ooapi/type "test"
                  ::ooapi/id   "31415"}
                 :done
                 (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" "code"))

    ;; without status
    (is (= {:token  "unknown"
            :status http-status/not-found
            :body   {:status :unknown}}
           (app {:token "unknown"})))

    ;; test pending
    (is (= {:token  "test-pending"
            :status http-status/ok
            :body   {:status   :pending
                     :action   "upsert"
                     :token    "test-pending"
                     :created-at true
                     :started-at nil
                     :resource "test/314"}}
           (cond-> (app {:token "test-pending"})
                   :created-at (assoc-in [:body :created-at] true))))

    ;; test done status
    (is (= {:token  "test-done"
            :status http-status/ok
            :body   {:status     :done
                     :action     "delete"
                     :token      "test-done"
                     :resource   "test/31415"
                     :created-at true
                     :started-at  "2024-08-30T08:41:34.929378Z"
                     :finished-at true
                     :attributes {:opleidingseenheidcode "code"}}}
           (cond-> (app {:token "test-done"})
                   :created-at (assoc-in [:body :created-at] true)
                   :finished-at (assoc-in [:body :finished-at] true))))

    ;; test error status
    (is (= {:token  "test-error"
            :status http-status/ok
            :body   {:status   :error
                     :token    "test-error"
                     :action   "link"
                     :resource "test/3141"
                     :created-at true
                     :started-at  "2024-08-30T08:41:34.929378Z"
                     :finished-at true
                     :phase    "middle"
                     :message  "error"}}
           (cond-> (app {:token "test-error"})
                   :created-at (assoc-in [:body :created-at] true)
                   :finished-at (assoc-in [:body :finished-at] true))))
    (status/purge! config)))

(deftest jobqueue
  (let [req (authenticated-request :post "/job/upsert/education-specifications/12345678-1234-2345-3456-123456789abc")
        req (assoc-in req [:headers "x-callback"] "https://google.com/")]
    (is (= http-status/ok (:status (api-routes req))))
    (is (= "https://google.com/" (::job/callback-url @last-job)))))

;; In the http-messages, generally keywords are used for keys, except for the headers, there we use strings.
(defn- stringify-headers [{req :req, res :res}]
  {:res res
   :req (assoc req :headers (walk/stringify-keys (:headers req)))})

(deftest status-http-messages
  (let [http-message (-> (slurp "test-v5/fixtures/http-messages-1.json")
                          (json/read-str :key-fn keyword)
                          :http-messages
                          first
                          stringify-headers
                          api-middleware/add-single-parsed-json-response)]
    (is (string? (get-in http-message [:res :body])))
    (is (map? (get-in http-message [:res :json-body])))
    (is (= "/programs/0f212491-c96a-4141-8718-86d40a4ebfd3?returnTimelineOverrides=true"
           (get-in http-message [:res :json-body :gateway :request])))))

(deftest api-documentation
  (testing "documented requests are accepted"
    (doseq [[method path] (api-doc/documented-requests :v5)]
      (let [{:keys [status]} (-> (authenticated-request method path)
                                 (assoc :institution-schac-home "edu.nl")
                                 api-routes)]
        (is (= (if (= :get method) http-status/ok (api-doc/documented-job-response-status :v5))
               status)
            (str method " " path))))))
