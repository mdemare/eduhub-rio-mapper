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

(ns nl.surf.eduhub-rio-mapper.worker
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [nl.surf.eduhub-rio-mapper.endpoints.metrics :as metrics]
            [nl.surf.eduhub-rio-mapper.utils.exception-utils :as ex-util]
            [nl.surf.eduhub-rio-mapper.utils.logging :as logging]
            [nl.surf.eduhub-rio-mapper.utils.redis :as redis]
            [taoensso.carmine :as car])
  (:import java.io.EOFException
           java.util.UUID
           [java.net UnknownHostException]
           [java.time Instant]))

(defn- prefix-key
  [{:keys [redis-key-prefix]
    :or {redis-key-prefix "eduhub-rio-mapper"}}
   key]
  (str redis-key-prefix ".worker:" key))

(defn- lock-name [config queue]
  (prefix-key config (str "lock:" queue)))

(defn acquire-lock!
  "Acquire lock on `queue` with `ttl-ms`.
  Return a unique token which can be used to release / extend the
  lock or `nil` when lock is already taken."
  [{:keys [redis-conn] :as config} queue ttl-ms]
  (let [k     (lock-name config queue)
        token (str (UUID/randomUUID))]
    (when (= "OK" (redis/set redis-conn k token "NX" "PX" ttl-ms))
      token)))

(defn release-lock!
  "Release lock on `queue`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config} queue token]
  (let [k (lock-name config queue)
        lua-script "if redis.call('get', _:k) == _:token then
                      redis.call('del', _:k);
                      return 1;
                    else
                      return 0;
                    end"
        lua-result (car/wcar redis-conn (car/lua lua-script {:k k} {:token token}))]
    (assert (number? lua-result))
    (when (not= 1 lua-result)
      (throw (ex-info "Lock lost before release!" {:lock-name k})))))

(defn extend-lock!
  "Extend TTL on lock on `queue` with `token` by `ttl-ms`.
  Uses `token` to verify if the lock is still ours and throws an
  exception when it's not."
  [{:keys [redis-conn] :as config} queue token ttl-ms]
  (let [k (lock-name config queue)
        lua-script "if redis.call('get', _:k) == _:token then
                      redis.call('set', _:k, _:token, 'px', _:ttl-ms);
                      return 1;
                    else
                      return 0;
                    end"
        lua-result (car/wcar redis-conn (car/lua lua-script {:k k} {:token token, :ttl-ms ttl-ms}))]
    (assert (number? lua-result))
    (when (not= 1 lua-result)
      (throw (ex-info "Lock lost before extend!" {:lock-name k})))))

(defn- queue-key [config queue]
  (prefix-key config (str "queue:" queue)))

(defn- busy-queue-key [config queue]
  (prefix-key config (str "busy-queue:" queue)))

(defn queue-counts-by-key [query-type {:keys [redis-conn] :as config}]
  (let [query (case query-type
                :queue (queue-key config "*")
                :busy-queue (busy-queue-key config "*"))
        prefix-len (dec (count query))]
    (->> (redis/keys redis-conn query)
         (map (juxt #(subs % prefix-len)
                    #(redis/llen redis-conn %)))
         (into {}))))

(defn- add-to-queue!
  [{:keys [redis-conn]
    {:keys [queue-fn
            set-status-fn]} :worker
    :as config} job side]
  ((case side
     :left redis/lpush
     :right redis/rpush)
   redis-conn (queue-key config (queue-fn job)) job)
  (set-status-fn job :pending)
  job)

(defn enqueue!
  "Enqueue a job."
  [config job]
  (add-to-queue! config job :right))

(defn enqueue-first!
  "Enqueue a job in front of the queue."
  [config job]
  (add-to-queue! config job :left))

(defn- pop-job!
  "Pop job from a `queue`.
  The job is stored on the associated busy queue to allow restarting
  when aborted.  Returns a job or `nil` when queue is empty."
  [{:keys [redis-conn] :as config} queue]
  (redis/lmove redis-conn
               (queue-key config queue)
               (busy-queue-key config queue)
               "LEFT"
               "RIGHT"))

(defn occupied-queues
  "Return list of queues having jobs queued."
  [{:keys [redis-conn] {:keys [queues]} :worker :as config}]
  (let [script (apply str "local ids = {};"
                      (conj
                       (mapv #(str "if redis.call('llen', _:i" % ") > 0 then
                                      table.insert(ids, _:v" % ")
                                    end;")
                             (range (count queues)))
                       "return ids;"))
        ks   (into {} (map #(vector (keyword (str "i" %1))
                                    (queue-key config %2))
                           (range (count queues))
                           queues))
        vs   (into {} (map #(vector (keyword (str "v" %1)) %2)
                           (range (count queues))
                           queues))]
    (car/wcar redis-conn (car/lua script ks vs))))

(defn- recover-aborted-job!
  "Recover aborted job, when available, and queue on `queue` it."
  [{:keys [redis-conn] :as config} queue]
  (redis/lmove redis-conn
               (busy-queue-key config queue)
               (queue-key config queue)
               "RIGHT"
               "LEFT"))

(defn- job-done!
  "Remove job from `queue` from its busy queue."
  [{:keys [redis-conn] :as config} queue]
  (redis/lpop redis-conn (busy-queue-key config queue)))

(defn- crash-result
  "Build an error result for a job which did not produce a result.
  This happens when the job throws a `Throwable` which is not handled
  by the job itself (like an `AssertionError` or `StackOverflowError`)
  or when the job channel closes without a value.  The error is logged
  and the result is an `:errors` map, like the ones returned by
  `job/run!`, which is not retryable."
  [job ex]
  (let [error-id      (UUID/randomUUID)
        trace-context (:trace-context job)
        message       (if ex
                        (str "Job crashed: " (or (ex-message ex) (.getName (class ex))))
                        "Job crashed: no result")]
    (logging/with-mdc (assoc trace-context :phase :unknown)
      (if ex
        (logging/log-exception ex error-id)
        (log/errorf "%s, error-id: %s, job: %s" message error-id (pr-str job))))
    {:errors {:error-id      error-id
              :trace-context trace-context
              :phase         :unknown
              :message       message
              :retryable?    false}}))

(defn- worker-loop
  "Worker loop.

  It loops through list of queues and tries to acquire a lock for them
  to do work (this ensures jobs are run in order for a queue).  When
  the lock has been acquired it pops a job from the queue and runs it.
  If the job failed but can be restarted (see also `retryable?`), we
  waits `retry-wait-ms` and puts the job back in the front of the
  queue.

  Configuration takes several functions:

  - `queue-fn` a function which takes one argument, a job, and returns
    a queue name for that job.

  - `run-job-fn` a function which takes one argument, a job, it
    returns the result of the job.

  - `set-status-fn` a function which takes two or three arguments; a
    job, a status symbol and some extra data.

  - `error-fn` a function which takes one argument, the result of the
    job, and returns true when the job failed.

  - `retryable-fn` a function which takes one argument, the result of the
    job, and returns true when job failed but can be retried.

  - `jobs-counter-fn` a function which takes two arguments, a job and a job status,
    and updates the metrics related to the job status type.

  - `queued-jobs-gauge-fn` a function which takes no arguments, and updates the metrics for the queues.
  "
  [{{:keys [queues
            lock-ttl-ms
            max-retries
            nap-ms
            retry-wait-ms

            ;; functions
            queue-fn
            error-fn
            retryable-fn
            run-job-fn
            set-status-fn
            jobs-counter-fn
            queued-jobs-gauge-fn]
     ;; Set lock expiry to 1 minute; locks in production have unexpectedly expired with shorter intervals
     :or {lock-ttl-ms   60000
          nap-ms        1000}} :worker
    :as                        config}
   stop-atom]
  {:pre [retry-wait-ms
         max-retries
         jobs-counter-fn
         queued-jobs-gauge-fn
         (seq queues)
         (fn? run-job-fn) (fn? set-status-fn)
         (ifn? retryable-fn) (ifn? error-fn) (ifn? queue-fn)]}

  ;; Extend lock at least each second
  (let [timeout-ms (min 1000 (/ lock-ttl-ms 2))]
    (loop [queues (occupied-queues config)]
      (if-let [[queue & queues] queues]
        (do
          ;; try and acquire lock of queue
          (when-let [token (some-> (acquire-lock! config queue lock-ttl-ms) atom)]
            (try
              ;; recover jobs which have been aborted
              (recover-aborted-job! config queue)

              (when-let [job (pop-job! config queue)]
                ; Set started-at when job is popped from queue. Only set it the first time, not during retries
                (let [job (assoc job :started-at (or (:started-at job)
                                                     (str (Instant/now))))]
                  ;; Don't count job as started while retrying it
                  (when (nil? (::retries job))
                    (jobs-counter-fn job :started)
                    (queued-jobs-gauge-fn))
                  ;; run job asynchronous
                  (let [set-status-fn (metrics/wrap-increment-count jobs-counter-fn set-status-fn)
                        ;; Wrap the result, because a channel can not convey
                        ;; `nil` and closes without a value when the body throws.
                        c             (async/thread
                                        (.setName (Thread/currentThread) (str "runner-" queue))
                                        (try
                                          {::result (run-job-fn job)}
                                          (catch Throwable ex
                                            {::crash ex})))]
                    (set-status-fn job :in-progress)

                    ;; wait for job to finish and refresh lock regularly while waiting
                    (loop []
                      (let [outcome (async/alt!! c ([v] v)
                                                 (async/timeout timeout-ms) ::ping)]
                        (extend-lock! config queue @token lock-ttl-ms)

                        (cond
                          (= ::ping outcome)
                          (recur)

                          ;; crashed or no result at all, set error status
                          (not (contains? outcome ::result))
                          (set-status-fn job :error (crash-result job (::crash outcome)))

                          :else
                          (let [result (::result outcome)]
                            (cond
                              ;; ack, success
                              (not (or (error-fn result)
                                       (retryable-fn result)))
                              (set-status-fn job :done result)

                              ;; ack, not retryable, log and set error status
                              (not (retryable-fn result))
                              (do
                                (logging/with-mdc
                                  (:trace-context job)
                                  (log/debugf "Job %s returns error %s" (pr-str job) (pr-str result)))
                                (set-status-fn job :error result))

                              ;; nack, retryable error, too many retries
                              (>= (or (::retries job) 0) max-retries)
                              (set-status-fn job :time-out result)

                              ;; nack, retryable error, schedule retry
                              :else
                              (do
                                (enqueue-first! config (update job ::retries (fnil inc 0)))
                                ;; extend lock lease to retry-wait
                                (extend-lock! config queue @token retry-wait-ms)
                                ;; prevent release
                                (reset! token nil)))))))))

                ;; done, remove from busy
                (job-done! config queue)

                ;; update gauge only after jobs removed from queue and busy-queue
                (queued-jobs-gauge-fn))

              (finally
                (some->> @token (release-lock! config queue)))))

          (when-not @stop-atom
            ;; next queue
            (recur queues)))

        (when-not @stop-atom
          ;; do nothing for a while to give redis a rest
          (async/<!! (async/timeout nap-ms))

          ;; start all over
          (recur (occupied-queues config)))))))

(defn start-worker!
  "Start a worker loop asynchronously.
  Returns an atom and go block channel which can be used by
  `stop-worker!` to stop the created worker."
  [config]
  (log/info "Starting worker")

  (let [stop-atom (atom nil)
        worker-busy (atom false)
        shutdown-handler (fn []
                           (reset! stop-atom true)
                           (loop []
                             (when @worker-busy
                               (Thread/sleep 500)
                               (recur))))]
    (.addShutdownHook (Runtime/getRuntime) (new Thread ^Runnable shutdown-handler))
    [stop-atom
     (async/thread
       (.setName (Thread/currentThread) "worker")
       (try
         (reset! worker-busy true)
         (worker-loop config stop-atom)
         ::stopped
         (catch UnknownHostException ex
           (if (ex-util/backtrace-matches-regex? ex #"carmine")
             (RuntimeException. "Redis is not available")
             ex))
         (catch EOFException ex
           (if (ex-util/backtrace-matches-regex? ex #"carmine")
             (RuntimeException. "Redis is not available")
             ex))
         (catch Throwable ex
           ex)
         (finally
           (reset! worker-busy false))))]))

(defn wait-worker
  "Wait for worker to finish.
  When the worker returns an exception rethrow it."
  [[_ chan]]
  (let [x (async/<!! chan)]
    (when-not (= ::stopped x)
      (log/fatal "Worker threw an exception" x)
      (throw x))))

(defn stop-worker!
  "Signal worker to stop and wait for it to finish.
  See also `wait-worker`."
  [[stop-atom :as worker]]
  (reset! stop-atom true)
  (wait-worker worker))

(defn purge!
  "Delete all queues and locks."
  [{:keys [redis-conn] {:keys [queues]} :worker :as config}]
  (doseq [queue queues]
    (redis/del redis-conn (queue-key config queue))
    (redis/del redis-conn (busy-queue-key config queue))
    (redis/del redis-conn (lock-name config queue))))
