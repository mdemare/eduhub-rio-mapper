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

(ns nl.surf.eduhub-rio-mapper.utils.xml-utils
  (:import [java.io StringReader StringWriter]
           [javax.xml XMLConstants]
           [javax.xml.parsers DocumentBuilder DocumentBuilderFactory]
           [javax.xml.transform Transformer TransformerFactory]
           [javax.xml.transform.dom DOMSource]
           [javax.xml.transform.stream StreamResult]
           [org.w3c.dom Document Element NodeList]
           [org.xml.sax EntityResolver InputSource SAXException]))

(defn- do-string-writer [write]
  (-> (StringWriter.)
      (doto write)
      (.toString)))

(defn- db-factory ^DocumentBuilderFactory []
  ;; These protections are required: let configuration failures propagate so
  ;; an unsupported parser cannot silently fall back to unsafe parsing.
  (doto (DocumentBuilderFactory/newInstance)
    (.setNamespaceAware true)
    (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
    (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
    (.setFeature "http://xml.org/sax/features/external-general-entities" false)
    (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
    (.setFeature "http://apache.org/xml/features/nonvalidating/load-external-dtd" false)
    (.setAttribute XMLConstants/ACCESS_EXTERNAL_DTD "")
    (.setAttribute XMLConstants/ACCESS_EXTERNAL_SCHEMA "")
    (.setXIncludeAware false)
    (.setExpandEntityReferences false)))

(defn- document-builder ^DocumentBuilder []
  (doto (.newDocumentBuilder (db-factory))
    (.setEntityResolver
     (reify EntityResolver
       (resolveEntity [_ _ _]
         (throw (SAXException. "External XML entities are forbidden")))))))

(defn- different-keys? [content]
  (when content
    (let [unique-tags (set (keep :tag content))]
      (= (count unique-tags) (count content)))))

(defn xml-event-tree->edn
  "Convert xml event tree (as produced by clojure.data.xml/parse-str) on simplified edn structure.
   It uses a map with keywords :content, :tag and :attrs for individual nodes."
  [element]
  (cond
    (nil? element) nil
    (string? element) element

    (sequential? element) (if (> (count element) 1)
                            (if (different-keys? element)
                              (reduce into {} (map (partial xml-event-tree->edn ) element))
                              (map xml-event-tree->edn element))
                            (xml-event-tree->edn  (first element)))

    (and (map? element) (empty? element)) {}
    (map? element) (if (:attrs element)
                     {(:tag element) (xml-event-tree->edn (:content element))
                      (keyword (str (name (:tag element)) "Attrs")) (:attrs element)}
                     {(:tag element) (xml-event-tree->edn  (:content element))})

    :else nil))

;;; Conversion functions between the following formats:
;;; sexp: hiccup-like s-expressions
;;; xml: string with xml document
;;; dom: Java Document with parsed xml
;;; element: Single Element within a DOM tree
;;; edn: Clojure representation of XML document

(defn sexp-element-with-tag?
  "Return whether a hiccup-like XML s-expression has the given tag."
  [element tag]
  (and (sequential? element) (= tag (first element))))

(defn sexp-child-elements
  "Return direct child elements of an XML s-expression, ignoring text and attributes."
  [element]
  (filter sequential? (rest element)))

(defn sexp-element-text
  "Concatenate direct text strings in an XML s-expression, preserving whitespace.
  Returns nil if there are no text strings; excludes text in nested elements."
  [element]
  (when-let [parts (seq (filter string? (rest element)))]
    (apply str parts)))

(defn sexp-child-text
  "Return direct text from the first child element with the given tag, or nil if absent."
  [element tag]
  (some-> (some #(when (sexp-element-with-tag? % tag) %) (sexp-child-elements element))
          sexp-element-text))

(defn str->dom
  "Parses self-contained XML into org.w3c.dom.Document, rejecting DOCTYPEs."
  ^Document [^String xml]
  (let [builder (document-builder)
        doc (.parse builder (InputSource. (StringReader. xml)))]
    (.normalize (.getDocumentElement doc))
    doc))

(defn dom->str
  "Renders org.w3c.dom.Document to a String."
  ([dom]
   (dom->str dom (-> (TransformerFactory/newInstance) .newTransformer)))
  ([dom ^Transformer transformer]
   (do-string-writer
     #(.transform transformer (DOMSource. dom) (StreamResult. ^StringWriter %)))))

(defn node-list->seq
  "Return a lazy sequence of the nodes in a DOM NodeList, in their existing order."
  [^NodeList nodes]
  (map #(.item nodes %) (range (.getLength nodes))))

(defn- dom-reducer-jvm [^Element element tagname]
  (when element
    (.item (.getElementsByTagName element tagname) 0)))

(defn get-in-dom
  "Get element in DOM tree using path of tag-names.

  Walks through the DOM-tree starting with element, choosing the first
  element with matching qualified name, returns `nil` if no matching
  element is found. With namespace-uri, match local names in that namespace."
  (^Element [current-element tag-names]
   (reduce dom-reducer-jvm current-element tag-names))
  (^Element [current-element namespace-uri local-names]
   (reduce (fn [^Element element local-name]
             (when element
               (.item (.getElementsByTagNameNS element namespace-uri local-name) 0)))
           current-element
           local-names)))


;; A simple pretty printer for debugging purposes.  It's slow,
;; incomplete and may emit unparseable XML but is useful for human
;; consumption.
(defmulti ^:private debug-print-xml-node (fn [node _ _] (map? node)))

(defmethod debug-print-xml-node true
  [node indent indent-str]
  (print (str indent "<" (name (:tag node))))
  (when (:attrs node)
    (doseq [attr (:attrs node)]
      (print (str " " (name (key attr)) "=\"" (val attr) "\""))))
  (if (:content node)
    (do
      (println ">")
      (doseq [c (:content node)]
	(debug-print-xml-node c (str indent indent-str) indent-str))
      (print indent)
      (println (str "</" (name (:tag node)) ">")))
    (println "/>")))

(defmethod debug-print-xml-node false
  [node indent _]
  (println (str indent node)))

(defn debug-print-xml
  "Basic XML pretty printer for debugging.

  It's slow, incomplete and may emit unparseable XML but is useful for
  human consumption.  Expects a `root` are returned by
  `clojure.xml.parse`.  Use `initial-indent` to set initial
  indentation string and `indent-str` to whatever is added as
  indentation."
  [root & {:keys [initial-indent
                  indent-str]
           :or   {initial-indent ""
                  indent-str     "  "}}]
  (debug-print-xml-node root initial-indent indent-str))

(defn single-xml-unwrapper
  "Find the content of the first child of `element` with type `tag`.

  Returns `nil` if no matching element is there"
  [element tag]
  (some-> element
          (get-in-dom [tag])
          (.getFirstChild)
          (.getTextContent)))
