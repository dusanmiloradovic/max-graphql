(ns graphqlserver.core
  (:require
   ["apollo-server-express" :refer (ApolloServer gql AuthenticationError)]
   ["express" :as express]
   ["child_process" :refer [fork]]
   ["express-session" :as session]
   
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

(defn max-session-check-middleware
  [req res next]
  (if (and
       (.-session req )
       (not (.-t (.-session req))))
    (do
      (.status res 401)
      (.send res "Authentication required")
      )
    (next)
    )
  )

;;the session check middleware and the http basic auth will be used just during the development. In production JWT will be utiziled. (no http headers, just errors in graphql response)

(defn main
  []
  (let [schema (slurp "schema/sample.graphql")
        typedefs (gql schema)
        server (ApolloServer. #js{:typeDefs typedefs :resolvers resolvers})
        app (express)]
    (.use app (session #js{:secret "keyboard cat" :cookie #js {:httpOnly false}}))
    (.use app max-session-check-middleware)

    (.applyMiddleware server #js {:app app})
    (.listen app #js {:port 4001}
             (fn [url]
               (.log js/console (str "server ready at" (.-graphqlPath server)))))))

(defn open-child-script
  []
  (let [prc (fork "out/gscript.js")
        pid (.-pid prc)]
    (.on prc "message" (fn [m]
                         (.log js/console m)
                         ))
    (.on prc "exit" (fn [_]
                      (.log js/console "child exit")
                      (swap! child-processes dissoc pid)))
    (swap! child-processes assoc pid prc)
    pid))

(defn kill-child-process
  [pid]
  (.send (@child-processes pid) #js{:type "kill" :val ""})
  )
