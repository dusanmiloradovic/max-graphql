(ns maximoplus.graphql.components
  (:require [maximoplus.basecontrols :as b :refer [UI Field Row Table ControlData Foundation Dialog Picker Workflow GL]]
            [maximoplus.core :as c]
            [maximoplus.utils :as u]
            [cljs.core.async :as a :refer [<! timeout]])
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on react-call with-react-props react-prop react-update-field react-call-control react-loop-fields loop-arr]]
                   [cljs.core.async.macros :refer [go-loop]])
  )

(def pending-subscription-events
  (atom []));;All the events from SSE will end up here. If there are any active subscriptiions, the core.async loop will


(def pending-subscribers
  (atom {}));;for every container there will be the list of subscriber functions(probably no need for closures) that will process the subscription events and send it to apollo server

(go-loop []
  (<! (timeout 100))
  (if-not
      (empty? @pending-subscribers)
    (u/debug "subscriberes processing")
    (reset! pending-subscription-events {})
    )
  (recur))

;;this will hold the implementions of the core library components. Containers are "final", and this is what will be used to do the queries and mutations (AppContainers and CommandContainers). However, for subscriptions we need to adapt the visual componets, because they react to the messages from the server. Also, the routeWF, changeStatus, getValueList will be easier implemented with the Visual Components then with the containers
(defn not-used;;assure basecomponents don't call the method, not used in reactive. After the extensive testing, all methods having the call to this can be deleted
  []
  (throw (js/Error. "should not be called")))

;;/bacic unit of information, all the data handling will be done on the List level

;;most probably we will define in advance all the possible subscriptions possible in Maximo. User will need to define the return type (if available for the subscriptions (along with the fields required), so we now how to construct the data. Right now, I will just create one function to send the event, and later I will plug-in the subscription system



(defn send-subscription-event
  [container event-name event-value]
  ;;this function will be called from all the methods that can send the data to be streamed
  ;;it will find is there any subscriptions for that type of event
  ;;  (u/debug js/console "Subscription event for " (c/get-id container) " and " event-name " and " event-value)
  ;;  (u/debug (c/get-id container) " " event-name " subscription event sent")
  (swap! pending-subscription-events conj {:container (c/get-id container) :event-name event-name :event-value event-value})
  )

(def-comp GridRow [container columns mxrow disprow] b/GridRow
  (^override fn* [] (this-as this (googbase this container columns mxrow disprow)))
  UI
  (^override draw-row [this]
   (b/mark-as-displayed this);;this will allow the listener to attach(here listener is just state)
   );;rendering done in react
  (^override draw-field [this]);rendering done in react
  (^override draw-fields [this]);first version of react will give us just the list, when we are ready to implement the grid, we will call this methid and make the listrow component which inherits this. we don't 
  (^override add-rendered-child [this rendered-child child]);rendering composition should be done in React
  Row
  (^override set-row-flags
   [this colfglags]
   (not-used));in the fist version the rows will be read only , so this is not imporant
  (^override highlight-selected-row
   [this]
   (not-used))
  (^override unhighlight-selected-row
   [this]
   (not-used))
  (^override listen-row
   [this rowSelectedActionF])
  (^override get-disp-row
   [this]
   (not-used))
  (^override set-disprow! [this dr]
   (not-used))
  (^override set-row-value
   [this column value]
   (not-used)
   )
  (^override set-row-values
   [this colvals]
   (not-used)
   )
  (add-default-lookups [this columns])
  (^override set-field-flag
   [this field flag]
   (not-used))
  )

;;list holds the set of informations

(def-comp Grid [container columns norows] b/Grid 
  (^override fn* []
   (this-as this (googbase this container columns norows)))
  Table
  (^override main-grid-frame [this])
  (^override grid-toolbar [this])
  (^override get-qbe-row [this])
  (^override get-label-row [this])
  (^override qbe-row [this])
  (^override header-row [this])
  (^override get-row-control
   [this mxrow disprow]
   (GridRow. container columns mxrow disprow))
  (^override set-grid-row-values
   [this row values]
   (send-subscription-event container "data-row-update" {:row row :values values}))
  (^override set-grid-row-value
   [this row column value]
   (send-subscription-event container "data-update" {:row row :column column :data value}))
  (^override set-grid-row-flags
   [this row flags]
   (send-subscription-event container "flags-row-update" {:row row :flags flags}))
  (^override update-paginator [this fromRow toRow numRows]
   (send-subscription-event container "update-table-meta" {:meta "paginator" :value {:from fromRow :to toRow :numrows numRows}}))
  (^override highlight-grid-row
   [this row]
   (send-subscription-event container "update-row-meta" {:row row :meta "highlighted" :value true}))
  (^override unhighlight-grid-row
   [this row]
   (send-subscription-event container "update-row-meta" {:row row :meta "highlighted" :value false}))
  Picker
  (^override pick-row
   [this row]
   (send-subscription-event container "update-row-meta" {:row row :meta "highlighted" :value true}))
  (^override unpick-row
   [this row]
   (send-subscription-event container "update-row-meta" {:row row :meta "highlighted" :value false}))
  Foundation
  (^override dispose-child
   [this row])
  UI
  (^override render-row
   [this row])
  (^override render-row-before
   [this row existing-row])
  )
