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
  [[res _ _] wf-action-set]
  (println res))

;;when processing the callback of the workflow action, we will get (refer to the basecontriols workflow command container which we don;t use here)
;;the warnnings, title, information has the workflow been finished, or in the case it is interaction node, the interaction data( which app, tab, etc)
;;in case user action is required I will send the type (INPUTWF, COMPLETEWF) and the handle to graphql

(defn process-reassign-result
  [[res _ _] wf-action-set]
  (println res))



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
    (prom-then->
     (prom-command! c/set-value action-set "actionid" action-id )
     (prom-command! c/set-value action-set "memo" memo)
     (prom-command! c/choose-wf-action (c/get-id app-container) action-set);;director and app container have the same id
     (fn [res]
       (process-wf-result res action-set)))
    (throw  (js/Error. "Invalid action set id"))))

;;to make the things consistent, reassigning will be two step process - first initiate it, get the list of values in  the reassign mbo, and the next function is to actually reassing. If I do it in one function like below, there will be no list of values (like below)
;;(defn reassign-wf-old
;;  [app-container personid memo send-mail]
;;  (let [reassign-set-id (b/get-next-unique-id)]
;;    (prom->
;;     (prom-command! c/reassign-wf reassign-set-id (c/get-id app-container))
;;     (prom-command! c/set-value reassing-set-id "personid" personid)
;;     (prom-command! c/set-value reassing-set-id "memo" memo)
;;     (prom-command! c/execute-reassign-wf reassign-set-id (c/get-id app-container) ))))

(defn init-reassign-wf
  [app-container]
  (let [reassign-set-id (b/get-next-unique-id)]
    (prom-then->
     (prom-command! c/reassign-wf reassign-set-id (c/get-id app-container))
     (fn [res]
       (c/toggle-state app-container :wf-reassign-set reassign-set-id)
       (proces-reassign-result res reassign-set-id)))))

(defn process-reassign-wf
  [app-container personid memo send-mail]
  (if-let [reassign-set-id (c/get-state app-container :wf-reassign-set)]
    (prom-then->
     (prom-command! c/set-value reassing-set-id "personid" personid)
     (prom-command! c/set-value reassing-set-id "memo" memo)
     (prom-command! c/set-value reassing-set-id "send-main" send-mail)
     (prom-command! c/execute-reassign-wf reassign-set-id (c/get-id app-container) ))
    (throw  (js/Error. "Invalid reassign set id"))))

