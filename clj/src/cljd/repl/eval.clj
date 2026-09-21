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

(defn- poll-future
  "Poll +cljd-repl-fbox+ every 100ms until it holds a value or \"__CLJD_ERR__ …\", or timeout."
  [client iso-id lib timeout-ms]
  (let [deref-dart (compiler/form->dart-expr '(cljd.core/deref cljd.core/+cljd-repl-fbox+) false)
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
        ;; capture *e on both sync and await paths (await? is on by default)
        expr (if remember? (capturing-e expr) expr)
        dart (if await? (compiler/form->dart-await-expr expr) (compiler/form->dart-expr expr))
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
      (if trigger-reload
        ;; Flutter needs its own hot reload (dart->kernel first); trigger-reload delivers true/false to done
        (let [done (promise)
              _    (trigger-reload done)
              ok   (deref done reload-timeout-ms ::timeout)]
          {:kind :reload :success (true? ok)
           :report {:via :flutter-hot-reload :result ok}})
        (let [report (vm/reload-sources client iso-id)]
          {:kind :reload :success (boolean (:success report)) :report report})))

    :else
    (eval-expression client iso-id form ns-lib-uri await? await-timeout-ms remember?)))

;; host->device crossings that compile must bind the compiler dynamic vars; a bare `future` doesn't inherit them.

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
