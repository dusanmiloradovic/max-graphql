(ns maximoplus.graphql
  (:require [maximoplus.basecontrols :as b :refer [AppContainer Grid Section MboContainer]]
            [maxiomplus.core :as c]
            ["xhr2-cookies" :refer [XMLHttpRequest]]
            ["cookiejar" :as cookies :refer [CookieJar]]
            ["eventsource" :as EventSource])
  (:require-macros [maximoplus.macros :as mm :refer [def-comp googbase kk! kk-nocb! kk-branch-nocb! p-deferred p-deferred-on custom-this kc!]]
                   [cljs.core.async.macros :refer [go]]))

(aset XMLHttpRequest "cookiejar" (CookieJar.))

(aset js/global "XMLHttpRequest" XMLHttpRequest)

(aset js/global "EventSource" EventSource)

(c/setGlobalFunction "global_login_function"
                     (fn [err]
                       (c/max-login "maxadmin" "maxadmin"
                                    (fn [ok]
                                      (.log js/console "logged in"))
                                    (fn [err]
                                      (.log js/console "Not logged in error")
                                      (.loj js/console err)))))

(defn main
  []
  (.log js/console "bla"))



(defn ojsa
  [x y]
  (+ x y))

(defn blala
  [x]
  (- x 2))
