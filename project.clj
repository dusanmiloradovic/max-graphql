(defproject cl "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies
  [[org.clojure/clojure "1.9.0-alpha17"]
   [maximoplus-client "1.0.0-SNAPSHOT"]
   [thheller/shadow-cljs "2.4.33"]] ;; <- changed here
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
