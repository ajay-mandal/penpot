;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.ignore-tree-test
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.component :as ctk]
   [app.main.data.workspace.modifiers :as dwm]
   [cljs.test :as t :include-macros true]))

(t/use-fixtures :each {:before cthi/reset-idmap!})

(defn- setup
  []
  (let [file (-> (cthf/sample-file :file1)
                 (ctho/add-frame :board)
                 (ctho/add-nested-component-with-copy :component1 :main1-root :main1-child
                                                      :component2 :main2-root :nested-head
                                                      :copy2-root
                                                      :copy2-root-params {:parent-label :board}))]
    (:objects (cthf/current-page file))))

(defn- ignore-tree
  [objects moved-ids]
  (let [move (gmt/translate-matrix (gpt/point 10 20))]
    (dwm/calculate-ignore-tree-wasm (zipmap moved-ids (repeat move)) objects)))

(defn- copy-ids
  [objects]
  (->> (cfh/get-children-ids-with-self objects (cthi/id :copy2-root))
       (filter #(ctk/in-component-copy? (get objects %)))))

(t/deftest moving-a-board-keeps-its-copies-untouched
  (let [objects (setup)
        tree    (ignore-tree objects (cfh/get-children-ids-with-self objects (cthi/id :board)))]
    (t/is (seq (copy-ids objects)))
    (t/is (every? #(true? (get tree %)) (copy-ids objects)))))

(t/deftest moving-a-copy-keeps-its-children-untouched
  (let [objects (setup)
        tree    (ignore-tree objects (cfh/get-children-ids-with-self objects (cthi/id :copy2-root)))]
    (t/is (every? #(true? (get tree %)) (copy-ids objects)))))

(t/deftest moving-a-shape-inside-a-copy-touches-it
  (let [objects (setup)
        rect-id (last (copy-ids objects))
        tree    (ignore-tree objects [rect-id])]
    (t/is (false? (get tree rect-id)))))

(defn- objects
  [file]
  (:objects (cthf/current-page file)))

(t/deftest a-copy-moved-as-a-whole-is-not-touched
  (let [file     (ctho/add-simple-component-with-copy (cthf/sample-file :file1)
                                                      :component1 :main-root :main-child :copy-root)
        objects  (objects file)
        root-id  (cthi/id :copy-root)
        child-id (first (:shapes (get objects root-id)))
        move     (gmt/translate-matrix (gpt/point 10 0))
        tree     (dwm/calculate-ignore-tree-wasm {root-id move child-id move} objects)]
    (t/is (true? (get tree root-id)))
    (t/is (true? (get tree child-id)))))

(t/deftest a-copy-child-moved-alone-is-touched
  (let [file     (ctho/add-simple-component-with-copy (cthf/sample-file :file1)
                                                      :component1 :main-root :main-child :copy-root)
        objects  (objects file)
        child-id (first (:shapes (get objects (cthi/id :copy-root))))
        tree     (dwm/calculate-ignore-tree-wasm {child-id (gmt/translate-matrix (gpt/point 10 0))}
                                                 objects)]
    (t/is (false? (get tree child-id)))))

(t/deftest the-shapes-out-of-copies-are-left-out
  (let [file    (ctho/add-simple-component-with-copy (cthf/sample-file :file1)
                                                     :component1 :main-root :main-child :copy-root)
        objects (objects file)
        main-id (cthi/id :main-root)
        tree    (dwm/calculate-ignore-tree-wasm {main-id (gmt/translate-matrix (gpt/point 10 0))}
                                                objects)]
    (t/is (empty? tree))))

(t/deftest a-copy-in-a-main-is-compared-with-its-own-root
  ;; The main is resized and the copy inside it stays where it is: its
  ;; shapes keep their place in the copy, though not in the main.
  (let [file     (ctho/add-nested-component (cthf/sample-file :file1)
                                            :component1 :main1-root :main1-child
                                            :component2 :main2-root :nested-head)
        objects  (objects file)
        main-id  (cthi/id :main2-root)
        head-id  (cthi/id :nested-head)
        child-id (first (:shapes (get objects head-id)))
        tree     (dwm/calculate-ignore-tree-wasm {main-id  (gmt/scale-matrix (gpt/point 2 1))
                                                  head-id  (gmt/matrix)
                                                  child-id (gmt/matrix)}
                                                 objects)]
    (t/is (true? (get tree head-id)))
    (t/is (true? (get tree child-id)))))
