(ns graphqlserver.core
  (:require
   ["apollo-server" :refer (ApolloServer gql)]
   [cljs-node-io.core :as io :refer [slurp spit]]))


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
                   :Query: #js{
                               :books (fn [] books)
                               }})



(defn main
  []
  (let [schema (slurp "schema/sample.graphql")
        typedefs (gql schema)
        server (ApolloServer. #js{:typeDefs typedefs :resolvers resolvers})]
    (.then
     (.listen server )
     (fn [url]
       (.log js/console (str "server ready at" url))))))
