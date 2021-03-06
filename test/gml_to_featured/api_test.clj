(ns gml-to-featured.api-test
  (:require [gml-to-featured.api :refer :all]
            [gml-to-featured.filesystem :as fs]
            [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.format :as time-format]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.mock.request :refer :all]
            [environ.core :refer [env]]
            [clojure.test :refer :all])
  (:import (java.io File)))

(defn use-test-result-store [f]
  "Set a specific, temporary result-store in tmp-dir"
  (let [test-result-store (.getPath (io/file (System/getProperty "java.io.tmpdir") (fs/uuid)))]
    (log/debug "Using gml-to-featured.jsonstore during TEST" test-result-store)
    (env "gml-to-featured.jsonstore" test-result-store)
    (f)
    (fs/safe-delete test-result-store)))

(use-fixtures :once use-test-result-store)

(def built-in-formatter (time-format/formatters :basic-date-time))

(defn test-get [file-uri]
  (let [response (app (request :get file-uri))]
    (log/info "Using following url for GET: " file-uri)
    (is (= (:status response) 200))))

(deftest single-file-converted-correctly
  "Test if input-file api-test/Landsgrens.gml results in a zip. Note does not test CONTENT of result"
  (let [mapping (slurp (io/resource "api-test/bestuurlijkegrenzen.edn"))
        input (io/input-stream (io/resource "api-test/Landsgrens.gml"))
        tmp (File/createTempFile "fgtest" "json")
        _ (clojure.java.io/copy input tmp)
        validity (time-format/unparse built-in-formatter (time/now))
        result (process-downloaded-xml2json-data "test" mapping validity :plain tmp "inputnaam.gml")
        _ (clojure.java.io/delete-file tmp)]
    ; check resulting content
    (is (= 1 (count (:json-files result))))
    (is (.endsWith (first (:json-files result)) "inputnaam.gml.json.zip"))
    (test-get (first (:json-files result)))))


(deftest zip-file-converted-correctly
  "Test if input-file api-test/bestuurlijkegrenzen.zip results in multiple zip files. Note does not test CONTENT of result"
  (let [mapping (slurp (io/resource "api-test/bestuurlijkegrenzen.edn"))
        input (io/input-stream (io/resource "api-test/bestuurlijkegrenzen.zip"))
        tmp (File/createTempFile "fgtest" "zip")
        _ (clojure.java.io/copy input tmp)
        validity (time-format/unparse built-in-formatter (time/now))
        result (process-downloaded-xml2json-data "test" mapping validity :zip tmp "bestuurlijkegrenzen.zip")
        _ (clojure.java.io/delete-file tmp)]
    ; check resulting content
    (is (= 2 (count (:json-files result))))
    (doall
      (map test-get (:json-files result)))))
