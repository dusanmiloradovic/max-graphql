(ns graphqlserver.core
  (:require
   ["apollo-server-express" :refer (ApolloServer gql AuthenticationError)]
   ["express" :as express]
   ["child_process" :refer [fork]]
   ["express-session" :as session]
   ["basic-auth" :as auth]
   ["graphql" :refer [buildSchema graphqlSync introspectionQuery]]
   [cljs-node-io.core :as io :refer [slurp spit]]))

(def child-processes (atom {}))

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

(def resolvers #js{
                   :Query #js{
                              :books
                              ;;(fn [] (throw (AuthenticationError.)))
                              (fn [obj args context info]
                                (.log js/console info)
                                books)
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
        server (ApolloServer. #js{:typeDefs typedefs :resolvers resolvers})
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

(defn process-child-message
  [m process cb]
  ;;handle to session t
  (let [type (aget m "type")
        value (aget m "val")
        pid (.-pid process)]
    (condp = type
      "loggedout" (logged-out pid)
      "loggedin" (logged-in process value cb)
      "loginerror" (login-error value cb)
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
