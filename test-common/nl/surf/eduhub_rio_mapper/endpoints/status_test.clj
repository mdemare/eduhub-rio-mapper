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

(ns nl.surf.eduhub-rio-mapper.endpoints.status-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.rio.mutation-test-helper :as mutation-helper]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis]))

(deftest successful-upsert-retains-rio-code-test
  ;; OOAPI types: v5 education-specification/program, v6 programme.
  (doseq [[description ooapi-type result expected-attributes]
          [["opleidingseenheid v5" "education-specification"
            (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" "1010O8815")
            {:opleidingseenheidcode "1010O8815"}]
           ["opleidingseenheid v6" "programme"
            (mutation-helper/successful-mutation "aanleveren_opleidingseenheid" "1010O8815")
            {:opleidingseenheidcode "1010O8815"}]
           ["aangeboden opleiding v5" "program"
            (assoc (mutation-helper/successful-mutation "aanleveren_aangebodenOpleiding"
                                                       "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672")
                   ::rio/aangeboden-opleiding-code "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672")
            {:aangebodenopleidingcode "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672"}]
           ["aangeboden opleiding v6" "programme"
            (assoc (mutation-helper/successful-mutation "aanleveren_aangebodenOpleiding"
                                                       "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672")
                   ::rio/aangeboden-opleiding-code "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672")
            {:aangebodenopleidingcode "bd6cb46b-3f4e-49c2-a1f7-e24ae82b0672"}]]]
    (testing description
      (let [stored-status (atom nil)]
        ;; Capture the persisted status without requiring Redis.
        (with-redefs [redis/set (fn [_conn _key value & _opts]
                                 (reset! stored-status value))]
          ((status/make-set-status-fn {:status-ttl-sec 60} ::callback-url)
           {:token "test-upsert"
            :action "upsert"
            ::ooapi/type ooapi-type
            ::ooapi/id "12345678-1234-2345-3456-123456789abc"}
           :done result))
        (is (= :done (:status @stored-status)))
        (is (= (str ooapi-type "/12345678-1234-2345-3456-123456789abc")
               (:resource @stored-status)))
        (is (= expected-attributes (:attributes @stored-status)))))))

(deftest callback-url-key-test
  (let [callbacks     (atom [])
        set-status-fn (status/make-set-status-fn {:status-ttl-sec 60} ::callback-url)
        job           {:token "test-callback-url"
                       :action "delete"
                       ::callback-url "https://example.com/hook"}]
    (with-redefs [redis/set                (fn [& _])
                  status/do-async-callback (fn [_config url job]
                                             (swap! callbacks conj [url (:token job)]))]
      (testing "no callback for non-final status"
        (set-status-fn job :in-progress)
        (is (empty? @callbacks)))

      (testing "callback url is read from the given key on final status"
        (set-status-fn job :done)
        (is (= [["https://example.com/hook" "test-callback-url"]] @callbacks)))

      (testing "no callback without a url under the given key"
        (reset! callbacks [])
        (set-status-fn (dissoc job ::callback-url) :error)
        (set-status-fn (-> job
                           (dissoc ::callback-url)
                           (assoc :callback-url "https://example.com/hook"))
                       :error)
        (is (empty? @callbacks))))))

(def ^:private retry-sleep-ms 300)

(defn- run-callback
  "Run the webhook callback with `responses` (status codes, or exceptions
  to throw) and return the attempt timestamps and the finish time."
  [responses]
  (let [responses (atom responses)
        attempts  (atom [])]
    (with-redefs [status/rget                    (fn [_ _] {:status :done})
                  status/callback-retry-sleep-ms retry-sleep-ms
                  logging/log-exception          (fn [_ _])
                  http-utils/send-http-request   (fn [_req]
                                                   (swap! attempts conj (System/currentTimeMillis))
                                                   (let [r (first @responses)]
                                                     (swap! responses rest)
                                                     (if (instance? Exception r)
                                                       (throw r)
                                                       {:status r})))]
      (async/<!! (#'status/do-async-callback {} "https://example.com/hook" {:token "test-callback"}))
      {:attempts @attempts
       :finished (System/currentTimeMillis)})))

(deftest callback-retries-test
  (testing "successful callback is not retried"
    (is (= 1 (count (:attempts (run-callback [200]))))))

  (testing "failing callback is attempted 4 times without sleeping after the last attempt"
    (let [{:keys [attempts finished]} (run-callback (repeat 500))]
      (is (= 4 (count attempts)))
      (is (< (- finished (last attempts)) (/ retry-sleep-ms 2)))))

  (testing "connection errors are retried"
    (let [ex (java.net.ConnectException. "Connection refused")]
      (is (= 3 (count (:attempts (run-callback [ex ex 200])))))
      (is (= 4 (count (:attempts (run-callback (repeat ex)))))))))
