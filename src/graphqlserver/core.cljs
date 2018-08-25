(ns graphqlserver.core
  (:require
   ["apollo-server-express" :refer (ApolloServer gql AuthenticationError)]
   ["express" :as express]
   ["child_process" :refer [fork]]
   ["express-session" :as session]
   ["basic-auth" :as auth]
   [cljs-node-io.core :as io :refer [slurp spit]]))

(def child-processes (atom {}))

(def books #js[#js{
                   :title "Harry Potter and the Chamber of Secrets"
                   :author "J.K. Rowling"
                   }
               #js{
                   :title "Jurassic Park"
                   :author "Michael Crichton"
                   }]
  )

(def resolvers #js{
                   :Query #js{
                              :books
                              ;;(fn [] (throw (AuthenticationError.)))
                              (fn [] books)
                              }})
(defn max-authorizer
  [username password cb]
  (.log js/console "test auth ")
  (cb "testsessionid")
  )

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

(defn max-basic-auth-middleware
  [req res next]
  ;;for development only. the production will use jwt and error callback instead of http codes
  ;;it will not throw the exception, session middleware will
  (if-let [credentials (auth req)]
    (let [username (.-name credentials)
          password (.-pass credentials)
          session (.-session req)]
      (max-authorizer username password
                      (fn [sessionid]
                        (it (and (map?  sessionid) (:error sessionid))
                            ;;invalid username and password
                            (do
                              (.status res 401)
                              (.setHeader res "WWW-Authenticate" "Basic realm=\"User Visible Realm\"")
                              (.send res "Authentication required"))
                            (when sessionid
                              (aset session "t" sessionid)
                              (.log js/console (str "setting session to " sessionid))))
                        (next))))
    (next)))

;;the session check middleware and the http basic auth will be used just during the development. In production JWT will be utiziled. (no http headers, just errors in graphql response)

(defn main
  []
  (let [schema (slurp "schema/sample.graphql")
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
    (swap! child-processess (assoc obj :maximo-session maximo-session-id))
    (when cb (cb maximo-session-id))
    ))

(defn login-error
  [err cb]
  (when cb (cb {:error err })))

(declare kill-child-process)

(defn logged-out
  [pid]
  (kill-child-process pid)
  (swap! child-prcesses dissoc pid))

(defn get-maximoplus-process
  [req-session]
  (when-let [max-session-id (aget req-session "t")]
    (:process
     (filter (fn [p]
               (= max-session-id (:maximo-session p)))
             @child-processes))))

(defb process-child-process-message
  [m process cb]
  ;;handle to session t
  (let [type (aget m "type")
        value (aget m "val")
        pid (.-pid process)]
    (condp = type
      "logout" (logged-out pid)
      "login" (logged-in process value cb)
      "loginerror" (login-error value cb)
      :default)
    )
  )

(defn open-child-script
  [req-session credentials cb]
  ;;this will be called from the login middleware (or the login function), when the login occurs, callback will be called with the maximo session id
  (let [prc (fork "out/gscript.js")
        pid (.-pid prc)]
    (.on prc "message" (fn [m]
                         (.log js/console m)
                         ))
    (.on prc "exit" (fn [_]
                      (.log js/console "child exit")
                      (when (@child-processes pid)
                        (swap! child-processes dissoc pid))))
    (swap! child-processes assoc pid {:process prc})
    (.send (:process (@child-processes pid))
           #js{:type "login" :value #js{:credentials credentials}})
    pid))

(defn kill-child-process
  [pid]
  (.send (:process (@child-processes pid)) #js{:type "kill" :val ""})
  )
