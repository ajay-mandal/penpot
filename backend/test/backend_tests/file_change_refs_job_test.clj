;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-change-refs-job-test
  (:require
   [app.common.exceptions :as ex]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.util.blob :as blob]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   java.util.UUID))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-file
  [i]
  (let [profile (th/create-profile* i)]
    (:id (th/create-file* i {:profile-id (:id profile)
                             :project-id (:default-project-id profile)}))))

(defn- get-missing
  [file-id]
  (refs/get-missing-change-ids th/*system* file-id 128))

(defn- get-pending-jobs
  [job-name file-id]
  (th/db-query :job {:name (name job-name)
                     :status "new"
                     :label (str file-id)}))

(defn- submit-exclusive
  [job-name file-id]
  (jobs/submit th/*system* {::jobs/name job-name
                            ::jobs/params {:file-id file-id}
                            ::jobs/label (str file-id)
                            ::jobs/skip-if-active true}))

(defn- invoke
  [{:keys [id name params] :as job}]
  (jobs/invoke (assoc th/*system*
                      ::jobs/name (keyword name)
                      ::jobs/params params
                      ::jobs/context (jobs/make-context job)
                      ::jobs/job-id id)))

(t/deftest explicit-index-jobs-ignore-missing-changes-and-are-replay-safe
  (let [file-id   (create-file 1)
        change-id (uuid/next)
        absent-id (uuid/next)
        params    {:file-id file-id :change-ids [absent-id change-id change-id]}]
    (th/db-insert! :file-change {:file-id file-id :id change-id})
    (binding [cf/config (assoc cf/config :fragments-gc-enabled false)]
      (let [id  (jobs/submit th/*system* {::jobs/name :index-file-change-refs
                                          ::jobs/params params})
            job (jobs/get-job th/*system* id)]
        (t/is (= "default" (:queue job)))
        (invoke job)
        (invoke job)
        (t/is (= [change-id] (mapv :file-change-id (th/db-query :file-change-refs {:file-id file-id}))))
        (t/is (empty? (get-pending-jobs :fragments-gc file-id)))))))

(t/deftest explicit-index-params-have-a-bounded-uuid-vector
  (let [file-id   (uuid/next)
        change-id (uuid/next)
        submit    (fn [change-ids]
                    (jobs/submit th/*system* {::jobs/name :index-file-change-refs
                                              ::jobs/params {:file-id file-id :change-ids change-ids}}))]
    (t/is (some? (submit (vec (repeat 128 change-id)))))
    (t/is (thrown? Exception (submit (vec (repeat 129 change-id)))))
    (t/is (thrown? Exception (submit [(str change-id)])))))

(t/deftest repair-continues-in-batches-despite-running-jobs
  (let [file-id    (create-file 1)
        change-ids (mapv #(UUID. 0 (long %)) (range 1 34))
        repair-id  (submit-exclusive :repair-file-change-refs file-id)
        gc-id      (submit-exclusive :fragments-gc file-id)]
    (doseq [change-id change-ids]
      (th/db-insert! :file-change {:file-id file-id :id change-id}))
    (doseq [job-id [repair-id gc-id]]
      (th/db-update! :job {:status "scheduled"} {:id job-id})
      (t/is (= 1 (jobs/claim th/*system* job-id (:scheduled-at (jobs/get-job th/*system* job-id))))))
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)
              cf/flags  (disj cf/flags :tiered-file-data-storage)]
      (invoke (jobs/get-job th/*system* repair-id))
      (t/is (= [(peek change-ids)] (get-missing file-id)))
      (t/is (empty? (get-pending-jobs :fragments-gc file-id)))
      (let [[continuation :as pending] (get-pending-jobs :repair-file-change-refs file-id)]
        (t/is (= 1 (count pending)))
        (t/is (= "default" (:queue continuation)))
        (invoke continuation))
      (t/is (empty? (get-missing file-id)))
      (t/is (= 1 (count (get-pending-jobs :fragments-gc file-id))))
      (t/is (= "running" (:status (jobs/get-job th/*system* gc-id)))))))

(t/deftest repair-retries-an-unreadable-snapshot-without-undoing-earlier-work
  (let [file-id  (create-file 1)
        revision #uuid "00000000-0000-0000-0000-000000000001"
        snapshot #uuid "00000000-0000-0000-0000-000000000002"
        params   {:file-id file-id}]
    (th/db-insert! :file-change {:file-id file-id :id revision})
    (th/db-insert! :file-change {:file-id file-id :id snapshot :label "unreadable"})

    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)
              cf/flags  (disj cf/flags :tiered-file-data-storage)]
      (let [failure (ex/try! (th/run-task! :repair-file-change-refs params))]
        (t/is (= ::wrk/retry (:type (ex-data failure))))
        (t/is (= :invalid-snapshot-payload (:code (ex-data failure))))
        (t/is (= (ct/duration "1m") (:delay (ex-data failure)))))
      (t/is (= [snapshot] (get-missing file-id)))
      (t/is (empty? (get-pending-jobs :repair-file-change-refs file-id)))
      (t/is (empty? (get-pending-jobs :fragments-gc file-id)))

      ;; Restore a readable legacy payload and replay the same job params.
      (th/db-update! :file-change {:data (blob/encode {})} {:file-id file-id :id snapshot})
      (th/run-task! :repair-file-change-refs params)
      (t/is (empty? (get-missing file-id)))
      (t/is (= 1 (count (get-pending-jobs :fragments-gc file-id)))))))

(t/deftest repair-does-not-enqueue-marking-when-disabled
  (doseq [[i enabled flags] [[1 false #{}] [2 true #{:tiered-file-data-storage}]]]
    (let [file-id   (create-file i)
          change-id (uuid/next)]
      (th/db-insert! :file-change {:file-id file-id :id change-id})
      (binding [cf/config (assoc cf/config :fragments-gc-enabled enabled)
                cf/flags  (into (disj cf/flags :tiered-file-data-storage) flags)]
        (th/run-task! :repair-file-change-refs {:file-id file-id}))
      (t/is (empty? (get-missing file-id)))
      (t/is (empty? (get-pending-jobs :fragments-gc file-id))))))

(t/deftest repair-gc-continuation-is-visible-only-after-the-final-transaction-commits
  (let [file-id   (create-file 1)
        change-id (uuid/next)
        submit    jobs/submit]
    (th/db-insert! :file-change {:file-id file-id :id change-id})
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)
              cf/flags  (disj cf/flags :tiered-file-data-storage)]
      (with-redefs [jobs/submit (fn [cfg options]
                                  (let [id (submit cfg options)]
                                    (t/is (some? (jobs/get-job cfg id)))
                                    (t/is (nil? (jobs/get-job th/*system* id)))
                                    (throw (ex-info "force final transaction rollback" {}))))]
        (t/is (thrown-with-msg? Exception #"force final transaction rollback"
                                (th/run-task! :repair-file-change-refs {:file-id file-id}))))
      (t/is (empty? (get-missing file-id)))
      (t/is (empty? (get-pending-jobs :fragments-gc file-id)))
      (th/run-task! :repair-file-change-refs {:file-id file-id})
      (t/is (= 1 (count (get-pending-jobs :fragments-gc file-id)))))))
