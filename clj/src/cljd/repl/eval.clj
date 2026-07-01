(ns cljd.repl.eval
  "VM-Service REPL eval-core: route a form to `evaluate` (expressions) or
   `reloadSources` (new code), tying the compiler keystone (form->dart-expr /
   recompile-form) to the vmservice client. Runs inside the bootstrapped cljd.build
   compiler context. See BUILD-PLAN.md."
  (:require [cljd.compiler :as compiler]
            [cljd.repl.vmservice :as vm]
            [clojure.walk :as walk]))

(def ^:private history-reads
  "*1/*2/*3 are ^:dynamic in cljd.core and their `set!` can't persist across separate
   `evaluate` calls (no shared binding frame). So history lives in plain holder vars and
   reads of *1/*2/*3 (and *env, the picked widget's scope map) are rewritten to them."
  '{*1   cljd.core/+cljd-repl-h1+
    *2   cljd.core/+cljd-repl-h2+
    *3   cljd.core/+cljd-repl-h3+
    *env cljd.core/+cljd-repl-env+})

(def ^:private toplevel-ops
  "Form heads (by name, ns-ignored) that introduce/change top-level program code and
   therefore need a hot reload rather than an expression `evaluate`."
  '#{def defn defn- defmacro deftype defrecord defprotocol defmulti defmethod
     definterface definline declare ns require use import refer in-ns
     extend-type extend-protocol extend gen-class def-
     deftype* defprotocol* defmulti* defmethod* defcontrib* extend-type-protocol*})

(defn emits-new-toplevel?
  "True if FORM (assumed macroexpanded) introduces top-level code → reload path.
   `do` is a top-level splice; recurse. Plain expressions → false → evaluate path."
  [form]
  (boolean
   (and (seq? form)
        (let [op (first form)]
          (cond
            (= op 'do)   (some emits-new-toplevel? (rest form))
            (symbol? op) (contains? toplevel-ops (symbol (name op)))
            :else        false)))))

(defn- poll-future
  "Poll cljd.core/+cljd-repl-fbox+ until the scheduled Future resolved (the box holds
   the pr-str'd value, or \"__CLJD_ERR__ …\"), or timeout. Returns an :eval result."
  [client iso-id lib timeout-ms]
  (let [deref-dart (compiler/form->dart-expr '(cljd.core/deref cljd.core/+cljd-repl-fbox+) false)
        deadline   (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [r (vm/evaluate client iso-id lib deref-dart)
            v (:valueAsString r)]
        (cond
          (= (:type r) "@Error")    {:kind :eval :error true :message (:message r) :ref r}
          (or (nil? v) (= v "null"))
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep 100) (recur))
            {:kind :eval :error true :message "await timed out (Future did not complete)"})
          (.startsWith ^String v "__CLJD_ERR__")
          {:kind :eval :error true :message (subs v (min (count v) (count "__CLJD_ERR__ ")))}
          :else {:kind :eval :value v :ref r})))))

(defn- eval-expression
  "Compile EXPR to a Dart IIFE and `evaluate` it for a clean value. With await?, a
   Future result is scheduled into the box and polled to its resolved value. With
   remember? (sync path only), the result is threaded through cljd.core's injected
   `+cljd-repl-remember` so it lands in *1/*2/*3 on-device."
  [client iso-id expr ns-lib-uri await? await-timeout-ms remember?]
  (let [expr (if remember? (walk/postwalk-replace history-reads expr) expr)
        expr (if (and remember? (not await?))
               (list 'cljd.core/+cljd-repl-remember expr)
               expr)
        dart (if await? (compiler/form->dart-await-expr expr) (compiler/form->dart-expr expr))
        lib  (vm/library-id client iso-id ns-lib-uri)
        r    (vm/evaluate client iso-id lib dart)]
    (cond
      (= (:type r) "@Error")
      {:kind :eval :error true :message (:message r) :ref r}
      (and await? (= "__cljd_future_pending__" (:valueAsString r)))
      (poll-future client iso-id lib await-timeout-ms)
      :else
      {:kind :eval :value (:valueAsString r) :ref r})))

(defn- existing-def?
  "True if NAME already resolves to a global var (a :def) in the current ns."
  [name]
  (boolean (try (= :def (first (compiler/resolve-symbol name {}))) (catch Throwable _ false))))

(defn eval-form
  "Evaluate FORM against the running app and return a result map.

   Expression  -> {:kind :eval  :value <pr-str string> :ref <instanceRef>}  (or :error)
   New code    -> {:kind :reload :success <bool> :report <ReloadReport>}

   Requires a bootstrapped compiler context (nses + analyzer, as in cljd.build) and a
   connected vmservice CLIENT to ISO-ID. `ns-lib-uri` selects the evaluate scope
   (defaults to the cljd.user library)."
  [client iso-id form
   {:keys [recompile-count repltag ns-lib-uri trigger-reload reload-timeout-ms
           await? await-timeout-ms remember?]
    :or   {recompile-count 0 repltag "repl" ns-lib-uri "cljd/user.dart"
           reload-timeout-ms 60000 await-timeout-ms 30000}}]
  (cond
    ;; --- redefinition of an existing value-var: instant set!, NO reload ---
    ;; cljd vars are mutable `name$vN` statics; (set! name init) assigns the backing
    ;; static directly. A plain (def name newval) reload wouldn't take — Flutter hot
    ;; reload doesn't re-run a static initializer. New vars fall through to reload.
    (and (seq? form) (= 'def (first form)) (= 3 (count form)) (existing-def? (second form)))
    (eval-expression client iso-id (list 'set! (second form) (nth form 2))
                     ns-lib-uri await? await-timeout-ms remember?)

    ;; --- new code: write the .dart into the app, then hot reload it in ---
    (emits-new-toplevel? form)
    (do
      (compiler/recompile-form form recompile-count repltag)
      (if trigger-reload
        ;; Drive Flutter's own hot reload ("r" -> frontend-server recompiles
        ;; dart->kernel, THEN reloadSources). Raw VM-Service `reloadSources` alone
        ;; fails for Flutter ("Error while starting Kernel isolate task") because the
        ;; new .dart hasn't been compiled to kernel. `trigger-reload` takes a promise
        ;; the build's reload daemon delivers true/false on completion.
        (let [done (promise)
              _    (trigger-reload done)
              ok   (deref done reload-timeout-ms ::timeout)]
          {:kind :reload :success (true? ok)
           :report {:via :flutter-hot-reload :result ok}})
        ;; fallback (no build daemon wired in): raw reloadSources
        (let [report (vm/reload-sources client iso-id)]
          {:kind :reload :success (boolean (:success report)) :report report})))

    ;; --- expression ---
    :else
    (eval-expression client iso-id form ns-lib-uri await? await-timeout-ms remember?)))
