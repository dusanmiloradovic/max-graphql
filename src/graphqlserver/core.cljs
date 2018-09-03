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
   [cljs.core.async :as a :refer [<! put! chan]]
   [cognitect.transit :as transit])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def child-processes (atom {}))

(def pending-messages (atom {}));;we send the commadn to the child process. When we receive it back, the promise is resolved, apollo returns the data to the client, and we delete the message from the map

(def transit-reader (transit/reader :json))

(defn transit-read [x]
  (transit/read transit-reader x))

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

(defn transform-maximo-query-object
  [component-id]
  (fn [dta]
    (into {}
          (conj
           (map (fn [[k v]]
                  [(if (= k "_uniqueid") "id" (.toLowerCase k)) v]
                  )
                dta)
           ["_handler" component-id]))))

(defn test-po-resolver
  [obj args context info]
  (let [from-row (aget args "fromRow")
        num-rows (aget args "numRows")
        res-p (send-graphql-command 
               (aget context "pid")
               #js{:command "fetch"
                   :args #js{:app "po"
                             :object-name "postd"
                             :columns #js["ponum" "status" "description"]
                             :start-row from-row
                             :num-rows num-rows}}
               )]
    (.then res-p
                   (fn [res]
                     (let [component-id (first res)
                           _res (rest res)]
                       (map
                        (fn [[rownum data flaga]]
                          (into {}
                                (conj
                                 (map (fn [[k v]]
                                        [(if (= k "_uniqueid") "id" (.toLowerCase k)) v]
                                        )
                                      data)
                                 ["_handler" component-id])))
                        _res)))
                   )
    ))

(def resolvers #js{
                   :Query #js{
                              :books
                              ;;(fn [] (throw (AuthenticationError.)))
                              (fn [obj args context info]
                                (.log js/console info)
                                books)
                              :book
                              (fn[obj args context info]
                                (aget books 0))
                              }})

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

(defn main
  []
  (let [schema (get-schema-string)
        typedefs (gql schema)
        server (ApolloServer.
                #js{:typeDefs typedefs
                    :resolvers resolvers
                    :context (fn [obj]
                               (let [req-arg (aget obj "req")
                                     session (aget req-arg "session")]
                                 #js{:pid (get-maximoplus-process session)}))} )
        app (express)]
    (.use app (session #js{:secret "keyboard cat" :cookie #js {:httpOnly false}}))
    (.use app max-basic-auth-middleware)
    (.use app max-session-check-middleware)


    (.applyMiddleware server #js {:app app})
    (.listen app #js {:port 4001}
             (fn [url]
               (.log js/console (str "server ready at" (.-graphqlPath server)))))))

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
  (let [prc (fork "out/gscript.js")
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

(defn get-function-type-signatures
  []
  (let [types (-> (get-ast-tree) (aget "__schema") (aget "types"))
        flt (filter
             (fn [t]
               (and (= "OBJECT" (aget t "kind"))
                    (not (.startsWith (aget t "name") "_"))))
             types)
        get-field-data
        (fn [t]
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
        ]
    (map (fn [f]
           {:name (aget f "name")
            :fields (filter #(not= :scalar (:type %))
                            (map get-field-data (aget f "fields")))})
         flt)))

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
