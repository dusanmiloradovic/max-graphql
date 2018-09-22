(ns maximoplus.net.node
  (:require
   [maximoplus.net :as n :refer [INet]]
   [maximoplus.utils :as u :refer [transit-read]]
   ["request" :as request]
   ["eventsource" :as EventSource])
  )

(def cookie-jar (.jar request));; I have to get it explicitely, to share with eventsource

;;we can put jar true, because oll the variables are global for one session, so we have to spawn this from the main nodejs process (and then there is no need to have multiple jars)

(def tabsess (atom "123"))

(def event-source (atom nil))

(def session-cookie (atom ""))

(defn is-error?
  ;;with the request node lib, the error will not come even if the http error code is 40x (maybe even 50x), it seems that the error is thrown only when the lib was not able to send
  [resp]
  (> (.-statusCode resp) 400))

(defn get-url-with-tabsess
  [url]
  (str url  (if (= -1 (.indexOf url "?")) "?" "&") "t=" @tabsess))

(defn send-data
  [option callback error-callback]
  (request option 
           (fn [err resp body]
             (reset! session-cookie (.getCookieString cookie-jar (aget option "url")))
             (if err
               (error-callback [[:net err] 6 (when resp (.-statusCode resp))]) ;;For the compatibility reasons with browser, I will use just 6 (error) and 0 no error
               (if (is-error? resp)
                 (do
                   (.log js/console "there has been an error")
                   (.log js/console (.-statusCode resp))
                   (error-callback [(transit-read body) 0 (.-statusCode resp)])
                   )
                 (let [tr-resp  [(transit-read body) 0 (.-statusCode resp)]]
                  ;; (println tr-resp)
                   (callback tr-resp)))))))

(deftype Node []
  INet
  (-send-get
    [this url callback error-callback]
    (n/-send-get this url nil callback error-callback))
  (-send-get;;ingore data, them later just remove this method, I think it is obsolette
    [this url data callback error-callback]
    (send-data #js{:url (get-url-with-tabsess url)
                   :method "GET"
                   :jar cookie-jar} callback error-callback))
  (-send-post
    [this url data callback error-callback]
    (n/-send-post this url data callback error-callback nil))
  (-send-post
    [this url data callback error-callback progress-callback];;ignore progress for the time, i don't think it is relevant at this point
    (send-data #js{:url (get-url-with-tabsess url)
                   :body data
                   :jar cookie-jar
                   :method "POST"} callback error-callback))
  (-start-server-push-receiving
    [this callback error-callback]
    (let [sse-url (n/sse)
          ev-s (EventSource. sse-url #js {:withCredentials true
                                          :headers #js{"Cookie"  @session-cookie}})]
      (reset! event-source ev-s)
      (.addEventListener ev-s "message"
                         (fn [message]
                           (.nextTick js/process
                            (fn []
                              (let [_data (aget message "data")
                                    data (if (= "" _data) ["" 0 200] (u/transit-read _data))]
                                (callback data))))))
      (.addEventListener ev-s "error"
                         (fn [error]
                           (.log js/console error)
                           (u/debug "SSE error" error)
                           ))))
  (-stop-server-push-receiving
    [this]
    (when @event-source
      (.close @event-source)
      (reset! event-source nil))
    (when (not= "123" @tabsess)
      ;;where there was a real session. for graphql nodejs this means logout
      (.send js/process #js{:type "loggedout" :val @tabsess})
      ))
  (-get-tabsess;;tabsess handling will be done by the implemntation (browser or node)
    [this]
    @tabsess)
  (-set-tabsess!
    [this _tabsess]
    (reset! tabsess _tabsess)
    ;;in graphql nodejs client, this will sign of successful login, so i need to send back the tab session to the parent process (will be used for a lookup(. In case I want to resuse this lib for React native this will not be there

    )
  )
