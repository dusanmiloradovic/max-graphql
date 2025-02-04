(ns graphqlserver.core
  (:require
   ["apollo-server-express" :refer (ApolloServer gql AuthenticationError ApolloError)]
   ["express" :as express]
   ["child_process" :refer [fork]]
   ["express-session" :as session]
   ["basic-auth" :as auth]
   ["uniqid" :as uniqid]
   ["graphql" :refer [buildSchema graphqlSync introspectionQuery]]
   ["graphql-tools" :refer [mergeSchemas makeExecutableSchema addMockFunctionsToSchema]]
   ["merge-graphql-schemas" :refer [mergeTypes]]
   ["fs" :as fs]
   [cljs-node-io.core :as io :refer [slurp spit]]
   [cljs.core.async :as a :refer [<! put! chan promise-chan]]
   [cognitect.transit :as transit])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(set! *warn-on-infer* true)

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

(defn get-combined-types
  []
  (let [internal-schema (-> "internal_schema/system.graphql" slurp gql)
        files (.map
               (.filter (fs/readdirSync "schema")
                        (fn [f] (.endsWith  f ".graphql")))
               (fn [f] (->> f (str "schema/") slurp gql)))]
    (.push files internal-schema)
    (mergeTypes files #js{:all true})))

(defn get-ast-tree
  []
  (let [schema (buildSchema (get-combined-types))]
    (.-data (graphqlSync schema introspectionQuery))))

(declare send-graphql-command)

(declare get-maximo-scalar-fields)

(defn process-data-rows
  [res]
  (let [component-id (first res)
        _res (rest res)]
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

(defn process-data-one-row
  [[component-id data flags]]
;;  (println "process-data-one row " data)
  (clj->js
   (into {}
         (conj
          (map (fn [[k v]]
                 [(if (= k "_uniqueid") "id" (.toLowerCase k)) v]
                 )
               data)
          ["_handle" component-id]))))

(defn process-metadata
  [res]
  (clj->js
   (loop [mdt res rez {}]
     (if (empty? mdt)
       rez
       (let [rr (first mdt)]
         (recur (rest mdt) (assoc rez (.toLowerCase (:attributeName rr)) rr)))))))


(def rel-temp-promises (atom {}))


(defn max-session-check-middleware
  [^js req ^js res next]
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
  [^js req ^js res next]
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
;;                                 (.log js/console (str "setting session to " sessionid))
                                 (next)))))
        (do
  ;;        (.log js/console "valid session skipping logging")
          (next))))
    (next)))

;;the session check middleware and the http basic auth will be used just during the development. In production JWT will be utiziled. (no http headers, just errors in graphql response)

(declare get-maximoplus-pid)

(def running (atom nil)) ;;so we can stop and start from repl

(declare get-auto-resolvers)

(defn main
  []
  (let [
        schema (get-combined-types)
        typedefs (gql schema)
        server (ApolloServer.
                #js{
                    :typeDefs typedefs
                    :resolvers (get-auto-resolvers)
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
                       (println (str "server ready at" (.-graphqlPath server))))))))

(defn logged-in
  [^js process  maximo-session-id cb]
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
;;    (.log js/console "trying to get the process for maximo sesion")
  ;;  (.log js/console max-session-id)
    (:process (second (first
                       (filter (fn [[k v]]
                                 (= max-session-id (:maximo-session v)))
                               @child-processes))))))

(defn get-maximoplus-pid
  [req-session]
  (when-let [max-session-id (aget req-session "t")]
;;    (.log js/console "trying to get the process for maximo sesion")
  ;;  (.log js/console max-session-id)
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
  [m ^js process cb]
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
;;                      (.log js/console "child exit")
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
;;    (println command-object)
    (swap! pending-messages assoc uid ch)
    (.send (:process (@child-processes pid))
           #js{:type "command"
               :uid uid
               :val command-object})
    (js/Promise.
     (fn [resolve reject]
       (go
         (let [command-return-val (<! ch)]
           (if-let [error-text (and (map? command-return-val)
                                    (:error-text command-return-val))]
             (reject (ApolloError. error-text (:error-code command-return-val)))
             (resolve
              (get-graphql-value command-return-val)))))))))


;;the major idea is to automatically create the graphql resolver function based on schema
;;once the AST is parsed, it should assign the resolvers for each field witht the return Maximo type. Arguments will be fixed. Inside the resolver, it should call the child script and get the data. The arguments passed: type (the type of the field - Query for the root). field name - for queries object for root and relationship name for children. handler - for pagination, the control id, pagination object

;; the resolver function will returnm the data object from mp client script

(defn get-field-data
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
  (let [ast-tree (get-ast-tree)
        types (-> ast-tree (aget "__schema") (aget "types"))]
    (filter
     (fn [t]
       (and (= "OBJECT" (aget t "kind"))
            (not (.startsWith (aget t "name") "_"))))
     types)))

(defn get-function-type-signatures
  []
  (let [flt (get-filtered-types)]
    (map (fn [f]
           (let [fnam (aget f "name")]
             {:name fnam
              :fields (filter #(or (= fnam "Mutation") (not= :scalar (:type %)))
                              (map get-field-data (aget f "fields")))}))
         flt)))
;;Mutation will allow children scalar fields to have resolvers (like save, rollback, delete). By definition, there will be only one scalar type return, and that is Boolean (instead of void). That is important, so I can simplify, and replace :scalar with Boolean in resolvers

(defn get-scalar-fields
  [gql-type]
;;  (println "getting the scalar fields for " gql-type)
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

(defn get-meta-columns
  [meta-type]
  (clj->js
   (map (fn [t] (:name (get-field-data t)))
        (aget
         (->>
          (get-filtered-types)
          (filter (fn [t]
                    (= meta-type (aget t "name"))
                    ))
          (first)) "fields"))))

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

(defn get-app-resolver-function
  [type field return-type]
  (fn [obj args context info]
    (let [from-row (aget args "fromRow")
          num-rows (aget args "numRows")
          handle (aget args "_handle")
          qbe (aget args "qbe")
          res-p (send-graphql-command 
                 (aget context "pid")
                 #js{:command "fetch"
                     :args #js{:app field
                               :object-name return-type
                               :columns (get-maximo-scalar-fields return-type)
                               :start-row from-row
                               :num-rows num-rows
                               :handle handle
                               :qbe qbe
                               }}
                 )]
      (.then res-p process-data-rows))))

;;TODO for all the resolver function, have strict validation, or it will fail
(defn get-list-domain-resolver-function
  [type field return-type]
  (fn  [obj args context info]
    (let [from-row (aget args "fromRow")
          num-rows (aget args "numRows")
          handle (aget args "_handle")
          parent-handle (aget obj "_handle")
          parent-id (aget obj "id")
          pid (aget context "pid")
          qbe (aget args "qbe")
          command-object #js{:command "fetch"
                             :args #js{:list-column (aget (.split field "_") 1)
                                       :columns (get-maximo-scalar-fields return-type)
                                       :parent-handle parent-handle 
                                       :parent-id (aget obj "id")
                                       :start-row from-row
                                       :num-rows num-rows
                                       :parent-object type
                                       :handle handle
                                       :qbe qbe
                                       }}
          res-p (send-graphql-command pid command-object) 
          ]
      (.then res-p process-data-rows))))

(defn get-rel-resolver-function
  [type field return-type]
  (fn   [obj args context info]
    (let [from-row (aget args "fromRow")
          num-rows (aget args "numRows")
          handle (aget args "_handle")
          parent-handle (aget obj "_handle")
          parent-id (aget obj "id")
          rel-name field
          pid (aget context "pid")
          qbe (aget args "qbe")
;;          _ (println (str "calling the rel resolver for parent id " parent-id  " and rel-name " rel-name))
          command-object #js{:command "fetch"
                             :args #js{:relationship rel-name
                                       :columns (get-maximo-scalar-fields return-type)
                                       :parent-handle parent-handle 
                                       :parent-id (aget obj "id")
                                       :start-row from-row
                                       :num-rows num-rows
                                       :parent-object type
                                       :handle handle
                                       :qbe qbe
                                       }}
          res-p (send-graphql-command pid command-object) 
          ]
      (.then res-p process-data-rows))))

(defn get-metadata-resolver-function
  [type field return-type]
  (fn [obj args context info]
    (let [handle (aget obj "_handle")
          pid (aget context "pid")
          command-object #js{:command "metadata"
                             :args #js{:columns (get-meta-columns return-type)
                                       :handle handle
                                       }}
          res-p (send-graphql-command pid command-object)]
      (.then res-p process-metadata))))

(defn get-column-meta-resolver-function
  [field]
  (fn [obj args context info]
    (aget obj field)))

(defn get-add-mutation-resolver
  [field return-type]
  (fn [obj args context info]
    (let [handle (aget args "_handle")
          pid (aget context "pid")
          data (aget args "data")
          command-object #js{:command "add"
                             :args #js{:handle handle
                                       :data data}}
          res-p (send-graphql-command pid command-object)]
      (.then res-p process-data-one-row))))

(defn get-update-mutation-resolver
  [field return-type]
  (fn [obj args context info]
    (let [handle (aget args "_handle")
          pid (aget context "pid")
          id (aget args "id")
          data (aget args "data")
          command-object #js{:command "update"
                             :args #js{:handle handle
                                       :id id
                                       :data data}}
          res-p (send-graphql-command pid command-object)]
      (.then res-p process-data-one-row))))

(defn get-delete-mutation-resolver
  [field return-type]
  (fn [obj args context info]
    (let [handle (aget args "_handle")
          pid (aget context "pid")
          id (aget args "id")
          command-object #js{:command "delete"
                             :args #js{:handle handle
                                       :id id}}
          res-p (send-graphql-command pid command-object)]
      (.then res-p (fn [_] true)))))

(defn get-command-mutation-resolver
  [field return-type]
  (fn [obj args context info]
    (let [handle (aget args "_handle")
          pid (aget context "pid")
          id (aget args "id")
          command (aget args "command")
          isMbo (aget args "isMbo")
          command-object #js{:command "execute"
                             :args #js{:handle handle
                                       :id id
                                       :command command
                                       :mbo isMbo}}
          res-p (send-graphql-command pid command-object)]
      (.then res-p (fn [_] true)))))

(defn get-save-mutation-resolver
  ;;saves all the changes for the user
  []
  (fn [obj args context info]
    (let [pid (aget context "pid")]
      (.then
       (send-graphql-command pid #js{:command "save"})
       (fn [_] true)))))

(defn get-rollback-mutation-resolver
  ;;saves all the changes for the user
  []
  (fn [obj args context info]
    (let [pid (aget context "pid")]
      (.then
       (send-graphql-command pid #js{:command "rollback"})
       (fn [_] true)))))

(defn get-routewf-mutation-resolver
  []
  (fn [obj args context info]
    (let [handle (aget args "_handle")
          process-name (aget args "processName")
          pid (aget context "pid")]
      (.then
       (send-graphql-command pid #js{:command "routeWF"
                                     :args #js{:handle handle
                                               :processName process-name}})
       (fn [wf-data]
         (clj->js wf-data))))))

(defn get-choosewf-mutation-resolver
  []
  (fn [obj args context info]
    (let [handle (aget args "_handle")
          action-id (aget args "actionid")
          memo (aget args "memo")
          pid (aget context "pid")]
      (.then
       (send-graphql-command pid #js{:command "chooseWFAction"
                                     :args #js{:handle handle
                                               :actionid action-id
                                               :memo memo
                                               }})
       (fn [wf-data]
         (clj->js wf-data))))))

(defn get-mutation-resolver
  [type field return-type]
  (cond
    (.startsWith field "add") (get-add-mutation-resolver field return-type)
    (.startsWith field "delete") (get-delete-mutation-resolver field return-type)
    (.startsWith field "update") (get-update-mutation-resolver field return-type)
    (.startsWith field "command") (get-command-mutation-resolver field return-type)
    (= field "save") (get-save-mutation-resolver)
    (= field "rollback") (get-rollback-mutation-resolver)
    (= field "routeWF") (get-routewf-mutation-resolver)
    (= field "chooseWFAction") (get-choosewf-mutation-resolver)
    :else (fn [x] x)))

(defn get-query-resolver
  [type field return-type]
  (cond
    (= type "Query") (get-app-resolver-function type field return-type)
    (.startsWith field "list_") (get-list-domain-resolver-function type field return-type)
    (= "_metadata" field) (get-metadata-resolver-function type field return-type)
    :else (get-rel-resolver-function type field return-type)))

(defn get-resolver-function
  [type field return-type]
  (cond
    (= "ColumnMetadata" return-type) (get-column-meta-resolver-function field)
    (= type "Mutation") (get-mutation-resolver type field return-type)
    :else (get-query-resolver type field return-type)))

;;workflow operations return union. Object returned from the process
;;shoukld have the type attribute to resolve on type
(def union-type-resolvers
  {:WFActionResult
   {"__resolveType"
    (fn   [obj context info]
      (aget obj "type"))}})

(defn get-auto-resolvers
  ;;for the time being, just for the queries
  []
  (clj->js
   (merge
    (reduce
     (fn[m v]
       (if (empty? (:fields v))
         m
         (assoc m (:name v)
                (reduce (fn[mm vv]
                          (assoc mm (:name vv)
                                 (get-resolver-function (:name v) (:name vv) (:type vv))
                                 ;;[(:name v) (:name vv) (:type vv)]
                                 )) {}  (:fields v))))) {}
     (get-function-type-signatures))
    union-type-resolvers)))

(defn ^:dev/before-load stop []
  (println "stop")
  (.close @running))

(defn ^:dev/after-load start []
  (println "start")
  (main))
