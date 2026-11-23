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
   [app.worker :as wrk]
   [integrant.core :as ig])
  (:import
   java.sql.SQLException))

(def ^:private batch-size 32)
(def ^:private retry-delay (ct/duration "1m"))

(defn schedule-index
  "Coalesce pending requests, but let a running scan get a successor."
  [cfg file-id]
  (jobs/submit cfg {::jobs/name :index-file-change-refs
                    ::jobs/params {:file-id file-id}
                    ::jobs/label (str file-id)
                    ::jobs/dedupe true}))

(defn- retry-index
  [file-id change-id cause]
  (let [{:keys [code hint]} (ex-data cause)]
    (ex/raise :type ::wrk/retry
              :code (or code :file-change-index-failed)
              :hint (or hint "unable to index file change references")
              :file-id file-id
              :change-id change-id
              :delay retry-delay
              :cause cause)))

(defn- index-changes
  [cfg file-id first-failure change-ids]
  (reduce (fn [failure change-id]
            (jobs/heartbeat cfg)
            (try
              ;; Each publication commits independently. Keep the first
              ;; unreadable payload as a cause for retry, but index the rest.
              (refs/index-change cfg file-id change-id)
              failure
              (catch InterruptedException cause
                (throw cause))
              (catch SQLException cause
                (retry-index file-id change-id cause))
              (catch Exception cause
                (when (= :interrupt (:type (ex-data cause)))
                  (throw cause))
                (or failure [change-id cause]))))
          first-failure
          change-ids))

(defn execute-index-file-change-refs
  "Fill missing index rows in bounded pages. A later event can enqueue a
  new scan for changes committed after this scan passed their IDs."
  [cfg {:keys [file-id]}]
  (loop [after-id nil
         failure  nil]
    (let [change-ids (refs/get-missing-change-ids cfg file-id batch-size :after-id after-id)
          failure    (index-changes cfg file-id failure change-ids)]
      (if (= batch-size (count change-ids))
        (recur (peek change-ids) failure)
        (when-let [[change-id cause] failure]
          (retry-index file-id change-id cause))))))

(def ^:private schema:params
  [:map {:closed true}
   [:file-id ::sm/uuid]])

(def ^:private valid-config?
  (sm/validator [:map ::db/pool ::mtx/metrics ::sto/storage]))

(defmethod ig/init-key ::job-def
  [_ cfg]
  (assert (valid-config? cfg) "expected a database pool, metrics, and storage")
  {::jobs/name      :index-file-change-refs
   ::jobs/schema    schema:params
   ::jobs/handler   (fn [_context params] (execute-index-file-change-refs cfg params))
   ::jobs/decoder   (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator (sm/validator schema:params)})
