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

(ns nl.surf.eduhub-rio-mapper.worker-test
  (:require [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis]
            [nl.surf.eduhub-rio-mapper.worker :as worker]))

(deftest enqueue-status-ordering
  (doseq [[enqueue push] [[worker/enqueue! #'redis/rpush]
                        [worker/enqueue-first! #'redis/lpush]]
          final-status [:done :error :time-out]]
    (testing (str (:name (meta push)) " with immediate " final-status)
      (let [job {:queue "foo" :token "token"}
            result {:message "completed"}
            statuses (atom [])
            last-status (atom nil)
            set-status! (fn [_ status & [data]]
                          (swap! statuses conj status)
                          (reset! last-status [status data]))
            config {:worker {:queue-fn :queue :set-status-fn set-status!}}]
        ;; Run the consumer before the push returns, forcing the interleaving
        ;; that allowed the producer's pending write to overwrite completion.
        (with-redefs-fn {push (fn [_ queue published-job]
                               (is (= "eduhub-rio-mapper.worker:queue:foo" queue))
                               (is (= job published-job))
                               (set-status! published-job :in-progress)
                               (set-status! published-job final-status result)
                               1)}
          #(is (= job (enqueue config job))))
        (is (= [:pending :in-progress final-status] @statuses))
        (is (= [final-status result] @last-status))))))

(deftest enqueue-failures
  (doseq [[enqueue push] [[worker/enqueue! #'redis/rpush]
                        [worker/enqueue-first! #'redis/lpush]]]
    (let [failure (ex-info "Redis push failed" {})
          statuses (atom [])
          config {:worker {:queue-fn :queue
                           :set-status-fn (fn [_ status & [_]]
                                            (swap! statuses conj status))}}]
      (testing "a failed push propagates its original exception and leaves no pending job"
        (with-redefs-fn {push (fn [& _] (throw failure))}
          #(is (identical? failure
                           (try (enqueue config {:queue "foo"})
                                (catch Exception ex ex)))))
        (is (= [:pending :error] @statuses)))
      (testing "a failed pending write prevents publishing the job"
        (let [pushed? (atom false)]
          (with-redefs-fn {push (fn [& _] (reset! pushed? true))}
            #(is (identical? failure
                             (try (enqueue (assoc-in config [:worker :set-status-fn]
                                                     (fn [& _] (throw failure)))
                                           {:queue "foo"})
                                  (catch Exception ex ex)))))
          (is (false? @pushed?))))
      (testing "a failed error-status write does not mask the push failure"
        (let [status-failure (ex-info "Redis status write failed" {})
              config (assoc-in config [:worker :set-status-fn]
                               (fn [_ status & [_]]
                                 (when (= :error status)
                                   (throw status-failure))))]
          (with-redefs-fn {push (fn [& _] (throw failure))}
            #(is (identical? failure
                             (try (enqueue config {:queue "foo"})
                                  (catch Exception ex ex))))))))))
