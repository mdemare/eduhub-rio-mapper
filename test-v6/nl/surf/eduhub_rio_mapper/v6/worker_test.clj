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

(ns nl.surf.eduhub-rio-mapper.v6.worker-test
  (:require [clojure.test :refer :all]
            [nl.surf.eduhub-rio-mapper.redis-test-helper :as redis-helper]
            [nl.surf.eduhub-rio-mapper.v6.test-helper :as helper]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(def num-of-workers 50)

(def config
  {:redis-conn       redis-helper/redis-conn
   :redis-key-prefix redis-helper/redis-key-prefix
   :status-ttl-sec   10
   :worker           {:nap-ms               10
                      :retry-wait-ms        10
                      :max-retries          3
                      :queues               ["foo" "bar"]
                      :queue-fn             :queue
                      :retryable-fn         (constantly false)
                      :error-fn             (constantly false)
                      :jobs-counter-fn      (constantly nil)
                      :queued-jobs-gauge-fn (constantly nil)
                      :set-status-fn        (fn [_ _ & [_]] (comment "nop"))}})

(deftest ^:redis worker
  (let [job-runs (atom {"foo" [], "bar" []})
        config   (-> config
                     (assoc-in [:worker :run-job-fn]
                               (fn [{:keys [queue job-nr]}]
                                 (swap! job-runs update queue conj job-nr))))]
    (worker/purge! config)

    ;; queue some without running workers
    (doseq [n (range 100)]
      (worker/enqueue! config {:queue "foo", :job-nr n}))
    (doseq [n (range 50)]
      (worker/enqueue! config {:queue "bar", :job-nr n}))

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]

      ;; wait till work is done and check it
      (let [expected {"foo" (range 100)
                      "bar" (range 50)}]
        (helper/wait-for-expected expected job-runs 30))

      ;; queue more work
      (doseq [n (range 100 200)]
        (worker/enqueue! config {:queue "foo", :job-nr n}))
      (doseq [n (range 50 100)]
        (worker/enqueue! config {:queue "bar", :job-nr n}))

      ;; wait till work is done and check it
      (let [expected {"foo" (range 200)
                      "bar" (range 100)}]
        (helper/wait-for-expected expected job-runs 30)
        (is (= expected @job-runs)))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis all-retries
  (let [last-seen-job (atom nil)
        max-retries   3
        config        (-> config
                          (assoc-in [:worker :max-retries] max-retries)
                          (assoc-in [:worker :jobs-counter-fn] (constantly nil))
                          (assoc-in [:worker :run-job-fn]
                                    (fn [job]
                                      (reset! last-seen-job (dissoc job :started-at))
                                      :from-job))
                          (assoc-in [:worker :retryable-fn]
                                    (fn [result]
                                      (= result :from-job))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]
      ;; queue a nacking job
      (worker/enqueue! config {:queue "foo"})

      ;; wait job to finish and check it
      (let [expected {:queue           "foo"
                      ::worker/retries max-retries}]
        (helper/wait-for-expected expected last-seen-job 5))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis two-retries
  (let [last-seen-job (atom nil)
        max-retries   3
        config        (-> config
                          (assoc-in [:worker :max-retries] max-retries)
                          (assoc-in [:worker :jobs-counter-fn] (constantly nil))
                          (assoc-in [:worker :run-job-fn]
                                    (fn [job]
                                      (reset! last-seen-job (dissoc job :started-at))
                                      (::worker/retries job)))
                          (assoc-in [:worker :retryable-fn]
                                    (fn [result]
                                      (not= 2 result))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]
      ;; queue a nacking job
      (worker/enqueue! config {:queue "foo"})

      ;; wait job to finish and check it
      (let [expected {:queue           "foo"
                      ::worker/retries 2}]
        (helper/wait-for-expected expected last-seen-job 5))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis retry-wait
  (let [last-seen-job (atom nil)
        retry-wait-ms 3000
        config        (-> config
                          (assoc-in [:worker :retry-wait-ms] retry-wait-ms)
                          (assoc-in [:worker :jobs-counter-fn] (constantly nil))
                          (assoc-in [:worker :run-job-fn]
                                    (fn [job]
                                      (reset! last-seen-job (dissoc job :started-at))
                                      (::worker/retries job)))
                          (assoc-in [:worker :retryable-fn]
                                    (fn [result]
                                      (not= 1 result))))]
    (worker/purge! config)

    ;; spin up a bunch of workers
    (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]

      ;; queue a job to be retried once
      (worker/enqueue! config {:queue "foo"})

      (let [before-ms (System/currentTimeMillis)
            expected  {:queue           "foo"
                       ::worker/retries 1}]
        (helper/wait-for-expected expected last-seen-job 10)
        (is (>= (- (System/currentTimeMillis) before-ms)
                retry-wait-ms)
            "wall time should be at least retry-wait-ms"))

      ;; stop workers
      (doseq [worker workers] (worker/stop-worker! worker))
      (worker/purge! config))))

(deftest ^:redis set-status
  (testing "enqueuing jobs"
    (let [last-seen-status (atom nil)
          config           (-> config
                               (assoc-in [:worker :error-fn] :error?)
                               (assoc-in [:worker :run-job-fn] identity)
                               (assoc-in [:worker :jobs-counter-fn] (constantly nil))
                               (assoc-in [:worker :set-status-fn]
                                         (fn [job status & [data]]
                                           (reset! last-seen-status {:job    (dissoc job :started-at)
                                                                     :status status
                                                                     :data   (dissoc data :started-at)}))))]
      (worker/purge! config)

      ;; queue a successful job
      (worker/enqueue! config {:queue "foo"})
      (is (= {:job {:queue "foo"}, :status :pending, :data nil}
             @last-seen-status))

      ;; spin up a bunch of workers
      (let [workers (mapv (fn [_] (worker/start-worker! config)) (range num-of-workers))]

        ;; wait for successful job to finish
        (helper/wait-for-expected {:job    {:queue "foo"}
                                   :status :done
                                   :data   {:queue "foo"}}
                                  last-seen-status 10)

        ;; queue and wait for error job
        (worker/enqueue! config {:queue "foo", :error? true})
        (helper/wait-for-expected {:job    {:queue "foo", :error? true}
                                   :status :error
                                   :data   {:queue "foo", :error? true}}
                                  last-seen-status 10)

        ;; stop workers
        (doseq [worker workers] (worker/stop-worker! worker))
        (worker/purge! config)))))
