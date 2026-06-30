(ns cljd.repl.nrepl
  "nREPL front for the VM-Service REPL. Editors (CIDER/Calva) and clojure-mcp connect
   here; each `eval` is classified and routed via cljd.repl.eval to the running app's
   isolate (expression -> VM-Service evaluate; def/new-code -> recompile + reloadSources).
   Runs inside the bootstrapped cljd.build process, so the compiler context is captured
   from there and re-bound per request (dynamic bindings don't cross threads)."
  (:require [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]
            [cljd.compiler :as compiler]
            [cljd.repl.vmservice :as vm]
            [cljd.repl.eval :as repl-eval]
            [cljd.repl.errors :as errors])
  (:import [java.io PushbackReader StringReader]
           [java.util UUID]))

(defn- read-forms [code]
  (compiler/with-cljd-reader
    (let [r (PushbackReader. (StringReader. code))]
      (loop [acc []]
        (let [f (compiler/read {:eof ::eof :read-cond :allow :features #{:cljd}} r)]
          (if (= f ::eof) acc (recur (conj acc f))))))))

(defn- ns->lib-uri
  "cljd library uri suffix for a namespace, e.g. kora.data.temporal -> kora/data/temporal.dart.
   `vm/library-id` matches by suffix, so the VM-Service `evaluate` runs in that ns's scope —
   its own defs and its required aliases resolve."
  [ns-sym]
  (str (.replace (name ns-sym) "." "/") ".dart"))

(defn- unwrap-quote [x]
  (if (and (seq? x) (= 'quote (first x))) (second x) x))

(defn- ns-exists? [ns-sym]
  (boolean (and (symbol? ns-sym) (get @compiler/nses ns-sym))))

(defn make-handler
  "cfg: {:client :iso-id :analyzer :dart-version :*current-ns :ns-lib-uri :trigger-reload :await?}"
  [{:keys [client iso-id analyzer dart-version *current-ns ns-lib-uri trigger-reload await?]
    :or {ns-lib-uri "cljd/core.dart"}}]
  (fn [{:keys [op transport id session code]}]
    (let [send! (fn [m] (transport/send transport (merge {:id id} (when session {:session session}) m)))]
      (case op
        "clone"       (transport/send transport {:id id :new-session (str (UUID/randomUUID)) :status ["done"]})
        "ls-sessions" (send! {:sessions [] :status ["done"]})
        "describe"    (send! {:ops (zipmap ["clone" "describe" "eval" "close" "ls-sessions" "interrupt"] (repeat {}))
                              :versions {:cljd {:major 0 :minor 1}} :status ["done"]})
        "interrupt"   (send! {:status ["done" "interrupted"]})
        "close"       (send! {:status ["done" "session-closed"]})
        "eval"
        (binding [compiler/*hosted* true
                  compiler/*dart-version* dart-version
                  compiler/analyzer-info analyzer
                  compiler/dynamic-warning compiler/on-dynamic-warn
                  compiler/*current-ns* @*current-ns]
          ;; forward the app's Stdout/Stderr WriteEvents to this eval's transport
          ;; while it runs (println output etc.), then detach the sink.
          (vm/set-sink! client (fn [stream text]
                                 ;; strip Flutter's own per-line "flutter: " stdout prefix
                                 (send! {(if (= stream "Stderr") :err :out)
                                         (.replaceAll text "(?m)^flutter: " "")})))
          (let [errored (volatile! false)
                switch-ns! (fn [ns-sym]            ; keep atom + per-batch dynamic binding in sync
                             (reset! *current-ns ns-sym)
                             (set! compiler/*current-ns* ns-sym))]
            (try
              (doseq [form (read-forms code)]
                (let [head (and (seq? form) (first form))]
                  (cond
                    ;; (in-ns 'x): switch the eval/compile context to an existing ns —
                    ;; no recompile; its defs + required aliases become resolvable.
                    (= 'in-ns head)
                    (let [target (unwrap-quote (second form))]
                      (if (ns-exists? target)
                        (do (switch-ns! target)
                            (send! {:value (str target) :ns (name target)}))
                        (do (vreset! errored true)
                            (send! {:err (str "No such namespace: " target
                                              " (only namespaces compiled into the app are available)")
                                    :ex "cljd.no-such-ns"}))))
                    :else
                    (let [r (repl-eval/eval-form client iso-id form
                                                 {:ns-lib-uri (ns->lib-uri @*current-ns)
                                                  :trigger-reload trigger-reload
                                                  :await? await?})]
                      (case (:kind r)
                        :reload (do (when (= 'ns head) (switch-ns! (second form)))
                                    (send! {:value (str "#reloaded " (pr-str (:report r))) :ns (name @*current-ns)}))
                        :eval   (if (:error r)
                                  (do (vreset! errored true)
                                      ;; runtime Dart exception: clean message + demunged user frames
                                      (send! {:err (errors/format-runtime (:message r))
                                              :ex "dart.runtime-exception"}))
                                  (send! {:value (:value r) :ns (name @*current-ns)})))))))
              (send! {:status (if @errored ["done" "error"] ["done"])})
              (catch Throwable e
                ;; compile-time error from turning the form into Dart
                (send! {:err (errors/format-compile e)
                        :ex (str (class e)) :status ["done" "error"]}))
              (finally (vm/set-sink! client nil)))))
        (send! {:status ["done" "error" "unknown-op"]})))))

(defn start!
  "Start the nREPL server. Returns the nrepl server (has :port). Writes .nrepl-port."
  [{:keys [port] :or {port 0} :as cfg}]
  (let [server (nrepl-server/start-server :port port :handler (make-handler cfg))]
    (spit ".nrepl-port" (str (:port server)))
    server))
