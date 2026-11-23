;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.features.file-change-refs
  (:require
   [app.common.exceptions :as ex]
   [app.db :as db]
   [app.features.fdata :as fdata]
   [app.util.pointer-map :as pmap])
  (:import
   java.sql.SQLException))

(def ^:private sql:missing-ref
  ;; fragment_ids is NOT NULL: a matching row is sufficient even when its
  ;; array is empty, and this probe need not load the array.
  "NOT EXISTS (
     SELECT 1 FROM file_change_refs r
      WHERE r.file_id = c.file_id AND r.file_change_id = c.id
   )")

(def ^:private sql:get-source
  (str "SELECT c.id, c.file_id, c.label,
               CASE WHEN fd.id IS NULL AND c.label IS NOT NULL THEN c.data END AS legacy_data,
               fd.data, COALESCE(fd.backend, 'legacy-db') AS backend, fd.storage_object_id
          FROM file_change c
          LEFT JOIN file_data fd ON fd.file_id = c.file_id AND fd.id = c.id
                                AND fd.type = 'snapshot' AND c.label IS NOT NULL
         WHERE c.file_id = ? AND c.id = ? AND " sql:missing-ref))

(def ^:private sql:get-source-key
  "SELECT id FROM file_change WHERE file_id = ? AND id = ?")

(def ^:private sql:insert-refs
  ;; Take the row lock only at publication, not during the payload read.
  ;; When a concurrent purge won, the locking SELECT yields no row; when
  ;; this insert wins, the FK removes its result with the source.
  "INSERT INTO file_change_refs (file_id, file_change_id, fragment_ids)
   SELECT c.file_id, c.id, ?::uuid[]
     FROM file_change c
    WHERE c.file_id = ? AND c.id = ?
      FOR KEY SHARE OF c
   ON CONFLICT (file_id, file_change_id) DO NOTHING")

(defn- get-snapshot-fragment-ids
  [cfg {:keys [file-id id] :as source}]
  (try
    (fdata/with-untracked
      (binding [pmap/*load-fn* nil]
        (let [data (->> source
                        (fdata/resolve-file-data cfg)
                        (fdata/decode-file-data cfg)
                        :data)]
          (when-not (map? data)
            (ex/raise :type :internal
                      :code :invalid-snapshot-payload
                      :hint "snapshot payload is missing or is not a map"
                      :file-id file-id
                      :id id))
          (fdata/get-used-pointer-ids data))))
    (catch InterruptedException cause
      (throw cause))
    (catch SQLException cause
      (throw cause))
    (catch Exception cause
      ;; objects-gc can purge the payload before the metadata. Do not
      ;; record coverage or hide the failure while that source still exists.
      (when (db/exec-one! cfg [sql:get-source-key file-id id])
        (throw cause)))))

(defn index-change
  "Index the persisted fragment refs of a file change, if not already
  indexed. Snapshots are immutable under this key; offload copies their
  bytes without changing the refs. Revisions contribute no fragment refs.
  Returns nil. A purged change is a no-op; an unreadable snapshot whose
  metadata is still present raises and stays uncovered."
  [cfg file-id change-id]
  (db/run! cfg
           (fn [{:keys [::db/conn] :as cfg}]
             (when-let [source (db/exec-one! conn [sql:get-source file-id change-id])]
               (when-let [ids (if (some? (:label source))
                                (get-snapshot-fragment-ids cfg source)
                                [])]
                 (db/exec-one! conn [sql:insert-refs
                                     (db/create-array conn "uuid" ids)
                                     file-id change-id])))))
  nil)

(defn get-missing-change-ids
  "Return up to `limit` uncovered file change IDs in database UUID order,
  including marked changes. Pass `:after-id` to resume after a previous
  page; omit it when checking coverage of the whole file."
  [cfg file-id limit & {:keys [after-id]}]
  (let [sql (str "SELECT c.id FROM file_change c WHERE c.file_id = ? AND "
                 (when after-id "c.id > ? AND ")
                 sql:missing-ref " ORDER BY c.id LIMIT ?")]
    (->> (if after-id
           (db/exec! cfg [sql file-id after-id limit])
           (db/exec! cfg [sql file-id limit]))
         (mapv :id))))
