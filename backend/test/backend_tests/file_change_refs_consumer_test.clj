;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.file-change-refs-consumer-test
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.loggers.audit.consumers.file-change-refs :as consumer]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- insert-event
  [name source initiator props]
  (th/db-exec-one!
   ["INSERT INTO audit_log (id, name, type, source, profile_id, ip_addr, props, context)
     VALUES (?, ?, 'action', ?, ?, '127.0.0.1', ?, ?)"
    (uuid/next) name source uuid/zero (db/tjson props)
    (db/tjson {:initiator initiator})]))

(defn- pending
  [name]
  (mapv (comp db/decode-json-pgobject :params)
        (th/db-exec! ["SELECT params FROM job WHERE name = ? ORDER BY created_at" name])))

(t/deftest permanent-consumer-indexes-without-marking-or-external-destinations
  (let [file-id  (uuid/next)
        change   (uuid/next)
        snapshot (uuid/next)]
    (binding [cf/flags #{}]
      (insert-event "update-file" "backend" "app"
                    {:id file-id :outcome "partial" :file-change-id change})
      (insert-event "file-change-created" "backend" "app"
                    {:file-id file-id :file-change-id snapshot})
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= [{:file-id (str file-id)
                 :change-ids [(str change) (str snapshot)]}]
               (pending "index-file-change-refs")))
      (t/is (empty? (pending "fragments-gc")))
      (t/is (pos? (:seq (th/db-exec-one!
                         ["SELECT seq FROM audit_log_consumer_cursor WHERE consumer = 5"]))))
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= 1 (count (pending "index-file-change-refs")))))))

(t/deftest only-effective-backend-saves-activate-fragment-marking
  (let [file-id     (uuid/next)
        untrusted   (uuid/next)
        change-props (fn [file-id outcome]
                       {:id file-id :outcome outcome :file-change-id (uuid/next)})]
    (binding [cf/config (assoc cf/config :fragments-gc-enabled true)
              cf/flags #{}]
      (insert-event "update-file" "backend" "app" (change-props file-id "saved"))
      (insert-event "update-file" "backend" "app" (change-props file-id "partial"))
      (insert-event "update-file" "backend" "app" (change-props untrusted "rejected"))
      (insert-event "update-file" "frontend" "app" (change-props untrusted "saved"))
      (insert-event "update-file" "telemetry:backend" "app" (change-props untrusted "saved"))
      (insert-event "update-file" "backend" "admin-console" (change-props untrusted "saved"))
      (consumers/run-pass th/*system* consumer/consumer)
      (t/is (= [{:file-id (str file-id)}] (pending "fragments-gc")))
      (t/is (= #{(str file-id)} (into #{} (map :file-id) (pending "index-file-change-refs")))))))

(t/deftest audit-cursor-and-index-submission-roll-back-together
  (let [file-id (uuid/next)
        change  (uuid/next)
        submit  jobs/submit]
    (consumers/ensure-cursor th/*system* consumers/file-change-refs)
    (insert-event "update-file" "backend" "app"
                  {:id file-id :outcome "saved" :file-change-id change})
    (with-redefs [jobs/submit (fn [cfg options]
                                (submit cfg options)
                                (throw (ex-info "abort consumer chunk" {})))]
      (t/is (thrown-with-msg? Exception #"abort consumer chunk"
                              (consumers/run-pass th/*system* consumer/consumer))))
    (t/is (empty? (pending "index-file-change-refs")))
    (t/is (= 0 (:seq (th/db-exec-one!
                      ["SELECT seq FROM audit_log_consumer_cursor WHERE consumer = 5"]))))
    (consumers/run-pass th/*system* consumer/consumer)
    (t/is (= [{:file-id (str file-id) :change-ids [(str change)]}]
             (pending "index-file-change-refs")))))
