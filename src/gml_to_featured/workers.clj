(ns gml-to-featured.workers
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [gml-to-featured.zip :refer [xml-entries]]
            [gml-to-featured.config :as config])
  (:gen-class)
  (:import (com.netflix.conductor.client.worker Worker)
           (com.netflix.conductor.common.metadata.tasks TaskResult$Status TaskResult)
           (java.io File)
           (java.util.zip ZipFile ZipEntry ZipOutputStream)
           (com.microsoft.azure.storage CloudStorageAccount)
           (com.microsoft.azure.storage.blob CloudBlobClient CloudBlockBlob CloudBlobContainer BlobContainerPermissions BlobContainerPublicAccessType)))

(defn download-uri [^String uri]
  "Copy to tmp file, return handle"
  (let [file (File/createTempFile "tmpfile" nil)]
    (with-open [in (io/input-stream uri)
                out (io/output-stream file)]
      (io/copy in out))
    file))

(defn unzip [^File file]
  (let [zipfile (ZipFile. file)
        ^ZipEntry entry (first (xml-entries zipfile))
        name (.getName entry)
        ^File tmp-dir (File/createTempFile "unzip" nil)
        _ (do (.delete tmp-dir) (.mkdirs tmp-dir))
        target (File. tmp-dir name)]
    (with-open [in (.getInputStream zipfile entry)
                out (io/output-stream target)]
      (io/copy in out))
    target))

(defn zip [^File file]
  (let [name (str (.getName file) ".zip")
        target (File. (.getParent file), name)
        entry (ZipEntry. (.getName file))]
    (with-open [outstream (io/output-stream target)
                zipstream (ZipOutputStream. outstream)
                instream (io/input-stream file)]
      (.putNextEntry zipstream entry)
      (io/copy instream zipstream))
    target))

(defn make-public! [^CloudBlobContainer container]
  (let [permissions (doto (BlobContainerPermissions.) (.setPublicAccess BlobContainerPublicAccessType/CONTAINER))]
    (.uploadPermissions container permissions)))

(defn upload [dataset ^File file]
  (let [storage-account (CloudStorageAccount/parse (config/env :storage-connection-string))
        client (.createCloudBlobClient storage-account)
        container (.getContainerReference client (str (config/env :storage-container-prefix "gml-to-featured-out-") dataset))
        _ (when-not (.exists container) (.create container) (make-public! container))
        blob (.getBlockBlobReference container (.getName file))]
    (with-open [in (io/input-stream file)]
      (.upload blob in (.length file)))
    blob))

(defn delete-recursively [fname]
  (let [func (fn [func f]
               (when (.isDirectory f)
                 (doseq [f2 (.listFiles f)]
                   (func func f2)))
               (clojure.java.io/delete-file f))]
    (func func (clojure.java.io/file fname))))

(deftype Transformer [^String name transfn]
  Worker
  (getTaskDefName [this] name)
  (execute [this task]
    (let [^TaskResult result (doto (TaskResult. task) (.setStatus TaskResult$Status/FAILED))
          output (.getOutputData result)]
      (try
        (log/info "Working ...")
        (let [input (.getInputData task)
              dataset (.get input "dataset")
              validity (.get input "validity")
              mapping-uri (.get input "mapping_uri")
              file-uri (.get input "uri")
              local-zip (download-uri file-uri)
              local-mapping (download-uri mapping-uri)
              unzipped (unzip local-zip)
              target-dir (File/createTempFile "target" nil)
              _ (do (.delete target-dir) (.mkdirs target-dir))
              write-fn
              #(doseq [[idx stream] (map-indexed vector %)]
                 (let [out-file (File. target-dir
                                       (str (.getName unzipped) "_" (->> idx inc (format "%04d")) ".json"))]
                   (with-open [writer (io/writer out-file :encoding "utf-8")]
                     (doseq [fragment stream]
                       (.write writer fragment)))))]
          (with-open [reader (io/input-stream unzipped)]
            (transfn dataset (slurp local-mapping) validity reader write-fn))
          (let [blobs
                (for [json-file (.listFiles target-dir)]
                  (let [zipped (zip json-file)
                        ^CloudBlockBlob uploaded (upload dataset zipped)]
                    {"name" (.getName uploaded) "uri" (.getUri uploaded)}))]
            (.put output "files" (doall blobs)))
          (do (.delete local-zip)
              (.delete local-mapping)
              (delete-recursively (.getParentFile unzipped))
              (delete-recursively target-dir)))
        (log/info "... Finished")
        (doto result (.setStatus TaskResult$Status/COMPLETED))
        (catch Exception e (log/error e) (log/info "... Finished with errors") result)))))

;(def t (Transformer. "test" (fn [_ _ _ _ _])))
;(def test-task (Task.))
;(.setStatus test-task Task$Status/IN_PROGRESS)
;(doto (.getInputData test-task)
;  (.put "dataset" "top10")
;  (.put "validity" "20171023")
;  (.put "mapping_uri" "https://transformstorage.blob.core.windows.net/mappings/top10gmltofeaturedmapping.edn")
;  (.put "uri" "https://transformstorage.blob.core.windows.net/top10nl/TOP10NL_01O.gml.zip")
;  (.put "name" "TOP10NL_01O.gml.zip"))
;(.execute t test-task)