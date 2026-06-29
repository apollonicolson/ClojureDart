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
            [cljd.repl.eval :as repl-eval])
  (:import [java.io PushbackReader StringReader]
           [java.util UUID]))

(defn- read-forms [code]
  (compiler/with-cljd-reader
    (let [r (PushbackReader. (StringReader. code))]
      (loop [acc []]
        (let [f (compiler/read {:eof ::eof :read-cond :allow :features #{:cljd}} r)]
          (if (= f ::eof) acc (recur (conj acc f))))))))

(defn make-handler
  "cfg: {:client :iso-id :analyzer :dart-version :*current-ns :ns-lib-uri :trigger-reload}"
  [{:keys [client iso-id analyzer dart-version *current-ns ns-lib-uri trigger-reload]
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
          (try
            (doseq [form (read-forms code)]
              (let [r (repl-eval/eval-form client iso-id form
                                           {:ns-lib-uri ns-lib-uri :trigger-reload trigger-reload})]
                (case (:kind r)
                  :reload (do (when (= 'ns (and (seq? form) (first form)))
                                (reset! *current-ns (second form)))
                              (send! {:value (str "#reloaded " (pr-str (:report r))) :ns (name @*current-ns)}))
                  :eval   (if (:error r)
                            (send! {:err (str (:message r))})
                            (send! {:value (:value r) :ns (name @*current-ns)})))))
            (send! {:status ["done"]})
            (catch Throwable e
              (send! {:err (str (.getMessage e) " | " (pr-str (ex-data e)))
                      :ex (str (class e)) :status ["done" "error"]}))))
        (send! {:status ["done" "error" "unknown-op"]})))))

(defn start!
  "Start the nREPL server. Returns the nrepl server (has :port). Writes .nrepl-port."
  [{:keys [port] :or {port 0} :as cfg}]
  (let [server (nrepl-server/start-server :port port :handler (make-handler cfg))]
    (spit ".nrepl-port" (str (:port server)))
    server))
