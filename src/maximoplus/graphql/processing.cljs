(ns maximoplus.graphql.processing
  (:require [maximoplus.basecontrols :as b :refer [MboContainer AppContainer RelContainer ListContainer UniqueMboAppContainer UniqueMboContainer]]
            [maximoplus.core :as c :refer [get-id get-fetched-row-data get-column-metadata]]
            [maximoplus.promises :as p]))

(def registered-containers
  (atom {}))

(def registered-controls
  (atom {}))

;;rigth now for fetching the data, containers are enough. If I need anything more I will use controls (probably required for subscriptions)

(def registered-qbe
  (atom {}))

(defn data-changed?
  [container-id]
  false)
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
                   (aget qbe "id")
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
  (if-let [uniqueid (aget qbe "id")]
    (b/move-to-uniqueid cont uniqueid nil nil)
    (do
      (doseq [[k v] qbe]
        (b/set-qbe cont k v nil nil))
      (b/reset cont nil nil))))

(defn fetch-data
  [container-id columns start-row num-rows qbe]
  ;; all the graphql error handling needs to be done inline here, so I need promise, but i think capturing will be done in the outermost loop
  (let [cont (@registered-containers container-id)]
    (b/register-columns cont columns nil nil)
    (if (and qbe (not= qbe (@registered-qbe container-id)))
      (if (data-changed? container-id)
        (throw (js/Error. "Data changed, first rollback or save the data"))
        (set-qbe cont qbe)))
    (.then
     (let [has-uniqueid? (when qbe (aget qbe "uniqueid"))
           _start-row (if has-uniqueid? 0 start-row)
           _num-rows (if has-uniqueid? 1 num-rows)]
       (b/fetch-data cont _start-row _num-rows nil nil))
     (fn [[data _ _]]
       (filter some? (map get-fetched-row-data data))))))

(defn add-data
  [container-id data]
  (let [cont (@registered-containers container-id)
        columns (js-keys data)]
    (b/register-columns cont columns nil nil)
    (b/add-new-row cont nil nil)
    (doseq [c columns] ;;TODO later make a function on the server side to accept this at once and improve the performance
      (b/set-value cont c (aget data c) nil nil))
    (.then
     (b/fetch-current cont nil nil)
     (fn [data]
       (get-fetched-row-data [0 (first data)])))))

(defn update-data-with-handle
  [container-id uniqueid data]
  (let [cont (@registered-containers container-id)
        columns (js-keys data)]
    (b/register-columns cont columns nil nil)
    (b/move-to-uniqueid cont uniqueid nil nil)
    (doseq [c columns] ;;TODO later make a function on the server side to accept this at once and improve the performance
      (b/set-value cont c (aget data c) nil nil))
    (.then
     (b/fetch-current cont nil nil)
     (fn [data] (get-fetched-row-data [0 (first data)])))))

;;the difference is that if there is no handle we already get the unique container, we need just to update the data
(defn update-data-no-handle
  [container-id  data]
  (let [cont (@registered-containers container-id)
        columns (js-keys data)]
    (b/register-columns cont columns nil nil)
    (doseq [c columns] ;;TODO later make a function on the server side to accept this at once and improve the performance
      (b/set-value cont c (aget data c) nil nil))
    (.then
     (b/fetch-current cont nil nil)
     (fn [data] (get-fetched-row-data [0 (first data)])))))

(defn delete-data-with-handle
  [container-id uniqueid]
  (let [cont (@registered-containers container-id)]
    (b/move-to-uniqueid cont uniqueid nil nil)
    (b/del-row cont nil nil)))

;;if there is no handle that means that the container is unique
(defn delete-data-no-handle
  [container-id]
  (let [cont (@registered-containers container-id)]
    (b/del-row cont nil nil)))

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
    (.then
     (b/register-columns cont columns nil nil)
     (fn [_]
       (map (fn [col] (get-column-metadata container-id col))
            columns)))))
