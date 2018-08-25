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
  [val] ;;credentials will be the javascripit object send from tne parent process
  (let [credentials (aget val "credentials")
        _ (u/debug credentials)
        username (aget credentials "username")
        _ (u/debug username)
        password (aget credentials "password")
        _ (u/debug password)]
    (if-not (and username password)
      (.log js/console "logging in without username and password not yet implemented")
      (do
        (.log js/console "logging in script")
        (c/max-login username password
                     (fn [ok]
                       (c/page-init)
                       (p-deferred-on @c/page-init-channel
                                      (.send js/process #js{:type "loggedin" :val (n/get-tabsess)})
                                      (.log js/console "logged in process sent the message")
                                      ))
                     (fn [err]
                       (.send js/process #js{:type "loginerror" :val err}))
                     )))))

;;(c/setGlobalFunction "global_login_function"
;;                     (fn [err]
;;                       (u/debug "logging in")
;;                       (c/max-login "maxadmin" "maxadmin"
;;                                    (fn [ok]
;;                                      (.log js/console "logged in")
;;                                      (test-app))
;;                                    (fn [err]
;;                                      (.log js/console "Not logged in error")
;;                                      (.loj js/console err)))))
;;global login function should hot be required. 



(.on js/process "message"
     (fn [m]
       (when-let [type (aget m "type")]
         (let [val (aget m "val")]
           (.log js/console "processing pparent message")
           (.log js/console (str "type=" type))
           (.log js/console (str "value=" val))
           (.log js/console m)
           (.log js/console "++++++++++++++++++++++++")
           (condp = type
             "kill" (do
                      (.log js/console "killing child process")
                      (.exit js/process))
             "login" (login val)
             :default)))))

(defn main
  []
  (.log js/console "child process started"))
