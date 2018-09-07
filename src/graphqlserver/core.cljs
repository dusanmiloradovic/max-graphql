(ns graphqlserver.core
  (:require
   ["apollo-server-express" :refer (ApolloServer gql AuthenticationError)]
   ["express" :as express]
   ["child_process" :refer [fork]]
   ["express-session" :as session]
   ["basic-auth" :as auth]
   ["uniqid" :as uniqid]
   ["graphql" :refer [buildSchema graphqlSync introspectionQuery]]
   [cljs-node-io.core :as io :refer [slurp spit]]
   [cljs.core.async :as a :refer [<! put! chan promise-chan]]
   [cognitect.transit :as transit])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def child-processes (atom {}))

(def pending-messages (atom {}));;we send the commadn to the child process. When we receive it back, the promise is resolved, apollo returns the data to the client, and we delete the message from the map

(def transit-reader (transit/reader :json))

(defn transit-read [x]
  (transit/read transit-reader x))

(def debug-process (atom nil))

(declare open-child-script)

(when (aget (.-env js/process) "DEBUG")
  (println "debbuging on the port 6554")
  (reset! debug-process (fork (str js/__dirname "/gscript.js") #js[] #js{:execArgv #js["--inspect=6554" ]}))
  (.log js/console @debug-process))
  

(defn get-schema-string
  []
  (slurp "schema/sample.graphql"))

(def books #js[#js{
                   :title "Harry Potter and the Chamber of Secrets"
                   :author "J.K. Rowling"
                   }
               #js{
                   :title "Jurassic Park"
                   :author "Michael Crichton"
                   }]
  )

(defn get-ast-tree
  []
  (let [schema (buildSchema (get-schema-string))]
    (.-data (graphqlSync schema introspectionQuery))))

(declare send-graphql-command)

(declare get-maximo-scalar-fields)

;;(def test-names {:app "po"
;;                 :object-name "POSTD"
;;                 :rel-name "POLINESTD"})

(def test-names {:app "po"
                 :object-name "PO"
                 :rel-name "POLINE"})

(defn process-fetch
  [res]
  (let [component-id (first res)
        _res (rest res)]
    (println "processing " _res)
    (clj->js
     (map
      (fn [[rownum data flaga]]
        (into {}
              (conj
               (map (fn [[k v]]
                      [(if (= k "_uniqueid") "id" (.toLowerCase k)) v]
                      )
                    data)
               ["_handle" component-id])))
      _res))))

(defn test-po-resolver
  [obj args context info]
  (let [from-row (aget args "fromRow")
        num-rows (aget args "numRows")
        handle (aget args "_handle")
        qbe (aget args "qbe")
        res-p (send-graphql-command 
               (aget context "pid")
               #js{:command "fetch"
                   :args #js{:app (:app test-names)
                             :object-name (:object-name test-names)
                             :columns (get-maximo-scalar-fields (:object-name test-names))
                             :start-row from-row
                             :num-rows num-rows
                             :handle handle
                             :qbe qbe
                             }}
               )]
    (.then res-p process-fetch)))

(def rel-temp-promises (atom {}))

;;this is too complicated, and throws maximum call size exceeded
;;I am making MVP, leave this just in case someone requests this functionality(all the lines to have the same handle)
(defn test-poline-resolver-old
  [obj args context info]
  (let [from-row (aget args "fromRow")
        num-rows (aget args "numRows")
        handle (aget args "_handle")
        parent-handle (aget obj "_handle")
        parent-id (aget obj "id")
        rel-name (:rel-name test-names)
;;        context-handle (@(aget context "rel-handles") {:parent-handle parent-handle :rel-name rel-name })
        pid (aget context "pid")
        qbe (aget args "qbe")
        _ (.log js.console (str "calling the poline resolver for parent id " parent-id ))
        command-object #js{:command "fetch"
                           :args #js{:relationship rel-name
                                     :columns (get-maximo-scalar-fields (:rel-name test-names))
                                     :parent-handle parent-handle 
                                     :parent-id (aget obj "id")
                                     :start-row from-row
                                     :num-rows num-rows
                                     :handle handle
                                     :qbe qbe
                                     }}
        res-p (if-let [ex-prom (@rel-temp-promises {:pid pid :parent-handle parent-handle :rel-name rel-name})]
                (let [new-prom (.then ex-prom
                                      (fn [_]
                                        (let [context-handle (@(aget context "rel-handles") {:parent-handle parent-handle :rel-name rel-name })]
                                          (println "sending command for parent-id " parent-id " and context handle " context-handle)
                                          (when-not (aget command-object "handle")
                                            (aset (aget command-object "args" ) "handle" context-handle))
                                          (send-graphql-command pid command-object))))]
                  (swap! rel-temp-promises assoc
                         {:pid pid :parent-handle parent-handle :rel-name rel-name}
                         new-prom)
                  new-prom)
                (let [_ (println "sending command for parent-id " parent-id)
                      rs (send-graphql-command pid command-object)
                      new-prm (.then rs
                                     (fn [rs]
                                       (swap! (aget context "rel-handles")
                                              assoc
                                              {:parent-handle parent-handle :rel-name rel-name}
                                              (first rs))
                                       rs
                                       ))]
                  (swap! rel-temp-promises assoc
                         {:pid pid :parent-handle parent-handle :rel-name rel-name}
                         new-prm)
                  new-prm))
        ]
    (.then res-p
           (fn [res]
             (println "got the response for the parent id " parent-id)
             (process-fetch res)))))

;;here every line will have a different handle. Think about the save, how it should work (this will go to the relationship to the parent uniquembocontainer)
(defn test-poline-resolver
  [obj args context info]
  (let [from-row (aget args "fromRow")
        num-rows (aget args "numRows")
        handle (aget args "_handle")
        parent-handle (aget obj "_handle")
        parent-id (aget obj "id")
        rel-name (:rel-name test-names)
;;        context-handle (@(aget context "rel-handles") {:parent-handle parent-handle :rel-name rel-name })
        pid (aget context "pid")
        qbe (aget args "qbe")
        _ (.log js.console (str "calling the poline resolver for parent id " parent-id ))
        command-object #js{:command "fetch"
                           :args #js{:relationship rel-name
                                     :columns (get-maximo-scalar-fields (:rel-name test-names))
                                     :parent-handle parent-handle 
                                     :parent-id (aget obj "id")
                                     :start-row from-row
                                     :num-rows num-rows
                                     :parent-object (:object-name test-names)
                                     :handle handle
                                     :qbe qbe
                                     }}
        res-p (send-graphql-command pid command-object) 
        ]
    (.then res-p prcess-fetch)))

(def resolvers #js{
                   :Query #js{
                              :books
                              ;;(fn [] (throw (AuthenticationError.)))
                              (fn [obj args context info]
                                books)
                              :book
                              (fn[obj args context info]
                                (aget books 0))
                              :po test-po-resolver
                              }
                   :POSTD #js{
                              :books
                              ;;(fn [] (throw (AuthenticationError.)))
                              (fn [obj args context info] 
                                books)
                              }
                   :PO #js{
                           :poline test-poline-resolver
                           }
                   })

(defn max-session-check-middleware
  [req res next]
  (if (and
       (.-session req )
       (not (.-t (.-session req))))
    (do
      (.status res 401)
      (.setHeader res "WWW-Authenticate" "Basic realm=\"User Visible Realm\"")
      (.send res "Authentication required")
      )
    (next)))

(declare open-child-script)
(declare get-maximoplus-process)

(defn max-basic-auth-middleware
  [req res next]
  ;;for development only. the production will use jwt and error callback instead of http codes
  ;;it will not throw the exception, session middleware will
  (if-let [credentials (auth req)]
    (let [username (.-name credentials)
          password (.-pass credentials)
          session (.-session req)]
      (if-not (get-maximoplus-process session)
        (open-child-script session #js{:username username :password password}
                           (fn [sessionid]
                             (if (and (map?  sessionid) (:error sessionid))
                               ;;invalid username and password
                               (do
                                 (.status res 401)
                                 (.setHeader res "WWW-Authenticate" "Basic realm=\"Maximo-GraphQL Realm\"")
                                 (.send res "Authentication required"))
                               (when sessionid
                                 (aset session "t" sessionid)
                                 (.log js/console (str "setting session to " sessionid))
                                 (next)))))
        (do
          (.log js/console "valid session skipping logging")
          (next))))
    (next)))

;;the session check middleware and the http basic auth will be used just during the development. In production JWT will be utiziled. (no http headers, just errors in graphql response)

(declare get-maximoplus-pid)

(def running (atom nil)) ;;so we can stop and start from repl

(defn main
  []
  (let [schema (get-schema-string)
        typedefs (gql schema)
        server (ApolloServer.
                #js{:typeDefs typedefs
                    :resolvers resolvers
                    :playground #js{:settings #js{"editor.theme" "light"
                                                  "request.credentials" "same-origin"
                                                  }
                                    }
                    :context (fn [obj]
                               (let [req-arg (aget obj "req")
                                     session (aget req-arg "session")]
                                 #js{:pid (get-maximoplus-pid session)
                                     :rel-handles (atom {});;in one call the line resolver should all call through one rel container, fot the parent handle, always the same handle (container)
                                     :rel-temp-channels (atom {});;should ensure sequential calling of rel container operations
                                     }))} )
        app (express)
        ]
    (.use app (session #js{:secret "keyboard cat" :cookie #js {:httpOnly false}}))
    (.use app max-basic-auth-middleware)
    (.use app max-session-check-middleware)


    (.applyMiddleware server #js {:app app})
    (reset! running
            (.listen app #js {:port 4001}
                     (fn [url]
                       (.log js/console (str "server ready at" (.-graphqlPath server))))))))

(defn logged-in
  [process  maximo-session-id cb]
  (let [pid (.-pid process)
        obj (@child-processes pid)]
    (swap! child-processes assoc pid (assoc obj :maximo-session maximo-session-id))
    (when cb (cb maximo-session-id))))

(defn login-error
  [err cb]
  (when cb (cb {:error err })))

(declare kill-child-process)

(defn logged-out
  [pid]
  (kill-child-process pid)
  (swap! child-processes dissoc pid))

(defn get-maximoplus-process
  [req-session]
  (when-let [max-session-id (aget req-session "t")]
    (.log js/console "trying to get the process for maximo sesion")
    (.log js/console max-session-id)
    (:process (second (first
     (filter (fn [[k v]]
               (= max-session-id (:maximo-session v)))
             @child-processes))))))

(defn get-maximoplus-pid
  [req-session]
  (when-let [max-session-id (aget req-session "t")]
    (.log js/console "trying to get the process for maximo sesion")
    (.log js/console max-session-id)
    (first(first
           (filter (fn [[k v]]
                     (= max-session-id (:maximo-session v)))
                   @child-processes)))))

(defn process-command
  [pid uid value]
  (when-let [ch (@pending-messages uid)]
    (swap! pending-messages dissoc uid)
    (go
      (put! ch (transit-read value)))))

(defn process-child-message
  [m process cb]
  ;;handle to session t
  (let [type (aget m "type")
        value (aget m "val")
        pid (.-pid process)
        uid (aget m "uid")]
    (condp = type
      "loggedout" (logged-out pid)
      "loggedin" (logged-in process value cb)
      "loginerror" (login-error value cb)
      "command" (process-command pid uid value)
      :default))
  )

(defn open-child-script
  [req-session credentials cb]
  ;;this will be called from the login middleware (or the login function), when the login occurs, callback will be called with the maximo session id
  (let [debug? (aget (.-env js/process) "DEBUG")
        prc (if debug?
              @debug-process
              (fork "out/gscript.js"))
        pid (.-pid prc)]
    (.on prc "message" (fn [m]
                         (process-child-message m prc cb)
                         ))
    (.on prc "exit" (fn [_]
                      (.log js/console "child exit")
                      (when (@child-processes pid)
                        (swap! child-processes dissoc pid))))
    (swap! child-processes assoc pid {:process prc})
    (.send (:process (@child-processes pid))
           #js{:type "login" :val #js{:credentials credentials}})
    pid))

(defn kill-child-process
  [pid]
  (.send (:process (@child-processes pid)) #js{:type "kill" :val ""})
  )

(defn get-graphql-value
  [val]
  ;;this will depend on what is inside
  val)

(defn send-graphql-command
  [pid command-object]
  ;;this will send the message of the type "command" to the child process with the command object containing all the necessary things
  ;;inernally, I will create the channel, and the id of it will be sent to the child process. Once the command is finished, it will send back the results
  ;;with this id, and the promise will be resolved
  (let [uid (uniqid)
        ch (chan)]
    (swap! pending-messages assoc uid ch)
    (.send (:process (@child-processes pid))
           #js{:type "command"
               :uid uid
               :val command-object})
    (js/Promise.
     (fn [resolve reject]
       (go
         (let [command-return-val (<! ch)]
           (resolve
            (get-graphql-value command-return-val))))))))


;;the major idea is to automatically create the graphql resolver function based on schema
;;once the AST is parsed, it should assign the resolvers for each field witht the return Maximo type. Arguments will be fixed. Inside the resolver, it should call the child script and get the data. The arguments passed: type (the type of the field - Query for the root). field name - for queries object for root and relationship name for children. handler - for pagination, the control id, pagination object

;; the resolver function will returnm the data object from mp client script

(defn   get-field-data
  [t]
  {:name (aget t "name")
   :type (let [tp (-> t (aget "type") (aget "kind"))]
           (if (= "LIST" tp)
             (-> t (aget "type") (aget "ofType") (aget "name"))
             (if (= "OBJECT" tp)
               (-> t (aget "type") (aget "name"))
               :scalar)
             ))
   :list (= "LIST"  (-> t (aget "type") (aget "kind")))
   })

(defn get-filtered-types
  []
  (let [types (-> (get-ast-tree) (aget "__schema") (aget "types"))]
    (filter
     (fn [t]
       (and (= "OBJECT" (aget t "kind"))
            (not (.startsWith (aget t "name") "_"))))
     types)))

(defn get-function-type-signatures
  []
  (let [flt (get-filtered-types)]
    (map (fn [f]
           {:name (aget f "name")
            :fields (filter #(not= :scalar (:type %))
                            (map get-field-data (aget f "fields")))})
         flt)))

(defn get-scalar-fields
  [gql-type]
  (filter #(= (:type %) :scalar)
          (map get-field-data
               (aget
                (->>
                 (get-filtered-types)
                 (filter (fn [t]
                           (= gql-type (aget t "name"))
                           ))
                 (first)) "fields")))
  )

(defn get-maximo-scalar-fields
  [gql-type]
  ;;this will be used in resolver to create the list of fields to fetch from the container. The automatically created resolver will use this.
  (->> (get-scalar-fields gql-type)
       (filter #(and (not= (:name %) "id" )
                    (not (.startsWith (:name %) "_"))))
       (map :name)
       clj->js))

(defn get-field-type
  [object-name field-name]
  ;;should return :qyery or :mutation but
  ;;the implemntaiton looks naive, but here is the reasoning:
  ;;There will be no nested mutations in GraphQL for Maixmo
  ;;and subscriptions are always only one level deep (and then the data can be captured with the simple query
  (if (= "Subscription" object-name)
    :subscription
    (if (= "Mutation" object-name)
        :mutation
        :query)))

(defn ^:dev/before-load stop []
  (js/console.log "stop")
  (.close @running))

(defn ^:dev/after-load start []
  (js/console.log "start")
  (main))
