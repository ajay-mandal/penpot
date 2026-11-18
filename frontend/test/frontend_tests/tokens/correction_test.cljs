;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.correction-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.viewer :as dv]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.data.workspace.tokens.leader :as leader]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.util.activity :as activity]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
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

;; --- Leader

(t/deftest the-lowest-editor-on-the-page-goes-first
  (let [[a b c] (sort-by str [(uuid/next) (uuid/next) (uuid/next)])
        page    (uuid/next)
        other   (uuid/next)
        presence {a {:can-edit true :page-id page}
                  b {:can-edit true :page-id page}
                  c {:can-edit true :page-id other}}]
    (t/is (= 0 (leader/position presence a page nil)))
    (t/is (= 1 (leader/position presence b page nil)))
    (t/testing "sessions on other pages don't count"
      (t/is (= 0 (leader/position presence c other nil))))
    (t/testing "sessions that can't edit don't count"
      (t/is (= 0 (leader/position (assoc-in presence [a :can-edit] false) b page nil))))
    (t/testing "the author goes first"
      (t/is (= 1 (leader/position presence a page b)))
      (t/is (= 0 (leader/position presence b page b))))
    (t/testing "the author goes first when it sets the page"
      (t/is (= b (leader/author-first presence page b false)) "it sets every page")
      (t/is (= b (leader/author-first presence page b true)) "it is on the page")
      (t/is (nil? (leader/author-first presence other b true)) "a lazy change sets only its page")
      (t/is (nil? (leader/author-first presence page nil true))))
    (t/testing "a session that joins later knows the page of the idle ones"
      (t/is (= 1 (leader/position {a {:can-edit true :page-id page}} c page nil))
            "presence alone tells where `a` is"))
    (t/is (= 0 (leader/wait-ms 0)))
    (t/is (= 3000 (leader/wait-ms 1)))
    (t/is (= 4000 (leader/wait-ms 2)))))

;; --- Correction

(defn- setup-file
  []
  (-> (ctht/sample-file-with-tokens
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
      ;; holds the value of a previous version of the token
      (ctho/add-frame :stale)
      (ctht/apply-token-to-shape :stale "radius" [:r1] [:r1] 10)
      (cths/update-shape :stale :token-values {:r1 10})
      ;; from before the values were kept
      (ctho/add-frame :legacy)
      (ctht/apply-token-to-shape :legacy "radius" [:r1] [:r1] 10)))

(defn- watch-commits
  [store]
  (let [commits (atom [])]
    (rx/subs! #(swap! commits conj (deref %))
              (rx/filter dch/commit? (ptk/input-stream store)))
    commits))

(def ^:private stop-on-ready
  (tohs/stop-on ::dwtc/tokens-ready))

(t/deftest a-page-without-tokens-is-ready-when-it-opens
  (t/async
    done
    (let [store (ths/setup-store (cthf/sample-file :file))
          ready (atom false)]
      (rx/subs! #(reset! ready true)
                (rx/filter (ptk/type? ::dwtc/tokens-ready) (ptk/input-stream store)))
      (ptk/emit! store (dwtc/check-page {:opening? true}))
      (js/setTimeout #(do (t/is @ready) (done)) 100))))

(t/deftest editors-set-the-page-when-it-opens
  (t/async
    done
    (let [store   (ths/setup-store (setup-file))
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)
               commit (first @commits)]
           (t/is (= 50 (:r1 (cths/get-shape file' :stale))))
           (t/is (= 50 (:r1 (cths/get-shape file' :legacy))) "unassigned values are set by editors")
           (t/is (= :local (:source commit)))
           (t/is (false? (:save-undo? commit)) "corrections are not undoable")
           (t/is (contains? (:tags commit) :token-propagation))))
       stop-on-ready))))

(t/deftest a-long-correction-blocks-the-workspace-first
  (t/async
    done
    (let [weight dwtpr/long-weight
          _      (set! dwtpr/long-weight 1)
          store  (ths/setup-store (setup-file))
          events (atom [])]
      (rx/subs! #(cond
                   (ptk/type? ::dwtpr/show %)     (swap! events conj :show)
                   (dch/commit? %)                (swap! events conj :commit)
                   (ptk/type? ::dwtpr/add-work %)  (swap! events conj [:add (deref %)])
                   (ptk/type? ::dwtpr/work-done %) (swap! events conj [:done (deref %)]))
                (ptk/input-stream store))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (set! dwtpr/long-weight weight)
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (= [[:add 1] :show] (take 2 (remove #{:commit} @events)))
               "the dialog shows before the values are set")
         (t/is (= [:done 1] (last @events))))
       stop-on-ready))))

(t/deftest the-progress-shows-until-the-work-is-done
  (let [tick    #'dwtpr/tick
        long    (js/Date. (- (js/Date.now) 5000))
        work*   (volatile! {:total 2 :done 0 :quiet 0 :started long})
        type-of (fn [events]
                  (let [first* (atom nil)]
                    (when events
                      (rx/sub! (rx/take 1 events) #(reset! first* (ptk/type %))))
                    @first*))]
    (t/is (= ::dwtpr/show (type-of (tick work*)))
          "shown once the work runs longer than the delay")
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)))
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)) "the texts and layouts may follow")
    (t/is (= ::dwtpr/hide (type-of (tick work*)))
          "hidden when the values are set and nothing is pending")
    (t/is (nil? @work*))))

(t/deftest the-progress-does-not-show-once-the-values-are-set
  (wrf/reset-pending!)
  (let [tick  #'dwtpr/tick
        long  (js/Date. (- (js/Date.now) 5000))
        task  (wrf/task :layout [(uuid/next)])
        work* (volatile! {:total 1 :done 1 :quiet 0 :started long})]
    (wrf/start! task)
    (t/is (nil? (tick work*)) "texts and layouts in flight don't show it")
    (t/is (nil? @work*))
    (wrf/finish! task)))

(t/deftest the-progress-waits-only-for-the-work-in-flight-when-the-values-are-set
  (wrf/reset-pending!)
  (let [tick    #'dwtpr/tick
        long    (js/Date. (- (js/Date.now) 5000))
        before  (wrf/task :text-resize [(uuid/next)])
        after   (wrf/task :layout [(uuid/next)])
        work*   (volatile! {:total 1 :done 1 :quiet 0 :started long :shown? true})
        type-of (fn [events]
                  (let [first* (atom nil)]
                    (when events
                      (rx/sub! (rx/take 1 events) #(reset! first* (ptk/type %))))
                    @first*))]
    (wrf/start! before)
    (t/is (nil? (tick work*)) "the work in flight holds it")
    (wrf/start! after)
    (wrf/finish! before)
    (t/is (nil? (tick work*)))
    (t/is (= ::dwtpr/hide (type-of (tick work*)))
          "work started after the values doesn't hold it")
    (wrf/finish! after)))

(t/deftest quick-work-shows-no-progress
  (let [tick  #'dwtpr/tick
        work* (volatile! {:total 1 :done 0 :quiet 0 :started (ct/now)})]
    (t/is (nil? (tick work*)))
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)))
    (t/is (nil? (tick work*)) "done before the delay")
    (t/is (nil? @work*))))

(t/deftest the-save-waits-for-the-dialog-to-close
  (t/async
    done
    (let [stream  (rx/subject)
          emitted (atom [])
          watch   (fn [state]
                    (rx/sub! (ptk/watch (dwtpr/persist-when-closed) state stream)
                             #(swap! emitted conj %)))]
      (watch {})
      (t/is (= [::dps/force-persist] @emitted) "at once without the dialog")
      (reset! emitted [])
      (watch {::dwtpr/progress true})
      (t/is (= [] @emitted) "not while the dialog shows")
      (rx/push! stream (#'dwtpr/hide))
      (t/is (= [] @emitted) "not before the closed dialog is painted")
      (js/setTimeout (fn []
                       (t/is (= [::dps/force-persist] @emitted) "once it is painted")
                       (done))
                     (+ dwtpr/paint-timeout-ms 100)))))

(t/deftest readers-set-the-values-in-memory
  (t/async
    done
    (let [store   (ths/setup-store (setup-file))
          _       (swap! store assoc :permissions {:can-edit false})
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 50 (:r1 (cths/get-shape file' :stale))))
           (t/is (= 10 (:r1 (cths/get-shape file' :legacy))) "readers trust unassigned values")
           (t/is (every? #(= :in-memory (:source %)) @commits) "nothing is saved")))
       stop-on-ready))))

(defn- answer-from-another-session
  "Set the radius of `id` back to 10, as a session that resolves the token
  to 10 would."
  [id]
  (ptk/reify ::answer-from-another-session
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            page-id (:current-page-id state)]
        (update-in state [:files file-id :data :pages-index page-id :objects id]
                   assoc :r1 10 :token-values {:r1 10})))))

(defn- after-ready
  "Emit `events` once the page holds its token values."
  [& events]
  (ptk/reify ::after-ready
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::dwtc/tokens-ready))
           (rx/take 1)
           (rx/mapcat #(rx/from events))))))

(defn- after-propagations
  "Emit `events` once the running token propagation has finished."
  [& events]
  (ptk/reify ::after-propagations
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? :app.main.data.workspace.tokens.propagation/propagation-finished))
           (rx/take 1)
           (rx/mapcat #(rx/from events))))))

(t/deftest a-session-sets-a-value-once
  (t/async
    done
    (let [store   (ths/setup-store (setup-file))
          commits (watch-commits store)
          stale   (cthi/id :stale)]
      (tohs/run-store
       store done [(dwtc/check-page {:ids [stale]})
                   (after-ready (answer-from-another-session stale)
                                (dwtc/check-page {:ids [stale]}))]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 10 (:r1 (cths/get-shape file' :stale))) "this session doesn't answer again")
           (t/is (= 1 (count (filter (fn [commit] (some #(= :mod-obj (:type %)) (:redo-changes commit)))
                                     @commits))))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

(defn- lay-out-again
  "Commit of the layout of `id` of `page-id` after a change, as the texts
  get."
  [page-id id]
  (dch/commit-changes {:redo-changes [{:type :mod-obj :id id :page-id page-id
                                       :operations [{:type :set :attr :name :val "laid out"}]}]
                       :undo-changes []
                       :save-undo? false
                       :tags #{:position-data}}))

(t/deftest a-session-sets-a-value-once-also-when-laid-out-again
  (t/async
    done
    (let [file    (setup-file)
          store   (ths/setup-store file)
          commits (watch-commits store)
          stale   (cthi/id :stale)]
      (tohs/run-store
       store done [(dwtc/initialize (:id file))
                   (dwtc/check-page {:ids [stale]})
                   (after-ready (lay-out-again (cthf/current-page-id file) stale)
                                (answer-from-another-session stale)
                                (dwtc/check-page {:ids [stale]}))]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 10 (:r1 (cths/get-shape file' :stale))) "this session doesn't answer again")
           (t/is (= 1 (count (filter (fn [commit]
                                       (and (contains? (:tags commit) :token-propagation)
                                            (some #(= :mod-obj (:type %)) (:redo-changes commit))))
                                     @commits))))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 1)
              (rx/delay 300)))))))

(defn- with-two-editors
  "Store of `file` for this session and another editor on the current page;
  `first?` when this session goes first."
  [file first?]
  (let [[low high] [#uuid "00000000-0000-0000-0000-000000000001"
                    #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"]
        [this other] (if first? [low high] [high low])
        store        (ths/setup-store file)]
    (swap! store assoc
           :session-id this
           :workspace-presence {other {:can-edit true :page-id (cthf/current-page-id file)}})
    store))

(defn- watch-event
  "Atom with the times `event`, a keyword, goes through `store`."
  [store event]
  (let [events (atom [])]
    (rx/subs! #(swap! events conj %)
              (rx/filter #(= event %) (ptk/input-stream store)))
    events))

(t/deftest a-waiting-session-saves-nothing-of-a-remote-change
  (t/async
    done
    (let [store   (with-two-editors (setup-file) false)
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:remote? true})]
       (fn [_]
         (t/is (empty? @commits) "neither the shapes nor the resolved values"))
       (tohs/stop-on ::dwtc/set-out-of-date)))))

(t/deftest the-first-session-sends-a-remote-change-at-once
  (t/async
    done
    (let [store    (with-two-editors (setup-file) true)
          commits  (watch-commits store)
          persists (watch-event store :app.main.data.persistence/force-persist)]
      (tohs/run-store
       store done [(dwtc/check-page {:remote? true})]
       (fn [state]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (some (fn [commit] (some #(= :set-tokens-resolved (:type %)) (:redo-changes commit))) @commits))
         (t/is (= 1 (count @persists)) "the others wait for these values"))
       stop-on-ready))))

(t/deftest the-tokens-are-resolved-again-only-when-they-change
  (t/async
    done
    (let [calls   (atom 0)
          resolve dwta/resolve-sets
          store   (ths/setup-store (setup-file))]
      (set! dwta/resolve-sets
            (fn [sets base-font-size] (swap! calls inc) (resolve sets base-font-size)))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})
                   ;; another check once the first one is done
                   (ptk/reify ::check-again
                     ptk/WatchEvent
                     (watch [_ _ stream]
                       (->> stream
                            (rx/filter (ptk/type? ::dwtc/tokens-ready))
                            (rx/take 1)
                            (rx/map (fn [_] (dwtc/check-page {:opening? true}))))))]
       (fn [_]
         (set! dwta/resolve-sets resolve)
         (t/is (= 1 @calls)))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

(t/deftest a-change-of-the-tokens-is-checked
  (t/async
    done
    (let [file    (setup-file)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          ;; as an undo commits it: no propagation of its own
          changes (-> (pcb/empty-changes nil)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70)))]
      (tohs/run-store
       store done [(dwtc/initialize (:id file))
                   (dwtc/check-page {:opening? true})
                   (dch/commit-changes changes)]
       (fn [state]
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale)))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

(t/deftest the-values-wait-for-the-renderer-to-hold-the-page
  (t/async
    done
    (let [file    (setup-file)
          page-id (cthf/current-page-id file)
          store   (ths/setup-store file)
          _       (swap! store assoc :features #{"render-wasm/v1"})
          commits (watch-commits store)
          before  (atom nil)
          ;; the renderer loads the shapes of the page a while after it opens
          loaded  (ptk/reify ::loaded
                    ptk/WatchEvent
                    (watch [_ _ _]
                      (->> (rx/of (dw/page-shapes-loaded page-id))
                           (rx/delay 100)
                           (rx/tap #(reset! before (count @commits))))))]
      (tohs/run-store
       store done [(dwtc/initialize (:id file))
                   (ptk/data-event :app.main.data.workspace.pages/initialized page-id)
                   loaded]
       (fn [state]
         (t/is (= 0 @before) "nothing is set before the shapes are loaded")
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale)))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 1)))))))

(t/deftest the-text-resize-of-a-correction-is-not-an-edit
  (t/async
    done
    (let [calls   (atom [])
          resize  dwwt/resize-wasm-text-all
          file    (-> (ctht/sample-file-with-tokens
                       :lib-fn #(-> %
                                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                           :name "theme"
                                                                           :sets #{"set"}))
                                    (ctob/add-token (cthi/id :set)
                                                    (ctob/make-token :id (cthi/new-id! :size)
                                                                     :name "size"
                                                                     :type :font-size
                                                                     :value 20)))
                       :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
                      (ctho/add-frame-with-text :frame :text "hello")
                      ;; holds the value of a previous version of the token
                      (ctht/apply-token-to-shape :text "size" [:font-size] [:font-size] "14")
                      (cths/update-shape :text :token-values {:font-size 14}))
          store   (ths/setup-store file)]
      (set! dwwt/resize-wasm-text-all
            (fn
              ([ids] (ptk/data-event ::resized ids))
              ([ids opts]
               (swap! calls conj opts)
               (ptk/data-event ::resized ids))))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [_]
         (set! dwwt/resize-wasm-text-all resize)
         (t/is (= [{:skip-component-sync? true :save-undo? false}] @calls)
               "the copies get their values from the tokens and nothing is undoable"))
       stop-on-ready))))

(t/deftest the-texts-are-resized-in-the-pass-of-the-sizes
  (t/async
    done
    (let [flags    cf/flags
          resize   dwwt/resize-wasm-text-all
          measure  dwwt/resize-wasm-text-modifiers
          ready?   dwwt/content-fonts-ready?
          apply    dwm/apply-wasm-modifiers
          resized  (atom 0)
          passes   (atom [])
          file     (-> (ctht/sample-file-with-tokens
                        :lib-fn #(-> %
                                     (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                                     (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                            :name "theme"
                                                                            :sets #{"set"}))
                                     (ctob/add-token (cthi/id :set)
                                                     (ctob/make-token :id (cthi/new-id! :size)
                                                                      :name "size"
                                                                      :type :font-size
                                                                      :value 20)))
                        :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
                       (ctho/add-frame-with-text :frame :text "hello")
                       ;; holds the value of a previous version of the token
                       (ctht/apply-token-to-shape :text "size" [:font-size] [:font-size] "14")
                       (cths/update-shape :text :token-values {:font-size 14}))
          text-id  (cthi/id :text)
          store    (ths/setup-store file)
          restore  (fn []
                     (set! cf/flags flags)
                     (set! dwwt/resize-wasm-text-all resize)
                     (set! dwwt/resize-wasm-text-modifiers measure)
                     (set! dwwt/content-fonts-ready? ready?)
                     (set! dwm/apply-wasm-modifiers apply))]
      (set! cf/flags (conj flags :lazy-token-propagation))
      (set! dwwt/resize-wasm-text-all
            (fn
              ([ids] (swap! resized inc) (ptk/data-event ::resized ids))
              ([ids _] (swap! resized inc) (ptk/data-event ::resized ids))))
      (set! dwwt/resize-wasm-text-modifiers
            (fn
              ([shape] {(:id shape) {:modifiers :measured}})
              ([shape _] {(:id shape) {:modifiers :measured}})))
      (set! dwwt/content-fonts-ready? (constantly true))
      (set! dwm/apply-wasm-modifiers
            (fn [tree & {:as opts}]
              (swap! passes conj [tree (:save-undo? opts)])
              (ptk/data-event ::geometry tree)))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [_]
         (restore)
         (t/is (= 0 @resized) "no second pass")
         (t/is (= [[{text-id {:modifiers :measured}} false]] @passes)
               "the text gets its measured size in a single pass, not undoable"))
       stop-on-ready))))

(t/deftest only-the-texts-the-renderer-resizes-wait-for-it
  (t/async
    done
    (let [file    (-> (ctht/sample-file-with-tokens
                       :lib-fn #(-> %
                                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                           :name "theme"
                                                                           :sets #{"set"}))
                                    (ctob/add-token (cthi/id :set)
                                                    (ctob/make-token :id (cthi/new-id! :color)
                                                                     :name "color"
                                                                     :type :color
                                                                     :value "#ff0000"))
                                    (ctob/add-token (cthi/id :set)
                                                    (ctob/make-token :id (cthi/new-id! :size)
                                                                     :name "size"
                                                                     :type :font-size
                                                                     :value 20)))
                       :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
                      ;; both hold the value of a previous version of their token
                      (ctho/add-frame-with-text :colored-frame :colored "hello")
                      (ctht/apply-token-to-shape :colored "color" [:fill] [] nil)
                      (cths/update-shape :colored :token-values {:fill "#00ff00"})
                      (ctho/add-frame-with-text :sized-frame :sized "hello")
                      (ctht/apply-token-to-shape :sized "size" [:font-size] [:font-size] "14")
                      (cths/update-shape :sized :token-values {:font-size 14}))
          store   (ths/setup-store file)
          reflows (atom #{})]
      (rx/subs! #(swap! reflows into (:ids (deref %)))
                (rx/filter (ptk/type? :text/reflow) (ptk/input-stream store)))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= {:fill "#ff0000" :font-size 20}
                    (merge (:token-values (cths/get-shape file' :colored))
                           (:token-values (cths/get-shape file' :sized))))
                 "both texts are set")
           (t/is (= #{(cthi/id :sized)} @reflows)
                 "a new color leaves the size of the text as it is")))
       stop-on-ready))))

;; --- Values for the readers

(t/deftest editors-save-the-resolved-values
  (t/async
    done
    (let [store (ths/setup-store (setup-file))]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (t/is (= {"radius" 50}
                  (:tokens-resolved (:data (ths/get-file-from-state state))))))
       stop-on-ready))))

(defn- viewer-radius
  "Runs the viewer resolution of `tokens` with the `saved` values on the
  bundle of `setup-file` and calls `check` with the radius of its stale and
  legacy shapes."
  [done tokens saved check]
  (let [file    (setup-file)
        page-id (cthf/current-page-id file)]
    (->> (dv/resolve-token-values {:file file :tokens tokens :tokens-resolved saved} page-id)
         (rx/subs! (fn [bundle]
                     (let [objects (get-in bundle [:file :data :pages-index page-id :objects])]
                       (check (get-in objects [(cthi/id :stale) :r1])
                              (get-in objects [(cthi/id :legacy) :r1]))))
                   (fn [cause]
                     (t/is (nil? cause))
                     (done))
                   done))))

(t/deftest the-viewer-sets-out-of-date-shapes-to-the-resolved-tokens
  (t/async
    done
    (viewer-radius done
                   {"radius" {:name "radius" :type :border-radius :value "{base}"}
                    "base"   {:name "base" :type :border-radius :value "60"}}
                   {"radius" 50}
                   (fn [stale legacy]
                     (t/is (= 60 stale) "the current tokens win over the saved values")
                     (t/is (= 10 legacy) "readers trust unassigned values")))))

(t/deftest the-viewer-uses-the-saved-value-of-a-token-that-does-not-resolve
  (t/async
    done
    (viewer-radius done
                   {"radius" {:name "radius" :type :border-radius :value "{missing}"}}
                   {"radius" 50}
                   (fn [stale _]
                     (t/is (= 50 stale))))))

(t/deftest the-viewer-uses-the-saved-values-without-tokens
  (t/async
    done
    (viewer-radius done nil {"radius" 50}
                   (fn [stale _]
                     (t/is (= 50 stale))))))

(t/deftest the-viewer-sets-only-the-page-it-shows
  (t/async
    done
    (let [file     (setup-file)
          page1-id (cthf/current-page-id file)
          file     (-> file
                       (cthf/add-sample-page :page2)
                       (ctho/add-frame :stale2)
                       (ctht/apply-token-to-shape :stale2 "radius" [:r1] [:r1] 10)
                       (cths/update-shape :stale2 :token-values {:r1 10}))
          page2-id (cthi/id :page2)
          r1       (fn [bundle page-id label]
                     (get-in bundle [:file :data :pages-index page-id :objects (cthi/id label) :r1]))
          bundle   {:file file :tokens nil :tokens-resolved {"radius" 50}}]
      (->> (rx/zip (dv/resolve-token-values bundle page2-id)
                   (dv/resolve-token-values bundle nil))
           (rx/subs! (fn [[shown first-page]]
                       (t/is (= 50 (r1 shown page2-id :stale2)))
                       (t/is (= 10 (r1 shown page1-id :stale)) "the other pages are left as they are")
                       (t/is (= 50 (r1 first-page page1-id :stale)) "without a page, the first one")
                       (t/is (= 10 (r1 first-page page2-id :stale2))))
                     (fn [cause]
                       (t/is (nil? cause))
                       (done))
                     done)))))

;; --- Lazy propagation

(defn- setup-file-with-pages
  "`setup-file` with a second page that has a board and a main component
  applying the token. The first page stays the current one."
  []
  (let [file          (setup-file)
        first-page-id (cthf/current-page-id file)]
    (-> file
        (cthf/add-sample-page :page2)
        (ctho/add-frame :other)
        (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 50)
        (cths/update-shape :other :token-values {:r1 50})
        (ctho/add-simple-component :component1 :main-root :main-child)
        (ctht/apply-token-to-shape :main-root "radius" [:r1] [:r1] 50)
        (cths/update-shape :main-root :token-values {:r1 50})
        (vary-meta assoc :current-page-id first-page-id))))

(defn- with-lazy-flag
  "Run `f` with the lazy token propagation flag on; it returns the restore
  function to call when the test ends."
  []
  (let [flags cf/flags]
    (set! cf/flags (conj flags :lazy-token-propagation))
    #(set! cf/flags flags)))

(t/deftest lazy-changes-set-only-the-current-page
  (t/async
    done
    (let [restore (with-lazy-flag)
          file    (setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page2   #(cths/get-shape (ths/get-file-from-state %1) %2 :page-label :page2)]
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))) "the current page")
         (t/is (= 50 (:r1 (page2 state :other))) "the other pages wait")
         (t/is (= 50 (:r1 (page2 state :main-root))) "their main components too"))))))

(t/deftest lazy-pages-are-set-when-opened
  (t/async
    done
    (let [restore (with-lazy-flag)
          file    (setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page2   (cthi/id :page2)]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))
                   (after-propagations #(assoc % :current-page-id page2)
                                       (dwtc/check-page {:opening? true}))]
       (fn [state]
         (restore)
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))))
       (fn [stream]
         (->> stream
              (rx/filter #(and (ptk/type? ::dwtc/tokens-ready %)
                               (= page2 (:page-id (deref %)))))
              (rx/take 1)))))))

(t/deftest lazy-changes-drain-the-other-pages
  (t/async
    done
    (let [restore (with-lazy-flag)
          file    (setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          other   (cthi/id :other)
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))
               "the other page is set in the background")
         (t/is (false? (:save-undo? (last @commits))) "not undoable"))
       (fn [stream]
         (->> stream
              (rx/filter dch/commit?)
              (rx/filter #(some (fn [change] (= other (:id change))) (:redo-changes (deref %))))
              (rx/take 1)
              (rx/delay 10)))))))

(t/deftest the-change-is-saved-and-the-other-pages-are-not
  (t/async
    done
    (let [flags   cf/flags
          _       (set! cf/flags (-> flags
                                     (conj :lazy-token-propagation)
                                     (disj :lazy-token-propagation-drain)))
          file    (setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page1   (cthf/current-page-id file)
          events  (atom [])]
      (rx/subs! (fn [event]
                  (cond
                    (= :app.main.data.persistence/force-persist event)
                    (swap! events conj :persist)

                    (dch/commit? event)
                    (swap! events conj (if (some #(= page1 (:page-id %)) (:redo-changes (deref event)))
                                         :current-page
                                         :other-page))))
                (ptk/input-stream store))
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [_]
         (set! cf/flags flags)
         (t/is (= [:current-page :persist] @events)))))))

(t/deftest the-other-pages-go-in-the-background
  (t/async
    done
    (let [flags   cf/flags
          _       (set! cf/flags (-> flags
                                     (conj :lazy-token-propagation)
                                     (disj :lazy-token-propagation-drain)))
          file    (setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page1   (cthf/current-page-id file)
          events  (atom [])]
      (rx/subs! (fn [event]
                  (cond
                    (ptk/type? ::dwtpr/add-work event)
                    (swap! events conj [:add-work (deref event)])

                    (ptk/type? :app.main.data.workspace.undo/commit-undo-transaction event)
                    (swap! events conj :undo-entry)

                    (dch/commit? event)
                    (let [commit (deref event)]
                      (swap! events conj [(if (some #(= page1 (:page-id %)) (:redo-changes commit))
                                            :current-page
                                            :other-page)
                                          (:save-undo? commit true)]))))
                (ptk/input-stream store))
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [_]
         (set! cf/flags flags)
         (t/is (= [[:add-work 1] [:current-page true] :undo-entry] @events)
               "the undo entry closes with the current page, nothing waits for the other pages"))))))

(defn- without-drain
  "Run with the lazy token propagation flag on and its drain off; it
  returns the restore function to call when the test ends."
  []
  (let [flags cf/flags]
    (set! cf/flags (-> flags
                       (conj :lazy-token-propagation)
                       (disj :lazy-token-propagation-drain)))
    #(set! cf/flags flags)))

(defn- component-thumbnail
  "Id of the component thumbnail of `label`, on the page `page-label`."
  [file page-label label]
  (thc/fmt-object-id (:id file) (cthi/id page-label) (cthi/id label) "component"))

(t/deftest the-other-pages-wait-for-a-pause-of-the-user
  (t/async
    done
    (let [restore (without-drain)
          idle    activity/idle
          pause   (rx/subject)
          file    (setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          thumb   (component-thumbnail file :page2 :main-root)
          kept?   #(contains? (:thumbnails %) thumb)
          before  (atom nil)]
      (swap! store assoc :thumbnails {thumb {:uri "main"}})
      (set! activity/idle (fn ([_ _] pause) ([_ _ _] pause)))
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (set! activity/idle idle)
         (t/is (true? @before) "nothing is cleared while the user works")
         (t/is (not (kept? state)) "the thumbnail goes after the pause"))
       (fn [stream]
         ;; The user makes a pause 50 ms after the current page is set
         (->> stream
              (rx/filter (ptk/type? :app.main.data.workspace.undo/commit-undo-transaction))
              (rx/take 1)
              (rx/delay 50)
              (rx/subs! (fn [_]
                          (reset! before (kept? @store))
                          (rx/push! pause nil)
                          (rx/end! pause))))
         (->> stream
              (rx/filter #(and (ptk/type? ::dwt/clear-thumbnail %) (= thumb (deref %))))
              (rx/take 1)
              (rx/delay 10)))))))

(t/deftest lazy-changes-clear-the-stale-main-thumbnails
  (t/async
    done
    (let [restore (without-drain)
          file    (let [file          (setup-file)
                        first-page-id (cthf/current-page-id file)]
                    (-> file
                        (cthf/add-sample-page :page2)
                        (ctho/add-simple-component :component1 :main-root :main-child)
                        (ctht/apply-token-to-shape :main-root "radius" [:r1] [:r1] 50)
                        (cths/update-shape :main-root :token-values {:r1 50})
                        (ctho/add-simple-component :component2 :plain-root :plain-child)
                        (vary-meta assoc :current-page-id first-page-id)))
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          main    (component-thumbnail file :page2 :main-root)
          plain   (component-thumbnail file :page2 :plain-root)]
      (swap! store assoc :thumbnails {main {:uri "main"} plain {:uri "plain"}})
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :main-root :page-label :page2)))
               "the main waits to be set")
         (t/is (not (contains? (:thumbnails state) main)) "its thumbnail goes")
         (t/is (contains? (:thumbnails state) plain) "a main without the token keeps it"))))))

(t/deftest lazy-changes-clear-the-stale-frame-thumbnails
  (t/async
    done
    (let [restore (without-drain)
          file    (let [file          (setup-file)
                        first-page-id (cthf/current-page-id file)]
                    (-> file
                        (cthf/add-sample-page :page2)
                        (ctho/add-frame :other)
                        (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 50)
                        (cths/update-shape :other :token-values {:r1 50})
                        (ctho/add-frame :plain)
                        (vary-meta assoc :current-page-id first-page-id)))
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          thumb   #(thc/fmt-object-id (:id file) (cthi/id :page2) (cthi/id %) "frame")]
      (swap! store assoc :thumbnails {(thumb :other) {:uri "other"}
                                      (thumb :plain) {:uri "plain"}})
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))
               "the frame waits to be set")
         (t/is (not (contains? (:thumbnails state) (thumb :other))) "its thumbnail goes")
         (t/is (contains? (:thumbnails state) (thumb :plain)) "a frame without the token keeps it"))))))

(t/deftest the-drain-saves-the-change-and-each-page-at-once
  (t/async
    done
    (let [pause   dwtp/drain-delay-ms
          _       (set! dwtp/drain-delay-ms 1)
          restore #(set! dwtp/drain-delay-ms pause)
          stream  (rx/subject)
          events  (atom [])
          persist :app.main.data.persistence/force-persist
          drain   (#'dwtp/drain-pages {} {} [(uuid/next) (uuid/next)])]
      (rx/subs!
       (fn [event]
         (swap! events conj (if (= persist event) :persist :page))
         (when (ptk/type? ::dwtp/drain-scope event)
           ;; the page is set
           (rx/push! stream (ptk/data-event ::dwtp/page-done {:scope-id (deref event)}))))
       (fn [cause] (restore) (t/is (nil? cause)) (done))
       (fn []
         (restore)
         (t/is (= [:persist :page :persist :page :persist] @events)
               "the change before the drain, and each page right after it is set")
         (done))
       (ptk/watch drain {} stream)))))

(t/deftest the-drain-stops-when-the-tokens-change
  (t/async
    done
    (let [pause   dwtp/drain-delay-ms
          _       (set! dwtp/drain-delay-ms 1)
          restore #(set! dwtp/drain-delay-ms pause)
          stream  (rx/subject)
          events  (atom [])
          persist :app.main.data.persistence/force-persist
          drain   (#'dwtp/drain-pages {} {} [(uuid/next) (uuid/next)])]
      (rx/subs!
       (fn [event]
         (swap! events conj (if (= persist event) :persist :page))
         (when (ptk/type? ::dwtp/drain-scope event)
           (rx/push! stream (ptk/data-event ::dwtp/page-done {:scope-id (deref event)}))
           ;; an undo of the change, once the first page is set
           (rx/push! stream (ptk/data-event ::dch/commit {:redo-changes [{:type :set-token}]}))))
       (fn [cause] (restore) (t/is (nil? cause)) (done))
       (fn []
         (restore)
         (t/is (= [:persist :page :persist] @events)
               "the second page is left for the editors and readers")
         (done))
       (ptk/watch drain {} stream)))))

(t/deftest nested-mains-lose-their-thumbnail
  (let [[container main rect other] (repeatedly 4 uuid/next)
        objects {uuid/zero {:id uuid/zero :parent-id uuid/zero :type :frame}
                 container {:id container :parent-id uuid/zero :type :frame}
                 main      {:id main :parent-id container :type :frame :main-instance true}
                 rect      {:id rect :parent-id main :type :rect}
                 other     {:id other :parent-id container :type :rect}}]
    (t/is (= #{main} (dwtp/main-instance-ids objects [rect])) "the main that holds it, inside a container")
    (t/is (= #{main} (dwtp/main-instance-ids objects [main])))
    (t/is (empty? (dwtp/main-instance-ids objects [other])) "no main holds it")))

;; --- Main components with out of date values

(defn- sample-file-with-radius
  "File `label` whose active token `radius` is `value`."
  [label value & {:as params}]
  (ctht/sample-file-with-tokens
   (assoc params
          :file-id label
          :lib-fn #(-> %
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                       (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                              :name "theme"
                                                              :sets #{"set"}))
                       (ctob/add-token (cthi/id :set)
                                       (ctob/make-token :name "radius"
                                                        :type :border-radius
                                                        :value value)))
          :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))))

(defn- with-radius
  "Apply the token `radius` to the radius `r1` of `label`, set to `value`."
  [file label value]
  (-> file
      (ctht/apply-token-to-shape label "radius" [:r1] [:r1] value)
      (cths/update-shape label :token-values {:r1 value})))

(defn- copy-values
  "The `r1` values that the commits of `commits` write in the shape `id`."
  [commits id]
  (into []
        (comp (mapcat :redo-changes)
              (filter #(= id (:id %)))
              (mapcat :operations)
              (keep #(get-in % [:values :r1])))
        commits))

(defn- run-correction
  "Run `events` with the correction of `file` on, and call `check` when the
  correction is done with the page twice: when it opens and after `events`."
  [store done file events check]
  (tohs/run-store
   store done (into [(dwtc/initialize (:id file))
                     (dwtc/check-page {:opening? true})]
                    events)
   check
   (fn [stream]
     (->> stream
          (rx/filter (ptk/type? ::dwtc/tokens-ready))
          (rx/take 2)
          (rx/last)))))

(t/deftest a-copy-synced-from-an-out-of-date-library-main-is-set-again
  (t/async
    done
    (let [;; the main holds the value of a previous version of the token
          library  (-> (sample-file-with-radius :library 50 :is-shared true)
                       (ctho/add-simple-component :component1 :main-root :main-child)
                       (with-radius :main-root 10))
          consumer (-> (sample-file-with-radius :consumer 50)
                       (cthc/instantiate-component :component1 :copy-root :library library)
                       (with-radius :copy-root 50))
          store    (ths/setup-store consumer {:libraries [library]})
          commits  (watch-commits store)
          copy-id  (cthi/id :copy-root)]
      (run-correction
       store done consumer [(dwl/sync-file (:id consumer) (:id library))]
       (fn [state]
         (let [copy (cths/get-shape (ths/get-file-from-state state) :copy-root)]
           (t/is (= [10 50] (copy-values @commits copy-id))
                 "the sync brings the value of the main, the correction sets it again")
           (t/is (= 50 (:r1 copy)))
           (t/is (= {:r1 50} (:token-values copy)))))))))

(t/deftest a-copy-reset-from-an-out-of-date-main-is-set-again
  (t/async
    done
    (let [file    (sample-file-with-radius :file1 50)
          page1   (cthf/current-page-id file)
          ;; the main, on another page, holds a previous value of the token
          file    (-> file
                      (cthf/add-sample-page :page2)
                      (ctho/add-simple-component :component1 :main-root :main-child)
                      (with-radius :main-root 10)
                      (vary-meta assoc :current-page-id page1)
                      (cthc/instantiate-component :component1 :copy-root)
                      (with-radius :copy-root 50))
          store   (ths/setup-store file)
          commits (watch-commits store)
          copy-id (cthi/id :copy-root)]
      (run-correction
       store done file [(dwl/reset-component copy-id)]
       (fn [state]
         (let [copy (cths/get-shape (ths/get-file-from-state state) :copy-root)]
           (t/is (= [10 50] (copy-values @commits copy-id))
                 "the reset brings the value of the main, the correction sets it again")
           (t/is (= 50 (:r1 copy)))
           (t/is (= {:r1 50} (:token-values copy)))))))))
