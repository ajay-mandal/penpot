;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.file-change-refs
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.tasks.fragments-gc :as fragments-gc]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def max-change-ids 128)
(def ^:private repair-batch-size 32)
(def ^:private retry-delay (ct/duration "1m"))

(defn- index-changes
  [cfg file-id change-ids]
  (doseq [change-id change-ids]
    (jobs/heartbeat cfg)
    (try
      ;; No batch transaction: each publication commits independently and
      ;; the next attempt can reuse every successfully indexed change.
      (refs/index-change cfg file-id change-id)
      (catch InterruptedException cause
        (throw cause))
      (catch Exception cause
        (let [{:keys [type code hint]} (ex-data cause)]
          (when (= :interrupt type)
            (throw cause))
          (ex/raise :type ::wrk/retry
                    :code (or code :file-change-index-failed)
                    :hint (or hint "unable to index file change references")
                    :file-id file-id
                    :change-id change-id
                    :delay retry-delay
                    :cause cause))))))

(defn execute-index-file-change-refs
  "Index explicit changes from one file, without any GC or cursor work."
  [cfg {:keys [file-id change-ids]}]
  (index-changes cfg file-id (distinct change-ids)))

(defn- submit-continuation
  [cfg job-name file-id]
  ;; Only an existing new job can cover this submission: a running or
  ;; retrying job may have finished reading the state it acts on.
  (jobs/submit cfg {::jobs/name job-name
                    ::jobs/params {:file-id file-id}
                    ::jobs/label (str file-id)
                    ::jobs/dedupe true}))

(defn- continue-repair
  [cfg file-id]
  (if (seq (refs/get-missing-change-ids cfg file-id 1))
    (submit-continuation cfg :repair-file-change-refs file-id)
    (when (fragments-gc/marking-enabled? cfg)
      (submit-continuation cfg :fragments-gc file-id))))

(defn execute-repair-file-change-refs
  "Index one batch of missing changes, then schedule repair or GC from
  the final coverage check's transaction. An unreadable source stops the
  batch and leaves the job runner to retry; no GC is scheduled on failure."
  [cfg {:keys [file-id]}]
  (let [change-ids (refs/get-missing-change-ids cfg file-id repair-batch-size)]
    (index-changes cfg file-id change-ids)
    (jobs/heartbeat cfg)
    (db/tx-run! cfg continue-repair file-id))
  nil)

(def schema:index-params
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:change-ids [:vector {:max max-change-ids} ::sm/uuid]]])

(def schema:repair-params
  [:map {:closed true}
   [:file-id ::sm/uuid]])

(def ^:private valid-config?
  (sm/validator [:map ::db/pool ::mtx/metrics ::sto/storage]))

(defn- make-job-def
  [cfg job-name schema handler]
  (assert (valid-config? cfg) "expected a database pool, metrics, and storage")
  {::jobs/name      job-name
   ::jobs/schema    schema
   ::jobs/handler   (fn [_context params] (handler cfg params))
   ::jobs/decoder   (sm/decoder schema sm/json-transformer)
   ::jobs/validator (sm/validator schema)})

(defmethod ig/init-key ::index-job-def
  [_ cfg]
  (make-job-def cfg :index-file-change-refs schema:index-params execute-index-file-change-refs))

(defmethod ig/init-key ::repair-job-def
  [_ cfg]
  (make-job-def cfg :repair-file-change-refs schema:repair-params execute-repair-file-change-refs))
