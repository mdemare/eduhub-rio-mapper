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

(ns nl.surf.eduhub-rio-mapper.v6.e2e-helper
  (:require [clj-http.client :as http]
            [clojure.data.xml :as clj-xml]
            [clojure.string :as str]
            [clojure.test :as test]
            [environ.core :refer [env]]
            [nl.jomco.http-status-codes :as http-status]
            [nl.surf.eduhub-rio-mapper.clients-info :as clients-info]
            [nl.surf.eduhub-rio-mapper.endpoints.status :as status]
            [nl.surf.eduhub-rio-mapper.remote-entities-helper :as remote-entities]
            [nl.surf.eduhub-rio-mapper.rio.loader :as rio-loader]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.utils.http-utils :as http-utils]
            [nl.surf.eduhub-rio-mapper.utils.printer :as printer]
            [nl.surf.eduhub-rio-mapper.utils.xml-utils :as xml-utils]
            [nl.surf.eduhub-rio-mapper.v6.config :as config])
  (:import [java.io File StringWriter]
           [java.net ConnectException]
           [java.util Base64 List]
           [javax.xml.xpath XPathConstants XPathFactory]
           [org.w3c.dom Element Node NodeList]))

(def ^:private last-seen-testing-contexts (atom nil))

(defn- print-testing-contexts
  "Print eye catching testing context (when it's not already printed)."
  []
  (when (seq test/*testing-contexts*)
    (when-not (= @last-seen-testing-contexts test/*testing-contexts*)
      (reset! last-seen-testing-contexts test/*testing-contexts*)
      (println)
      (println "╔═══════════════════")
      (println (str/replace (first test/*testing-contexts*) #"(?m)^\s*" "║ ")))))

(def ^:private last-boxed-print (atom nil))

(defmacro print-boxed
  "Print pretty box around output of evaluating `form`."
  [title & form]
  `(let [sw# (StringWriter.)
         r#  (binding [*out* sw#] ~@form)
         s#  (str sw#)]
     (if (= @last-boxed-print s#)
       (do
         (print ".")
         (flush))
       (do
         (print-testing-contexts)
         (println)
         (print "╭─────" ~title "\n│ ")
         (println (str/replace (str/trim s#) #"\n" "\n│ "))
         (println "╰─────")
         (reset! last-boxed-print s#)))
     r#))

(defn- print-api-message
  "Print boxed API request and response."
  [{{:keys [method url]}  :req
    {:keys [status body]} :res}]
  (println (str/upper-case (name method)) url status)
  (when body
    (printer/print-json body)))

(defn print-single-http-message [title print-fn msg]
  (print-boxed title (print-fn msg)))

(defn print-http-messages
  "Print HTTP message as returned by API status."
  [http-messages]
  (printer/print-http-messages-with-boxed-printer http-messages print-single-http-message))

;; Defer running make-config so running some (other!) tests is still
;; possible when environment incomplete.
(def config (delay (config/make-config
                    (assoc env
                           "WORKER_API_PORT" "8081"
                           "JOB_MAX_RETRIES" "1"
                           "JOB_RETRY_WAIT_MS" "1000"
                           "RIO_RETRY_ATTEMPTS_SECONDS" "5,5"))))

(def base-url
  (delay (str "http://"
              (-> @config :api-config :host)
              ":"
              (-> @config :api-config :port))))

(defn- encode-base64
  "Base64 bytes of given string."
  [s]
  (str/join (map char (.encode (Base64/getEncoder) ^bytes (.getBytes s)))))

(def ^:private bearer-token
  ;; A conext bearer token expires in 3600 seconds (1 hour), should be
  ;; plenty of time to run all e2e tests..
  (delay
    (let [{:keys [client-id
                  client-secret
                  token-endpoint]} env]
      (-> {:method       :post
           :url          token-endpoint
           :content-type :x-www-form-urlencoded
           :query-params {"grant_type" "client_credentials"
                          "audience"   client-id}

           :headers {"Authorization" (str "Basic "
                                          (encode-base64 (str client-id
                                                              ":"
                                                              client-secret)))}
           :as      :json}
          (http/request)
          (get-in [:body :access_token])))))

(defn- api-path
  "Returns path for given API action."
  [action args]
  (case action
    :status
    (let [[token] args]
      (str "/status/" token))

    :upsert
    (let [[type ooapi-id] args]
      (str "/job/upsert/" (name type) "/" ooapi-id))

    :dry-run/upsert
    (let [[type ooapi-id] args]
      (str "/job/dry-run/upsert/" (name type) "/" ooapi-id))

    :delete
    (let [[type ooapi-id] args]
      (str "/job/delete/" (name type) "/" ooapi-id))

    :link
    (let [[rio-id type ooapi-id] args]
      (str "/job/link/" rio-id "/" (name type) "/" ooapi-id))

    :unlink
    (let [[rio-id type] args]
      (str "/job/unlink/" rio-id "/" (name type)))))

(defn ooapi-id
  "Get OOAPI UUID of automatically uploaded fixture."
  [type id]
  {:pre [(#{:programmes :courses} type)]}
  (get remote-entities/*session*
  (case type
    :programmes (str "programmes" "/" id)
    :courses (str "courses" "/" id))))

(defn- interpret-post-job-args
  "Automatically find OOAPI ID from session.

  When the last 2 arguments are a keyword and a string, the keyword is
  interpreted as an OOAPI type and the string as an ID known by
  `remote-entities/*session*`.  In that case the last argument is
  replaced by the UUID from the session using the `ooapi` function."
  [args]
  (let [[type id] (take-last 2 args)]
    (concat (drop-last args)
            [(if (and (keyword? type) (string? id))
               (let [uuid (ooapi-id type id)]
                 (assert uuid (str "Expect a UUID for " type " " id))
                 uuid)
               id)])))

(defn- api-http-request [method path]
  (let [req {:url              (str @base-url path)
             :method           method
             :headers          {"Authorization" (str "Bearer " @bearer-token)}
             :query-params     {:http-messages "true"}
             :as               :json
             :throw-exceptions false}]
    {:req req, :res (http/request req)}))

(defn- call-api-status
  "Make API status call, print results and http-message, and return response."
  [token]
  (let [req-res        (api-http-request :get (api-path :status [token]))
        http-messages (-> req-res :res :body :http-messages)]
    (print-boxed "API"
                 (print-api-message req-res))
    (when (seq http-messages)
      (print-boxed (str "Status: Job HTTP messages (" (count http-messages) ")")
                   (print-http-messages http-messages)))
    (:res req-res)))

(defn- call-api
  "Make API POST call, print results and http-message, and return response."
  [action args]
  (let [{:keys [req res]} (->> args
                               interpret-post-job-args
                               (api-path action)
                               (api-http-request :post))
        http-messages     (-> res :body :http-messages)
        res               (if (map? (:body res)) ;; expect JSON response
                            ;; but can be something else on error
                            (update res :body dissoc :http-messages)
                            res)]
    (print-boxed "API"
                 (print-api-message {:req req, :res res}))
    (when (seq http-messages)
      (print-boxed "Job HTTP messages"
                   (print-http-messages http-messages)))
    res))

(defn- api-status-final?
  "Determine if polling can be stopped from API status call response."
  [res]
  (status/final-status? (-> res :body :status keyword)))

(def job-status-poll-sleep-msecs 500)
(def job-status-poll-total-msecs 60000)

(defn- delayed-result [{:keys [status] {:keys [token]} :body}]
  (delay
    (if (not= http-status/created status)
      (println (str "failed to post job - status: " status))
      (loop [tries-left (/ job-status-poll-total-msecs
                           job-status-poll-sleep-msecs)]
        (let [{:keys [status body] :as res}
              (call-api-status token)]
          (cond
            (zero? tries-left)
            (do
              (println "\n\n⚠ too many retries on status\n")
              ::time-out)

            (not= http-status/ok status)
            (do
              (println "\n\n⚠ get status failed\n")
              ::get-status-failed)

            (api-status-final? res)
            body

            :else
            (do
              (Thread/sleep ^long job-status-poll-sleep-msecs)
              (recur (dec tries-left)))))))))

(defn post-job
  "Post a job through the API.
  Return the HTTP response of the call and includes a \"delay\" to access
  the job result at `:result-delay`."
  [action & args]
  {:pre [(#{:upsert :delete :link :unlink :status :dry-run/upsert} action)]}
  (spit "logs/e2e-v6-posted-jobs.log" (str action " " (prn-str args)) :append true)
  (let [res (call-api action args)]
    (assoc res :result-delay (delayed-result res))))

(defn job-result
  "Use `get-in` to access job response from `post-job`."
  [job & ks]
  (get-in @(:result-delay job) ks))

(defn job-result-status
  "Short cut to `post-job` job response status."
  [job]
  (job-result job :status))

(defn job-result-attributes
  "Short cut `get-in` to `post-job` job response attributes."
  [job & ks]
  (apply job-result job :attributes ks))

(defn job-result-opleidingseenheidcode
  "Short cut to `post-job` job response attributes opleidingseenheidcode."
  [job]
  (job-result-attributes job :opleidingseenheidcode))

(defmethod test/assert-expr 'job-result-opleidingseenheidcode [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)
         result# (job-result-opleidingseenheidcode job#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect job result attributes to include opleidingseenheidcode."),
                      :expected '~form, :actual attrs#})
     result#))

(defn job-result-aangebodenopleidingcode
  "Short cut to `post-job` job response attributes aangebodenopleidingcode."
  [job]
  (or
   (job-result-attributes job :aangebodenopleidingcode)
   (throw (ex-info "error job-result-aangebodenopleidingcode" (dissoc @(:result-delay job) :http-messages)))))

(defmethod test/assert-expr 'job-result-aangebodenopleidingcode [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)
         result# (job-result-aangebodenopleidingcode job#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect job result attributes to include aangebodenopleidingcode."),
                      :expected '~form, :actual attrs#})
     result#))

(defn job-has-diffs?
  "Returns `true` if \"diff\" is detected in given attributes."
  [job]
  (->> job
       (job-result-attributes)
       (map #(:diff (val %)))
       (filter (partial = true))
       seq))

(defmethod test/assert-expr 'job-has-diffs? [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)
         result# (job-has-diffs? job#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect job result attributes to have diffs"),
                      :expected '~form, :actual attrs#})
     result#))

(defn job-without-diffs?
  "Complement of `job-has-diffs?`."
  [job]
  (not (job-has-diffs? job)))

(defmethod test/assert-expr 'job-without-diffs? [msg form]
  `(let [job# ~(second form)
         attrs# (job-result-attributes job#)
         result# (job-without-diffs? job#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect job result attributes to not have diffs"),
                      :expected '~form, :actual attrs#})
     result#))

(defn job-done?
  "Final job status is 'done'."
  [job]
  (println "XXX in JOB DONE")
  (= "done" (job-result-status job)))

(defmethod test/assert-expr 'job-done? [msg form]
  `(let [job# ~(second form)
         status# (job-result-status job#)
         result# (= "done" status#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect final job status to equal 'done'"),
                      :expected "done", :actual status#})
     result#))

(defn job-error?
  "Final job status is 'error'."
  [job]
  (= "error" (job-result-status job)))

(defmethod test/assert-expr 'job-error? [msg form]
  `(let [job# ~(second form)
         status# (job-result-status job#)
         result# (= "error" status#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect final job status to equal 'error'"),
                      :expected "error", :actual status#})
     result#))

(defn job-dry-run-found?
  "Final job status attributes status is 'found'."
  [job]
  (= "found" (:status (job-result-attributes job))))

(defmethod test/assert-expr 'job-dry-run-found? [msg form]
  `(let [job# ~(second form)
         status# (:status (job-result-attributes job#))
         result# (= "found" status#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect final job status attributes status to equal 'found'"),
                      :expected "found", :actual status#})
     result#))

(defn job-dry-run-not-found?
  "Final job status attributes status is 'not-found'."
  [job]
  (= "not-found" (:status (job-result-attributes job))))

(defmethod test/assert-expr 'job-dry-run-not-found? [msg form]
  `(let [job# ~(second form)
         status# (:status (job-result-attributes job#))
         result# (= "not-found" status#)]
     (test/do-report {:type (if result# :pass :fail)
                      :message (or ~msg "Expect final job status attributes status to equal 'not-found'"),
                      :expected "not-found", :actual status#})
     result#))

(def ^:private rio-getter (delay (rio-loader/make-getter (:rio-config @config))))
(def ^:private rio-resolver (delay (rio-loader/make-resolver (:rio-config @config))))
(def ^:private client-info (delay (clients-info/client-info (:clients @config)
                                                            (:client-id env))))

(defn- rio-get [req]
  (let [messages-atom (atom [])
        result (binding [http-utils/*http-messages* messages-atom]
                 (@rio-getter req))]
    (print-http-messages @messages-atom)
    result))

(defn rio-resolve [rio-type id]
  {:pre [(#{:oe :ao} rio-type)
         (string? id)]}
  (let [messages-atom (atom [])
        result (binding [http-utils/*http-messages* messages-atom]
                 (@rio-resolver rio-type id (:institution-oin @client-info)))]
    (print-http-messages @messages-atom)
    result))

(defn rio-relations
  "Call RIO `opvragen_opleidingsrelatiesBijOpleidingseenheid`."
  [code]
  {:pre [code]}
  (print-boxed "rio-relations"
               (rio-get {::rio/type           rio-loader/opleidingsrelaties-bij-opleidingseenheid-type
                         ::rio/opleidingscode code
                         :institution-oin            (:institution-oin @client-info)})))

(defn rio-with-relation?
  "Fetch relations of `rio-child` and test if it includes `rio-parent`.

  Note: RIO may take some time to register relations so we retry for
  10 seconds."
  [rio-parent rio-child]
  (loop [tries 20]
    (let [relations (rio-relations rio-child)
          result    (some #(contains? (:opleidingseenheidcodes %) rio-parent)
                          relations)]
      (if result
        result
        (if (pos? tries)
          (do
            (Thread/sleep 500)
            (recur (dec tries)))
          result)))))

(defn rio-opleidingseenheid
  "Call RIO `opvragen_opleidingseenheid`."
  [code]
  {:pre [code]}
  (print-boxed "rio-opleidingseenheid"
               (-> {::rio/type           rio-loader/opleidingseenheid-type
                    ::rio/opleidingscode code
                    :institution-oin            (:institution-oin @client-info)
                    :response-type              :literal}
                   rio-get
                   xml-utils/str->dom)))

(defn- extract-kenmerken [node]
  (if (map? node)
    [(:kenmerken node)]
    (keep :kenmerken node)))

(defn element->edn
  "Convert org.w3c.dom.Element into simplified edn structure."
  [^Element element]
  (-> element xml-utils/dom->str clj-xml/parse-str xml-utils/xml-event-tree->edn))

(defn- kenmerken-tekst-opleidingseenheid [rio-code naam]
  (as-> rio-code $
    (rio-opleidingseenheid $)
    (element->edn $)
    (:Envelope $)
    (:Body $)
    (:opvragen_opleidingseenheid_response $)
    (some $ rio-loader/opleidingseenheid-namen)
    (extract-kenmerken $)
    (filter #(= naam (:kenmerknaam %)) $)
    (first $)
    (:kenmerkwaardeTekst $)))

(defn eigen-opleidingseenheid-sleutel [rio-code]
  {:pre [(string? rio-code)]}
  (kenmerken-tekst-opleidingseenheid rio-code "eigenOpleidingseenheidSleutel"))

(defn rio-aangebodenopleiding
  "Call RIO `opvragen_aangebodenOpleiding`."
  [id]
  (print-boxed "rio-aangebodenopleiding"
               (-> {::rio/type                      rio-loader/aangeboden-opleiding-type
                    ::rio/aangeboden-opleiding-code id
                    :institution-oin                (:institution-oin @client-info)
                    :response-type                  :literal}
                   rio-get
                   xml-utils/str->dom)))

(defn kenmerken-values-aangeboden-opleiding [ao-dom naam kenmerk-type]
  (as-> ao-dom $
    (element->edn $)
    (:Envelope $)
    (:Body $)
    (:opvragen_aangebodenOpleiding_response $)
    (some $ rio-loader/aangeboden-opleiding-namen)
    (extract-kenmerken $)
    (filter #(= naam (:kenmerknaam %)) $)
    (map kenmerk-type $)))

(defn eigen-aangeboden-opleiding-sleutel [rio-code]
  (first (kenmerken-values-aangeboden-opleiding
          (rio-aangebodenopleiding rio-code)
          "eigenAangebodenOpleidingSleutel"
          :kenmerkwaardeTekst)))

(defn get-in-xml
  "Get text node from `path` starting at `node`."
  [node path]
  {:pre [(instance? Node node)]}
  (let [xpath (str "//"
                   (->> path
                        (map #(str "*[local-name()='" % "']"))
                        (str/join "/")))]
    (.evaluate (.newXPath (XPathFactory/newInstance))
               xpath
               node)))

(defn get-all-in-xml
  "Get text node from `path` starting at `node`."
  [node path]
  {:pre [(instance? Node node)]}
  (let [xpath           (str "//"
                             (->> path
                                  (map #(str "*[local-name()='" % "']"))
                                  (str/join "/")))
        ^NodeList nodes (.evaluate (.newXPath (XPathFactory/newInstance))
                                   xpath
                                   node
                                   XPathConstants/NODESET)
        node-length     (.getLength nodes)
        values          (mapv #(.getTextContent (.item nodes %))
                              (range node-length))]
    values))

;; Using atoms to keep process to make interactive development easier.
(defonce ^:private serve-api-process-atom (atom nil))
(defonce ^:private worker-process-atom (atom nil))

(def services [{:cmd ["serve-api"]
                :proc-atom serve-api-process-atom
                :log-file "logs/test-e2e-serve-api.log"}
               {:cmd ["worker"]
                :proc-atom worker-process-atom
                :log-file "logs/test-e2e-worker.log"}])

(defn start-services
  "Start the serve-api and worker services."
  []
  (let [config (config/make-config env)]

    (when (= (:api-config config) (:worker-api-config config))
      (println "The api and the worker must run on separate ports.")
      (System/exit 1)))
  (doseq [{:keys [cmd proc-atom ^String log-file]} services]

    (let [process-builder (ProcessBuilder. ^List (into ["clojure" "-M:mapper-v6"] cmd))
          log-file (File. log-file)]
      (when-let [parent (.getParentFile log-file)]
        (.mkdirs parent))
      (.redirectErrorStream process-builder true)
      (.redirectOutput process-builder log-file)
      (println "Starting mapper" cmd)
      (reset! proc-atom (.start process-builder)))))

(defn stop-services
  "Stop the serve-api and worker services (if the are started)."
  []
  (doseq [{:keys [cmd proc-atom]} services]
    (when-let [proc @proc-atom]
      (println "Stopping mapper" cmd)
      (.destroy proc)
      (reset! proc-atom nil))))

(def wait-for-serve-api-sleep-msec 500)
(def wait-for-serve-api-total-msec 120000)

(defn with-running-mapper
  "Wrapper to use with `use-fixtures` to automatically start mapper services."
  [f]
  (when-not (:mapper-url env) ;; TODO
    (start-services)
    (.addShutdownHook (Runtime/getRuntime) (Thread. stop-services))

    ;; wait for serve-api to be up and running
    (loop [tries-left (/ wait-for-serve-api-total-msec
                         wait-for-serve-api-sleep-msec)]
      (when (neg? tries-left)
        (throw (ex-info "Failed to start serve-api"
                        {:msecs wait-for-serve-api-total-msec})))
      (let [result
            (try
              (http/get (str @base-url "/health")
                        {:throw-exceptions false})
              true
              (catch ConnectException _
                false))]
        (when-not result
          (Thread/sleep ^long wait-for-serve-api-sleep-msec)
          (recur (dec tries-left))))))

  ;; run tests
  (f))
