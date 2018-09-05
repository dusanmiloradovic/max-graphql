(ns maximoplus.graphql.processing
  (:require [maximoplus.basecontrols :as b :refer [MboContainer AppContainer RelContainer ListContainer]]
            [maximoplus.core :as c :refer [get-id get-fetched-row-data]]
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

(defn register-container
  [app-name object-name]
  (let [cont (AppContainer. object-name app-name)]
    (swap! registered-containers assoc (get-id cont) cont )
    (get-id cont)))

(defn register-rel-contanier
  [parent-id rel-name]
  (let [cont (RelContainer. (@registered-containers parent-id) rel-name)]
    (swap! registered-containers assoc (get-id cont) cont)))

(defn set-qbe
  [cont qbe]
  (swap! registered-qbe assoc (c/get-id cont) qbe)
  (doseq [[k v] qbe]
    (b/set-qbe cont k v nil nil)))

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
     (b/fetch-data cont start-row num-rows nil nil)
     (fn [[data _ _]]
       (map get-fetched-row-data data)))))

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
