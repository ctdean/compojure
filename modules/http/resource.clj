(in-ns* 'http-resource)
(import '(java.io File FileInputStream))
(import '(javax.servlet.http HttpServletRequest HttpServletResponse))
(import '(clojure.lang FixNum))

;;;; Mimetypes ;;;;

(def *default-mimetype* "application/octet-stream")

(defn context-mimetype
  "Get the mimetype of a filename using the ServletContext."
  [context filename]
  (or (. context (getMimeType filename))
      *default-mimetype*))

;;;; Routes ;;;;

(def symbol-regex (re-pattern ":([a-z_]+)"))

(defn parse-route
  "Turn a route string into a regex and seq of symbols."
  [route]
  (let [segment  "([^/.,;?]+)"
        matcher  (re-matcher symbol-regex (re-escape route))
        symbols  (re-find-all matcher)
        regex    (. matcher (replaceAll segment))]
    [(re-pattern regex) (map second symbols)]))

(defn match-route 
  "Match a path against a parsed route. Returns a map of keywords and their
  matching path values."
  [[regex symbols] path]
  (let [matcher (re-matcher regex path)]
    (if (. matcher (matches))
      (apply hash-map
        (interleave symbols (rest (re-groups matcher)))))))

(def #^{:doc
  "A global list of all registered resources. A resource is a vector
  consisting of a HTTP method, a parsed route, function that takes in
  a context, request and response object.
  e.g.
  [\"GET\"
   (parse-route \"/welcome/:name\") 
   (fn [context request response] ...)]"}
  *resources* '())

(defn assoc-route
  "Associate a HTTP method and route with a resource."
  [method route resource]
  (def *resources*
    (cons [method (parse-route route) resource]
          *resources*)))

;;;; Response ;;;;

(defn base-responder
  "Basic Compojure responder. Handles the following datatypes:
    string - Adds to the response body
    seq    - Adds all containing elements to the response body
    map    - Updates the HTTP headers
    FixNum - Updates the status code
    File   - Updates the response body via a file stream"
  [#^HttpServletResponse response context update]
  (cond 
    (string? update)
      (.. response (getWriter) (print update))
    (seq? update)
      (let [writer (. response (getWriter))]
        (doseq d update
          (. writer (print d))))
    (map? update)
      (doseq [k v] update
        (. response (setHeader k v)))
    (instance? FixNum update)
      (. response (setStatus update))
    (instance? File update)
      (let [out (. response (getOutputStream))
            in  (new FileInputStream update)]
        (. response (setHeader
          "Content-Type" (context-mimetype context (str update))))
        (pipe-stream in out))))

(def *responders*
  (list base-responder))

(defn add-responder [func]
  (def *responders*
    (cons func *responders*)))

(defn update-response
  "Destructively update a HttpServletResponse via a Clojure datatype. Vectors
  can be used to string different values together."
  [#^HttpServletResponse response context update]
  (if (vector? update)
    (doseq d update
      (update-response response context d))
    (some #(% response context update) *responders*)))

;;;; Resource ;;;;

(def #^{:doc
  "A set of bindings available to each resource. This can be extended
  by plugins, if required."}
  *resource-bindings*
  '(method    (. request (getMethod))
    full-path (. request (getPathInfo))
    param    #(. request (getParameter %))
    header   #(. request (getHeader %))
    mime     #(http-resource/context-mimetype (str %))))

(defn add-resource-binding
  "Add a binding to the set of default bindings assigned to a resource."
  [name binding]
  (def *resource-bindings*
    (list* name binding *resource-bindings*)))

(defmacro new-resource
  "Create a pseudo-servlet from a resource. It's not quite a real
  servlet because it's a function, rather than an HttpServlet object."
  [& body]
  `(fn ~'[route context request response]
     (let ~(apply vector *resource-bindings*)
       (update-response ~'response ~'context (do ~@body)))))

(def *default-resource*
  (new-resource
    (let [static-file (file "public" full-path)]
      (if (. static-file (isFile))
        static-file
        [404 "Cannot find file"]))))

(defn find-resource
  "Find the first resource that matches the HttpServletRequest"
  [#^HttpServletRequest request response]
  (let [method    (. request (getMethod))
        path      (. request (getPathInfo))
        matches?  (fn [[meth route resource]]
                    (if (= meth method)
                      (if-let route-params (match-route route path)
                        (partial resource route-params) nil)))]
    (or
      (some matches? *resources*)
      (partial *default-resource* {}))))