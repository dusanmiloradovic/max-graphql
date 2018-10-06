(ns maximoplus.graphqlmacros)

(defmacro prom->
  [& args]
  (loop [ar (rest args) res (first args)]
    (if (empty? ar)
      res
      (recur (rest ar)
             `(.then ~res (fn [ok#] ~(first ar)))))))

(defmacro prom-then->
  [& args]
  (let [fun-ex (last args)
        _args (butlast args)]
    `(.then
      (prom-> ~@_args)
      ~fun-ex)))

(defmacro prom-command!
  [command-f & args]
  `(maximoplus.promises.get-promise
    (fn [resolve# reject#]
      (~command-f ~@args
       (fn [ok#]
         (resolve# ok#))
       (fn [err#]
         (reject# err#))))))


