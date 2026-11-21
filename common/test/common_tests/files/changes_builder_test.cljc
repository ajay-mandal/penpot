;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.files.changes-builder-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [clojure.test :as t]))

(defn- make-shape
  [id type parent-id shapes x y size]
  (-> (cts/setup-shape {:id id :type type :x x :y y :width size :height size})
      (assoc :parent-id parent-id
             :frame-id uuid/zero
             :shapes shapes)))

(t/deftest resize-parents-resizes-inner-parents-first
  (let [outer-id (uuid/next)
        inner-id (uuid/next)
        moved-id (uuid/next)
        other-id (uuid/next)

        ;; :moved left both groups, which still hold their old size
        objects  {uuid/zero (make-shape uuid/zero :frame nil [outer-id] 0 0 100)
                  outer-id  (make-shape outer-id :group uuid/zero [inner-id other-id] 0 0 30)
                  inner-id  (make-shape inner-id :group outer-id [moved-id] 0 0 10)
                  moved-id  (make-shape moved-id :rect inner-id [] 100 100 10)
                  other-id  (make-shape other-id :rect outer-id [] 20 20 10)}

        changes  (-> (pcb/empty-changes nil uuid/zero)
                     (pcb/with-objects objects)
                     (pcb/resize-parents [other-id moved-id]))

        objects' (pcb/lookup-objects changes)]

    (t/is (= 110.0 (double (get-in objects' [inner-id :selrect :x2]))))
    ;; The outer group holds the inner group once resized
    (t/is (= 110.0 (double (get-in objects' [outer-id :selrect :x2]))))))

(t/deftest resize-parents-keeps-moved-booleans
  (let [bool-id  (uuid/next)
        child-id (uuid/next)

        ;; The child left the boolean, which still holds its old content
        objects  {uuid/zero (make-shape uuid/zero :frame nil [bool-id] 0 0 100)
                  bool-id   (-> (make-shape bool-id :bool uuid/zero [child-id] 0 0 10)
                                (assoc :bool-type :union))
                  child-id  (make-shape child-id :rect bool-id [] 50 50 10)}

        resized  (fn [moved-bool-ids]
                   (-> (pcb/empty-changes nil uuid/zero)
                       (pcb/with-objects objects)
                       (pcb/resize-parents [child-id] moved-bool-ids)
                       (pcb/lookup-objects)
                       (get-in [bool-id :selrect :x1])
                       (double)))]

    (t/is (= 50.0 (resized nil)))
    (t/is (= 0.0 (resized #{bool-id})))))

(t/deftest update-shapes-without-touched-keeps-the-same-local-objects
  (let [rect-id (uuid/next)
        objects {uuid/zero (make-shape uuid/zero :frame nil [rect-id] 0 0 100)
                 rect-id   (-> (make-shape rect-id :rect uuid/zero [] 10 10 10)
                               (assoc :blocked true))}

        update  #(-> %
                     (assoc :x 40 :name "moved")
                     (dissoc :blocked))

        updated (fn [ignore-touched]
                  (-> (pcb/empty-changes nil uuid/zero)
                      (pcb/with-objects objects)
                      (pcb/update-shapes [rect-id] update {:ignore-touched ignore-touched})
                      (pcb/lookup-objects)
                      (get rect-id)))]

    (t/is (= 40 (:x (updated true))))
    (t/is (= (updated false) (updated true)))))
