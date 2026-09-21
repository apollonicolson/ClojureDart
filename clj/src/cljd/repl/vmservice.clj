(ns cljd.repl.vmservice
  "Minimal Dart VM-Service client (JSON-RPC over a JDK WebSocket) for the cljd REPL."
  (:require [clojure.data.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.util.concurrent.atomic AtomicLong]))

(defn connect
  "Open a VM-Service websocket at WS-URI. Returns {:ws :pending :idgen :sink :event-sink};
   :sink / :event-sink are atoms holding optional handlers for stream output and Extension events."
  [ws-uri]
  (let [pending (atom {})
        idgen (AtomicLong. 0)
        sink (atom nil)
        event-sink (atom nil)
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
                      (get @pending (:id msg))
                      (let [p (get @pending (:id msg))]
                        (swap! pending dissoc (:id msg))
                        (deliver p msg))
                      (= "streamNotify" (:method msg))
                      (let [{:keys [streamId event]} (:params msg)]
                        (cond
                          (and (#{"Stdout" "Stderr"} streamId)
                               (= "WriteEvent" (:kind event))
                               (:bytes event))
                          (when-some [f @sink]
                            (f streamId (String. (.decode decoder ^String (:bytes event)) "UTF-8")))
                          (and (= "Extension" streamId) (= "Extension" (:kind event)))
                          (when-some [g @event-sink]
                            (g (:extensionKind event) (:extensionData event)))))))
                  (catch Exception _ nil))))
            (.request ws 1)
            nil)
          (onError [_ _ err] (binding [*out* *err*] (println "[vmservice]" (.getMessage err)))))]
    {:ws (-> (HttpClient/newHttpClient) .newWebSocketBuilder
             (.buildAsync (URI/create ws-uri) listener) .join)
     :pending pending :idgen idgen :sink sink :event-sink event-sink}))

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
  "Evaluate Dart EXPR in TARGET-ID's scope. Returns an @Instance or @Error response map;
   valueAsString is capped at 128 code units (see get-string-full)."
  [client iso-id target-id expr]
  (rpc client "evaluate" {:isolateId iso-id :targetId target-id :expression expr}))

(defn reload-sources
  "Hot-reload changed sources into the isolate. Returns a ReloadReport {:success …}."
  [client iso-id]
  (rpc client "reloadSources" {:isolateId iso-id}))

(defn get-object
  "Fetch a device object's full structure by id, without valueAsString truncation."
  [client iso-id obj-id]
  (rpc client "getObject" {:isolateId iso-id :objectId obj-id}))

(defn get-string-full
  "Full value of String OBJ-ID of LENGTH code units, paged via getObject offset/count."
  [client iso-id obj-id length]
  (if (or (nil? length) (<= length 0))
    ""
    (loop [off 0 sb (StringBuilder.)]
      (if (>= off length)
        (.toString sb)
        (let [r (rpc client "getObject"
                     {:isolateId iso-id :objectId obj-id :offset off :count (- length off)})
              s (:valueAsString r)]
          (if (or (nil? s) (zero? (count s)))
            (.toString sb)                    ; no progress: stop
            (recur (+ off (count s)) (doto sb (.append ^String s)))))))))

(defn call-ext
  "Call a registered Dart service extension METHOD; PARAMS values must be strings.
   Returns the extension's result parsed as JSON."
  [client iso-id method params]
  (rpc client method (merge {:isolateId iso-id} params)))

(defn set-event-sink!
  "Set (or clear) the Extension-event handler (fn [extension-kind data-map]) on CLIENT."
  [{:keys [event-sink]} f]
  (reset! event-sink f))

(defn listen-streams!
  "Subscribe to Stdout/Stderr/Extension streams; already-subscribed errors are ignored."
  [client]
  (doseq [stream ["Stdout" "Stderr" "Extension"]]
    (try (rpc client "streamListen" {:streamId stream})
         (catch Exception _ nil))))

(defn set-sink!
  "Set (or clear, with nil) the output sink fn (stream-id, text) on CLIENT."
  [{:keys [sink]} f]
  (reset! sink f))

(defn close [{:keys [^WebSocket ws]}]
  (.sendClose ws WebSocket/NORMAL_CLOSURE "bye"))
