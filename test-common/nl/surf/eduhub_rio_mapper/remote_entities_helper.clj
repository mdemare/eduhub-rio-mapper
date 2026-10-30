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

(ns nl.surf.eduhub-rio-mapper.remote-entities-helper
  "Always fresh OOAPI entities in SWIFT ObjectStore for running tests on.

  Using the `remote-entities-fixture` fixture function, all JSON files
  in the `fixtures/remote-entities` directory are uploaded to the
  configured SWIFT ObjectStore.  Before uploading, the base names of
  the JSON files are replaced by random UUIDs and the paths and base
  names replace with those UUIDs when referenced using `{{` and `}}`.

  For instance `education-specifications/some.json` may contain:

  ```
    {
      \"level\": \"bachelor\",
      \"parent\": \"{{education-specifications/some}}\",
      \"organization\": \"{{organizations/acme}}\",
  ```

  Say we generated a UUID of `beefbeef-beef-beef-beef-beefbeefbeef`
  for this entity and `cafecafe-cafe-cafe-cafe-cafecafecafe` for
  `organizations/acme`, the uploaded version will be named
  `education-specifications/beefbeef-beef-beef-beef-beefbeefbeef`
  and contain:

  ```
    {
      \"level\": \"bachelor\",
      \"parent\": \"beefbeef-beef-beef-beef-beefbeefbeef\",
      \"organization\": \"cafecafe-cafe-cafe-cafe-cafecafecafe\",
  ```

  and the uploaded version of `organizations/acme` will be named
  `organizations/cafecafe-cafe-cafe-cafe-cafecafecafe`.

  Nested resources are handled slightly different.  For resource like
  `programs/some/offerings.json` the `programs/some` part will be
  considered its name and the uploaded object will be named something
  like: `programs/cafecafe-cafe-cafe-cafe-cafecafecafe/offerings`.

  This only works if the following environment variables are set:

  - OS_USERNAME
  - OS_PASSWORD
  - OS_PROJECT_NAME
  - OS_AUTH_URL
  - OS_CONTAINER_NAME

  Note: the public endpoint of the SWIFT ObjectStore container should
  exposed by the gateway and accessable to the test client."
  (:require [clj-http.client :as client]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [nl.jomco.envopts :as envopts])
  (:import java.util.UUID))

(def opts-spec
  {:os-username            ["ObjectStore username" :str
                            :in [:username]]
   :os-password            ["ObjectStore password" :str
                            :in [:password]]
   :os-user-domain-name    ["ObjectStore user domain name" :str
                            :in [:user-domain-name]
                            :default "Default"]
   :os-project-domain-name ["ObjectStore project domain name" :str
                            :in [:user-domain-name]
                            :default "Default"]
   :os-auth-url            ["ObjectStore auth url" :str
                            :in [:auth-url]]
   :os-container-name      ["ObjectStore container name" :str
                            :in [:container-name]]
   :os-project-name        ["ObjectStore project name" :str
                            :in [:project-name]]})

(defn config
  []
  (let [[config err] (envopts/opts env opts-spec)]
    (when err
      (throw (ex-info (envopts/errs-description err) err)))
    config))



;;; ObjecStore / Swift client

(defn swift-auth-info
  [{:keys [username password user-domain-name project-domain-name project-name auth-url]
    :or   {user-domain-name "Default" project-domain-name "Default"}}]
  {:pre [username password user-domain-name project-domain-name project-name auth-url]}
  (let [res (client/post (str auth-url "/auth/tokens")
                         {:accept       :json
                          :as           :json
                          :content-type :json
                          :form-params  {:auth {:identity {:methods  ["password"]
                                                           :password {:user {:domain   {:name user-domain-name}
                                                                             :name     username
                                                                             :password password}}}
                                                :scope    {:project {:domain {:name project-domain-name}
                                                                     :name   project-name}}}}})]
    {:token       (get-in res [:headers "x-subject-token"])
     :storage-url (->> (get-in res [:body :token :catalog 1 :endpoints])
                       (some (fn [{:keys [interface url]}]
                               (when (= "public" interface)
                                 url))))}))

(defn os-req
  "Create a request to the ObjectStore."
  [{:keys [token storage-url] :as _info} method path]
  {:headers {"X-Auth-Token" token}
   :url     (str (str/replace storage-url #"/$" "") path)
   :accept  :json
   :as      :json
   :method  method})

(defn os-delete-container
  [info container-name]
  (client/request (os-req info :delete (str "/" container-name))))

(defn os-create-public-container
  [info container-name]
  (client/request (-> (os-req info :put (str "/" container-name))
                      (assoc-in [:headers "X-Container-Read"] ".r:*,.rlistings"))))

(defn os-list-containers
  [info]
  (->> (client/request (os-req info :get "/"))
       :body
       (map :name)))

(defn os-list-objects
  [info container]
  (->> (client/request (os-req info :get (str "/" container)))
       :body
       (map :name)))

(defn os-get-object
  "Retrieve an object from the ObjectStore."
  [info container-name {:keys [path]}]
  {:pre [(seq path)]}
  (->> (str "/" container-name "/" path)
    (os-req info :get)
    (client/request)
    :body))

(defn os-put-object
  [info container-name {:keys [path body]}]
  {:pre [(seq path) (string? body)]}
  (client/request (assoc (os-req info :put (str "/" container-name "/" path))
                         :content-type :json
                         :body body)))

(defn os-delete-object
  [info container-name {:keys [path]}]
  {:pre [(seq path)]}
  (client/request (os-req info :delete (str "/" container-name "/" path))))



;;;; Entities on disk to that will be mirrord on the object store
;;;; using a unique set of ids per session.

(def ^:private entities-resource-path
  "fixtures/remote-entities")

(defn- entities-dir
  []
  (let [dir (-> entities-resource-path
                io/resource
                io/file)]
    (when-not (and dir (.isDirectory dir))
      (throw (IllegalStateException.
              (str entities-resource-path " is not a directory -- are you running in a jar?"))))
    dir))

;; Sequence of strings like "/app/eduhub-mapper/test-v6/fixtures/remote-entities/courses/some.json"
(defn- input-files
  ([]
   (input-files (entities-dir)))
  ([dir]
   (->> (.listFiles dir)
        (filter #(or (.isDirectory %)
                     (.endsWith (.getName %) ".json")))
        (mapcat #(if (.isDirectory %) (input-files %) [%])))))

;; e.g. file: ~/eduhub-mapper/test-v6/fixtures/remote-entities/education-specifications/dorothy.json
;;      key:  education-specifications/dorothy.json
(defn- file->base
  [file]
  (str/replace
   (subs (.getCanonicalPath file)
         (inc (count (.getCanonicalPath (entities-dir)))))
   #"\.json$" ""))

;; e.g. file: ~/eduhub-mapper/test-v6/fixtures/remote-entities/education-specifications/dorothy.json
;;      key:  education-specifications/dorothy
(defn- file->key
  [file]
  (second
   (re-matches #"^([^/]+/[^./]+).*"
               (file->base file))))

(def OOAPI-VERSION
  (when (= "true" (System/getenv "VCR_RECORD"))
    (if-let [v (System/getenv "OOAPI_VERSION")]
      v
      (throw (ex-info "OOAPI_VERSION is undefined, must be set in record mode" {})))))

(def ^:dynamic *identity-overrides*
  "Known entity identities to preserve when generating a session."
  {})
(def ^:dynamic *mapping-file*
  "Optional scenario mapping destination; nil keeps the legacy record location."
  nil)
(def ^:dynamic *object-transform*
  "Optional transformation of rendered objects before uploading."
  identity)

(defn make-session
  "Return a new session map of entity names to random ids.

  For every json file in `entities-resource-path`, maps its dir +
  basename to a random UUID.

  So `courses/some-course.json` will be mapped as:

    \"courses/some-course\" => some-random-uuid

  and `programs/some-program/offerings.json` will be mapped as:

    \"programs/some-program\" => some-other-random-uuid

  Known identities in `*identity-overrides*` replace generated IDs before
  saving the mapping or rendering any references.

  See also `with-session`"
  []
  (let [mapping (into {}
                      (->> (input-files)
                           (map file->key)
                           (set)
                           (map #(vector % (UUID/randomUUID)))))
        mapping (merge mapping *identity-overrides*)]
    ;; In record mode, this ENV var set to true, and the mapping is written in the fixtures/vcr dir as an edn file.
    (when (or *mapping-file* (= "true" (System/getenv "VCR_RECORD")))
      (let [file-path (or *mapping-file* (str "test-" OOAPI-VERSION "/fixtures/vcr/mapping.edn"))]
        (clojure.java.io/make-parents file-path)
        (spit file-path (prn-str (update-vals mapping str)))))
    mapping))

(defn- replace-content-exprs
  [templ session]
  (str/replace templ
                  #"\{\{\s*([^}\s]+)\s*}\}"
                  (fn [[_ name]]
                    (str (or (get session name)
                             (throw (ex-info (str "Can't find name '" name "'")
                                             {:name    name
                                              :session session})))))))
(defn- object-content
  [f session]
  (-> f
      (slurp)
      (replace-content-exprs session)))

(defn- object-location
  "Determine object location from file name and session."
  [f session]
  (let [loc (file->base f)]
    (loop [[[k v] & more] session]
      (assert k)
      (if (str/starts-with? loc k)
        (let [[_ base] (re-matches #"^([^/]+)/.*" k)]
          (str base "/" (str/replace loc k (str v))))
        (recur more)))))

;; For all session keys X, there must not exist a session key Y such that X != Y and (str/starts-with? Y X)
;; Otherwise the remote entities system will match X with both X and Y
(defn- validated-session-entries
  [session]
  (let [skeys (sort-by count (keys session))]
    (loop [[shorter-name & more] skeys]
      (when shorter-name
        (when-let [longer-name (some (fn [[name]]
                                       (when (str/starts-with? name shorter-name)
                                         name))
                                     more)]
          (throw (ex-info (str "Ambiguous remote entity names: '" shorter-name
                               "' is a prefix of '" longer-name "'")
                          {:name           shorter-name
                           :ambiguous-name longer-name})))
        (recur more)))))

(defn remote-objects
  [session]
  (let [i               (input-files)]
    (validated-session-entries session)
    ;; Sequence of strings like "/app/eduhub-mapper/test-v6/fixtures/remote-entities/courses/some.json"
    ;; location looks like: programmes/7f1567ac-a06b-47e7-9641-a9dc1451659a or programmes/8e54eca4-4096-48df-8735-6b41d114fd13/programme-offerings
    (map (fn [f]
           (*object-transform*
            {:path (object-location f session)
             :body (object-content f session)}))
         i)))

(defn- put-session
  [info container-name session]
  (println "Adding remote entities to" container-name)
  (doseq [object (remote-objects session)]
    (os-put-object info container-name object)))

(defn- delete-session
  [info container-name session]
  (println "Removing entities from" container-name)
  (doseq [object (remote-objects session)]
    (os-delete-object info container-name object)))

(def ^:dynamic
  *session*
  "Map of name => uuid for remote entities. Will be set by `with-session`."
  nil)

(defmacro ^:private with-session
  "Evaluate `body` with `*session*` bound to a new session.

  See also `make-session`."
  [& body]
  `(binding [*session* (make-session)]
    ~@body))

(defn entity-id
  "Return the UUID of an entity `n` in `*session*`.

  See also `with-session`"
  [n]
  {:pre [*session*]
   :post [%]}
  (get *session* n))

(defn remote-entities-fixture
  "A fixture that uploads the entities to the remote container.

  Container will be created if necessary. Entities are deleted when
  the JVM terminates.

  In the fixture's scope, `(entity-id NAME)` returns the UUID of the
  entity in the current session."
  [test-fn]
  (let [{:keys [container-name] :as cfg} (config)
        info                             (swift-auth-info cfg)]
    (when-not (some #(= container-name %) (os-list-containers info))
      (println "Creating container '" container-name "'")
      (os-create-public-container info container-name))
    (with-session
      (let [session        *session*
            delete-session #(delete-session info container-name session)]
        (.addShutdownHook (Runtime/getRuntime) (Thread. delete-session))
        (put-session info container-name *session*)
        (test-fn)))))

(comment
  ;; delete public container
  (let [{:keys [container-name]
         :as   config} (config)
        info           (swift-auth-info config)]
    (doseq [path (os-list-objects info container-name)]
      (os-delete-object info container-name {:path path}))))
