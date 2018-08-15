(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer Grid Section MboContainer]]
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

(defn test-app
  []
  (u/debug "1")
  (let [a (AppContainer. "po" "po")
        g (Grid. a ["ponum" "status"] 20)]
    (u/debug "2")
    (b/render-deferred g)
        (u/debug "3")
        (b/init-data g)
        (u/debug "4")
    (b/page-next g)
    (b/fetch-more g 5)))

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



(defn main
  []
  (.log js/console "bla")
  (test-app))



(defn ojsa
  [x y]
  (+ x y))

(defn blala
  [x]
  (- x 2))
