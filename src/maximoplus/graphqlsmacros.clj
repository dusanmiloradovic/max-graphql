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


