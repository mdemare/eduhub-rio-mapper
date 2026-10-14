;; This file is part of eduhub-mapper
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

(ns nl.surf.eduhub-rio-mapper.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nl.surf.eduhub-rio-mapper.config :as config])
  (:import [clojure.lang ExceptionInfo]
           [java.io File]))

(def versions ["v5" "v6"])

(defn- default-env [version]
  {:surf-conext-introspection-endpoint                  "https://gateway.dev.surfnet.nl/",
   :keystore-alias                                      "default",
   (keyword (str "gateway-password-" version))          "default",
   :keystore                                            "test/keystore.jks",
   :clients-info-path                                   "test/test-clients.json",
   :rio-recipient-oin                                   "default",
   :rio-update-url                                      "default",
   (keyword (str "gateway-user-" version))              "default",
   :surf-conext-client-id                               "default",
   :surf-conext-client-secret                           "default",
   (keyword (str "gateway-root-url-" version))          "https://gateway.test.surfeduhub.nl/",
   :rio-read-url                                        "default"})

(def default-expected-value {:keystore-pass "repelsteeltje!",
                             :gateway-credentials
                             {:password "default", :username "default"},
                             :redis-conn {:spec {:uri "redis://localhost"}}})

(defn- test-env [version env]
  (config/load-config-from-env version (merge (default-env version) env)))

(defn- selected [[cfg errs]]
  (is (nil? errs))
  (select-keys cfg [:keystore-pass :gateway-credentials :redis-conn]))

(defn- temp-file-with [content]
  (let [path (.getAbsolutePath (File/createTempFile "test-secret" ".txt"))]
    (spit path content)
    path))

(deftest missing-secret
  (doseq [version versions]
    (is (= {:keystore-password "missing"}
           (last (test-env version {}))))))

(deftest only-value-secret
  (doseq [version versions]
    (is (= default-expected-value
           (selected (test-env version {:keystore-password "repelsteeltje!"}))))))

(deftest only-file-secret
  (doseq [version versions]
    (is (= default-expected-value
           (selected (test-env version {:keystore-password-file (temp-file-with "repelsteeltje!")}))))))

(deftest file-secret-for-env-with-default
  (doseq [version versions]
    (is (= (assoc-in default-expected-value [:redis-conn :spec :uri] "redis://localhost:6381")
           (selected (test-env version {:redis-uri-file    (temp-file-with "redis://localhost:6381")
                                        :keystore-password "repelsteeltje!"}))))))

(deftest file-secret-for-versioned-gateway-password
  (doseq [version versions]
    (let [password-key (keyword (str "gateway-password-" version))
          env          (-> (default-env version)
                           (dissoc password-key)
                           (assoc (keyword (str "gateway-password-" version "-file")) (temp-file-with "s3cr3t\n")
                                  :keystore-password "repelsteeltje!"))]
      (is (= {:password "s3cr3t", :username "default"}
             (-> (config/load-config-from-env version env) first :gateway-credentials))))))

(deftest only-file-secret-file-missing
  (doseq [version versions]
    (is (thrown? ExceptionInfo (test-env version {:keystore-password-file "missing-file"})))))

(deftest both-types-of-secret-specified
  (doseq [version versions]
    (is (thrown? ExceptionInfo (test-env version {:keystore-password      "repelsteeltje!"
                                                  :keystore-password-file (temp-file-with "repelsteeltje!")})))))

(deftest gateway-options-are-versioned
  (testing "only the gateway options of the requested version are accepted"
    (let [[_ errs] (config/load-config-from-env "v6" (assoc (default-env "v5") :keystore-password "repelsteeltje!"))]
      (is (= #{:gateway-user-v6 :gateway-password-v6 :gateway-root-url-v6}
             (set (keys errs))))))
  (doseq [version versions]
    (let [other (first (remove #{version} versions))
          spec  (config/make-opts-spec version)
          help  (config/help version)]
      (is (every? spec (map #(keyword (str "gateway-" % "-" version)) ["user" "password" "root-url"])))
      (is (not-any? #(str/ends-with? (name %) other) (keys spec)))
      (is (= (dissoc (config/make-opts-spec "v5") :gateway-user-v5 :gateway-password-v5 :gateway-root-url-v5)
             (dissoc (config/make-opts-spec "v6") :gateway-user-v6 :gateway-password-v6 :gateway-root-url-v6)))
      (doseq [env-var ["GATEWAY_USER_" "GATEWAY_PASSWORD_" "GATEWAY_ROOT_URL_"]]
        (is (str/includes? help (str env-var (str/upper-case version))))
        (is (not (str/includes? help (str env-var (str/upper-case other)))))))))

(defn- full-env [version]
  (assoc (default-env version)
         :keystore-password "xxxxxx"
         :keystore-alias "test-surf"
         :gateway-root-url-v5 "https://gateway.test.surfeduhub.nl///"
         :gateway-root-url-v6 "https://gateway.test.surfeduhub.nl///"))

(deftest make-config-test
  (doseq [version versions]
    (let [cfg (config/make-config version (full-env version))]
      (is (= "https://gateway.test.surfeduhub.nl/" (:gateway-root-url cfg))
          "trailing slashes are normalized to one")
      (is (some? (-> cfg :rio-config :credentials :certificate)))
      (is (seq (:clients cfg))))))

(defn- version-fns [version calls]
  {:version            version
   :make-handlers      (fn [cfg] (swap! calls conj [:make-handlers (:gateway-credentials cfg)]) {:handlers version})
   :make-set-status-fn (fn [_cfg] ::set-status-fn)
   :run-job            (fn [handlers job store-http-requests]
                         (swap! calls conj [:run-job handlers job store-http-requests])
                         ::job-result)
   :retryable?         ::retryable?
   :errors?            ::errors?})

(defmacro ^:private with-test-env [version & body]
  `(let [make-config# config/make-config]
     (with-redefs [config/make-config (fn [v#] (make-config# v# (full-env ~version)))]
       ~@body)))

(deftest make-config-and-handlers-web-test
  (doseq [version versions]
    (let [calls (atom [])
          {:keys [handlers config]} (with-test-env version
                                      (config/make-config-and-handlers-web (version-fns version calls)))]
      (is (= {:handlers version} handlers))
      (is (= [[:make-handlers {:username "default" :password "default"}]] @calls))
      (is (= ::set-status-fn (-> config :worker :set-status-fn)))
      (is (= :institution-schac-home (-> config :worker :queue-fn)))
      (is (seq (-> config :worker :queues)))
      (is (nil? (-> config :worker :run-job-fn))))))

(deftest make-config-and-handlers-worker-test
  (doseq [version versions]
    (let [calls (atom [])
          {:keys [handlers config]} (with-test-env version
                                      (config/make-config-and-handlers-worker (version-fns version calls)))
          {:keys [run-job-fn retryable-fn error-fn set-status-fn
                  jobs-counter-fn queued-jobs-gauge-fn queues]} (:worker config)]
      (is (= {:handlers version} handlers))
      (is (= ::set-status-fn set-status-fn))
      (is (= ::retryable? retryable-fn))
      (is (= ::errors? error-fn))
      (is (fn? jobs-counter-fn))
      (is (fn? queued-jobs-gauge-fn))
      (is (seq queues))
      (testing "run-job-fn passes handlers and store-http-requests setting"
        (is (= ::job-result (run-job-fn {:id "job"})))
        (is (= [:run-job {:handlers version} {:id "job"} true] (last @calls)))))))
