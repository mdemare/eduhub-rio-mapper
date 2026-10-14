(ns nl.surf.eduhub-rio-mapper.v6.specs.ooapi
  (:require [clojure.spec.alpha :as s]))

;; The allowed values of .consumer.specificationType and what each one
;; becomes in RIO. This is the single source for these values; add or
;; rename specification types here only.
;;
;; :opleidingseenheid    - XML element name of the opleidingseenheid
;; :soort                - value of the "soort" kenmerk, or nil when absent
;; :aangeboden-opleiding - XML element name of the aangeboden opleiding
(def specification-types
  {"course"    {:opleidingseenheid    "hoOnderwijseenheid"
                :soort                nil
                :aangeboden-opleiding "aangebodenHOOpleidingsonderdeel"}
   "programme" {:opleidingseenheid    "hoOpleiding"
                :soort                "OPLEIDING"
                :aangeboden-opleiding "aangebodenHOOpleiding"}
   "variant"   {:opleidingseenheid    "hoOpleiding"
                :soort                "VARIANT"
                :aangeboden-opleiding "aangebodenHOOpleiding"}
   "cluster"   {:opleidingseenheid    "hoOnderwijseenhedencluster"
                :soort                "HOEC"
                :aangeboden-opleiding "aangebodenHOOpleidingsonderdeel"}
   "private"   {:opleidingseenheid    "particuliereOpleiding"
                :soort                nil
                :aangeboden-opleiding "aangebodenParticuliereOpleiding"}})

(s/def ::specification-type
  (set (keys specification-types)))
