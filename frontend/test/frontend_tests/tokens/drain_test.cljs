;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.drain-test
  "The drain sets in a worker of its own the pages that a lazy change of the
  tokens left out of date. The tests run the worker's work inline."
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.objects-map :as omap]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.drain :as drain]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-file
  "A file with the token `radius` (50) on the current page and a second page
  whose frame `other` holds 10, a value of an older version of the token."
  []
  (let [file (-> (ctht/sample-file-with-tokens
                  :lib-fn #(-> %
                               (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                               (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                      :name "theme"
                                                                      :sets #{"set"}))
                               (ctob/add-token (cthi/id :set)
                                               (ctob/make-token :id (cthi/new-id! :radius)
                                                                :name "radius"
                                                                :type :border-radius
                                                                :value 50)))
                  :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
                 (ctho/add-frame :current))
        first-page-id (cthf/current-page-id file)]
    (-> file
        (cthf/add-sample-page :page2)
        (ctho/add-frame :other)
        (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 10)
        (cths/update-shape :other :token-values {:r1 10})
        (vary-meta assoc :current-page-id first-page-id))))

(defn- page2-id
  [file]
  (:id (cthf/get-page file :page2)))

(defn- inline-worker
  "Stand-in for the drain worker: the work of `app.worker.tokens-drain`, run
  here, and the requests it got in `requests`."
  [requests]
  (fn [{:keys [page values token-names] :as message}]
    (swap! requests conj (select-keys message [:values :token-names]))
    (rx/of (pv/drain-page page values token-names @fonts/fontsdb))))

(defn- inline-thumbnails
  "Stand-in for the drain worker when it looks for the stale thumbnails."
  [{:keys [page values token-names]}]
  (rx/of (pv/page-stale-thumbnails page values token-names)))

(defn- with-drain
  "Run with the lazy propagation, its drain, no waits and `request` as the
  worker that sets the pages (and `thumbnails` as the one that looks for
  their thumbnails); returns the function that restores them."
  ([request]
   (with-drain request inline-thumbnails))
  ([request thumbnails]
   (let [flags   cf/flags
         idle    drain/user-idle-ms
         worker  drain/request-page
         thumbs  drain/request-thumbnails]
     (set! cf/flags (conj flags :lazy-token-propagation :lazy-token-propagation-drain))
     (set! drain/user-idle-ms 1)
     (set! drain/request-page request)
     (set! drain/request-thumbnails thumbnails)
     #(do (set! cf/flags flags)
          (set! drain/user-idle-ms idle)
          (set! drain/request-page worker)
          (set! drain/request-thumbnails thumbs)))))

(defn- watch-commits
  [store]
  (let [commits (atom [])]
    (rx/subs! #(swap! commits conj (deref %))
              (rx/filter dch/commit? (ptk/input-stream store)))
    commits))

(defn- r1
  [state label]
  (:r1 (cths/get-shape (ths/get-file-from-state state) label :page-label :page2)))

(defn- drained?
  "Stopper of `run-store` once the run of the drain ends."
  [stream]
  (->> stream
       (rx/filter (ptk/type? ::drain/run-ended))
       (rx/take 1)
       (rx/delay 10)))

;; --- The work of the worker

(t/deftest the-worker-sets-the-out-of-date-shapes-of-a-page
  (let [file   (setup-file)
        page   (cthf/get-page file :page2)
        result (pv/drain-page page {"radius" 70} ["radius"] {})]
    (t/is (= 70 (-> (cthf/apply-changes file {:redo-changes (:redo-changes result)})
                    (cths/get-shape :other :page-label :page2)
                    :r1)))
    (t/is (= #{(cthi/id :other)} (:frame-ids result)) "the root frame whose thumbnail goes")
    (t/is (nil? (pv/drain-page page {"radius" 10} ["radius"] {})) "nothing out of date")))

(t/deftest the-worker-finds-the-stale-thumbnails-of-a-page
  (let [page (cthf/get-page (setup-file) :page2)]
    (t/is (= {:frame-ids #{(cthi/id :other)} :main-ids #{}}
             (pv/page-stale-thumbnails page {"radius" 70} ["radius"])))
    (t/is (nil? (pv/page-stale-thumbnails page {"radius" 10} ["radius"])) "nothing out of date")))

(t/deftest a-page-is-applied-only-when-it-still-holds-what-was-read
  (let [file   (setup-file)
        page   (cthf/get-page file :page2)
        result (pv/drain-page page {"radius" 70} ["radius"] {})
        edited (cths/update-shape file :other :r1 20 :page-label :page2)]
    (t/is (drain/holds-read-values? page (:undo-changes result)))
    (t/is (not (drain/holds-read-values? (cthf/get-page edited :page2) (:undo-changes result)))
          "the user changed the shape meanwhile")))

(t/deftest a-big-page-is-applied-in-chunks-with-their-undo
  (let [ids    (range 1201)
        mod    (fn [id] {:type :mod-obj :id id :operations []})
        result {:redo-changes (conj (mapv mod ids) {:type :mod-page :id :page})
                :undo-changes (conj (mapv mod ids) {:type :mod-page :id :page})}
        parts  (vec (#'drain/chunks result))]
    (t/is (= [500 500 202] (mapv (comp count :redo-changes) parts)))
    (t/is (every? #(= (map :id (filter (comp #{:mod-obj} :type) (:redo-changes %)))
                      (map :id (filter (comp #{:mod-obj} :type) (:undo-changes %))))
                  parts))
    (t/is (= :mod-page (:type (peek (:redo-changes (peek parts))))) "the other changes go last")))

;; --- The drain

(t/deftest a-lazy-change-drains-the-other-pages-in-the-worker
  (t/async
    done
    (let [requests (atom [])
          restore  (with-drain (inline-worker requests))
          file     (setup-file)
          store    (ths/setup-store file)
          token    (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          commits  (watch-commits store)]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 70 (r1 state :other)) "the other page is set")
         (t/is (= [{:values {"radius" 70} :token-names ["radius"]}] @requests) "once, for the change")
         (t/is (false? (:save-undo? (last @commits))) "not undoable")
         (t/is (nil? (::drain/drain state)) "the run ends"))
       drained?))))

(t/deftest the-shapes-changed-in-memory-are-encoded-before-the-request
  (t/async
    done
    (let [changed  (atom [])
          seen     (fn [{:keys [page]}]
                     (swap! changed conj (omap/changed-ids (:objects page))))
          restore  (with-drain (fn [message]
                                 (seen message)
                                 ((inline-worker (atom [])) message))
                     (fn [message]
                       (seen message)
                       (inline-thumbnails message)))
          file     (setup-file)
          other-id (cthi/id :other)
          ;; the shapes of the other page as loaded, one of them changed since
          file     (update-in file [:data :pages-index (page2-id file) :objects]
                              #(-> (omap/wrap %)
                                   (assoc other-id (get % other-id))))
          store    (ths/setup-store file)
          token    (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 70 (r1 state :other)) "the other page is set")
         (t/is (= [[] []] @changed) "both requests find every shape encoded"))
       drained?))))

(t/deftest the-same-pages-are-not-drained-twice
  (t/async
    done
    (let [requests (atom [])
          answer   (rx/subject)
          restore  (with-drain (fn [message]
                                 (swap! requests conj (:page message))
                                 answer))
          file     (setup-file)
          store    (ths/setup-store file)
          page-id  (page2-id file)
          resolved {"radius" {:name "radius" :resolved-value 70}}]
      (tohs/run-store
       store done [(drain/start resolved {"radius" {}} [page-id])
                   ;; The same tokens again while the page is in the worker
                   (drain/start resolved {"radius" {}} [page-id])
                   (ptk/reify ::answer
                     ptk/EffectEvent
                     (effect [_ state _]
                       (rx/push! answer (pv/drain-page (dsh/lookup-page state page-id)
                                                       {"radius" 70} ["radius"] {}))
                       (rx/end! answer)))]
       (fn [state]
         (restore)
         (t/is (= 1 (count @requests)))
         (t/is (= 70 (r1 state :other))))
       drained?))))

(t/deftest newer-tokens-replace-the-pages-waiting
  (t/async
    done
    (let [requests (atom [])
          old      (rx/subject)
          restore  (with-drain (fn [{:keys [page values token-names]}]
                                 (swap! requests conj values)
                                 (if (= {"radius" 70} values)
                                   ;; The older tokens wait in the worker
                                   old
                                   (rx/of (pv/drain-page page values token-names {})))))
          file     (setup-file)
          store    (ths/setup-store file)
          page-id  (page2-id file)]
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])
                   (drain/start {"radius" {:resolved-value 80}} {"radius" {}} [page-id])
                   (ptk/reify ::answer-old
                     ptk/EffectEvent
                     (effect [_ state _]
                       (rx/push! old (pv/drain-page (dsh/lookup-page state page-id)
                                                    {"radius" 70} ["radius"] {}))
                       (rx/end! old)))]
       (fn [state]
         (restore)
         (t/is (= [{"radius" 70} {"radius" 80}] @requests) "the page is asked again for the new tokens")
         (t/is (= 80 (r1 state :other)) "the result for the older tokens is discarded"))
       drained?))))

(t/deftest a-failing-worker-stops-the-drain
  (t/async
    done
    (let [restore (with-drain (fn [_] (rx/throw (ex-info "worker gone" {}))))
          file    (setup-file)
          store   (ths/setup-store file)
          commits (watch-commits store)]
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [(page2-id file)])]
       (fn [state]
         (restore)
         (t/is (nil? (::drain/drain state)))
         (t/is (empty? @commits) "the page is left for the editors")
         (t/is (= 10 (r1 state :other))))
       (fn [stream]
         (->> stream (rx/filter (ptk/type? ::drain/stop)) (rx/take 1) (rx/delay 10)))))))

(t/deftest the-drain-stops-when-the-tokens-change
  (t/async
    done
    (let [answer  (rx/subject)
          restore (with-drain (fn [_] answer))
          file    (setup-file)
          store   (ths/setup-store file)
          page-id (page2-id file)]
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])
                   ;; An undo of the change, while the page is in the worker
                   (dch/commit-changes {:redo-changes [{:type :set-tokens-status
                                                        :theme-ids #{(cthi/id :theme)}
                                                        :set-ids #{(cthi/id :set)}}]
                                        :undo-changes []
                                        :save-undo? false})
                   (ptk/reify ::answer
                     ptk/EffectEvent
                     (effect [_ state _]
                       (rx/push! answer (pv/drain-page (dsh/lookup-page state page-id)
                                                       {"radius" 70} ["radius"] {}))
                       (rx/end! answer)))]
       (fn [state]
         (restore)
         (t/is (nil? (::drain/drain state)))
         (t/is (= 10 (r1 state :other)) "the page is left for the editors"))
       (fn [stream]
         (->> stream (rx/filter (ptk/type? ::drain/stop)) (rx/take 1) (rx/delay 50)))))))

;; --- The thumbnails

(defn- three-pages-file
  "`setup-file` with a third page whose frame `third` holds 10 too."
  []
  (let [base (setup-file)]
    (-> base
        (cthf/add-sample-page :page3)
        (ctho/add-frame :third)
        (ctht/apply-token-to-shape :third "radius" [:r1] [:r1] 10)
        (cths/update-shape :third :token-values {:r1 10})
        (vary-meta assoc :current-page-id (cthf/current-page-id base)))))

(t/deftest the-thumbnails-of-every-page-are-cleared-before-any-page-is-set
  (t/async
    done
    (let [events  (atom [])
          restore (with-drain (inline-worker (atom [])))
          file    (three-pages-file)
          store   (ths/setup-store file)
          pages   [(page2-id file) (:id (cthf/get-page file :page3))]
          frame?  (fn [label] #(and (ptk/type? ::dwt/clear-thumbnail %)
                                    (str/includes? (deref %) (str (cthi/id label)))))]
      (->> (ptk/input-stream store)
           (rx/filter #(or (dch/commit? %) (ptk/type? ::dwt/clear-thumbnail %)))
           (rx/subs! #(swap! events conj %)))
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} pages)]
       (fn [_]
         (restore)
         (let [first-commit (d/index-of-pred @events dch/commit?)]
           (t/is (some? first-commit))
           (t/is (some (frame? :other) (take first-commit @events)))
           (t/is (some (frame? :third) (take first-commit @events)))))
       drained?))))

(t/deftest the-thumbnails-are-cleared-when-the-pages-are-never-set
  (t/async
    done
    (let [cleared (atom [])
          restore (with-drain (fn [_] (rx/throw (ex-info "worker gone" {}))))
          file    (setup-file)
          store   (ths/setup-store file)]
      (->> (ptk/input-stream store)
           (rx/filter (ptk/type? ::dwt/clear-thumbnail))
           (rx/subs! #(swap! cleared conj (deref %))))
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [(page2-id file)])]
       (fn [state]
         (restore)
         (t/is (= 10 (r1 state :other)) "the page is not set")
         (t/is (some #(str/includes? % (str (cthi/id :other))) @cleared) "its thumbnail is cleared"))
       (fn [stream]
         (->> stream (rx/filter (ptk/type? ::drain/stop)) (rx/take 1) (rx/delay 10)))))))

;; --- A change while the drain runs

(defn- third
  [state]
  (:r1 (cths/get-shape (ths/get-file-from-state state) :third :page-label :page3)))

(defn- held-worker
  "Stand-in for the drain worker that holds the requests for `held` values
  in `answer` and runs the others inline; the requests it got go to
  `requests`."
  [requests held answer]
  (fn [{:keys [page values token-names] :as message}]
    (if (= held values)
      answer
      (do (swap! requests conj (select-keys message [:values :token-names]))
          (rx/of (pv/drain-page page values token-names {}))))))

(t/deftest a-change-of-other-tokens-keeps-the-pages-the-stopped-run-left
  (t/async
    done
    (let [requests (atom [])
          restore  (with-drain (held-worker requests {"radius" 70} (rx/subject)))
          file     (three-pages-file)
          store    (ths/setup-store file)
          pages    [(page2-id file) (:id (cthf/get-page file :page3))]]
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} pages)
                   ;; A change of another token stops the run
                   (dch/commit-changes {:redo-changes [{:type :set-tokens-status
                                                        :theme-ids #{(cthi/id :theme)}
                                                        :set-ids #{(cthi/id :set)}}]
                                        :undo-changes []
                                        :save-undo? false})
                   (drain/start {"radius" {:resolved-value 70} "size" {:resolved-value 5}}
                                {"size" {}} pages)]
       (fn [state]
         (restore)
         (t/is (every? #(= ["size" "radius"] (:token-names %)) @requests)
               "the next run looks for the tokens of both changes")
         (t/is (= 70 (r1 state :other)))
         (t/is (= 70 (third state)))
         (t/is (nil? (::drain/left state))))
       drained?))))

(t/deftest newer-tokens-keep-the-token-names-of-the-run-they-follow
  (t/async
    done
    (let [requests (atom [])
          old      (rx/subject)
          restore  (with-drain (held-worker requests {"radius" 70} old))
          file     (three-pages-file)
          store    (ths/setup-store file)
          pages    [(page2-id file) (:id (cthf/get-page file :page3))]]
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} pages)
                   (drain/start {"radius" {:resolved-value 70} "size" {:resolved-value 5}}
                                {"size" {}} pages)
                   (ptk/reify ::answer-first-run
                     ptk/EffectEvent
                     (effect [_ state _]
                       (rx/push! old (pv/drain-page (dsh/lookup-page state (first pages))
                                                    {"radius" 70} ["radius"] {}))
                       (rx/end! old)))]
       (fn [state]
         (restore)
         (t/is (seq @requests))
         (t/is (every? #(= ["size" "radius"] (:token-names %)) @requests))
         (t/is (= 70 (r1 state :other)))
         (t/is (= 70 (third state))))
       drained?))))

;; --- The pages that an editor has open

(defn- open-page
  "Event that opens `page-id` in this session."
  [page-id]
  (ptk/reify ::open-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-page-id page-id))))

(defn- presence
  "Event that shows another session on `page-id`."
  [page-id can-edit]
  (ptk/reify ::presence
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-presence {(random-uuid) {:page-id page-id :can-edit can-edit}}))))

(defn- drain-with
  "Runs the drain of the second page after `before`, and checks the state
  and the pages the worker got with `check`."
  [done before check]
  (let [requests (atom [])
        restore  (with-drain (inline-worker requests))
        file     (setup-file)
        store    (ths/setup-store file)
        page-id  (page2-id file)]
    (tohs/run-store
     store done (conj (vec (before page-id))
                      (drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id]))
     (fn [state]
       (restore)
       (check state @requests))
     drained?)))

(t/deftest the-drain-leaves-the-page-this-session-has-open
  (t/async
    done
    (drain-with done
                (fn [page-id] [(open-page page-id)])
                (fn [state requests]
                  (t/is (empty? requests))
                  (t/is (= 10 (r1 state :other)) "left for the page check")))))

(t/deftest the-drain-leaves-the-page-another-editor-has-open
  (t/async
    done
    (drain-with done
                (fn [page-id] [(presence page-id true)])
                (fn [state requests]
                  (t/is (empty? requests))
                  (t/is (= 10 (r1 state :other)) "left for the editor on it")))))

(t/deftest the-drain-sets-the-page-only-viewers-have-open
  (t/async
    done
    (drain-with done
                (fn [page-id] [(presence page-id false)])
                (fn [state requests]
                  (t/is (= 1 (count requests)))
                  (t/is (= 70 (r1 state :other)) "viewers don't save it")))))

(t/deftest the-drain-discards-a-page-opened-while-in-the-worker
  (t/async
    done
    (let [answer  (rx/subject)
          sent    (atom 0)
          restore (with-drain (fn [_] (swap! sent inc) answer))
          file    (setup-file)
          store   (ths/setup-store file)
          page-id (page2-id file)]
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])
                   (presence page-id true)
                   (ptk/reify ::answer
                     ptk/EffectEvent
                     (effect [_ state _]
                       (rx/push! answer (pv/drain-page (dsh/lookup-page state page-id)
                                                       {"radius" 70} ["radius"] {}))
                       (rx/end! answer)))]
       (fn [state]
         (restore)
         (t/is (= 1 @sent) "sent before the editor opened it")
         (t/is (= 10 (r1 state :other)) "left for the editor on it"))
       drained?))))



(t/deftest an-edit-between-chunks-stops-the-page
  (t/async
    done
    (let [restore (with-drain (inline-worker (atom [])))
          size    drain/chunk-size
          base    (setup-file)
          file    (-> base
                      (vary-meta assoc :current-page-id (page2-id base))
                      (ctho/add-frame :another)
                      (ctht/apply-token-to-shape :another "radius" [:r1] [:r1] 10)
                      (cths/update-shape :another :token-values {:r1 10})
                      (vary-meta assoc :current-page-id (cthf/current-page-id base)))
          store   (ths/setup-store file)
          page-id (page2-id file)
          edited  (atom nil)]
      (set! drain/chunk-size 1)
      ;; Another session edits the shape of the second chunk after the first
      (->> (ptk/input-stream store)
           (rx/filter dch/commit?)
           (rx/map deref)
           (rx/filter #(contains? (:tags %) :token-propagation))
           (rx/take 1)
           (rx/subs! (fn [{:keys [redo-changes]}]
                       (let [first-id (:id (first redo-changes))
                             other-id (if (= first-id (cthi/id :other)) (cthi/id :another) (cthi/id :other))]
                         (reset! edited other-id)
                         (ptk/emit! store (dch/commit-changes
                                           {:redo-changes [{:type :mod-obj :page-id page-id :id other-id
                                                            :operations [{:type :set :attr :r1 :val 33}]}]
                                            :undo-changes []
                                            :save-undo? false}))))))
      (tohs/run-store
       store done [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])]
       (fn [state]
         (restore)
         (set! drain/chunk-size size)
         (let [file' (ths/get-file-from-state state)
               r1    #(:r1 (cths/get-shape-by-id file' % :page-label :page2))]
           (t/is (some? @edited))
           (t/is (= 33 (r1 @edited)) "the edit is kept")
           (t/is (= #{70 33} (into #{} (map r1) [(cthi/id :other) (cthi/id :another)])) "the first chunk is set")))
       drained?))))
\n