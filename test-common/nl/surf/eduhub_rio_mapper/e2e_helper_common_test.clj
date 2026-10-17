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

(ns nl.surf.eduhub-rio-mapper.e2e-helper-common-test
  (:require [clj-http.client :as http]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.e2e-helper-common :as common :refer [job-done? job-dry-run-not-found?]]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils])
  (:import [java.io File]))

(defn- job [result]
  {:result-delay (delay result)})

(deftest job-result-predicates
  (let [done  (job {:status     "done"
                    :attributes {:a {:diff true} :opleidingseenheidcode "OE1" :status "found"}})
        error (job {:status        "error"
                    :attributes    {:a {:diff false} :status "not-found"}
                    :http-messages [:msg]})]
    (with-out-str
      (is (job-done? done))
      (is (not (job-done? error))))
    (is (common/job-error? error))
    (is (= "OE1" (common/job-result-opleidingseenheidcode done)))
    (is (= {:diff true} (common/job-result done :attributes :a)))
    (is (common/job-has-diffs? done))
    (is (common/job-without-diffs? error))
    (is (common/job-dry-run-found? done))
    (is (job-dry-run-not-found? error))
    (is (= {:status "error" :attributes {:a {:diff false} :status "not-found"}}
           (try (common/job-result-aangebodenopleidingcode error)
                (catch clojure.lang.ExceptionInfo e (ex-data e)))))))

(deftest assert-expr-methods
  (let [reports (atom [])
        error   (job {:status "error" :attributes {:status "not-found"}})]
    (binding [report #(swap! reports conj (select-keys % [:type :expected :actual :message]))]
      (is (job-done? error))
      (is (job-dry-run-not-found? error)))
    (is (= [{:type :fail :expected "done" :actual "error"
             :message "Expect final job status to equal 'done'"}
            {:type :pass :expected "not-found" :actual "not-found"
             :message "Expect final job status attributes status to equal 'not-found'"}]
           @reports))))

(deftest xml-lookups
  (let [dom (xml-utils/str->dom "<a xmlns='urn:x'><b><c>1</c><c>2</c></b></a>")]
    (is (= "1" (common/get-in-xml dom ["b" "c"])))
    (is (= ["1" "2"] (common/get-all-in-xml dom ["b" "c"])))))

(defn- test-helper [post-job-status log-file]
  (common/make-helper {:make-config     (constantly {:api-config {:host "mapper" :port 1234}})
                       :ooapi-id        (fn [type id] (str (name type) "-uuid-" id))
                       :post-job-status post-job-status
                       :posted-jobs-log (str log-file)
                       :mapper-alias    "-M:test"}))

(deftest post-job
  (let [log-file (File/createTempFile "posted-jobs" ".log")
        requests (atom [])
        statuses (atom ["pending" "done"])]
    (try
      (with-redefs [http/request (fn [{:keys [method url]}]
                                   (swap! requests conj [method url])
                                   (cond
                                     (str/starts-with? (str url) "http://mapper:1234/job/")
                                     {:status http-status/created :body {:token "T"}}

                                     (= "http://mapper:1234/status/T" url)
                                     (let [[s] (swap-vals! statuses rest)]
                                       {:status http-status/ok :body {:status (first s)}})

                                     :else ; bearer token
                                     {:status http-status/ok :body {:access_token "tok"}}))]
        (with-out-str
          (testing "polls status until final when posted with expected status"
            (let [job (common/post-job (test-helper http-status/created log-file)
                                       :upsert :programmes "p1")]
              (is (= http-status/created (:status job)))
              (is (= {:status "done"} @(:result-delay job)))
              (is (some #{[:post "http://mapper:1234/job/upsert/programmes/programmes-uuid-p1"]}
                        @requests))))

          (testing "no result when post status is unexpected"
            (is (nil? @(:result-delay (common/post-job (test-helper http-status/ok log-file)
                                                       :link "OE1" :courses "c1")))))))
      (is (= ":upsert (:programmes \"p1\")\n:link (\"OE1\" :courses \"c1\")\n"
             (slurp log-file)))
      (finally
        (.delete log-file)))))
