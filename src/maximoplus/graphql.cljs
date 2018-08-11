(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer Grid Section MboContainer]])
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on custom-this kc!]]
                   [cljs.core.async.macros :refer [go]]))

(defn main
  []
  (.log js/console "bla"))
