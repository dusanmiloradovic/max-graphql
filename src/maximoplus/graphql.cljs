(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer MboContainer]]
            [maximoplus.graphql.components :as q :refer [Grid]]
            [maximoplus.core :as c]
            [maximoplus.net :as n]
            [maximoplus.utils :as u]
            [cljs.core.async :as a :refer [<!]]
            [clojure.string :as s :refer [replace]]
            [maximoplus.net.node :refer [Node]]
            [maximoplus.graphql.processing :as pr]
            [cognitect.transit :as transit]
            ["os-locale" :refer [sync]]
)
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on custom-this kc!]]
                   [cljs.core.async.macros :refer [go]]))

(n/set-net-type (Node.))

(n/set-server-root "http://localhost:8080")

(c/setGlobalFunction "globalDisplayWaitCursor" (fn [_]))

(c/setGlobalFunction "globalRemoveWaitCursor" (fn [_]))


(aset js/global "alert" (fn [text] (println "!!!!!" text ))) ;;temporarily

(def transit-writer (transit/writer :json))

(defn transit-write [x]
  ;use transit to create json from clojurescript object
  (transit/write transit-writer x)
  )

(def os-locale (sync))

(def thousands-sep-symbol
  (let [smpl (.toLocaleString 1111)]
    (aget smpl 1)))

(def comma-symbol
  (if (= thousands-sep-symbol ".") "," "."))

(def thousands-sep-regex
  (js/RegExp. (str "/" thousands-sep-symbol "/g")))

(defn number-from-string
  [num]
  (replace num thousands-sep-symbol ""))

(defn send-process
  [message]
  ;;the script should communicate with the parent script through process.send
  ;;this is undefined however, if we run the standalone script for development purposes
  (.log js/console message)
  (if (aget js/process "send")
    (.send js/process message)
    (do
      (.log js/console "fake message sending")
      (.log js/console message)
      )))

(.on js/process "uncaughtException"
     (fn [err] (u/debug "!" err)))


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

(defn normalize-column
  [container-id column-name val]
  ;;transorms maximo data to graphql data, right now only floats are affected
  ;;no option to chose, decimal from maximo will be always float in GraphQL
  (if-let [column-meta (c/get-column-metadata container-id column-name)]
    (let [numeric? (:numeric column-meta)
          max-type (:maxType column-meta)
          decimal? (or
                    (= "AMOUNT" max-type)
                    (= "FLOAT" max-type)
                    (= "DECIMAL" max-type))
          integer? (or
                    (= "SMALLINT" max-type)
                    (= "BIGINT" max-type)
                    (= "INTEGER" max-type))]
      (if-not numeric?;;still no support for dates
        val
        (if decimal?
          (js/parseFloat (number-from-string val))
          (js/parseInt (number-from-string val)))))
    val))

(defn normalize-data-object
  [container-id val]
  (reduce-kv (fn [m k v]
               (assoc m k (normalize-column container-id k v))
               )
             {} val))

(defn normalize-data-bulk
  [container-id data]
  (map (fn [[rownum data flags]]
         [rownum (normalize-data-object container-id data) flags])
       data))

(defn get-container
  [args]
  (let [handle (aget args "handle")
        data (aget args "data")
        uniqueid (aget args "id")
        ]
    [(if (and handle (@pr/registered-containers handle)) handle
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
           (.log js/console "ne radi " (.-stack  err))
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
      "rollback" (process-rollback uid)
      :default)))

(.on js/process "message"
     (fn [m]
       (when-let [type (aget m "type")]
         (let [val (aget m "val")
               uid (aget m "uid")]
        ;;   (.log js/console "processing pparent message")
;;           (.log js/console (str "type=" type))
  ;;         (.log js/console (str "value=" val))
    ;;       (.log js/console m)
      ;;     (.log js/console "++++++++++++++++++++++++")
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
