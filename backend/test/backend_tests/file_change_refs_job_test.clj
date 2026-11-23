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
   [app.tasks.file-change-refs :as index]
   [app.util.blob :as blob]
   [app.worker :as wrk]
   [backend-tests.helpers :as th]
   [clojure.test :as t])
  (:import
   java.util.UUID))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-file
  []
  (let [profile (th/create-profile* 1)]
    (:id (th/create-file* 1 {:profile-id (:id profile)
                             :project-id (:default-project-id profile)}))))

(defn- run-index
  [file-id]
  (th/run-task! :index-file-change-refs {:file-id file-id}))

(t/deftest index-fills-missing-rows-across-pages-without-activating-gc
  (let [file-id    (create-file)
        change-ids (mapv #(UUID. 0 (long %)) (range 1 67))
        first-id   (first change-ids)]
    (doseq [id change-ids]
      (th/db-insert! :file-change {:file-id file-id :id id}))
    (refs/index-change th/*system* file-id first-id)
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)]
      (let [id  (index/schedule-index th/*system* file-id)
            job (jobs/get-job th/*system* id)]
        (t/is (= "default" (:queue job)))
        (jobs/invoke (assoc th/*system*
                            ::jobs/name :index-file-change-refs
                            ::jobs/params (:params job))))
      (run-index file-id)
      (run-index (uuid/next)))
    (t/is (= (set change-ids)
             (into #{} (map :file-change-id) (th/db-query :file-change-refs {:file-id file-id}))))
    (t/is (empty? (th/db-query :job {:name "fragments-gc"})))
    (t/is (= 1 (count (th/db-query :job {:name "index-file-change-refs"}))))))

(t/deftest index-retries-an-unreadable-snapshot-after-indexing-the-rest-of-the-file
  (let [file-id   (create-file)
        snapshot  (UUID. 0 2)
        revisions (mapv #(UUID. 0 (long %)) (cons 1 (range 3 36)))]
    (doseq [id revisions]
      (th/db-insert! :file-change {:file-id file-id :id id}))
    (th/db-insert! :file-change {:file-id file-id :id snapshot :label "unreadable"})

    (let [failure (ex/try! (run-index file-id))]
      (t/is (= ::wrk/retry (:type (ex-data failure))))
      (t/is (= :invalid-snapshot-payload (:code (ex-data failure))))
      (t/is (= snapshot (:change-id (ex-data failure))))
      (t/is (= (ct/duration "1m") (:delay (ex-data failure)))))
    (t/is (= [snapshot] (refs/get-missing-change-ids th/*system* file-id 2)))

    (th/db-update! :file-change {:data (blob/encode {})} {:file-id file-id :id snapshot})
    (run-index file-id)
    (t/is (empty? (refs/get-missing-change-ids th/*system* file-id 2)))))
