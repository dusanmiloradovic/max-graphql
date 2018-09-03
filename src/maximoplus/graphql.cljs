(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer MboContainer]]
            [maximoplus.graphql.components :as q :refer [Grid]]
            [maximoplus.core :as c]
            [maximoplus.net :as n]
            [maximoplus.utils :as u]
            [cljs.core.async :as a :refer [<!]]
            [maximoplus.net.node :refer [Node]]
            [maximoplus.graphql.processing :as pr]
            [cognitect.transit :as transit]
)
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on custom-this kc!]]
                   [cljs.core.async.macros :refer [go]]))

(n/set-net-type (Node.))

(n/set-server-root "http://localhost:8080")

(c/setGlobalFunction "globalDisplayWaitCursor" (fn [_]))

(c/setGlobalFunction "globalRemoveWaitCursor" (fn [_]))

(def transit-writer (transit/writer :json))

(defn transit-write [x]
  ;use transit to create json from clojurescript object
  (transit/write transit-writer x)
  )

(defn send-process
  [message]
  ;;the script should communicate with the parent script through process.send
  ;;this is undefined however, if we run the standalone script for development purposes
  (if (aget js/process "send")
    (.send js/process message)
    (do
      (.log js/console "fake message sending")
      (.log js/console message))))

(.on js/process "uncaughtException"
     (fn [err] (u/debug "!" err)))


;;(defn test-app
;;  []
;;
;;  (let [a (AppContainer. "po" "po")
;;;;        a (AppContainer. "po" "po")
;;        g (Grid. a ["ponum" "status"] 20)
;;        ]
;;    (b/render-deferred g)
;;    (b/init-data g)
;;    (b/page-next g)
;;    (b/fetch-more g 5)
;;    ))

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
                                      (send-process #js{:type "loggedin" :val (n/get-tabsess)})
                                      (.log js/console "logged in process sent the message")
                                      ))
                     (fn [err]
                       (send-process #js{:type "loginerror" :val err}))
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
(defn process-fetch
  [uid args]
  (let [app-name (aget args "app")
        object-name (aget args "object-name")
        columns (aget args "columns")
        start-row (aget args "start-row")
        num-rows (aget args "num-rows")
        handle (aget args "handle") ;;handle is container id, useful for the paging
        ]
    (let [cont-id (if handle handle
                      (pr/register-container app-name object-name))
          fetch-prom (pr/fetch-data cont-id columns start-row num-rows)]
      (.then fetch-prom
             (fn [data]
               (send-process #js{:type "command"
                                 :uid uid
                                 :val (transit-write
                                       (conj data cont-id))}))))))

(defn process-command
  [uid val]
  (let [command (aget val "command")
        args (aget val "args")]
    (condp = command
      "fetch" (process-fetch uid args)
      :default)))

(.on js/process "message"
     (fn [m]
       (when-let [type (aget m "type")]
         (let [val (aget m "val")
               uid (aget m "uid")]
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
             "command" (process-command uid val) 
             :default)))))

(defn main
  []
  (.log js/console "child process started"))
