(ns cljd.repl.eval
  "VM-Service REPL eval-core: route a form to `evaluate` (expressions) or
   `reloadSources` (new code), tying the compiler keystone (form->dart-expr /
   recompile-form) to the vmservice client. Runs inside the bootstrapped cljd.build
   compiler context. See BUILD-PLAN.md."
  (:require [cljd.compiler :as compiler]
            [cljd.repl.vmservice :as vm]))

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

(defn eval-form
  "Evaluate FORM against the running app and return a result map.

   Expression  -> {:kind :eval  :value <pr-str string> :ref <instanceRef>}  (or :error)
   New code    -> {:kind :reload :success <bool> :report <ReloadReport>}

   Requires a bootstrapped compiler context (nses + analyzer, as in cljd.build) and a
   connected vmservice CLIENT to ISO-ID. `ns-lib-uri` selects the evaluate scope
   (defaults to the cljd.user library)."
  [client iso-id form
   {:keys [recompile-count repltag ns-lib-uri trigger-reload reload-timeout-ms]
    :or   {recompile-count 0 repltag "repl" ns-lib-uri "cljd/user.dart"
           reload-timeout-ms 60000}}]
  (if (emits-new-toplevel? form)
    ;; --- new code: write the .dart into the app, then hot reload it in ---
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
    ;; --- expression: compile to a Dart IIFE and evaluate for a clean value ---
    (let [dart (compiler/form->dart-expr form)
          lib  (vm/library-id client iso-id ns-lib-uri)
          r    (vm/evaluate client iso-id lib dart)]
      (if (= (:type r) "@Error")
        {:kind :eval :error true :message (:message r) :ref r}
        {:kind :eval :value (:valueAsString r) :ref r}))))
