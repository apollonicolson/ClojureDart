(ns cljd.repl.eval
  "VM-Service REPL eval: route a form to `evaluate` (expressions) or hot reload (new code)."
  (:require [cljd.compiler :as compiler]
            [cljd.repl.vmservice :as vm]
            [clojure.walk :as walk]))

(def ^:private history-reads
  "*1/*2/*3/*e/*env reads, rewritten to holder vars (set! can't persist across evaluate calls)."
  '{*1   cljd.core/+cljd-repl-h1+
    *2   cljd.core/+cljd-repl-h2+
    *3   cljd.core/+cljd-repl-h3+
    *e   cljd.core/+cljd-repl-e+
    *env cljd.core/+cljd-repl-env+})

(defn- capturing-e
  "Wrap EXPR so a thrown error is stored in *e, then rethrown."
  [expr]
  (list 'try expr
    (list 'catch 'dynamic 'e '_st
      (list 'do (list 'set! 'cljd.core/+cljd-repl-e+ 'e) (list 'throw 'e)))))

(def ^:private toplevel-ops
  "Form heads (ns-ignored) that need a hot reload rather than `evaluate`."
  '#{def defn defn- defmacro deftype defrecord defprotocol defmulti defmethod
     definterface definline declare ns require use import refer in-ns
     extend-type extend-protocol extend gen-class def-
     deftype* defprotocol* defmulti* defmethod* defcontrib* extend-type-protocol*})

(defn emits-new-toplevel?
  "True if FORM (macroexpanded) introduces top-level code; recurses into `do`."
  [form]
  (boolean
   (and (seq? form)
        (let [op (first form)]
          (cond
            (= op 'do)   (some emits-new-toplevel? (rest form))
            (symbol? op) (contains? toplevel-ops (symbol (name op)))
            :else        false)))))

(defn- without-ns-effects
  "Call F, which compiles a form for `evaluate`, and restore compiler/nses afterwards. An evaluated
   expression runs inside an existing Dart library and cannot add imports or definitions to it, so
   any namespace change made while compiling it is phantom state; left in place, a lib alias it
   registered makes the current ns look like a dependant of that lib, and the next hot-reload
   recompile tears the ns down and recompiles others against it."
  [f]
  (let [before @compiler/nses]
    (try (f) (finally (reset! compiler/nses before)))))

(defn- poll-future
  "Poll +cljd-repl-fbox+ every 100ms until it holds a value or \"__CLJD_ERR__ …\", or timeout."
  [client iso-id lib timeout-ms]
  (let [deref-dart (without-ns-effects
                     #(compiler/form->dart-expr '(cljd.core/deref cljd.core/+cljd-repl-fbox+) false))
        deadline   (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (vm/evaluate client iso-id lib deref-dart)
            v (:valueAsString r)]
        (cond
          (= (:type r) "@Error")    {:kind :eval :error true :message (:message r) :dart-kind (:kind r) :ref r}
          (or (nil? v) (= v "null"))
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 100) (recur))
            {:kind :eval :error true :message "await timed out (Future did not complete)"})
          (.startsWith ^String v "__CLJD_ERR__")
          {:kind :eval :error true :message (subs v (min (count v) (count "__CLJD_ERR__ ")))}
          :else {:kind :eval :value v :ref r})))))

(defn- eval-expression
  "Compile EXPR and `evaluate` it; with await?, poll a pending Future to its value."
  [client iso-id expr ns-lib-uri await? await-timeout-ms remember?]
  (let [expr (if remember? (walk/postwalk-replace history-reads expr) expr)
        expr (if (and remember? (not await?))
               (list 'cljd.core/+cljd-repl-remember expr)
               expr)
        expr (if remember? (capturing-e expr) expr)
        dart (without-ns-effects
               #(if await? (compiler/form->dart-await-expr expr) (compiler/form->dart-expr expr)))
        lib  (vm/library-id client iso-id ns-lib-uri)
        r    (vm/evaluate client iso-id lib dart)]
    (cond
      (= (:type r) "@Error")
      {:kind :eval :error true :message (:message r) :dart-kind (:kind r) :ref r}
      (and await? (= "__cljd_future_pending__" (:valueAsString r)))
      (poll-future client iso-id lib await-timeout-ms)
      :else
      ;; evaluate caps valueAsString at 128 code units; page the rest via getObject
      {:kind :eval
       :value (if (and (= "String" (:kind r)) (:valueAsStringIsTruncated r))
                (vm/get-string-full client iso-id (:id r) (:length r))
                (:valueAsString r))
       :ref r})))

(defn- ns->lib-uri [ns-sym] (str (.replace (name ns-sym) "." "/") ".dart"))

(defn- unimported-ns
  "The namespace behind the Dart alias in an \"Undefined name 'alias'\" evaluate error, when it is
   a loaded namespace other than the current one: the form referenced a namespace whose library the
   evaluation library does not import."
  [message]
  (when-some [[_ alias] (some->> message (re-find #"Undefined name '([A-Za-z0-9_$]+)'"))]
    (let [{:keys [libs] :as nses} @compiler/nses
          ns-sym (some (fn [[_ {:keys [dart-alias ns]}]] (when (= alias dart-alias) ns)) libs)]
      (when (and ns-sym (not= ns-sym compiler/*current-ns*) (get nses ns-sym))
        ns-sym))))

(defn- eval-in-referenced-ns
  "Evaluate an expression; if it failed only because it named a namespace the evaluation library
   cannot see, retry once in that namespace's library. The failure arrives either as an @Error
   result or, when Dart rejects the expression at compile time, as a thrown RPC error. A failed
   retry keeps the first failure, with a hint naming the namespace."
  [client iso-id form ns-lib-uri await? await-timeout-ms remember?]
  (let [attempt #(try (eval-expression client iso-id form % await? await-timeout-ms remember?)
                      (catch clojure.lang.ExceptionInfo e e))
        message (fn [r] (if (instance? Throwable r)
                          (let [verr (:error (ex-data r))]
                            (or (get-in verr [:data :details]) (:message verr)))
                          (when (:error r) (:message r))))
        rethrow (fn [r] (if (instance? Throwable r) (throw r) r))
        r       (attempt ns-lib-uri)]
    (if-some [ns-sym (unimported-ns (message r))]
      (let [r2 (binding [compiler/*current-ns* ns-sym] (attempt (ns->lib-uri ns-sym)))]
        (if (message r2)
          (let [hint (str "\n(" ns-sym " is not visible from " compiler/*current-ns*
                          "'s library, and evaluating in " ns-sym " failed too: " (message r2)
                          ". Evaluate with :ns " ns-sym ", or (in-ns '" ns-sym ").)")]
            (if (instance? Throwable r)
              (throw (ex-info (str (ex-message r) hint)
                              (assoc-in (ex-data r) [:error :data :details] (str (message r) hint)) r))
              (update r :message str hint)))
          (rethrow r2)))
      (rethrow r))))

(defn- existing-def?
  "True if NAME already resolves to a :def in the current ns."
  [name]
  (boolean (try (= :def (first (compiler/resolve-symbol name {}))) (catch Throwable _ false))))

(defn eval-form
  "Evaluate FORM in the running app. Needs a bound compiler context and a connected CLIENT.
   Returns {:kind :eval :value <pr-str> :ref r} (or :error true)
   or {:kind :reload :success bool :report r}."
  [client iso-id form
   {:keys [recompile-count repltag ns-lib-uri trigger-reload reload-timeout-ms
           await? await-timeout-ms remember?]
    :or   {recompile-count 0 repltag "repl" ns-lib-uri "cljd/user.dart"
           reload-timeout-ms 60000 await-timeout-ms 30000}}]
  (cond
    ;; redef of an existing var: set! it, since hot reload doesn't re-run static initializers
    (and (seq? form) (= 'def (first form)) (= 3 (count form)) (existing-def? (second form)))
    (eval-expression client iso-id (list 'set! (second form) (nth form 2))
                     ns-lib-uri await? await-timeout-ms remember?)

    (emits-new-toplevel? form)
    (do
      (compiler/recompile-form form recompile-count repltag)
      ;; under flutter, reloadSources can't take .dart: use flutter's hot reload, which delivers true/false to done
      (if trigger-reload
        (let [done (promise)
              _    (trigger-reload done)
              ok   (deref done reload-timeout-ms ::timeout)]
          {:kind :reload :success (true? ok)
           :report {:via :flutter-hot-reload :result ok}})
        (let [report (vm/reload-sources client iso-id)]
          {:kind :reload :success (boolean (:success report)) :report report})))

    :else
    (eval-in-referenced-ns client iso-id form ns-lib-uri await? await-timeout-ms remember?)))

;; a bare `future` doesn't inherit the compiler dynamic vars; bind them via with-compiler-context

(defn context
  "Bundle the vmservice client, isolate, analyzer, dart-version and default ns/lib from CFG.
   Pass to eval! / call! / with-compiler-context."
  [{:keys [client iso-id *iso analyzer dart-version ns-lib-uri default-ns]
    :or {ns-lib-uri "cljd/core.dart" default-ns 'cljd.core}}]
  ;; *iso is an atom so eval!/call! follow the new isolate after a hot restart
  {:client client :*iso (or *iso (atom iso-id)) :analyzer analyzer :dart-version dart-version
   :ns-lib-uri ns-lib-uri :default-ns default-ns})

(defmacro with-compiler-context
  "Bind the compiler dynamic vars from CTX, with *current-ns* NS-SYM, around BODY.
   set! of *current-ns* inside BODY persists for the rest of BODY."
  [ctx ns-sym & body]
  `(let [c# ~ctx]
     (binding [compiler/*hosted* true
               compiler/*dart-version* (:dart-version c#)
               compiler/analyzer-info (:analyzer c#)
               compiler/dynamic-warning compiler/on-dynamic-warn
               compiler/*current-ns* ~ns-sym]
       ~@body)))

(defn eval!
  "eval-form FORM with CTX's coordinates inside with-compiler-context; safe from any thread.
   opts :ns sets the compile ns (default ctx's); other opts pass through to eval-form."
  ([ctx form] (eval! ctx form nil))
  ([ctx form {:keys [ns] :as opts}]
   (with-compiler-context ctx (or ns (:default-ns ctx))
     (eval-form (:client ctx) @(:*iso ctx) form
                (merge {:ns-lib-uri (:ns-lib-uri ctx)} (dissoc opts :ns))))))

(defn call!
  "Call device service extension METHOD with PARAMS via CTX."
  [ctx method params]
  (vm/call-ext (:client ctx) @(:*iso ctx) method params))
