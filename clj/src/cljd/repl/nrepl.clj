(ns cljd.repl.nrepl
  "nREPL front for the VM-Service REPL. Editors (CIDER/Calva) and clojure-mcp connect
   here; each `eval` is classified and routed via cljd.repl.eval to the running app's
   isolate (expression -> VM-Service evaluate; def/new-code -> recompile + reloadSources).
   Runs inside the bootstrapped cljd.build process, so the compiler context is captured
   from there and re-bound per request (dynamic bindings don't cross threads)."
  (:require [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]
            [clojure.string :as str]
            [cljd.compiler :as compiler]
            [cljd.repl.vmservice :as vm]
            [cljd.repl.eval :as repl-eval]
            [cljd.repl.errors :as errors]
            [cljd.repl.dartlsp :as dartlsp])
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

(defn- sym-info
  "Look up SYM (maybe ns-qualified) in @nses relative to CUR-NS. A def's info is stored
   at [ns sym] with :meta carrying :doc/:arglists/:macro (compiler/do-def). Falls back to
   cljd.core. Returns {:ns :name :arglists :doc :macro?} or nil."
  [nses cur-ns sym]
  (let [ns'  (if-let [n (namespace sym)] (symbol n) cur-ns)
        nm   (symbol (name sym))
        info (or (get-in nses [ns' nm]) (get-in nses ['cljd.core nm]))
        m    (:meta info)
        ;; :arglists is stored as the quoted form '(...); unwrap to the raw list of vectors.
        al   (let [a (:arglists m)] (if (and (seq? a) (= 'quote (first a))) (second a) a))]
    (when info
      {:ns (name (:ns info)) :name (name (:name info))
       :arglists al :doc (:doc m) :macro? (boolean (:macro m))})))

(defn flatten-smap
  "The compiler's lib smap is two-level: outer = per-def regions
   [dart-line _ {:slug :smap :str}], inner = positions within a def
   [rel-line _ {:file :line :column}] — but the inner interleaves REAL cljd positions
   with 1:1 'untracked glue' markers. Flatten to one vector, sorted by absolute Dart line,
   of [abs-dart-line {:file :line :column}] keeping only REAL positions (line>1). Then any
   Dart line maps coherently to the nearest preceding real cljd form — no 1:1 holes."
  [lib-smap]
  (->> lib-smap
       (mapcat (fn [[reg-line _ {:keys [smap]}]]
                 (keep (fn [[rel-line _ info]]
                         (when (and info (> (or (:line info) 0) 1))
                           [(+ reg-line (dec rel-line)) info]))
                       smap)))
       (sort-by first)
       vec))

(defn dart-line->cljd
  "Nearest preceding real cljd position for a Dart line, over a flattened smap. Coherent:
   defined for ANY Dart line that has any tracked form before it in the file."
  [flat dart-line]
  (some (fn [[dl info]] (when (<= dl dart-line) info))
        (reverse flat)))

(defn find-lib
  "The lib entry whose key ends with the dart path (e.g. 'kora/nav.dart')."
  [libs ^String path]
  (some (fn [[k v]] (let [ks (str k)] (when (or (= ks path) (.endsWith ks (str "/" path))) v))) libs))

(defn- short-cljd-path
  "Normalize a source-map :file to a consistent project-relative form: strip any file:// scheme
   and trim to after the last '/src/'. So 'file:///…/ClojureDart/clj/src/cljd/flutter.cljd' and
   the already-relative 'kora/nav.cljd' both render short ('cljd/flutter.cljd', 'kora/nav.cljd').
   The compiler stores :file inconsistently — app files relative, dep/ClojureDart files as URIs —
   so device wloc (short) and resolved cljd (was raw :file) matched only for app picks."
  [^String file]
  (let [f (if (.startsWith file "file://") (subs file 7) file)
        i (.lastIndexOf f "/src/")]
    (if (neg? i) f (subs f (+ i 5)))))

(defn resolve-wloc
  "A device pick's Dart wloc 'kora/nav.dart:249' -> its .cljd source 'kora/nav.cljd:78:12'
   via the host source map. Coherent — any Dart line resolves to the nearest preceding real
   cljd form (no 1:1 holes); nil only when the lib/smap is missing entirely."
  [libs ^String src]
  (when (and src (seq libs))
    (let [ci (.lastIndexOf src ":")]
      (when (pos? ci)
        (let [path (subs src 0 ci)
              line (try (Long/parseLong (subs src (inc ci))) (catch Throwable _ nil))
              entry (find-lib libs path)]
          (when (and line (:smap entry))
            (when-some [info (dart-line->cljd (flatten-smap (:smap entry)) line)]
              (str (short-cljd-path (:file info)) ":" (:line info)
                   (when (:column info) (str ":" (:column info)))))))))))

(defn- resolve-and-push!
  "Resolve a device pick's Dart wloc + its ancestor wlocs (the tree) to .cljd and push them back
   via ext.cljd.set-cljd (matches the pick by src; :tree is the ancestors' cljd locs, \\n-joined,
   index-aligned). Pure data over the channel — no `evaluate`, no compilation, so it's safe on the
   event future (which lacks the compiler's dynamic bindings). *env focus is done separately."
  [ctx data]
  (let [libs (:libs @compiler/nses)
        src  (:src data)
        ;; ALWAYS push a result so the device can distinguish pending from resolved: a cljd loc
        ;; for cljd source, or "" to confirm non-cljd Dart (show the Dart wloc, no flash).
        cljd (resolve-wloc libs src)
        tree (apply str (interpose "\n" (map #(or (resolve-wloc libs %) "") (:ancestors data))))]
    (repl-eval/call! ctx "ext.cljd.set-cljd" {:src src :cljd (or cljd "") :tree tree})))

;; Load the ACTIVE (last) pick's LIVE env into the device *env holder. `pick-env` re-reads the
;; scope from the retained ReplState, so value locals are current (not frozen at pick time).
;; Compiles a set!, so evaluating it needs the compiler's dynamic bindings. Shared by (picked)
;; and the on-device-pick auto-focus so there's ONE definition of "focus *env on the pick".
(def ^:private focus-active-env-form
  '(set! cljd.core/+cljd-repl-env+
         (cljd.flutter/pick-env (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+)))))

;; ── Coverage (execution visibility, no instrumentation) ──────────────────────
;; getSourceReport(Coverage, reportLines) → which Dart lines executed, mapped through the source map
;; → which cljd forms ran. MUST be called PER-SCRIPT: a whole-isolate Coverage call (libraryFilters,
;; no scriptId) OOMs/crashes the on-device app (measured 2026-07-03). Per-script is bounded + safe.
(defn- dart-uri->cljd
  "package:pkg/cljd-out/kora/nav.dart:42 → cljd loc via the source map, or nil."
  [libs uri-line]
  (let [marker "cljd-out/" i (.indexOf ^String uri-line marker)]
    (when (>= i 0) (resolve-wloc libs (subs uri-line (+ i (count marker)))))))

(defn- cljd-coverage
  "Set of 'package-uri:line' that executed, aggregated over our scripts one getSourceReport at a time."
  [client iso-id]
  (let [scripts (->> (:scripts (try (vm/rpc client "getScripts" {:isolateId iso-id}) (catch Throwable _ nil)))
                     (filter (fn [s] (.contains ^String (str (:uri s)) "cljd-out/kora"))))]
    (into #{}
      (for [s scripts
            :let [rep (try (vm/rpc client "getSourceReport"
                             {:isolateId iso-id :reports ["Coverage"] :reportLines true :scriptId (:id s)})
                           (catch Throwable _ nil))
                  uri (str (:uri s))]
            rng (:ranges rep)
            line (get-in rng [:coverage :hits])]
        (str uri ":" line)))))

;; ── Value-flow tracing: instrument a form (host-side rewrite) ─────────────────
;; (trace 'form) macroexpands, then wraps each plain fn-call in (record! coord …), so the device
;; streams every intermediate value with its form-tree coordinate — FlowStorm-style, no emit change.
;; v0: instruments fn-calls; special forms + interop pass through un-descended (the skip-list is the
;; correctness surface — wrapping a non-value position would break the form). Widens in later passes.
(def ^:private +trace-special+
  '#{quote fn fn* let let* loop loop* letfn letfn* if do def deftype deftype* defprotocol defprotocol*
     reify reify* try catch finally throw new set! . .. var recur case case* monitor-enter monitor-exit
     ns in-ns dart:async dart})
(defn- trace-member? [h] (and (symbol? h) (.startsWith (name h) ".")))
;; v1: fn-calls + the value positions of let*/if/do are instrumented; the other
;; special forms + interop still pass through (they don't have simple value-flow
;; sub-positions, or wrapping them would break the form). Binding SYMBOLS,
;; recur/set! targets, quote/fn bodies etc. are never wrapped — that skip-list is
;; the correctness surface.
(defn- record-at [coord x]
  (list 'cljd.flutter/record! (apply str (interpose "," coord)) x))
(defn- instrument
  ([form] (instrument [] form))
  ([coord form]
   (let [f (try (compiler/macroexpand {} form) (catch Throwable _ form))
         top (fn [x] (if (empty? coord) (record-at [] x) x))]
     (cond
       ;; leaf (not a symbol-headed call): record whole result only at the top
       (not (and (seq? f) (seq f) (symbol? (first f))))
       (top f)

       ;; let/let*/loop/loop*: instrument each binding VALUE (keep the symbol/pattern)
       ;; + body exprs. Handle both raw and macroexpanded heads — macroexpand may
       ;; not fire outside a full compile context.
       (contains? '#{let let* loop loop*} (first f))
       (let [[op bindings & body] f
             bindings' (vec (mapcat (fn [i [sym val]]
                                      [sym (instrument (conj coord (str "let" i)) val)])
                              (range) (partition 2 bindings)))
             body' (map-indexed (fn [i e] (instrument (conj coord (str "b" i)) e)) body)]
         (top (list* op bindings' body')))

       ;; if: test + both branches are value positions
       (= 'if (first f))
       (let [[_ test then else] f]
         (top (list 'if
                (instrument (conj coord "if?") test)
                (instrument (conj coord "then") then)
                (if (> (count f) 3) (instrument (conj coord "else") else) else))))

       ;; do: every form is a value position (last is the result)
       (= 'do (first f))
       (top (list* 'do (map-indexed (fn [i e] (instrument (conj coord (str "do" i)) e)) (rest f))))

       ;; other special form / interop: pass through, record the whole result at top
       (or (contains? +trace-special+ (first f)) (trace-member? (first f)))
       (top f)

       ;; plain fn-call → record its result + recurse into value-position args
       :else
       (record-at coord
         (cons (first f)
           (map-indexed (fn [i a] (instrument (conj coord (inc i)) a)) (rest f))))))))

(defn- tx-snapshot
  "Collapse an ordered transaction seq into the state map {id → value} by merging each tx's :delta,
   later txs winning per id. The basis for replay and time-travel: (seek! n) snapshots a prefix,
   (replay!)/boot snapshots the whole log."
  [txs]
  (reduce (fn [m tx] (merge m (:delta tx))) {} txs))

(defn- epoch-indices
  "The epoch cut-points, DERIVED from the tx-log: each keyframe tx carries :cause :epoch, so the
   markers ARE the log — no parallel index atom to keep in sync. (epoch!) appends such a tx; this
   reads the positions back out."
  [tx-log]
  (vec (keep-indexed (fn [i tx] (when (= :epoch (:cause tx)) i)) tx-log)))

(defn- errors-view
  "The error channel, DERIVED from the unified timeline: the :error entries' data (already coalesced
   at intake — consecutive same phase+message carry a :count). One store; (errors) is a read over it."
  [timeline]
  (mapv :data (filter #(= :error (:kind %)) timeline)))

;; ── Edit-back: pick → source form → surgical value splice ─────────────────────
;; Not a new subsystem — it extends the pick's existing app→source arrow one hop to WRITE.
;; The pick already resolves a widget to its .cljd file:line:col (resolve-wloc); edit-back reads
;; that form, finds a named property's value, and splices a new value into the FILE at the value's
;; exact reader-tracked span. Reuses the compiler reader for positions; the watcher recompiles +
;; hot-reloads on save. Spiked end-to-end 2026-07-04.

(defn- read-source-forms
  "Top-level forms of FILE, each collection form carrying :line/:column/:end-* meta (from a
   line-numbering reader — the compiler's own reader, so cljd syntax reads clean)."
  [^String file]
  (with-open [r (clojure.lang.LineNumberingPushbackReader. (java.io.FileReader. file))]
    (compiler/with-cljd-reader
      (loop [acc []]
        (let [f (compiler/read {:eof ::eof :read-cond :allow :features #{:cljd}} r)]
          (if (= f ::eof) acc (recur (conj acc f))))))))

(defn- line-start-offset
  "0-based char offset of the start of 1-based LINE in TEXT."
  [^String text line]
  (loop [off 0 ln 1]
    (if (>= ln line) off
      (let [nl (.indexOf text (int \newline) off)]
        (if (neg? nl) off (recur (inc nl) (inc ln)))))))

(defn- line-column->offset [^String text line col] (+ (line-start-offset text line) (dec col)))

(defn- loc-contains? [m line col]
  (and (:line m)
       (or (< (:line m) line) (and (= (:line m) line) (<= (:column m) col)))
       (or (> (:end-line m) line) (and (= (:end-line m) line) (>= (:end-column m) col)))))

(defn- form-at
  "Innermost collection form in FORMS whose span contains LINE:COL — the picked widget form."
  [forms line col]
  (->> (mapcat #(tree-seq coll? seq %) forms)
       (filter #(and (coll? %) (loc-contains? (meta %) line col)))
       (sort-by #(let [m (meta %)] [(- (:line m)) (- (:column m))]))
       first))

(defn- child-locs
  "Each direct child of the form in FORM-TEXT as {:form :start [line column] :end [line column]},
   read from the reader's OWN position — so bare literals (numbers/strings/keywords, not IMeta,
   which carry no :line meta) are located too."
  [^String form-text]
  (with-open [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. form-text))]
    (compiler/with-cljd-reader
      (.read r)                                    ; consume the opening delimiter
      (loop [acc []]
        (let [start-line (.getLineNumber r) start-column (.getColumnNumber r)
              form (try (compiler/read {:eof ::eof :read-cond :allow :features #{:cljd}} r)
                        (catch Exception _ ::eof))]
          (if (= form ::eof) acc
            (recur (conj acc {:form form :start [start-line start-column]
                              :end [(.getLineNumber r) (.getColumnNumber r)]}))))))))

(defn- prop-value-offsets
  "Abs [start end) char offsets in TEXT of the value following PROP in FORM (a widget form carrying
   :line/:column meta). Leading whitespace trimmed off the value. nil if PROP is absent."
  [^String text form prop]
  (let [m (meta form)
        fstart (line-column->offset text (:line m) (:column m))
        ftext  (subs text fstart (line-column->offset text (:end-line m) (:end-column m)))
        rel->abs (fn [[l c]] (+ fstart (line-column->offset ftext l c)))]
    (some (fn [[a b]]
            (when (= (:form a) prop)
              (let [s (rel->abs (:start b)) e (rel->abs (:end b))
                    lead (count (take-while #(Character/isWhitespace ^char %) (subs text s e)))]
                [(+ s lead) e])))
      (partition 2 1 (child-locs ftext)))))

(defn- resolve-source-file
  "The cljd loc's relative path (e.g. \"kora/nav.cljd\") → an absolute source file under SOURCE-DIRS."
  [source-dirs cljd-path]
  (some (fn [d] (let [f (java.io.File. (str d) ^String cljd-path)]
                  (when (.exists f) (.getPath f))))
    source-dirs))

;; ── clojure.repl parity: doc / source / dir / apropos / find-doc ──────────────
;; The compiler's @nses holds every def's :meta (:doc, :arglists, :macro) + provenance, so the
;; standard Clojure discovery toolkit is a pure host read — same ergonomics as upstream. `source`
;; reuses the edit-back reader (ns → file → the def form's text).

(def ^:private def-heads '#{def defn defn- defmacro defmulti defonce deftype defrecord defprotocol})

(defn- ns-def-names
  "Sorted names of the PUBLIC defs in NS-SYM — like clojure.repl/dir, excluding the compiler's
   generated internals (gensym/arity-munged names carry `__` or `$`, which user defs never do)."
  [nses ns-sym]
  (->> (get nses ns-sym) keys (filter symbol?) (map name)
       (remove #(re-find #"__|\$" %)) sort))

(defn- format-doc
  "clojure.repl/doc-style rendering of a sym-info map."
  [i]
  (when i
    (str "-------------------------\n"
         (:ns i) "/" (:name i) "\n"
         (when (:arglists i) (str (pr-str (:arglists i)) "\n"))
         (when (:macro? i) "Macro\n")
         "  " (or (:doc i) "(not documented)"))))

(defn- def-source
  "The source text of the top-level (def/defn/… NAME …) form in FILE, or nil."
  [file name-sym]
  (let [text (slurp file)]
    (some (fn [f]
            (when (and (seq? f) (>= (count f) 2) (contains? def-heads (first f)) (= (second f) name-sym))
              (let [m (meta f)]
                (subs text (line-column->offset text (:line m) (:column m))
                           (line-column->offset text (:end-line m) (:end-column m))))))
      (read-source-forms file))))

(defn- def-line
  "1-based line of the top-level (def/defn/… NAME …) form in FILE, or nil — for go-to-def.
   nses doesn't persist source location (compiler/do-def drops it), so the reader's form
   metadata off the file is the source of truth. Same finder as def-source."
  [file name-sym]
  (some (fn [f]
          (when (and (seq? f) (>= (count f) 2) (contains? def-heads (first f)) (= (second f) name-sym))
            (:line (meta f))))
        (read-source-forms file)))


;; ── Durable introspection state + the live connection cell ────────────────────
;; These are top-level `defonce` so `(require 'cljd.repl.nrepl :reload)` swaps the CODE below
;; (helpers, sinks, request dispatch — all reached through #'vars) while PRESERVING this state and
;; the live VM-Service connection. The running nREPL server holds only a trampoline into #'handle-request,
;; so a reload updates every op without a ~60s app relaunch (the compiler/nses-is-defonce trick, applied
;; to the socket handler). +state+ holds the per-connection context, populated by make-handler.
(defonce ^:private +cljd-tx-log+ (atom []))         ; the transaction log (seek/replay/epochs); :cause :epoch keyframes
(defonce ^:private +cljd-recording?+ (atom false))  ; recording armed? — gates cross-restart auto-replay
(defonce ^:private +cljd-coverage-snap+ (atom #{}))  ; last coverage snapshot — (ran) diffs against it
(defonce ^:private +cljd-timeline+ (atom []))       ; the UNIFIED timeline (tap/log/error/tx/trace), tagged :kind
(defonce ^:private +state+ (atom nil))              ; {:client :*iso :ctx :*eval-sink :*current-ns + cfg flags}

;; record-tl!: clock + append one entry onto the unified timeline (bounded ring).
(defn- record-tl! [kind data]
  (swap! +cljd-timeline+
    (fn [tl] (vec (take-last 500 (conj tl {:kind kind :t (System/currentTimeMillis) :data data}))))))

;; remember-error!: the ONE error intake (device-pushed AND eval-path) → a :kind :error timeline entry.
;; Coalesces a consecutive same phase+message into the previous entry's :count (so a reassemble burst of
;; one assertion doesn't flood). Returns the NEW error (live-forward) or nil when it coalesced a dup.
(defn- remember-error! [e]
  (let [prev (peek @+cljd-timeline+)
        dupe? (and (= :error (:kind prev))
                   (= (:phase (:data prev)) (:phase e))
                   (= (:message (:data prev)) (:message e)))]
    (if dupe?
      (do (swap! +cljd-timeline+
            (fn [tl] (conj (pop tl) (update (peek tl) :data update :count (fnil inc 1)))))
          nil)
      (do (record-tl! :error (assoc e :count 1))
          e))))

;; write-snap!: push a {id → value} snapshot to the device's live atoms (write-state), return how many
;; landed. The one device-write shared by replay / seek / restore-epoch. Reads the live connection from +state+.
(defn- write-snap! [snap]
  (let [{:keys [client *iso]} @+state+]
    (try (:value (repl-eval/eval-form client @*iso
                   (list 'cljd.flutter/write-state snap)
                   {:ns-lib-uri "cljd/flutter.dart"}))
         (catch Throwable _ nil))))

;; replay!: re-apply the whole recorded tx-log (last value wins per id) onto the device's live atoms.
(defn- replay! []
  (let [snap (tx-snapshot @+cljd-tx-log+)]
    (when (seq snap) {:atoms (count snap) :wrote (write-snap! snap)})))

;; refresh-iso!: re-resolve the CURRENT main isolate id after a hot restart spun a new one.
(defn- refresh-iso! []
  (let [{:keys [client *iso]} @+state+]
    (when-some [i (try (vm/main-isolate-id client) (catch Throwable _ nil))]
      (reset! *iso i))))

;; replay-settle!: the boot-time replay (fired on cljd.booted). Runs on the WS-listener future, which
;; lacks compiler bindings, so it establishes the context itself; retries until every recorded-and-live
;; id matches (startup pumps frames), then re-arms device recording.
(defn- replay-settle! []
  (let [{:keys [ctx *current-ns client *iso]} @+state+]
    (repl-eval/with-compiler-context ctx @*current-ns
      (let [snap (tx-snapshot @+cljd-tx-log+)
            read-live #(try (read-string (:value (repl-eval/eval-form client @*iso
                                                   '(cljd.flutter/read-state)
                                                   {:ns-lib-uri "cljd/flutter.dart" :await? true})))
                            (catch Throwable _ nil))
            landed? (fn [live] (and live (every? (fn [[id v]]
                                                   (or (not (contains? live id)) (= v (get live id))))
                                                 snap)))
            result (loop [tries 0]
                     (Thread/sleep (min 800 (+ 250 (* tries 200))))
                     (replay!)
                     (let [live (read-live)]
                       (if (or (landed? live) (>= tries 6))
                         {:tries tries :ok (boolean (landed? live))}
                         (recur (inc tries)))))]
        (record-tl! :log (merge {:cljd/replayed (count snap)} result))
        (try (repl-eval/eval-form client @*iso '(cljd.flutter/arm-recording! true)
               {:ns-lib-uri "cljd/flutter.dart"})
             (catch Throwable _ nil))
        result))))

;; stdout-sink: forward device REPL output to the active eval's transport.
(defn- stdout-sink [state stream text]
  (when-some [f @(:*eval-sink state)] (f stream text)))

;; event-sink: ONE structured-event intake off the Extension stream (cljd.pick / error / tx / tap /
;; log / trace / booted). Runs off the WS listener thread (futures — a sync rpc there would deadlock).
(defn event-sink [state kind data]
  (let [{:keys [ctx *eval-sink]} state]
       (cond
         (= kind "cljd.pick")
         (future
           (try
             (resolve-and-push! ctx data)                        ; resolve main + tree, compilation-free
             ;; focus *env on the just-picked widget so an ON-DEVICE pick is immediately usable in
             ;; the REPL (parity with (picked)). eval! carries the compiler context itself, so this
             ;; is safe on the bare event future — no hand-written binding block to forget.
             (repl-eval/eval! ctx focus-active-env-form
               {:ns 'cljd.flutter :ns-lib-uri "cljd/flutter.dart"})
             (catch Throwable _ nil)))

         (= kind "cljd.error")
         (when-some [e (remember-error! {:phase (or (:phase data) "runtime")
                                         :message (:message data) :stack (:stack data)})]
           ;; remember-error! already logged it; only forward NEW errors (nil = coalesced duplicate)
           (when-some [f @*eval-sink]
             (f "Stderr" (str "⚠ [" (:phase e) "] " (:message e)
                              (when (seq (:stack e)) (str "\n" (:stack e))) "\n"))))

         ;; a frame's batched changes (recording on): parse the EDN delta {id→value} + cause → append
         ;; ONE transaction to the log (indexed by position) + the timeline.
         (= kind "cljd.tx")
         (let [delta (try (read-string (:delta data)) (catch Throwable _ nil))
               cause (try (read-string (:cause data)) (catch Throwable _ nil))]
           (when (and (map? delta) (seq delta))
             (let [tx {:tx (count @+cljd-tx-log+) :t (System/currentTimeMillis) :delta delta :cause cause}]
               (swap! +cljd-tx-log+ conj tx)
               (record-tl! :tx tx))))

         ;; Clojure-native observability: (tap> x) and (log! m) on the device → the unified timeline.
         (= kind "cljd.tap")
         (let [v (try (read-string (:edn data)) (catch Throwable _ ::unreadable))]
           (when (not= v ::unreadable) (record-tl! :tap v)))

         (= kind "cljd.log")
         (let [v (try (read-string (:edn data)) (catch Throwable _ ::unreadable))]
           (when (not= v ::unreadable) (record-tl! :log v)))

         ;; (trace 'form): each instrumented sub-expression streams its coord + value here.
         (= kind "cljd.trace")
         (let [v (try (read-string (:value data)) (catch Throwable _ (:value data)))]
           (record-tl! :trace {:coord (:coord data) :value v}))

         ;; cljd.booted: the app root just (re)mounted. After a hot restart that's a NEW isolate, so
         ;; refresh the isolate id first (or every eval fails against the dead one), then — when
         ;; recording — replay the host-durable state into the fresh app. Off-thread (evals on the
         ;; device); replay-settle! owns the compiler context, retry-until-landed, and re-arm.
         (= kind "cljd.booted")
         (future
           (try
             (refresh-iso!)
             (when @+cljd-recording?+ (replay-settle!))
             (catch Throwable _ nil))))))
;; handle-request: the nREPL request dispatch. A top-level defn reached through a #'var trampoline, so
;; a (require 'cljd.repl.nrepl :reload) swaps every op's code while the server socket + connection stay up.
;; The per-connection context arrives as `state` and is destructured to the same local names the case
;; body already used (client/*iso/ctx/…) — so the dispatch below is unchanged.
(defn handle-request [state {:keys [op transport id session code] :as msg}]
  (let [{:keys [client *iso ctx *current-ns *eval-sink trigger-reload trigger-restart source-dirs ns-lib-uri await? pick? remember?]} state
        send! (fn [m] (transport/send transport (merge {:id id} (when session {:session session}) m)))]
      (case op
        "clone"       (transport/send transport {:id id :new-session (str (UUID/randomUUID)) :status ["done"]})
        "ls-sessions" (send! {:sessions [] :status ["done"]})
        "describe"    (send! {:ops (zipmap ["clone" "describe" "eval" "close" "ls-sessions"
                                            "interrupt" "complete" "info" "lookup" "eldoc"]
                                           (repeat {}))
                              :versions {:cljd {:major 0 :minor 1}} :status ["done"]})
        "interrupt"   (send! {:status ["done" "interrupted"]})
        "close"       (send! {:status ["done" "session-closed"]})
        ;; editor completion — answered host-side from @nses (current ns + cljd.core).
        "complete"
        (let [prefix (or (:prefix msg) (:symbol msg) "")
              ns-sym (or (some-> (:ns msg) symbol) @*current-ns)
              nses   @compiler/nses
              ;; defs live as direct symbol keys of the ns map (see resolve-non-local-symbol);
              ;; :mappings holds referred/aliased names. Gather both, for the ns + cljd.core.
              names  (mapcat (fn [n]
                               (let [m (get nses n)]
                                 (concat (filter symbol? (keys m)) (keys (:mappings m)))))
                             [ns-sym 'cljd.core])
              cands  (->> names (map name) distinct
                          (filter #(.startsWith ^String % prefix))
                          sort (take 100)
                          (mapv (fn [c] {:candidate c :ns (name ns-sym)})))]
          (send! {:completions cands :status ["done"]}))
        ;; symbol info / doc — arglists + docstring from the def's stored :meta.
        ("info" "lookup")
        (let [i    (sym-info @compiler/nses (or (some-> (:ns msg) symbol) @*current-ns) (symbol (or (:symbol msg) (:sym msg) "")))
              ;; go-to-def: nses has no source loc, so resolve ns → file (source-dirs) → the
              ;; def's line off the file (same path the `source` op uses). App/kora syms
              ;; resolve; cljd.core syms live in the fork's src (outside source-dirs) → no jump.
              path (some-> (:ns i) (str/replace "." "/") (str ".cljd"))
              file (when path (resolve-source-file source-dirs path))
              line (when (and file (:name i))
                     (try (def-line file (symbol (:name i))) (catch Throwable _ nil)))]
          (if i
            (send! (cond-> {:name (:name i) :ns (:ns i)
                            :arglists-str (if (:arglists i) (pr-str (:arglists i)) "")
                            :doc (or (:doc i) "")
                            :status ["done"]}
                     file (assoc :file (.getCanonicalPath (java.io.File. ^String file)))
                     line (assoc :line line)))
            ;; not a cljd def → maybe a Dart INTEROP element: ask the analysis server
            ;; (dart language-server) by name → its declaration in the Dart/Flutter source.
            (if-let [d (dartlsp/find-element (System/getProperty "user.dir")
                         (name (symbol (or (:symbol msg) (:sym msg) ""))))]
              (send! {:name (:name d) :ns "dart" :file (:file d) :line (:line d)
                      :arglists-str ""
                      :doc (or (:doc d) (str "Dart element (kind " (:kind d) ")"))
                      :status ["done"]})
              (send! {:status ["done" "no-info"]}))))
        "eldoc"
        (let [i (sym-info @compiler/nses (or (some-> (:ns msg) symbol) @*current-ns) (symbol (or (:symbol msg) (:sym msg) "")))]
          (if (and i (:arglists i))
            (send! {:name (:name i) :ns (:ns i) :type "function"
                    :eldoc (mapv (fn [al] (mapv str al)) (:arglists i))
                    :status ["done"]})
            (send! {:status ["done" "no-eldoc"]})))
        "eval"
        ;; the ONE crossing that changes *current-ns* across several forms (in-ns mid-batch),
        ;; so it owns a single binding frame; switch-ns! set!s within it. Inner device calls
        ;; below run inside this frame (eval-form directly), not eval! (which starts its own).
        (repl-eval/with-compiler-context ctx @*current-ns
          ;; forward the app's Stdout/Stderr WriteEvents to this eval's transport
          ;; while it runs (println output etc.), then detach the sink.
          (reset! *eval-sink (fn [stream text]
                               ;; strip Flutter's own per-line "flutter: " stdout prefix
                               (send! {(if (= stream "Stderr") :err :out)
                                       (.replaceAll text "(?m)^flutter: " "")})))
          (let [errored (volatile! false)
                switch-ns! (fn [ns-sym]            ; keep atom + per-batch dynamic binding in sync
                             (reset! *current-ns ns-sym)
                             (set! compiler/*current-ns* ns-sym))]
            (try
              (doseq [form (read-forms code)]
                (let [head (and (seq? form) (first form))
                      ;; always eval against the CURRENT isolate (refreshed on restart) — shadows
                      ;; the launch-time id so every op below survives a hot restart.
                      iso-id @*iso]
                  ;; dispatch the REPL's special ops as a table (case on the form head); anything
                  ;; not an op falls through to the default — compile + eval/reload on the device.
                  (case head
                    ;; (in-ns 'x): switch the eval/compile context to an existing ns —
                    ;; no recompile; its defs + required aliases become resolvable.
                    in-ns
                    (let [target (unwrap-quote (second form))]
                      (if (ns-exists? target)
                        (do (switch-ns! target)
                            (send! {:value (str target) :ns (name target)}))
                        (do (vreset! errored true)
                            (send! {:err (str "No such namespace: " target
                                              " (only namespaces compiled into the app are available)")
                                    :ex "cljd.no-such-ns"}))))

                    ;; ── clojure.repl parity — same ergonomics as upstream, read from @nses host-side ──
                    ;; (doc SYM): arglists + docstring.
                    doc
                    (send! {:value (or (format-doc (sym-info @compiler/nses @*current-ns
                                                     (unwrap-quote (second form))))
                                       (str "nothing known about " (unwrap-quote (second form))))
                            :ns (name @*current-ns)})

                    ;; (dir NS): sorted names of the defs in a namespace.
                    dir
                    (let [nsym (unwrap-quote (second form))]
                      (if (ns-exists? nsym)
                        (send! {:value (str/join "\n" (ns-def-names @compiler/nses nsym)) :ns (name @*current-ns)})
                        (send! {:err (str "No such namespace: " nsym) :ex "cljd.no-such-ns"})))

                    ;; (apropos STR-OR-SYM): all public names (ns-qualified) containing the string.
                    apropos
                    (let [pat (str (unwrap-quote (second form)))
                          nses @compiler/nses
                          hits (->> (keys nses) (filter symbol?)
                                    (mapcat (fn [n] (map #(str n "/" %) (ns-def-names nses n))))
                                    (filter #(.contains ^String % pat)) sort vec)]
                      (send! {:value (pr-str hits) :ns (name @*current-ns)}))

                    ;; (find-doc STR): defs whose name or docstring contains the string.
                    find-doc
                    (let [pat (str (unwrap-quote (second form)))
                          nses @compiler/nses
                          hits (for [n (filter symbol? (keys nses))
                                     nm (filter symbol? (keys (get nses n)))
                                     :let [i (sym-info nses n nm)]
                                     :when (and i (or (.contains (str nm) pat)
                                                      (and (:doc i) (.contains ^String (:doc i) pat))))]
                                 (format-doc i))]
                      (send! {:value (str/join "\n" hits) :ns (name @*current-ns)}))

                    ;; (source SYM): the def's source text — ns → file (source-dirs) → the def form.
                    source
                    (let [sym (unwrap-quote (second form))
                          i (sym-info @compiler/nses @*current-ns sym)
                          path (some-> (:ns i) (str/replace "." "/") (str ".cljd"))
                          file (when path (resolve-source-file source-dirs path))
                          txt (when (and file (:name i))
                                (try (def-source file (symbol (:name i))) (catch Throwable _ nil)))]
                      (send! {:value (or txt (str "source not found for " sym)) :ns (name @*current-ns)}))

                    ;; (pick!) / (pick! false): toggle the on-device widget picker.
                    pick!
                    (if-not pick?
                      (do (vreset! errored true)
                          (send! {:err "picker unavailable (needs a debug build whose root went through f/run)"
                                  :ex "cljd.no-picker"}))
                      (let [on? (if (>= (count form) 2) (not (false? (second form))) true)
                            r (repl-eval/eval-form client iso-id
                                (list 'cljd.flutter/arm! on?)
                                {:ns-lib-uri "cljd/flutter.dart"})]
                        (send! {:value (:value r) :ns (name @*current-ns)})))

                    ;; (picked): report the ACTIVE pick (most recent in the HUD's +cljd-picks+
                    ;; vector — the single pick store) and jump the REPL into its ns, loading its
                    ;; scope into *env. `(peek +cljd-picks+)` is the last-picked widget.
                    picked
                    (let [;; small scope-loc first — the full pick's gensym env keys can defeat
                          ;; read-string, which would block the ns jump.
                          loc-r (repl-eval/eval-form client iso-id
                                  '(:ns (:scope-loc (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+))))
                                  {:ns-lib-uri "cljd/flutter.dart"})
                          target (try (read-string (:value loc-r)) (catch Throwable _ nil))
                          ;; a clean summary (omit env/:rect — not read-string-friendly)
                          full-r (repl-eval/eval-form client iso-id
                                   '(let [p (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+))]
                                      {:scope-loc (:scope-loc p) :type (:type p)
                                       :src (:src (:widget-loc p)) :cljd (:cljd p)
                                       :env-keys (cljd.core/vec (cljd.core/keys (cljd.flutter/pick-env p)))})
                                   {:ns-lib-uri "cljd/flutter.dart"})
                          ;; load the active pick's LIVE scope into *env (cljd.core holder) so
                          ;; `*env` / `(get *env 'local)` resolve in subsequent evals.
                          _ (repl-eval/eval-form client iso-id focus-active-env-form
                              {:ns-lib-uri "cljd/flutter.dart"})]
                      (when (and (symbol? target) (ns-exists? target)) (switch-ns! target))
                      (send! {:value (:value full-r) :ns (name @*current-ns)}))
                    ;; (picks): read all on-device picks as STRUCTURED DATA via the
                    ;; ext.cljd.picks service extension — one call, JSON, no `evaluate`, no
                    ;; 128-char cap, no per-field reads. :cljd is already resolved by the
                    ;; Extension-event auto-resolver, so this op is now a pure read.
                    picks
                    (let [r (try (vm/call-ext client iso-id "ext.cljd.picks" {})
                                 (catch Throwable e {:error (.getMessage e)}))]
                      (send! {:value (pr-str (:picks r r)) :ns (name @*current-ns)}))

                    ;; (edit-back! PROP VALUE): write VALUE back to source as the named PROP of the
                    ;; ACTIVE pick's widget form. The pick already resolved widget → .cljd file:line:col;
                    ;; this reads that form, finds PROP's value span, and splices VALUE into the file —
                    ;; the watcher then recompiles + hot-reloads. The app→source arrow run to WRITE.
                    ;; e.g. (edit-back! .padding (m/EdgeInsets.all 40.0))
                    edit-back!
                    (let [prop (second form)
                          value-str (pr-str (nth form 2 nil))
                          cljd-r (repl-eval/eval-form client iso-id
                                   '(:cljd (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+)))
                                   {:ns-lib-uri "cljd/flutter.dart"})
                          loc (try (read-string (:value cljd-r)) (catch Throwable _ nil))
                          [path ln col] (when (and (string? loc) (seq loc))
                                          (let [ps (.split ^String loc ":")]
                                            (when (= 3 (alength ps))
                                              (try [(aget ps 0) (Long/parseLong (aget ps 1))
                                                    (Long/parseLong (aget ps 2))]
                                                   (catch Throwable _ nil)))))
                          file (when path (resolve-source-file source-dirs path))]
                      (cond
                        (not (and (string? loc) (seq loc)))
                        (send! {:err "no active pick with a resolved cljd loc — pick a cljd widget first"
                                :ex "cljd.no-pick"})
                        (not file)
                        (send! {:err (str "can't resolve source file for " path " under " (vec source-dirs))
                                :ex "cljd.no-file"})
                        :else
                        (let [text (slurp file)
                              wform (form-at (read-source-forms file) ln col)
                              span (and wform (prop-value-offsets text wform prop))]
                          (if span
                            (let [[s e] span
                                  old (subs text s e)]
                              (spit file (str (subs text 0 s) value-str (subs text e)))
                              (send! {:value (str path ":" ln "  " prop ": " old " → " value-str
                                                " (saved — watcher reloading)")
                                      :ns (name @*current-ns)}))
                            (send! {:err (str "no property " (pr-str prop) " found in the picked form at "
                                           path ":" ln)
                                    :ex "cljd.no-prop"})))))

                    ;; (errors): recent errors as structured DATA — the :error entries of the unified
                    ;; timeline (framework/async/explicit/eval-path), derived via errors-view. No parallel
                    ;; store. (errors :clear) drops those entries from the timeline.
                    errors
                    (do (when (= :clear (second form))
                          (swap! +cljd-timeline+ (fn [tl] (vec (remove #(= :error (:kind %)) tl)))))
                        (send! {:value (pr-str (errors-view @+cljd-timeline+)) :ns (name @*current-ns)}))

                    ;; (q FORM): evaluate FORM on the HOST over the collected introspection data
                    ;; as plain values — the bound symbols `errors` `timeline` `txs` `epochs`
                    ;; `coverage` are the current stores, so ops compose like any Clojure:
                    ;;   (q (count errors))
                    ;;   (q (frequencies (map :kind timeline)))
                    ;;   (q (filter #(= "flutter" (:phase %)) errors))
                    ;; Fixes the "ops aren't values" gap: the stores live host-side, so this is a
                    ;; pure host eval — no device round-trip, no dump-and-grep.
                    q
                    (let [data {'errors (errors-view @+cljd-timeline+)
                                'timeline @+cljd-timeline+
                                'txs @+cljd-tx-log+
                                ;; epochs resolve to the state map AT each cut-point (not the raw index)
                                'epochs (mapv #(tx-snapshot (take (inc %) @+cljd-tx-log+))
                                              (epoch-indices @+cljd-tx-log+))
                                'coverage @+cljd-coverage-snap+}
                          r (try
                              (eval (list 'let (vec (mapcat (fn [[k v]] [k (list 'quote v)]) data))
                                      (second form)))
                              (catch Throwable e {:q-error (.getMessage e)}))]
                      (send! {:value (pr-str r) :ns (name @*current-ns)}))

                    ;; (picks-do FORM): run FORM in the scope of EVERY pick at once — `*env` is
                    ;; rebound to each pick's lexical map in turn. Returns a vector of results.
                    ;; e.g. (picks-do (swap! (*env 'expanded?) not)) toggles all selected widgets.
                    picks-do
                    (if-not pick?
                      (do (vreset! errored true)
                          (send! {:err "picker unavailable (needs a debug build whose root went through f/run)"
                                  :ex "cljd.no-picker"}))
                      (let [user-form (second form)
                            ;; set! *env's holder to each pick's LIVE env, then eval the (rewritten)
                            ;; user form; mapv collects. `remember?` makes eval rewrite *env→holder.
                            wrapped (list 'cljd.core/mapv
                                          (list 'cljd.core/fn ['p]
                                                (list 'set! 'cljd.core/+cljd-repl-env+ (list 'cljd.flutter/pick-env 'p))
                                                user-form)
                                          '(cljd.core/deref cljd.flutter/+cljd-picks+))
                            r (repl-eval/eval-form client iso-id wrapped
                                {:ns-lib-uri "cljd/flutter.dart" :remember? remember?})]
                        (if (:error r)
                          (do (vreset! errored true) (send! {:err (:message r) :ex "cljd.eval-error"}))
                          (send! {:value (:value r) :ns (name @*current-ns)}))))

                    ;; (cljd-src "kora/nav.dart" 249) -> "kora/nav.cljd:78:12". The coherent
                    ;; Dart->cljd source map: works for ANY Dart line in a compiled lib.
                    cljd-src
                    (let [path (str (second form))
                          line (nth form 2)
                          cljd (resolve-wloc (:libs @compiler/nses) (str path ":" line))]
                      (send! {:value (pr-str (or cljd "unresolved")) :ns (name @*current-ns)}))

                    ;; (macroexpand '(...)) / (macroexpand-1 '(...)): host-side via the
                    ;; compiler, not shipped to the device (cljd macros are compile-time).
                    (macroexpand macroexpand-1)
                    (let [f (unwrap-quote (second form))
                          expanded ((if (= 'macroexpand-1 head)
                                      compiler/macroexpand-1 compiler/macroexpand) {} f)]
                      (send! {:value (pr-str expanded) :ns (name @*current-ns)}))

                    ;; (dart-of '(...)): the Dart the compiler emits for a form — "what will this
                    ;; become on device". Host-side (form->dart-expr), no device round-trip. A compile
                    ;; error (e.g. unknown symbol) is shown as such instead of the Dart.
                    dart-of
                    (let [f (unwrap-quote (second form))
                          dart (try (compiler/form->dart-expr f)
                                    (catch Throwable e (str "compile error: " (errors/format-compile e))))]
                      (send! {:value dart :ns (name @*current-ns)}))

                    ;; (trace 'form): value-flow — instrument the form (wrap each fn-call in record!),
                    ;; eval it on device; each sub-expression's coord+value streams to (timeline :trace).
                    ;; Returns the final value. v0 traces fn-calls; see instrument's skip-list.
                    trace
                    (let [f (unwrap-quote (second form))
                          instrumented (try (instrument f) (catch Throwable _ f))
                          r (repl-eval/eval-form client iso-id instrumented
                              {:ns-lib-uri (ns->lib-uri @*current-ns) :trigger-reload trigger-reload})]
                      (if (:error r)
                        (do (vreset! errored true) (send! {:err (:message r) :ex "cljd.trace-error"}))
                        (send! {:value (:value r) :ns (name @*current-ns)})))

                    ;; state time-travel, HOST-recorded, ONE timeline. (states) READs the whole app
                    ;; state as data. (epoch!) drops a full-state keyframe into the tx-log and
                    ;; marks its position; (epochs) lists the markers; (restore-epoch! N) seeks the
                    ;; device back to epoch N's cut-point. Epochs are discrete save points; (seek!)
                    ;; is continuous — both navigate the same tx-log, addressed by stable [loc sym].
                    states
                    (let [r (repl-eval/eval-form client iso-id '(cljd.flutter/read-state)
                              {:ns-lib-uri "cljd/flutter.dart" :await? true})]
                      (send! {:value (:value r) :ns (name @*current-ns)}))

                    epoch!
                    (let [r (repl-eval/eval-form client iso-id '(cljd.flutter/read-state)
                              {:ns-lib-uri "cljd/flutter.dart" :await? true})
                          state (try (read-string (:value r)) (catch Throwable _ {}))]
                      ;; append the full snapshot as a :epoch keyframe tx — the marker IS the log entry.
                      (swap! +cljd-tx-log+ conj {:tx (count @+cljd-tx-log+) :t (System/currentTimeMillis)
                                                 :delta state :cause :epoch})
                      (send! {:value (str "epoch " (dec (count (epoch-indices @+cljd-tx-log+))) " recorded ("
                                       (count state) " atoms → tx-log)")
                              :ns (name @*current-ns)}))

                    epochs
                    (send! {:value (str (count (epoch-indices @+cljd-tx-log+)) " epochs (markers into the tx-log)")
                            :ns (name @*current-ns)})

                    restore-epoch!
                    (let [i (second form)
                          idx (nth (epoch-indices @+cljd-tx-log+) i nil)]
                      (if (some? idx)
                        (let [wrote (write-snap! (tx-snapshot (take (inc idx) @+cljd-tx-log+)))]
                          (send! {:value (str "directed device to epoch " i " (" wrote " atoms)")
                                  :ns (name @*current-ns)}))
                        (send! {:err (str "no epoch " i) :ex "cljd.no-epoch"})))

                    ;; continuous recording: (record!) starts a fresh timeline with a base keyframe tx
                    ;; (the full state now) + arms device frame-batched recording; (record! false) stops.
                    record!
                    (let [on? (if (>= (count form) 2) (not (false? (second form))) true)]
                      (if on?
                        (let [r0 (repl-eval/eval-form client iso-id '(cljd.flutter/read-state)
                                   {:ns-lib-uri "cljd/flutter.dart" :await? true})
                              base (try (read-string (:value r0)) (catch Throwable _ {}))]
                          (reset! +cljd-tx-log+ [{:tx 0 :t (System/currentTimeMillis)
                                                  :delta base :cause :record/base}])
                          (reset! +cljd-recording?+ true)
                          (repl-eval/eval-form client iso-id '(cljd.flutter/arm-recording! true)
                            {:ns-lib-uri "cljd/flutter.dart"})
                          (send! {:value (str "recording on (" (count base) " base atoms)")
                                  :ns (name @*current-ns)}))
                        (do (reset! +cljd-recording?+ false)
                            (repl-eval/eval-form client iso-id '(cljd.flutter/arm-recording! false)
                              {:ns-lib-uri "cljd/flutter.dart"})
                            (send! {:value "recording off" :ns (name @*current-ns)}))))

                    txs
                    (send! {:value (str (count @+cljd-tx-log+) " transactions recorded") :ns (name @*current-ns)})

                    ;; (seek! N): DIRECT the device to the state as of transaction N — merge deltas
                    ;; 0..N into a snapshot (later txs win per id) → write-state. Continuous time-travel.
                    seek!
                    (let [n (second form)
                          snap (tx-snapshot (take (inc n) @+cljd-tx-log+))]
                      (if (seq snap)
                        (send! {:value (str "sought to tx " n " (" (write-snap! snap) " atoms)")
                                :ns (name @*current-ns)})
                        (send! {:err (str "no transactions up to " n " (record! first?)") :ex "cljd.no-tx"})))

                    ;; (replay!): re-apply the WHOLE recorded tx-log onto the live atoms — the
                    ;; latest state, not a point in time. Same write-state mechanism as (seek!); used
                    ;; to restore after a hot restart (fired automatically — see (restart!)). Manual
                    ;; call is the testable core of cross-restart replay.
                    replay!
                    (let [res (replay!)]
                      (if res
                        (send! {:value (str "replayed " (:atoms res) " atoms → " (:wrote res) " written")
                                :ns (name @*current-ns)})
                        (send! {:value "nothing to replay (record! first?)" :ns (name @*current-ns)})))

                    ;; (restart!): hot-restart the app (host writes "R" to flutter, as if typed). When
                    ;; recording is on, the recorded state replays automatically once the fresh isolate
                    ;; re-mounts (the device's cljd.booted event fires refresh-iso! + replay-settle!).
                    ;; Also the fix for the binding-shape-change friction — a restart no longer means
                    ;; losing your place.
                    restart!
                    (if trigger-restart
                      (do (trigger-restart)
                          (send! {:value (if @+cljd-recording?+
                                           "hot restart requested (state will replay on re-mount)"
                                           "hot restart requested (recording off → clean slate)")
                                  :ns (name @*current-ns)}))
                      (send! {:err "no restart trigger wired (flutter not running?)" :ex "cljd.no-restart"}))

                    ;; (coverage): snapshot which cljd forms have executed (line-granular, per-script
                    ;; getSourceReport). (ran): after an interaction, the cljd locs NEWLY executed
                    ;; since the last snapshot — "what code this action touched", no instrumentation.
                    coverage
                    (let [cov (cljd-coverage client iso-id)]
                      (reset! +cljd-coverage-snap+ cov)
                      (send! {:value (str (count cov) " dart lines covered — snapshot taken; interact then (ran)")
                              :ns (name @*current-ns)}))

                    ran
                    (let [now (cljd-coverage client iso-id)
                          fresh (remove @+cljd-coverage-snap+ now)
                          locs (->> fresh (keep #(dart-uri->cljd (:libs @compiler/nses) %)) distinct sort vec)]
                      (reset! +cljd-coverage-snap+ now)
                      (send! {:value (pr-str locs) :ns (name @*current-ns)}))

                    ;; (taps): device (tap> x) values. (timeline [:kind]): the unified event log
                    ;; (tap/log/error/state) in causal order, optionally filtered by kind.
                    taps
                    (send! {:value (pr-str (mapv :data (filter #(= :tap (:kind %)) @+cljd-timeline+)))
                            :ns (name @*current-ns)})

                    timeline
                    (let [k (second form)
                          tl (if k (filterv #(= k (:kind %)) @+cljd-timeline+) @+cljd-timeline+)]
                      (send! {:value (pr-str tl) :ns (name @*current-ns)}))

                    ;; default: not a special op → compile + eval (or reload) the form on the device
                    (let [r (repl-eval/eval-form client iso-id form
                                                 {:ns-lib-uri (ns->lib-uri @*current-ns)
                                                  :trigger-reload trigger-reload
                                                  :await? await?
                                                  :remember? remember?})]
                      (case (:kind r)
                        :reload (do (when (= 'ns head) (switch-ns! (second form)))
                                    (send! {:value (str "#reloaded " (pr-str (:report r))) :ns (name @*current-ns)}))
                        :eval   (if (:error r)
                                  (do (vreset! errored true)
                                      ;; runtime Dart exception: clean message + demunged user frames.
                                      ;; :dart-kind (from the @Error) tags phase: LanguageError→compile.
                                      (let [msg (errors/format-runtime (:message r)
                                                  (fn [dl] (resolve-wloc (:libs @compiler/nses) dl)))
                                            phase (if (= "LanguageError" (:dart-kind r)) "compile" "runtime")]
                                        (remember-error! {:phase phase :message msg :stack nil})
                                        (send! {:err msg :ex "dart.runtime-exception"})))
                                  (send! {:value (:value r) :ns (name @*current-ns)})))))))
              (send! {:status (if @errored ["done" "error"] ["done"])})
              (catch Throwable e
                ;; compile-time error from turning the form into Dart
                (let [msg (errors/format-compile e)]
                  (remember-error! {:phase "compile" :message msg :stack nil})
                  (send! {:err msg :ex (str (class e)) :status ["done" "error"]})))
              (finally (reset! *eval-sink nil)))))
        (send! {:status ["done" "error" "unknown-op"]}))))

;; make-handler is defined LAST — it only WIRES the pieces above (helpers, sinks, handle-request)
;; through #'var trampolines, so no forward declaration is needed. Called once by start!.
(defn make-handler
  "cfg: {:client :iso-id :analyzer :dart-version :*current-ns :ns-lib-uri :trigger-reload :trigger-restart :source-dirs :await? :pick? :remember?}"
  [{:keys [client iso-id analyzer dart-version *current-ns ns-lib-uri trigger-reload trigger-restart source-dirs await? pick? remember?]
    :or {ns-lib-uri "cljd/core.dart"}
    :as cfg}]
  (let [;; *iso: the CURRENT main isolate id. A Flutter hot restart (R) spins a NEW isolate, so the
        ;; id captured at launch goes stale; refresh-iso! re-resolves it on the cljd.booted event.
        *iso (atom iso-id)
        ctx (repl-eval/context (assoc cfg :*iso *iso :default-ns 'cljd.core))
        *eval-sink (atom nil)
        ;; the per-connection context every top-level fn/sink/op reads. Stashed in the +state+ defonce
        ;; so reloading this ns keeps the live connection while swapping the code around it.
        state {:client client :*iso *iso :ctx ctx :*eval-sink *eval-sink
               :*current-ns *current-ns :ns-lib-uri ns-lib-uri
               :trigger-reload trigger-reload :trigger-restart trigger-restart
               :source-dirs source-dirs :await? await? :pick? pick? :remember? remember?}]
    (reset! +state+ state)
    ;; sinks + request handler are var-trampolines into top-level defns, so (require ... :reload)
    ;; swaps their code while this connection stays live (single-slot set-*-sink! → no dup listeners).
    (vm/set-sink! client (fn [stream text] (#'stdout-sink @+state+ stream text)))
    (vm/set-event-sink! client (fn [kind data] (#'event-sink @+state+ kind data)))
    (fn [msg] (#'handle-request @+state+ msg))))

(defn start!
  "Start the nREPL server. Returns the nrepl server (has :port). Writes .nrepl-port."
  [{:keys [port] :or {port 0} :as cfg}]
  (let [server (nrepl-server/start-server :port port :handler (make-handler cfg))]
    (spit ".nrepl-port" (str (:port server)))
    server))
