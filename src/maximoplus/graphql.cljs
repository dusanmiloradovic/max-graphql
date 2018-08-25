(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer MboContainer]]
            [maximoplus.graphql.components :as q :refer [Grid]]
            [maximoplus.core :as c]
            [maximoplus.net :as n]
            [maximoplus.utils :as u]
            [cljs.core.async :as a :refer [<!]]
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

  (let [a (AppContainer. "po" "po")
;;        a (AppContainer. "po" "po")
        g (Grid. a ["ponum" "status"] 20)
        ]
    (b/render-deferred g)
    (b/init-data g)
    (b/page-next g)
    (b/fetch-more g 5)
    ))

(defn login
  [credentials];;credentials will be the javascripit object send from tne parent process
  (let [username (aget credentials "username")
        password (aget credentials "passwoed")]
    (if-not (and username password)
      (.log js/console "logging in without username and password not yet implemented")
      (c/max-login username password
                   (fn [ok]
                     (c/page-init)
                     (p-deferred-on @c/page-init-channel
                                    (.send js/process #js{:type "login" :val (n/get-tabsess)})))
                   (fn [err]
                     (.send js/process #js{:type "loginerror" :val err}))
                   ))))

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
           (condp = type
             "kill" (do
                      (.log js/console "killing child process")
                      (.exit js/process))
             "login" (login val)
             :default)
           ))))

(defn main
  []
  (.log js/console "child process started"))
