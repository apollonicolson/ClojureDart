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
    *e   cljd.core/+cljd-repl-e+
    *env cljd.core/+cljd-repl-env+})

(defn- capturing-e
  "Wrap EXPR so a thrown Dart error is bound to *e (the device holder) before rethrowing — the
   host still gets the @Error to report, and (println *e)/(ex-message *e) work like upstream. The
   exception OBJECT lives on the device at the catch site; this catches it before it's lost."
  [expr]
  (list 'try expr
    (list 'catch 'dynamic 'e '_st
      (list 'do (list 'set! 'cljd.core/+cljd-repl-e+ 'e) (list 'throw 'e)))))

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
          (= (:type r) "@Error")    {:kind :eval :error true :message (:message r) :dart-kind (:kind r) :ref r}
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
        ;; capture a thrown error into *e on BOTH the sync and await paths (await? is on by default,
        ;; so gating on the sync path alone would never fire). Rethrows so the host still reports it.
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
      ;; evaluate caps a String's valueAsString at 128 code units (:valueAsStringIsTruncated);
      ;; the REPL pr-strs results to Strings, so page the full value back via getObject.
      {:kind :eval
       :value (if (and (= "String" (:kind r)) (:valueAsStringIsTruncated r))
                (vm/get-string-full client iso-id (:id r) (:length r))
                (:valueAsString r))
       :ref r})))

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

;; ── The host↔device boundary ─────────────────────────────────────────────────
;; Everything that crosses from the host JVM compiler into the device Dart VM goes
;; through here, so no caller has to reason about the boundary. TWO things must be
;; true at every crossing that COMPILES a form: (1) the compiler's dynamic vars are
;; bound — form->dart / recompile-form read them, and a bare `future` does NOT inherit
;; them (the recurring silent bug); (2) the transport coordinates (client, iso-id) are
;; supplied. `context` bundles both once; the crossings are:
;;   • (eval! ctx form opts) — a self-contained device eval, safe from ANY thread.
;;   • (with-compiler-context ctx ns & body) — the binding frame, for the ONE case that
;;     changes *current-ns* across several forms (the multi-form eval batch).
;;   • (call! ctx method params) — a structured service-extension call (no compilation).

(defn context
  "Bundle the boundary coordinates once (from the nREPL cfg): the vmservice CLIENT +
   ISO-ID, the ANALYZER + DART-VERSION the compiler needs, the default evaluate scope
   (NS-LIB-URI) and the default compile ns (DEFAULT-NS). Pass to eval! / call! /
   with-compiler-context — nothing else should touch client/iso-id or the binding block."
  [{:keys [client iso-id *iso analyzer dart-version ns-lib-uri default-ns]
    :or {ns-lib-uri "cljd/core.dart" default-ns 'cljd.core}}]
  ;; *iso holds the CURRENT main isolate id in an atom (refreshed after a hot restart, which
  ;; spins a new isolate). eval!/call! deref it, so the boundary survives a restart. Callers
  ;; that only have a fixed iso-id still work — we box it.
  {:client client :*iso (or *iso (atom iso-id)) :analyzer analyzer :dart-version dart-version
   :ns-lib-uri ns-lib-uri :default-ns default-ns})

(defmacro with-compiler-context
  "Establish the compiler dynamic bindings from CTX (current-ns = NS-SYM) around BODY —
   THE single definition of that binding block, safe from any thread/future. Inside,
   `(set! cljd.compiler/*current-ns* …)` persists for the rest of BODY (a real binding
   frame), which is why the multi-form eval batch wraps itself in one of these."
  [ctx ns-sym & body]
  `(let [c# ~ctx]
     (binding [compiler/*hosted* true
               compiler/*dart-version* (:dart-version c#)
               compiler/analyzer-info (:analyzer c#)
               compiler/dynamic-warning compiler/on-dynamic-warn
               compiler/*current-ns* ~ns-sym]
       ~@body)))

(defn eval!
  "THE host→device eval. Self-contained: establishes the compiler context (so it is safe
   from any thread, including a bare future) and threads CTX's transport coordinates — the
   caller passes only FORM (+ opts). opts :ns sets the compile-time current-ns (default
   ctx's); the usual eval-form opts (:ns-lib-uri, :await?, :remember?, :trigger-reload …)
   pass through, defaulting :ns-lib-uri to ctx's."
  ([ctx form] (eval! ctx form nil))
  ([ctx form {:keys [ns] :as opts}]
   (with-compiler-context ctx (or ns (:default-ns ctx))
     (eval-form (:client ctx) @(:*iso ctx) form
                (merge {:ns-lib-uri (:ns-lib-uri ctx)} (dissoc opts :ns))))))

(defn call!
  "A device service-extension call (structured, compilation-free); coordinates from CTX."
  [ctx method params]
  (vm/call-ext (:client ctx) @(:*iso ctx) method params))
