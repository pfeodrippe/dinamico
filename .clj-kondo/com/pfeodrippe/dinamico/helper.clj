(ns com.pfeodrippe.dinamico.helper)

(defmacro with-action
  [op params & body]
  `(do (swap! routes* assoc ~op
              (fn ~params
                ~@body))
       ""))
