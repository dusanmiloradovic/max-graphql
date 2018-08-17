(ns maximoplus.net.node
  (:require
   [maximoplus.net :as n :refer [INet]]
   [maximoplus.utils :as u :refer [transit-read]]
   ["request" :as request]
   ["eventsource" :as EventSource])
  )

(def -req (.defaults request #js{:jar true}))
;;we can put jar true, because oll the variables are global for one session, so we have to spawn this from the main nodejs process (and then there is no need to have multiple jars)

(def tabsess (atom "123"))

(def event-source (atom nil))

(defn is-error?
  ;;with the request node lib, the error will not come even if the http error code is 40x (maybe even 50x), it seems that the error is thrown only when the lib was not able to send
  [resp]
  (> (.-statusCode resp) 400))

(defn get-url-with-tabsess
  [url]
  (str url  (if (= -1 (.indexOf url "?")) "?" "&") "t=" @tabsess))

(defn send-data
  [option callback error-callback]
  (-req option 
        (fn [err resp body]
          (if err
            (error-callback [err 6 (.-statusCode resp)]) ;;For the compatibility reasons with browser, I will use just 6 (error) and 0 no error
            (if (is-error? resp)
              (error-callback [(transit-read body) 0 (.-statusCode resp)])
              (callback [(transit-read body) 0 (.-statusCode resp)]))))))

(deftype Node []
  INet
  (-send-get
    [this url callback error-callback]
    (n/-send-get this url nil callback error-callback))
  (-send-get;;ingore data, them later just remove this method, I think it is obsolette
    [this url data callback error-callback]
    (send-data (get-url-with-tabsess url) callback error-callback))
  (-send-post
    [this url data callback error-callback]
    (n/-send-post this url data callback error-callback nil))
  (-send-post
    [this url data callback error-callback progress-callback];;ignore progress for the time, i don't think it is relevant at this point
    (send-data #js{:url (get-url-with-tabsess url)
                   :body data
                   :method "POST"} callback error-callback))
  (-start-server-push-receiving
    [this callback error-callback]
    (let [sse-url (n/sse)
          ev-s (EventSource. sse-url #js {:withCredentials true})]
      (reset! event-source ev-s)
      (.addEventListener ev-s "message"
                         (fn [message]
                           (let [_data (aget message "data")
                                 data (if (= "" _data) "" (u/transit-read _data))]
                             (callback data))))
      (.addEventListener ev-s "error"
                         (fn [error] (error-callback error)))))
  (-stop-server-push-receiving
    [this]
    (when @event-source
      (.close @event-source)
      (reset! event-source nil)))
  (-get-tabsess;;tabsess handling will be done by the implemntation (browser or node)
    [this]
    @tabsess)
  (-set-tabsess!
    [this _tabsess]
    (reset! tabsess _tabsess))
  )
