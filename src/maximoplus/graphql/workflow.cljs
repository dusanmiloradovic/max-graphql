(ns maximoplus.graphql.workflow
  (:require [maximoplus.basecontrols :as b]
            [maximoplus.core :as c]
            [maximoplus.utils :as u]
            [cljs.core.async :as a :refer [<! timeout]]
            )
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on react-call with-react-props react-prop react-update-field react-call-control react-loop-fields loop-arr prom-command!]]
                   [cljs.core.async.macros :refer [go go-loop]]
                   [maximoplus.graphql.proocessing :refer [prom-> prom-then->]])
  )

;;the existing workflow component from core library will be very difficult to use in apollo server. Here I will just have the set of functions that will be called from the mutation resolvers

(def wf-directors (atom []))
;;here I will register each wf-director with the same name as application container. The logic is that you can run only one worklfow on mbo at the time.
;;once the wf is finished it will be removed from here

(defn process-wf-result
  [res wf-action-set])

(defn get-wf-director
  [app-container wf-process-name]
  (if-not [contains? @wf-directors (c/get-id app-container)]
    (.then 
     (kk! app-container c/register-wf-director wf-process-name (c/get-id app-container))
     (fn [_] (swap! wf-directors conj (c/get-id app-container))))
    (.resolve js/Promise (c/get-id app-container))))

;;we pass the wf-action-set id to rooute function, and it returns handle to the input or complete-wf mboset
(defn route-wf
  [app-container]
  (let [wf-action-set-id (b/get-next-unique-id)]
    (.then
     (kk! app-container c/route-wf (aget app-container "appname") (c/get-id app-container) wf-action-set-id)
     (fn [res]
       (c/toggle-state app-container :wf-action-set wf-action-set-id)
       (process-wf-result res wf-action-set-id) ))))

(defn choose-wf-action
  [app-container action-id memo]
  ;;there is no point in separating this in two functions, one that will execute this, and another that will update the InputWF data. We have to do it manuaully.
  (if-let [action-set (c/get-state app-contanier :wf-action-set)]
    (prom-then>
     (prom-command! c/set-value action-set "actionid" action-id )
     (prom-command! c/set-value action-set "memo" memo)
     (prom-command! c/choose-wf-action (c/get-id app-container) action-set);;director and app container have the same id
     (fn [res]
       (process-wf-result res action-set)))))

(defn reassign-wf
  [app-cntainer personid memo send-mail])
;;TODO this will be the same as choose-wf-action. !!Make sure there is a check what can be called at what status (can't reassing in inputwf was returned for example

