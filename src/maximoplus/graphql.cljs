(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer MboContainer]]
            [maximoplus.graphql.components :as q :refer [Grid]]
            [maximoplus.core :as c]
            [maximoplus.net :as n]
            [maximoplus.utils :as u]
            [maximoplus.net.node :refer [Node]]
)
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on custom-this kc!]]
                   [cljs.core.async.macros :refer [go]]))

(n/set-net-type (Node.))

(n/set-server-root "http://localhost:8080")

(c/setGlobalFunction "globalDisplayWaitCursor" (fn [_]))

(c/setGlobalFunction "globalRemoveWaitCursor" (fn [_]))

(.on js/process "uncaughtException"
     (fn [err] (u/debug "!" err)))

(def buu (atom nil))

(defn test-app
  []

  (let [a (AppContainer. "po" "po")
;;        a (AppContainer. "po" "po")
        g (Grid. a ["ponum" "status"] 20)
        ]
    (b/render-deferred g)
    (b/init-data g)
    (b/page-next g)
    (b/fetch-more g 5)
;;    (reset! buu a)
    ))

(c/setGlobalFunction "global_login_function"
                     (fn [err]
                       (u/debug "logging in")
                       (c/max-login "maxadmin" "maxadmin"
                                    (fn [ok]
                                      (.log js/console "logged in")
                                      (test-app))
                                    (fn [err]
                                      (.log js/console "Not logged in error")
                                      (.loj js/console err)))))



(.on js/process "message"
     (fn [m]
       (when-let [type (aget m "type")]
         (let [val (aget m "val")]
           (when (= type "kill")
             (.log js/console "killing child process")
             (.exit js/process))
           ))))

(defn main
  []
  (.log js/console "child process started")
  (test-app)
  )
