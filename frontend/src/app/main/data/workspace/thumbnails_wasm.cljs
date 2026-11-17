;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.thumbnails-wasm
  "Component thumbnails with render-wasm/v1. The worker renders them
  from the shapes of their mains, which the main thread sends with the
  values of their tokens, and uploads the ones asked for."
  (:require
   [app.common.files.helpers :as cfh]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.correction :as-alias dwtc]
   [app.main.worker :as mw]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- token-values
  "Resolved values of the tokens that `shapes` apply, by name, when the
  tokens check of the workspace (`dwtc/snapshot`) resolved the current
  tokens; nil otherwise or when they apply none."
  [state shapes]
  (let [{:keys [tokens-lib tokens-status resolved]} (get state ::dwtc/snapshot)]
    (when (and (some? resolved)
               (identical? tokens-lib (dsh/lookup-tokens-lib state))
               (identical? tokens-status (dsh/lookup-tokens-status state)))
      (not-empty
       (into {}
             (keep (fn [token-name]
                     (when-let [value (get-in resolved [token-name :resolved-value])]
                       [token-name value])))
             (into #{} (mapcat (comp vals :applied-tokens)) shapes))))))

(defn render-thumbnail
  "Sends the worker the shapes of the main whose root is `root-id`, to
  render its component thumbnail, and to upload it when `persist?` is
  true. With them go the values of their tokens, since a main of another
  page can be out of date until its page is opened. Nothing comes back:
  `component-thumbnail-events` shows the render."
  [file-id page-id root-id & {:keys [persist?]}]
  (ptk/reify ::render-thumbnail
    ptk/EffectEvent
    (effect [_ state _]
      (let [objects (dsh/lookup-page-objects state file-id page-id)]
        ;; A removed component can bring a nil or deleted root
        (when (contains? objects root-id)
          (let [shapes (cfh/get-children-with-self objects root-id)
                values (token-values state shapes)]
            (mw/emit! (cond-> {:cmd :thumbnails/render-component
                               :file-id file-id
                               :page-id page-id
                               :root-id root-id
                               :persist? persist?
                               :objects (into {} (map (juxt :id identity)) shapes)}
                        (some? values) (assoc :token-values values)))))))))

(defn component-thumbnail-events
  "Shows each component thumbnail the worker renders for
  `render-thumbnail`."
  []
  (->> (mw/ask-many! {:cmd :thumbnails/watch-components})
       (rx/map (fn [{:keys [object-id blob]}]
                 (dwt/assoc-thumbnail object-id (wapi/create-uri blob))))))
