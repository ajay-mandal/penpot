;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.fragments-gc
  "Prepare fragment marks from one database snapshot, then verify the
  roots again under the file lock before applying a bounded lot."
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.metrics :as mtx]
   [app.storage :as sto]
   [app.tasks.file-change-refs :as index]
   [app.util.pointer-map :as pmap]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def ^:private lot-size 100)
(def ^:private preparation-size 1000)
(def ^:private retry-delay (ct/duration {:seconds 30}))
(def ^:private coverage-retry-delay (ct/duration "1m"))

(def ^:private sql:offload-pending
  "SELECT EXISTS (
     SELECT 1 FROM job WHERE tenant = ? AND name = 'offload-file-data'
                           AND status IN ('new', 'retry')
     UNION ALL
     SELECT 1 FROM job WHERE tenant = ? AND name = 'offload-file-data'
                           AND status = 'scheduled'
     UNION ALL
     SELECT 1 FROM job WHERE tenant = ? AND name = 'offload-file-data'
                           AND status = 'running'
   ) AS pending")

(defn marking-enabled?
  "Check before enqueue or preparation. Old producers must have stopped
  submitting tiered offload before an operator enables fragment marking."
  [cfg]
  (and (cf/fragments-gc-enabled?)
       (let [tenant (cf/get :tenant)]
         (not (:pending (db/exec-one! cfg [sql:offload-pending tenant tenant tenant]))))))

(def ^:private sql:get-file
  "SELECT revn, modified_at, xmin::text AS file_xid, ctid::text AS file_tid
     FROM file WHERE id = ? AND deleted_at IS NULL")

(def ^:private sql:lock-file
  (str sql:get-file " FOR UPDATE SKIP LOCKED"))

(def ^:private sql:get-main
  "SELECT id, file_id, backend, storage_object_id, data,
          xmin::text AS main_xid, ctid::text AS main_tid
     FROM file_data
    WHERE file_id = ? AND id = file_id AND type = 'main' AND deleted_at IS NULL")

(def ^:private sql:get-main-version
  "SELECT xmin::text AS main_xid, ctid::text AS main_tid
     FROM file_data
    WHERE file_id = ? AND id = file_id AND type = 'main' AND deleted_at IS NULL")

(def ^:private sql:get-history-ids
  "SELECT id FROM file_change WHERE file_id = ? AND revn = ?")

(def ^:private sql:history-changed
  "SELECT EXISTS (
     SELECT 1 FROM file_change WHERE file_id = ? AND revn = ?
       AND id <> ALL(?::uuid[])
   ) AS changed")

(def ^:private sql:get-candidates
  "WITH used AS MATERIALIZED (
     SELECT DISTINCT unnest(fragment_ids) AS id
       FROM file_change_refs WHERE file_id = ?
   )
   SELECT d.id FROM file_data d
    WHERE d.file_id = ? AND d.type = 'fragment' AND d.deleted_at IS NULL
      AND d.id <> ALL(?::uuid[])
      AND NOT EXISTS (SELECT 1 FROM used WHERE used.id = d.id)
    ORDER BY d.id LIMIT ?")

(def ^:private sql:mark-fragments
  "UPDATE file_data SET deleted_at = ?
    WHERE file_id = ? AND id = ANY(?::uuid[])
      AND type = 'fragment' AND deleted_at IS NULL
    RETURNING id")

(defn- get-deadline
  [{:keys [modified-at]}]
  (ct/plus modified-at (cf/get-file-clean-delay)))

(defn- get-main-ids
  [cfg file-id main]
  (fdata/with-untracked
    (binding [pmap/*load-fn* nil]
      (let [data (->> main
                      (fdata/resolve-file-data cfg)
                      (fdata/decode-file-data cfg)
                      :data)]
        (when-not (map? data)
          (ex/raise :type :internal
                    :code :invalid-main-payload
                    :hint "file main payload is missing or is not a map"
                    :file-id file-id))
        (fdata/get-used-pointer-ids data)))))

(defn- prepare-lot
  [{:keys [::db/pool] :as cfg} file-id]
  (db/transact! pool
                (fn [conn]
                  (let [cfg  (assoc cfg ::db/conn conn)
                        file (db/exec-one! conn [sql:get-file file-id])]
                    (if-not file
                      {:status :gone}
                      (let [deadline (get-deadline file)]
                        (cond
                          (ct/is-after? deadline (ct/now))
                          {:status :recent :deadline deadline}

                          (seq (refs/get-missing-change-ids cfg file-id 1))
                          {:status :incomplete}

                          :else
                          (if-let [main (db/exec-one! conn [sql:get-main file-id])]
                            (let [main-ids (get-main-ids cfg file-id main)
                                  ids      (into [] (map :id)
                                                 (db/exec! conn [sql:get-candidates file-id file-id
                                                                 (db/create-array conn "uuid" main-ids)
                                                                 preparation-size]))]
                              (if (seq ids)
                                {:status :prepared
                                 :ids ids
                                 :file-version (select-keys file [:revn :file-xid :file-tid])
                                 :main-version (select-keys main [:main-xid :main-tid])
                                 :history-ids (into [] (map :id)
                                                    (db/exec! conn [sql:get-history-ids file-id (:revn file)]))}
                                {:status :complete :marked 0}))
                            ;; A legacy head can be replaced by admin writers
                            ;; outside the revision protocol.
                            {:status :no-main}))))))
                {:read-only true :isolation :repeatable-read}))

(defn- mark-lot
  [{:keys [::db/pool]} file-id {:keys [file-version main-version history-ids ids]}]
  (db/transact! pool
                (fn [conn]
                  ;; Bound the SQL work while the file row is locked, including
                  ;; time behind a concurrent row purge.
                  (db/exec-one! conn ["SET LOCAL statement_timeout = '250ms'"])
                  (db/exec-one! conn ["SET LOCAL lock_timeout = '100ms'"])
                  (if-let [file (db/exec-one! conn [sql:lock-file file-id])]
                    (let [now (ct/now)
                          deadline (get-deadline file)]
                      (cond
                        (ct/is-after? deadline now)
                        {:status :recent :deadline deadline}

                        (or (not= file-version (select-keys file [:revn :file-xid :file-tid]))
                            (not= main-version (db/exec-one! conn [sql:get-main-version file-id]))
                            ;; This statement needs a new snapshot after taking
                            ;; the file lock, not the snapshot of the locking SELECT.
                            (:changed (db/exec-one! conn [sql:history-changed file-id (:revn file)
                                                          (db/create-array conn "uuid" history-ids)])))
                        {:status :stale}

                        :else
                        {:status :complete
                         :marked (count (db/exec! conn [sql:mark-fragments now file-id
                                                        (db/create-array conn "uuid" ids)]))}))
                    (if (db/exec-one! conn [sql:get-file file-id])
                      {:status :busy}
                      {:status :gone})))
                {:isolation :read-committed}))

(defn- mark-prepared
  [cfg file-id {:keys [ids] :as preparation}]
  ;; Reuse the same bounded preparation without rebuilding the history
  ;; query for every lot. Each lot still has to validate under a fresh lock.
  (reduce (fn [{:keys [marked]} ids]
            (let [result (mark-lot cfg file-id (assoc preparation :ids (vec ids)))]
              (if (= :complete (:status result))
                (update result :marked + marked)
                (reduced (assoc result :marked marked)))))
          {:status :complete :marked 0}
          (partition-all lot-size ids)))

(defn- schedule
  [cfg file-id delay]
  (jobs/submit cfg {::jobs/name :fragments-gc
                    ::jobs/params {:file-id file-id}
                    ::jobs/label (str file-id)
                    ::jobs/dedupe true
                    ::jobs/delay delay}))

(defn execute-fragments-gc
  [cfg {:keys [file-id]}]
  ;; Even a job enqueued before the opt-in was turned off cannot take a lock.
  (if-not (marking-enabled? cfg)
    {:status :disabled}
    (let [lot    (prepare-lot cfg file-id)
          result (if (= :prepared (:status lot))
                   (mark-prepared cfg file-id lot)
                   lot)]
      (case (:status result)
        :incomplete (do
                      (index/schedule-index cfg file-id)
                      (ex/raise :type ::wrk/retry
                                :code :incomplete-file-change-refs
                                :hint "file change references are not fully indexed"
                                :file-id file-id
                                :delay coverage-retry-delay))
        :recent     (let [now   (ct/now)
                          until (:deadline result)]
                      (schedule cfg file-id
                                (if (ct/is-after? until now) (ct/diff now until) 0)))
        (:stale :busy) (schedule cfg file-id retry-delay)
        :complete     (when (= preparation-size (count (:ids lot)))
                        (schedule cfg file-id 0))
        nil)
      (dissoc result :deadline))))

(def ^:private schema:params
  [:map {:closed true} [:file-id ::sm/uuid]])

(defmethod ig/assert-key ::job-def
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (sto/valid-storage? (::sto/storage cfg)) "expected valid storage")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::job-def
  [_ cfg]
  {::jobs/name      :fragments-gc
   ::jobs/schema    schema:params
   ::jobs/handler   (fn [_ params] (execute-fragments-gc cfg params))
   ::jobs/decoder   (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator (sm/validator schema:params)})
