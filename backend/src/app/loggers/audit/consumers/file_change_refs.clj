;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.file-change-refs
  (:require
   [app.common.time :as ct]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [app.tasks.file-change-refs :as refs]
   [app.tasks.fragments-gc :as fragments-gc]
   [integrant.core :as ig]))

(defn- get-work
  [row]
  (when (= "backend" (:source row))
    (let [{:keys [name props context]} (consumers/decode-row row)
          {:keys [outcome]} props]
      ;; Management ingest also uses source "backend", but the server
      ;; stamps its authenticated shared-key identity as initiator.
      (when (= "app" (:initiator context))
        (case name
          "update-file"
          (when (and (uuid? (:id props))
                     (contains? #{"saved" "partial"} outcome))
            {:file-id (:id props)
             :save true})

          "create-file-snapshot"
          (when (uuid? (:file-id props))
            {:file-id (:file-id props)})

          nil)))))

(defn- process-chunk
  [cfg rows]
  (let [work  (into [] (keep get-work) rows)
        saves (into #{} (comp (filter :save) (map :file-id)) work)]
    ;; Do not resolve snapshot payloads while the transaction owns the
    ;; cursor. Its only writes are durable work and the cursor itself.
    (doseq [file-id (into #{} (map :file-id) work)]
      (refs/schedule-index cfg file-id))
    (when (and (seq saves) (fragments-gc/marking-enabled? cfg))
      (doseq [file-id saves]
        (jobs/submit cfg {::jobs/name :fragments-gc
                          ::jobs/params {:file-id file-id}
                          ::jobs/label (str file-id)
                          ::jobs/delay (ct/duration {:minutes 10})
                          ::jobs/debounce true})))
    {:processed (count work)
     :skipped (- (count rows) (count work))}))

(def consumer
  {:id      consumers/file-change-refs
   :always  true
   :label   "file-change-refs"
   :process process-chunk})

(defmethod ig/assert-key ::consumer
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::consumer
  [_ cfg]
  (consumers/init cfg consumer))

(defmethod ig/halt-key! ::consumer
  [_ thread]
  (consumers/halt thread))
