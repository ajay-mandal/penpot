;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.fragments-gc-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.tasks.fragments-gc :as gc]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- fixture-file
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (th/db-exec-one! ["UPDATE file SET modified_at = ? WHERE id = ?"
                      (ct/in-past "72h") (:id file)])
    file))

(defn- fragment
  [file-id]
  (let [id (uuid/next)]
    (th/db-insert! :file-data {:file-id file-id :id id
                               :type "fragment" :backend "db"
                               :data (blob/encode {})})
    id))

(defn- marked?
  [file-id fragment-id]
  (some? (:deleted-at (th/db-exec-one!
                       ["SELECT deleted_at FROM file_data WHERE file_id = ? AND id = ?"
                        file-id fragment-id]))))

(defn- get-jobs
  [name]
  (th/db-exec! ["SELECT * FROM job WHERE name = ?" name]))

(defn- save-main
  [cfg file-id fragment-id]
  (db/exec-one! cfg
                ["UPDATE file_data SET data = ? WHERE file_id = ? AND id = ?"
                 (blob/encode {:components (pmap/create fragment-id {})})
                 file-id file-id]))

(defn- insert-snapshot
  [cfg {:keys [id revn]} snapshot-id fragment-id]
  (db/exec-one! cfg ["SELECT id FROM file WHERE id = ? FOR UPDATE" id])
  (db/insert! cfg :file-change {:id snapshot-id :file-id id
                                :revn revn :label "snapshot"
                                :deleted-at (ct/in-future "24h")})
  (db/insert! cfg :file-data {:file-id id :id snapshot-id
                              :type "snapshot" :backend "db"
                              :data (blob/encode {:components (pmap/create fragment-id {})})}))

(defn- run-gc
  [file-id]
  (gc/execute-fragments-gc th/*system* {:file-id file-id}))

(t/deftest marks-only-unreferenced-fragments-when-the-index-is-complete
  (let [{:keys [id] :as file} (fixture-file)
        main-ref    (fragment id)
        history-ref (fragment id)
        orphan      (fragment id)
        snapshot-id (uuid/next)]
    (save-main th/*system* id main-ref)
    (db/tx-run! th/*system* insert-snapshot file snapshot-id history-ref)
    (refs/index-change th/*system* id snapshot-id)
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (run-gc id)
      (run-gc id))
    (t/is (marked? id orphan))
    (t/is (not (marked? id main-ref)))
    (t/is (not (marked? id history-ref)))
    (t/is (empty? (get-jobs "fragments-gc")))))

(t/deftest missing-revision-refs-queue-repair-without-marking
  (let [{:keys [id revn]} (fixture-file)
        orphan (fragment id)]
    (th/db-insert! :file-change {:id (uuid/next) :file-id id :revn revn})
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (run-gc id)
      (t/is (not (marked? id orphan)))
      (t/is (= 1 (count (get-jobs "repair-file-change-refs"))))
      (th/run-task! :repair-file-change-refs {:file-id id})
      (t/is (empty? (refs/get-missing-change-ids th/*system* id 1)))
      (t/is (= 1 (count (get-jobs "fragments-gc"))))
      (run-gc id))
    (t/is (marked? id orphan))))

(t/deftest a-snapshot-committed-between-preparation-and-file-lock-invalidates-the-lot
  (let [{:keys [id] :as file} (fixture-file)
        main-ref    (fragment id)
        orphan      (fragment id)
        snapshot-id (uuid/next)
        prepare     (deref #'gc/prepare-lot)
        prepared    (promise)
        proceed     (promise)]
    (save-main th/*system* id main-ref)
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (with-redefs [gc/prepare-lot (fn [cfg file-id]
                                     (let [lot (prepare cfg file-id)]
                                       (deliver prepared lot)
                                       (when-not (deref proceed 10000 nil)
                                         (throw (ex-info "test did not release GC" {})))
                                       lot))]
        (let [run (atom nil)]
          (try
            (db/tx-run! th/*system*
                        (fn [cfg]
                          (insert-snapshot cfg file snapshot-id main-ref)
                          (reset! run (future (run-gc id)))
                          (t/is (contains? (set (:ids (deref prepared 10000 nil))) orphan))))
            (finally (deliver proceed true)))
          (t/is (= :stale (:status (deref @run 10000 nil)))))))
    (t/is (not (marked? id orphan)))
    (t/is (= 1 (count (get-jobs "fragments-gc"))))))

(t/deftest changing-the-main-payload-without-changing-revn-invalidates-the-lot
  (let [{:keys [id]} (fixture-file)
        orphan       (fragment id)
        prepare      (deref #'gc/prepare-lot)]
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (with-redefs [gc/prepare-lot (fn [cfg file-id]
                                     (let [lot (prepare cfg file-id)]
                                       (db/tx-run! cfg
                                                   (fn [cfg]
                                                     (db/exec-one! cfg ["SELECT id FROM file WHERE id = ? FOR UPDATE" id])
                                                     (save-main cfg id orphan)))
                                       lot))]
        (t/is (= :stale (:status (run-gc id))))))
    (t/is (not (marked? id orphan)))))

(t/deftest each-lot-revalidates-before-marking
  (let [{:keys [id] :as file} (fixture-file)
        main-ref (fragment id)
        snapshot (uuid/next)
        mark     (deref #'gc/mark-lot)
        lots     (atom 0)]
    (save-main th/*system* id main-ref)
    (fragment id)
    (fragment id)
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (with-redefs [gc/lot-size 1
                    gc/mark-lot (fn [cfg file-id lot]
                                  (let [result (mark cfg file-id lot)]
                                    (when (= 1 (swap! lots inc))
                                      (db/tx-run! cfg insert-snapshot file snapshot main-ref))
                                    result))]
        (t/is (= {:status :stale :marked 1} (run-gc id)))))
    (t/is (= 2 @lots))
    (t/is (= 1 (:total (th/db-exec-one!
                        ["SELECT COUNT(*) AS total FROM file_data
                          WHERE file_id = ? AND type = 'fragment' AND deleted_at IS NOT NULL" id]))))
    (t/is (= 1 (count (get-jobs "fragments-gc"))))))

(t/deftest a-recent-save-defers-fragment-marking
  (let [{:keys [id]} (fixture-file)
        orphan (fragment id)]
    (th/db-exec-one! ["UPDATE file SET modified_at = ? WHERE id = ?" (ct/now) id])
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (t/is (= :recent (:status (run-gc id)))))
    (t/is (not (marked? id orphan)))
    (t/is (ct/is-after? (:scheduled-at (first (get-jobs "fragments-gc")))
                        (ct/in-future "47h")))))

(t/deftest an-already-queued-fragment-gc-does-not-block-offload
  (let [{:keys [id]} (fixture-file)
        orphan     (fragment id)
        offload-id (jobs/submit th/*system* {::jobs/name :offload-file-data
                                             ::jobs/params {:file-id id}})]
    (jobs/submit th/*system* {::jobs/name :fragments-gc
                              ::jobs/params {:file-id id}})
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (with-redefs [gc/prepare-lot (fn [& _] (throw (ex-info "disabled job prepared a lot" {})))]
        (t/is (= :disabled (:status @(future (run-gc id)))))))
    (t/is (not (marked? id orphan)))
    (t/is (some? offload-id))
    (t/is (pos? (:offloaded (th/run-task! :offload-file-data {:file-id id}))))))
