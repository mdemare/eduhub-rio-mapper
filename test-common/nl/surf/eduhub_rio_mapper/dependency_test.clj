(ns nl.surf.eduhub-rio-mapper.dependency-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(defn- file->namespace
  "Convert a file path to a namespace symbol."
  [^java.io.File file]
  (-> (.getPath file)
      (str/replace #"^src-common/" "")
      (str/replace #"\.cljc?$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn- find-clojure-files
  "Find all .clj files in src-common directory."
  []
  (let [src-common (io/file "src-common")]
    (when (.exists src-common)
      (->> (file-seq src-common)
           (filter #(and (.isFile %)
                         (str/ends-with? (.getName %) ".clj")))
           (sort-by #(.getPath %))))))

(defn- load-all-common-namespaces!
  "Dynamically require all namespaces from src-common."
  []
  (doseq [file (find-clojure-files)]
    (require (file->namespace file))))

(deftest ^{:common true :slow true} common-has-no-v5-deps
  (load-all-common-namespaces!)
  (is true "Common code successfully loaded without v5 dependencies"))
