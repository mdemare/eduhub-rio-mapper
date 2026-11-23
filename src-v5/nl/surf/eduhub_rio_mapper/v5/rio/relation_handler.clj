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

(ns nl.surf.eduhub-rio-mapper.v5.rio.relation-handler
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [nl.surf.eduhub-rio-mapper.rio.mutator :as mutator]
            [nl.surf.eduhub-rio-mapper.rio.relation-preconditions :as preconditions]
            [nl.surf.eduhub-rio-mapper.specs.mutation :as-alias mutation]
            [nl.surf.eduhub-rio-mapper.specs.ooapi :as ooapi]
            [nl.surf.eduhub-rio-mapper.specs.rio :as rio]
            [nl.surf.eduhub-rio-mapper.v5.specs.relations :as relations]
            [nl.surf.eduhub-rio-mapper.v5.utils.ooapi :as ooapi-utils]))

(defn- narrow-valid-daterange
  "The relation's valid-from and valid-to is stricter than that of the parent or child.

  It starts as soon as both entities are valid, and ends as soon as either entity is no longer valid (may be nil)."
  [{:keys [parent child] :as relation}]
  (let [valid-from (last (sort (map :validFrom [parent child])))
        valid-to (first (sort (keep :validTo [parent child])))]
    (assoc relation
      :valid-from valid-from
      :valid-to valid-to)))

(defn- turn-into-relations [{:keys [parent child] :as relation}]
  {:pre  [(::rio/opleidingscode parent)
          (::rio/opleidingscode child)
          (:validFrom parent)
          (:validFrom child)]
   :post [(s/valid? ::relations/relation %)]}
  (assoc (select-keys relation [:valid-from :valid-to])
    :opleidingseenheidcodes (set [(::rio/opleidingscode parent) (::rio/opleidingscode child)])))

(defn- expected-relations [parent children]
  {:post [(s/valid? ::relations/relation-vector (vec %))]}
  (->> children
       (map (fn [child] {:parent parent :child child}))
       (map narrow-valid-daterange)
       (map turn-into-relations)))

(defn relation-differences
  "Returns the diff between actual relations and expected relations."
  [main-entity rel-dir secondary-entity actual]
  {:pre [(s/valid? (s/nilable ::relations/relation-collection) actual)]
   :post [(s/valid? ::relations/relation-diff %)]}
  ;; If rel-dir is :child, main-entity is child, secondary-entity is parent,
  ;; otherwise main-entity is parent, secondary entity is children (plural)
  (let [parent   (if (= rel-dir :child) secondary-entity main-entity)
        children (if (= rel-dir :child) [main-entity] secondary-entity)
        expected (expected-relations parent children)]
    {:missing     (set/difference (set expected) (set actual))
     :superfluous (set/difference (set actual) (set expected))}))

(defn parse-opleidingseenheidcodes [opleidingseenheidcodes]
  (let [[code-1 code-2] (seq opleidingseenheidcodes)]
    (s/assert ::rio/opleidingscode code-1)
    (s/assert ::rio/opleidingscode code-2)
    [code-1 code-2]))

(defn relation-mutation
  "Returns the request data needed to perform a mutation (either an insertion or a deletion)."
  [mutate-type institution-oin {:keys [opleidingseenheidcodes valid-from valid-to]}]
  {:pre [institution-oin (seq opleidingseenheidcodes)]
   :post [(s/valid? ::mutation/mutation-response %)]}
  (let [[code-1 code-2] (parse-opleidingseenheidcodes opleidingseenheidcodes)
        rio-sexp (case mutate-type
                   :insert `[[:duo:opleidingsrelatie
                              [:duo:begindatum ~valid-from]
                              ~@(when valid-to
                                  [[:duo:einddatum valid-to]])
                              [:duo:opleidingseenheidcode ~code-1]
                              [:duo:opleidingseenheidcode ~code-2]]]
                   :delete [[:duo:opleidingseenheidcode code-1]
                            [:duo:opleidingseenheidcode code-2]
                            [:duo:begindatum valid-from]])]
    {:action     (case mutate-type :insert "aanleveren_opleidingsrelatie"
                                   :delete "verwijderen_opleidingsrelatie")
     :sender-oin institution-oin
     :rio-sexp   rio-sexp}))

(defn load-relation-data [getter opleidingscode institution-oin]
  {:pre [(s/valid? ::rio/opleidingscode opleidingscode)]
   :post [(s/valid? (s/nilable ::relations/relation-vector) %)]}
  (getter {:institution-oin       institution-oin
           ::rio/type             "opleidingsrelatiesBijOpleidingseenheid"
           ::rio/opleidingscode   opleidingscode}))

(defn delete-relations [opleidingscode type institution-oin {:keys [rio-config getter]}]
  {:pre [(s/valid? ::rio/opleidingscode opleidingscode)]}
  (when (= type "education-specification")
    (doseq [rel (load-relation-data getter opleidingscode institution-oin)]
      (-> (relation-mutation :delete institution-oin rel)
          (mutator/mutate! rio-config)))))

(defn load-required-parent! [parent-id {:keys [institution-oin institution-schac-home]} {:keys [ooapi-loader resolver]}]
  (preconditions/require-parent!
   parent-id institution-oin :educationSpecificationId
   (fn [id] (ooapi-loader {::ooapi/type "education-specification" ::ooapi/id id
                           :institution-schac-home institution-schac-home}))
   (fn [entity]
     (if (::rio/opleidingscode entity)
       entity
       (when-let [code (resolver :oe (:educationSpecificationId entity) institution-oin)]
         (assoc entity ::rio/opleidingscode code))))))

(defn relation-mutations
  ([eduspec job handlers]
   (let [rio-consumer (ooapi-utils/extract-rio-consumer (:consumers eduspec))]
     (when (= "variant" (:educationSpecificationSubType rio-consumer))
       (preconditions/parent-reference! (:parent eduspec) (:institution-oin job)))
     (relation-mutations eduspec
                         :educationSpecificationId
                         (and (= "variant" (:educationSpecificationSubType rio-consumer))
                              (:parent eduspec))
                         (:children eduspec)
                         job
                         handlers)))
  ([eduspec primary-key variant-of variants {:keys [institution-oin institution-schac-home] :as job} {:keys [getter resolver ooapi-loader] :as handlers}]
   {:pre [eduspec]}
   (let [add-rio-code (fn add-rio-code [entity]
                        (if (::rio/opleidingscode entity)
                          entity
                          (when-let [rio-code (resolver :oe (primary-key entity) institution-oin)]
                            (assoc entity ::rio/opleidingscode rio-code))))
         load-eduspec (fn load-eduspec [id]
                        {:pre [id]}
                        (when-let [es (ooapi-loader {::ooapi/type            "education-specification"
                                                     ::ooapi/id              id
                                                     :institution-schac-home institution-schac-home})]
                          (add-rio-code es)))
         eduspec (add-rio-code eduspec)
         actual (load-relation-data getter (::rio/opleidingscode eduspec) institution-oin)
         [rel-dir entity] (if variant-of
                            [:child (or (::parent job) (load-required-parent! variant-of job handlers))]
                            [:parent (->> (keep load-eduspec variants)
                                          (filter #(s/valid? ::relations/child %)))])]
     (relation-differences eduspec rel-dir entity actual))))

(defn mutate-relations!
  [{:keys [missing superfluous] :as diff} {:keys [institution-oin] :as _job} {:keys [rio-config] :as _handlers}]
  {:pre [institution-oin (:recipient-oin rio-config)]}
  (let [mutator (fn [rel op] (-> (relation-mutation op institution-oin rel)
                                 (mutator/mutate! rio-config)))]
    ;; There may already be a relation between these two entities (but with a different start date),
    ;; and RIO allows only one relation, so insert first would cause an RIO API error.
    ;; Therefore we delete any existing relations before we insert new ones.
    (doseq [rel superfluous] (mutator rel :delete))
    (doseq [rel missing]     (mutator rel :insert)))
  diff)

(defn update-relations
  "Bring the relations of the education specification in sync.

   First calculates which relations are missing or superfluous, then creates the delete and insert mutations, and executes them."
  [eduspec job handlers]
  (when eduspec
    (-> eduspec
        (relation-mutations job handlers)
        (mutate-relations! job handlers))))
