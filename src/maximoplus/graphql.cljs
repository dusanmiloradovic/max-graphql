(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer MboContainer]]
            [maximoplus.graphql.components :as q :refer [Grid]]
            [maximoplus.core :as c]
            [maximoplus.net :as n]
            [maximoplus.utils :as u]
            [cljs.core.async :as a :refer [<!]]
            [clojure.string :as s :refer [replace]]
            [maximoplus.net.node :refer [Node]]
            [maximoplus.graphql.processing :as pr :refer [normalize-data-object normalize-data-bulk]]
            [cognitect.transit :as transit]
            ["os-locale" :refer [sync]]
            )
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on custom-this kc!]]
                   [cljs.core.async.macros :refer [go]]
                   ))

(set! *warn-on-infer* true)

(n/set-net-type (Node.))

(def SERVER_ROOT
  (if-let [server-root (aget (aget js/process "env") "SERVER_ROOT")]
    server-root
    "http://localhost:8080" ;;development only
    ))

;;(println "server root = " SERVER_ROOT)

(n/set-server-root SERVER_ROOT) 

(c/setGlobalFunction "globalDisplayWaitCursor" (fn [_]))

(c/setGlobalFunction "globalRemoveWaitCursor" (fn [_]))


(aset js/global "alert" (fn [text] (println "!!!!!" text ))) ;;temporarily

(def transit-writer (transit/writer :json))

(defn transit-write [x]
                                        ;use transit to create json from clojurescript object
  (transit/write transit-writer x)
  )

(def os-locale (sync))


(defn send-process
  [message]
  ;;the script should communicate with the parent script through process.send
  ;;this is undefined however, if we run the standalone script for development purposes
  (if (aget js/process "send")
    (.send js/process message)
    (do
      (println "fake message sending")
      (println message)
      )))

(.on js/process "uncaughtException"
     (fn [err] (u/debug "!" err)))


(defn login
  [val] ;;credentials will be the javascripit object send from tne parent process
  (let [credentials (aget val "credentials")
    ;;    _ (u/debug credentials)
        username (aget credentials "username")
      ;;  _ (u/debug username)
        password (aget credentials "password")
        ;;_ (u/debug password)
        ]
    (if-not (and username password)
      (println "logging in without username and password not yet implemented")
      (do
  ;;      (println "logging in script")
        (c/max-login username password
                     (fn [ok]
;;                       (println "got ok response" ok)
                       (c/page-init)
                       (p-deferred-on @c/page-init-channel
                                      (send-process #js{:type "loggedin" :val (n/get-tabsess)})
          ;;                            (println "logged in process sent the message")
                                      ))
                     (fn [err]
                       (println "logging in error  " err)
                       (send-process #js{:type "loginerror" :val err}))
                     )))))

;;(c/setGlobalFunction "global_login_function"
;;                     (fn [err]
;;                       (u/debug "logging in")
;;                       (c/max-login "maxadmin" "maxadmin"
;;                                    (fn [ok]
;;                                      (println "logged in")
;;                                      (test-app))
;;                                    (fn [err]
;;                                      (println "Not logged in error")
;;                                      (.loj js/console err)))))
;;global login function should hot be required.



(defn get-container
  [args]
  (let [handle (aget args "handle")
        data (aget args "data")
        uniqueid (aget args "id")
        ]
    [(if handle handle
         (pr/register-container args))
     handle
     data
     uniqueid]))

(defn process-command-error
  [uid [[error-type error-text mx-error-group mx-error-code] _ _]]
  (println "processing command error")
  (let [error-text (if (or (= :js error-type)
                           (= :net error-type))
                     (.toString error-text)
                     error-text
                     )
        error-code (if (= :mx error-type)
                     (str mx-error-group " " mx-error-code)
                     (name error-type))]
    (send-process #js {:type "command"
                       :uid uid
                       :val (transit-write
                             {:error-code error-code
                              :error-text error-text})})))

(defn process-fetch
  [uid args]
  (let [start-row (aget args "start-row")
        num-rows (aget args "num-rows")
        handle (aget args "handle") ;;handle is container id, useful for the paging
        columns (aget args "columns")
        qbe (aget args "qbe")
        ]
    (let [cont-id (if (and handle (@pr/registered-containers handle)) handle
                      (pr/register-container args))
          fetch-prom (pr/fetch-data cont-id columns start-row num-rows (js->clj qbe))]
      (..
       fetch-prom
       (then
        (fn [data]
          (send-process #js{:type "command"
                            :uid uid
                            :val (transit-write
                                  (conj
                                   (normalize-data-bulk cont-id data)
                                   cont-id))})))
       (catch
           (fn [err]
             (process-command-error uid err)))))))

(defn process-add
  [uid args]
  (let [[cont-id handle data uniqueid] (get-container args)]
    (..
     (pr/add-data cont-id data)
     (then
      (fn [data]
        (send-process #js{:type "command"
                          :uid uid
                          :val 
                          (transit-write
                           (conj
                            (seq
                             [(normalize-data-object cont-id (second data))
                              (nth data 2)])
                            cont-id
                            ))})))
     (catch
         (fn [err]
           (process-command-error uid err))))))

(defn process-update
  [uid args]
  (let  [[cont-id handle data uniqueid] (get-container args)]
    (..
     (if handle
       (pr/update-data-with-handle cont-id uniqueid data)
       (pr/update-data-no-handle cont-id data))
     (then
      (fn [data]
        (send-process #js{:type "command"
                          :uid uid
                          :val
                          (transit-write
                           (conj
                            (seq
                             [(normalize-data-object cont-id (second data))
                              (nth data 2)])
                            cont-id
                            ))})))
     (catch
         (fn [err]
           (process-command-error uid err))))))

(defn process-delete
  [uid args]
  (let  [[cont-id handle data uniqueid] (get-container args)]
    (..
     (if handle
       (pr/delete-data-with-handle cont-id uniqueid)
       (pr/delete-data-no-handle cont-id))
     (then
      (fn [data]
        (send-process #js{:type "command"
                          :uid uid
                          :val (transit-write true)})))
     (catch
         (fn [err]
           (process-command-error uid err))))))

(defn process-metadata
  [uid args]
  ;;metadata will be the child field of the object, so the handle will always be defined becuase will
  (let [handle (aget args "handle");;same as parent-handle for rel containers
        columns (aget args "columns")
        metadata (pr/get-metadata handle columns)]
    (..
     metadata
     (then
      (fn [_metadata]
        (send-process #js {:type "command"
                           :uid uid
                           :val (transit-write _metadata)})))
     (catch
         (fn [err]
           (process-command-error uid err))))))

(defn process-save
  [uid]
  (..
   (pr/save-changed)
   (then
    (fn [_]
      (send-process #js{:type "command"
                        :uid uid
                        :val (transit-write true)})))
   (catch
       (fn [err]
         (process-command-error uid err)))))

(defn process-rollback
  [uid]
  (..
   (pr/rollback-changed)
   (then
    (fn [_]
      (send-process #js{:type "command"
                        :uid uid
                        :val (transit-write true)})))
   (catch
       (fn [err]
         (process-command-error uid err)))))

(defn process-execute
  [uid args]
  (let [container-id (aget args "handle")
        command (aget args "command")
        id (aget args "id")
        mbo? (aget args "mbo")]
    (..
     (pr/execute-command container-id id command mbo?)
     (then
      (fn [_]
        (send-process #js{:type "command"
                          :uid uid
                          :val (transit-write true)})))
     (catch
         (fn [err]
           (println "execute command error  " err)
           (process-command-error uid err))))))

(defn process-route-wf
  [uid args]
  (let [container-id (aget args "handle")
        process-name (aget args "processName")]
    (..
     (pr/do-route-wf container-id process-name)
     (then
      (fn [res]
        (send-process #js {:type "command"
                           :uid uid
                           :val (transit-write res)})))
     (catch
         (fn [err]
           (process-command-error uid err))))))

(defn process-choose-wf-action
  [uid args]
  (let [container-id (aget args "handle")
        action-id (aget args "actionid")
        memo (aget args "memo")]
    (..
     (pr/choose-wf-action container-id action-id memo)
     (then
      (fn [res]
        (send-process #js {:type "command"
                           :uid uid
                           :val (transit-write res)})))
     (catch
         (fn [err]
           (process-command-error uid err))))))

(defn process-command
  [uid val]
  (let [command (aget val "command")
        args (aget val "args")]
    (condp = command
      "fetch" (process-fetch uid args)
      "add" (process-add uid args)
      "update" (process-update uid args)
      "delete" (process-delete uid args)
      "metadata" (process-metadata uid args)
      "save" (process-save uid)
      "routeWF" (process-route-wf uid args)
      "chooseWFAction" (process-choose-wf-action uid args)
      "rollback" (process-rollback uid)
      "execute" (process-execute uid args)
      :default)))

(.on js/process "message"
     (fn [m]
       (when-let [type (aget m "type")]
         (let [val (aget m "val")
               uid (aget m "uid")]
           ;;   (println "processing pparent message")
           ;;           (println (str "type=" type))
           ;;         (println (str "value=" val))
           ;;       (println m)
           ;;     (println "++++++++++++++++++++++++")
           (condp = type
             "kill" (do
;;                      (println "killing child process")
                      (.exit js/process))
             "login" (login val)
             "command" (process-command uid val) 
             :default)))))

(defn main
  []
  ;;(println "child process started")
  )
