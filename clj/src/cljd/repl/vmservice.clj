(ns cljd.repl.vmservice
  "Minimal Dart VM-Service client for the cljd REPL — JSON-RPC over a JDK WebSocket,
   no extra runtime. Used to `evaluate` expressions in, and `reloadSources` into, the
   running Flutter app's isolate. Connect to the ws:// URI flutter run prints
   (\"A Dart VM Service ... available at: http://…\" -> ws://…/ws)."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent.atomic AtomicLong]))

(defn connect
  "Open a VM-Service websocket. Returns a client map {:ws :pending :idgen :sink}.
   `:sink` is an atom holding an optional fn (stream-id, text) called for each
   Stdout/Stderr WriteEvent — used to forward app `println` output to the REPL."
  [ws-uri]
  (let [pending (atom {})                 ; request id -> promise
        idgen (AtomicLong. 0)
        sink (atom nil)                   ; optional (fn [stream-id text]) for stream output
        buf (StringBuilder.)
        decoder (java.util.Base64/getDecoder)
        listener
        (reify WebSocket$Listener
          (onOpen [_ ws] (.request ws 1))
          (onText [_ ws data last?]
            (.append buf (.toString ^CharSequence data))
            (when last?
              (let [s (.toString buf)]
                (.setLength buf 0)
                (try
                  (let [msg (json/read-str s :key-fn keyword)]
                    (cond
                      ;; response to a request
                      (get @pending (:id msg))
                      (let [p (get @pending (:id msg))]
                        (swap! pending dissoc (:id msg))
                        (deliver p msg))
                      ;; async stream notification (Stdout/Stderr WriteEvent)
                      (= "streamNotify" (:method msg))
                      (let [{:keys [streamId event]} (:params msg)]
                        (when (and (#{"Stdout" "Stderr"} streamId)
                                   (= "WriteEvent" (:kind event))
                                   (:bytes event))
                          (when-some [f @sink]
                            (f streamId (String. (.decode decoder ^String (:bytes event)) "UTF-8")))))))
                  (catch Exception _ nil))))   ; ignore unparsable frames
            (.request ws 1)
            nil)
          (onError [_ _ err] (binding [*out* *err*] (println "[vmservice]" (.getMessage err)))))]
    {:ws (-> (HttpClient/newHttpClient) .newWebSocketBuilder
             (.buildAsync (URI/create ws-uri) listener) .join)
     :pending pending :idgen idgen :sink sink}))

(defn rpc
  "Synchronous JSON-RPC call. Returns the :result map, or throws on error/timeout."
  [{:keys [ws pending idgen]} method params & {:keys [timeout-ms] :or {timeout-ms 30000}}]
  (let [id (str (.incrementAndGet ^AtomicLong idgen))
        p (promise)]
    (swap! pending assoc id p)
    (.sendText ^WebSocket ws (json/write-str {:jsonrpc "2.0" :id id :method method :params (or params {})}) true)
    (let [r (deref p timeout-ms ::timeout)]
      (when (= r ::timeout) (swap! pending dissoc id))
      (cond
        (= r ::timeout) (throw (ex-info "vmservice rpc timeout" {:method method}))
        (:error r) (throw (ex-info "vmservice rpc error" {:method method :error (:error r)}))
        :else (:result r)))))

(defn main-isolate-id [client]
  (->> (:isolates (rpc client "getVM" nil))
       (remove :isSystemIsolate) first :id))

(defn library-id
  "Library id whose uri ends with `uri-suffix` (e.g. \"cljd/user.dart\")."
  [client iso-id uri-suffix]
  (->> (:libraries (rpc client "getIsolate" {:isolateId iso-id}))
       (filter #(.endsWith ^String (:uri %) uri-suffix)) first :id))

(defn evaluate
  "Evaluate a Dart EXPRESSION string in `target-id`'s scope. Returns the response
   map: {:type \"@Instance\" :kind :valueAsString …} or {:type \"@Error\" …}."
  [client iso-id target-id expr]
  (rpc client "evaluate" {:isolateId iso-id :targetId target-id :expression expr}))

(defn reload-sources
  "Hot-reload changed sources into the isolate. Returns a ReloadReport {:success …}."
  [client iso-id]
  (rpc client "reloadSources" {:isolateId iso-id}))

(defn listen-streams!
  "Subscribe to the isolate's Stdout/Stderr streams so WriteEvents reach `:sink`.
   Flutter may already hold a subscription (error 103 \"Stream already subscribed\");
   that is harmless and ignored."
  [client]
  (doseq [stream ["Stdout" "Stderr"]]
    (try (rpc client "streamListen" {:streamId stream})
         (catch Exception _ nil))))   ; 103 already-subscribed, etc.

(defn set-sink!
  "Set (or clear, with nil) the output sink fn (stream-id, text) on CLIENT."
  [{:keys [sink]} f]
  (reset! sink f))

(defn close [{:keys [^WebSocket ws]}]
  (.sendClose ws WebSocket/NORMAL_CLOSURE "bye"))
