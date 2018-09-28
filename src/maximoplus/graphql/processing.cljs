(ns maximoplus.graphql.processing
  (:require [maximoplus.basecontrols :as b :refer [MboContainer AppContainer RelContainer ListContainer UniqueMboAppContainer UniqueMboContainer]]
            [maximoplus.core :as c :refer [get-id get-fetched-row-data get-column-metadata]]
            [maximoplus.promises :as p])
  (:require-macros [maximoplus.graphql.processing :refer [prom-> prom-then->]]))

(def registered-containers
  (atom {}))

(def registered-controls
  (atom {}))

;;rigth now for fetching the data, containers are enough. If I need anything more I will use controls (probably required for subscriptions)

(def registered-qbe
  (atom {}))

(def data-changed-containers (atom []))
;;only the top level containers, where the saving should be done

(defn add-to-data-change-old
  [container]
  (println "calling add-to-data-change")
  (let [main-cont
        (loop [cnt container]
          (println "loop cid" (c/get-id cnt))
          (let [prt (b/get-parent cnt)]
            (if-not prt
              cnt
              (recur prt))))
        cont-id (c/get-id main-cont)]
    (when-not
        (contains? @data-changed-containers cont-id)
      (swap! data-changed-containers conj cont-id))))

(defn add-to-data-change
  [container]
  ;;since rel containers are got using the uniquembocontainers, we can save idenpendetly
  (let [cont-id (c/get-id container)]
    (when-not
        (contains? @data-changed-containers cont-id)
      (swap! data-changed-containers conj cont-id))))


(defn data-changed?
  [container-id]
  (contains? @data-changed-containers container-id))
;;this will be implemented later with mutations
;;if the qbe has changed, and the data was changed, throw an error

;;with the idea of not using the controls, I will have to keep the track of the qbes. if the qbe posted with the query is the same as the one registered, I will do nothing. If the qbe posted is different, and the data was changed wihtout commit or rollback, the exception will be thrown

;;too complex, and throws the maximum stack size error. For the time being, just use the uniquembocontainer for the parent and take the relationship to it
(defn register-rel-container-old
  [parent-handle parent-id rel-name]
  (let [parent-cont (@registered-containers parent-handle)]
    (b/move-to-uniqueid parent-cont parent-id nil nil)
    ;;if we do just fetch on parent without move, the cursor will stay on the first record
    (let [cont (RelContainer.  parent-cont rel-name)]
      (swap! registered-containers assoc (get-id cont) cont)
      cont)))

(defn register-rel-container
  [parent-id parent-object-name rel-name ]
  ;;TODO now i will create the uniquembocotnaiers for each row. That might complicate things during
  ;;the save. Another option will be the new type of container - like SingleMboContainer, but it will not be reset when the row changes, and it accepts the row number in the constructor
  (let [u (UniqueMboContainer. parent-object-name parent-id)
        r (RelContainer. u rel-name)]
    (swap! registered-containers assoc (get-id r) r)
    r))

(defn register-list-container
  [parent-id parent-object-name column-name]
  (let [u (UniqueMboContainer. parent-object-name parent-id)
        r (ListContainer. u column-name)]
    (swap! registered-containers assoc (get-id r) r)
    r))

;;TODO both for all the containers, let the user chooses the type and attributes from Maxiom when building a schema

(defn register-container
  [args]
  (let [relationship (aget args "relationship")
        list-column (aget args "list-column")
        parent-handle (aget args "parent-handle")
        parent-object (aget args "parent-object")
        parent-id (aget args "parent-id")
        object-name (aget args "object-name")
        app-name (aget args "app")
        qbe (aget args "qbe")
        uniqueid (if qbe
                   (get qbe "id")
                   (aget args "id"))]
    (let [cont
          (if list-column
            (register-list-container parent-id parent-object list-column)
            (if relationship (register-rel-container  parent-id parent-object relationship)
                (if uniqueid
                  (if app-name
                    (UniqueMboAppContainer. object-name app-name uniqueid)
                    (UniqueMboContainer. object-name uniqueid))
                  (AppContainer. object-name app-name))))]
      (swap! registered-containers assoc (get-id cont) cont )
      (get-id cont))))



(defn set-qbe
  [cont qbe]
  (swap! registered-qbe assoc (c/get-id cont) qbe)
  (prom->
   (.all js/Promise
         (clj->js
          (map (fn [[k v]]
                 (b/set-qbe cont k v nil nil))
               qbe)))
   (b/reset cont nil nil)))

(defn fetch-with-uniqueid
  [container-id uniqueid]
  ;;if there is uniquid, I will fetch one record only
  (let [cont (@registered-containers container-id)]
    (prom-then->
     (b/move-to-uniqueid cont (js/parseInt uniqueid) nil nil)
     (b/fetch-current cont nil nil)
     (fn [[data _ _]]
       [(get-fetched-row-data [0 data])]))))

(defn fetch-data
  [container-id columns start-row num-rows qbe]
  ;; all the graphql error handling needs to be done inline here, so I need promise, but i think capturing will be done in the outermost loop
  (if-let [uniqueid (get qbe "id")]
    (fetch-with-uniqueid container-id uniqueid)
    (let [cont (@registered-containers container-id)]
      (..
       (b/register-columns cont columns nil nil)
       (then
        (fn [_]
          (if (and qbe (not= qbe (@registered-qbe container-id)))
            (if (data-changed? container-id)
              ;;            (throw (js/Error. "Data changed, first rollback or save the data"))
              (.reject js/Promise [[:js (js/Error. "Data changed, first rollback or save the data")] 6 nil])
              (set-qbe cont qbe)))))
       (then
        (fn [_]
          (let [_start-row (if-not start-row 0 start-row)
                _num-rows (if-not num-rows 1 num-rows)]
            (b/fetch-data cont _start-row _num-rows nil nil))))
       (then
        (fn [[data _ _]]
          (filter some? (map get-fetched-row-data data))))))))

(defn add-data
  [container-id data]
  (js/Promise.
   (fn [resolve reject]
     (let [cont (@registered-containers container-id)
           columns (js-keys data)]
       (if-not cont
         (reject [[:js (js/Error. "Invalid handle")] 6 nil])
         (prom-then->
          (b/register-columns cont columns nil nil)
          (b/add-new-row cont nil nil)
          (.all js/Promise
                (clj->js
                 (map
                  (fn [c](b/set-value cont c (aget data c) nil nil))
                  columns)))
          (b/fetch-current cont nil nil)
          (fn [data]
            (add-to-data-change cont)
            (get-fetched-row-data [0 (first data)])))))))) 

(defn update-data-with-handle
  [container-id uniqueid data]
  (js/Promise.
   (fn [resolve reject]
     (let [cont (@registered-containers container-id)
           columns (js-keys data)
           _uniqueid (when uniqueid (js/parseInt uniqueid))]
       (if-not cont
         (reject [[:js (js/Error. "Invalid handle")] 6 nil])
         (resolve
          (prom-then->
           (b/register-columns cont columns nil nil)
           (if (not _uniqueid)
             (.resolve js/Promise nil);;when no id, update the current one
             (b/move-to-uniqueid cont _uniqueid nil nil))
           (.all js/Promise
                 (clj->js
                  (map
                   (fn [c](b/set-value cont c (aget data c) nil nil))
                   columns)))
           (b/fetch-current cont nil nil)
           (fn [data]
             (add-to-data-change cont)
             (get-fetched-row-data [0 (first data)])))))))))

;;the difference is that if there is no handle we already get the unique container, we need just to update the data
(defn update-data-no-handle
  [container-id  data]
  (js/Promise.
   (fn [resolve reject]
     (let [cont (@registered-containers container-id)
           columns (js-keys data)]
       (if-not cont
         (reject [[:js (js/Error. "Invalid handle")] 6 nil])
         (resolve
          (prom-then->
           (b/register-columns cont columns nil nil)
           (.all js/Promise
                 (clj->js
                  (map
                   (fn [c](b/set-value cont c (aget data c) nil nil))
                   columns)))
           (b/fetch-current cont nil nil)
           (fn [data]
             (add-to-data-change cont)
             (get-fetched-row-data [0 (first data)])))))))))

(defn delete-data-with-handle
  [container-id uniqueid]
  (js/Promise.
   (fn [resolve reject]
     (let [cont (@registered-containers container-id)
           _uniqueid (when uniqueid (js/parseInt uniqueid))]
       (if-not cont
         (reject [[:js (js/Error. "Invalid handle")] 6 nil])
         (resolve
          (prom-then->
           (if (not _uniqueid)
             (.resolve js/Promise nil);;when no id, update the current one
             (b/move-to-uniqueid cont _uniqueid nil nil))
           (b/del-row cont nil nil)
           (fn [_]
             (add-to-data-change cont)))))))))

;;if there is no handle that means that the container is unique
(defn delete-data-no-handle
  [container-id]
  (let [cont (@registered-containers container-id)]
    (if-not cont
      (.reject js/Promise [[:js (js/Error. "Invalid handle")] 6 nil])
      (prom-then->
       (b/del-row cont nil nil)
       (fn [_]
         (add-to-data-change cont))))))

(defn save-changed
  []
  (when-not (empty? @data-changed-containers)
    (let [cnt-id (first @data-changed-containers)
          cont (@registered-containers cnt-id)]
      (if-not cont
        (.reject js/Promise [[:js (js/Error. "invalid container for save")] 6 nil] )
        (..
         (b/save cont nil nil)
         (then
          (fn [_]
            (reset! data-changed-containers (vec (rest @data-changed-containers)))
            (save-changed))))))))

(defn rollback-changed
  []
  (when-not (empty? @data-changed-containers)
    (let [cnt-id (first @data-changed-containers)
          cont (@registered-containers cnt-id)]
      (if-not cont
        (.reject js/Promise [[:js (js/Error. "invalid container for rollback")] 6 nil] )
        (..
         (b/reset cont nil nil)
         (then
          (fn [_]
            (reset! data-changed-containers (vec (rest @data-changed-containers)))
            (rollback-changed))))))))

(defn execute-command
  [container-id uniqueid command mbo?]
  (let [cont (@registered-containers container-id)]
    (if-not cont
      (.reject js/Promise [[:js (js/Error. "Invalid handle")] 6 nil])
      (prom->
       (if uniqueid
         (c/move-to cont uniqueid nil nil)
         (.resolve js/Promise nil))
       (if mbo?
         (b/mbo-command cont command nil nil nil)
         (b/mboset-command cont command nil nil nil))))))

(defn get-data
  [app-name
   object-name
   parent-object-id
   qbe-object
   unique-id
   object-id
   columns
   relationship
   pagination]
  ;;if there is object-id (that is control-id), control has already been registered, we can ignore all the other paremeters except the pagination. In pagination it is specified do we go to the next page, or use the simple paginaton. If there is no paginatin - fetch the data again for the current page.
  ;;if the app-name and object-name are provided - this is the root element, we will register the app container

  ;;if there is parent-object-id, relationship argument is required - we create the RelContainer
  )


(defn get-metadata
  [container-id columns]
  (let [cont (@registered-containers container-id)]
    (if-not cont
      (.reject js/Promise [[:js (js/Error. "Invalid handle")] 6 nil])
      (.then
       (b/register-columns cont columns nil nil)
       (fn [_]
         (map (fn [col] (get-column-metadata container-id col))
              columns))))))
