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
(defn- instrument
  ([form] (instrument [] form))
  ([coord form]
   (let [f (try (compiler/macroexpand {} form) (catch Throwable _ form))]
     (if (and (seq? f) (seq f) (symbol? (first f))
              (not (contains? +trace-special+ (first f)))
              (not (trace-member? (first f))))
       ;; plain fn-call → record its result + recurse into value-position args
       (list 'cljd.flutter/record! (apply str (interpose "," coord))
             (cons (first f)
                   (map-indexed (fn [i a] (instrument (conj coord (inc i)) a)) (rest f))))
       ;; special form / interop / non-call: pass through; still record the WHOLE result at the top
       (if (empty? coord) (list 'cljd.flutter/record! "" f) f)))))

(defn make-handler
  "cfg: {:client :iso-id :analyzer :dart-version :*current-ns :ns-lib-uri :trigger-reload :await? :pick? :remember?}"
  [{:keys [client iso-id analyzer dart-version *current-ns ns-lib-uri trigger-reload await? pick? remember?]
    :or {ns-lib-uri "cljd/core.dart"}
    :as cfg}]
  ;; ctx: the host↔device boundary, bundled once. Every crossing goes through repl-eval's
  ;; eval! / with-compiler-context / call! — no site re-writes the compiler binding block or
  ;; threads client/iso-id by hand. (default-ns 'cljd.core matches the *current-ns atom's start.)
  ;; *eval-sink: the swappable per-eval stdout forwarder. The persistent pick-resolver sink
  ;; (installed once) both auto-resolves CLJD_PICK markers and forwards through *eval-sink.
  (let [ctx (repl-eval/context (assoc cfg :default-ns 'cljd.core))
        *eval-sink (atom nil)
        ;; the ONE error store: EVERY error — device-pushed (framework/async/report-error!) AND
        ;; eval-path (runtime @Error, compile) — lands here as data via remember-error!. `(errors)`
        ;; reads it. Ring-bounded so it can't grow without limit.
        +cljd-errors+ (atom [])
        ;; host-recorded state timeline: each entry is a read-state snapshot (EDN {id→value}). The
        ;; DEVICE holds no epoch store — the durable memory is here, so it survives device restart.
        +cljd-state-log+ (atom [])
        ;; continuous change-stream: while recording, each device atom change arrives here as
        ;; {:id :value} in order. seek(N) replays 0..N into a state snapshot → write-state.
        +cljd-change-log+ (atom [])
        ;; last coverage snapshot (set of dart uri:line) — (ran) diffs against it.
        +cljd-coverage-snap+ (atom #{})
        ;; the UNIFIED timeline: every device event (tap / log / error / state-change) appended in
        ;; causal order, tagged :kind — one ordered log across all four channels. `record-tl!` clocks
        ;; each entry ((tl-now) is set per-eval below to avoid Date.now in this file's macros).
        +cljd-timeline+ (atom [])
        record-tl! (fn [kind data]
                     (swap! +cljd-timeline+
                       (fn [tl] (vec (take-last 500 (conj tl {:kind kind :t (System/currentTimeMillis) :data data}))))))
        ;; coalesce identical consecutive errors (same phase+message) into one entry with a :count,
        ;; so a reassemble burst of the same assertion doesn't flood the store. Returns the NEW error
        ;; (for live-forward) or nil when it coalesced a duplicate (so we don't re-forward it).
        remember-error!
        (fn [e]
          (let [prev (peek @+cljd-errors+)
                dupe? (and prev (= (:phase prev) (:phase e)) (= (:message prev) (:message e)))]
            (swap! +cljd-errors+
              (fn [es]
                (let [prev (peek es)]
                  (if (and prev (= (:phase prev) (:phase e)) (= (:message prev) (:message e)))
                    (conj (pop es) (update prev :count (fnil inc 1)))
                    (vec (take-last 25 (conj es (assoc e :count 1))))))))
            (when-not dupe? e)))]
   ;; stdout sink just forwards REPL output to the active eval's transport (no marker scanning).
   (vm/set-sink! client (fn [stream text] (when-some [f @*eval-sink] (f stream text))))
   ;; ONE structured-event intake off the Extension stream. `cljd.pick` resolves the pick; any
   ;; `cljd.error` event (from report-error!, the chained FlutterError hook, or any Dart extension
   ;; that adopts the convention) funnels into the one error store + live-forwards. Runs off the WS
   ;; listener thread (a future — a synchronous rpc on the listener thread would deadlock).
   (vm/set-event-sink! client
     (fn [kind data]
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
           (record-tl! :error e)
           ;; only forward NEW errors (remember-error! returns nil for a coalesced duplicate)
           (when-some [f @*eval-sink]
             (f "Stderr" (str "⚠ [" (:phase e) "] " (:message e)
                              (when (seq (:stack e)) (str "\n" (:stack e))) "\n"))))

         ;; a device atom change (recording on): parse the EDN id+value → change-log + timeline
         (= kind "cljd.state-change")
         (let [id (try (read-string (:id data)) (catch Throwable _ nil))
               v  (try (read-string (:value data)) (catch Throwable _ ::unreadable))]
           (when (and id (not= v ::unreadable))
             (swap! +cljd-change-log+ conj {:id id :value v})
             (record-tl! :state {:id id :value v})))

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
           (record-tl! :trace {:coord (:coord data) :value v})))))
   (fn [{:keys [op transport id session code] :as msg}]
    (let [send! (fn [m] (transport/send transport (merge {:id id} (when session {:session session}) m)))]
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
        (let [i (sym-info @compiler/nses @*current-ns (symbol (or (:symbol msg) (:sym msg) "")))]
          (if i
            (send! {:name (:name i) :ns (:ns i)
                    :arglists-str (if (:arglists i) (pr-str (:arglists i)) "")
                    :doc (or (:doc i) "")
                    :status ["done"]})
            (send! {:status ["done" "no-info"]})))
        "eldoc"
        (let [i (sym-info @compiler/nses @*current-ns (symbol (or (:symbol msg) (:sym msg) "")))]
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
                (let [head (and (seq? form) (first form))]
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

                    ;; (errors): recent device errors as structured DATA — everything the device
                    ;; pushed onto the one cljd.error stream (framework/async/explicit). (errors :clear)
                    ;; empties the store.
                    errors
                    (do (when (= :clear (second form)) (reset! +cljd-errors+ []))
                        (send! {:value (pr-str @+cljd-errors+) :ns (name @*current-ns)}))

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

                    ;; state time-travel, HOST-recorded. (states) READs the whole app state as data;
                    ;; (epoch!) records a snapshot into the host timeline; (restore-epoch N) DIRECTS
                    ;; the device back to that snapshot. The recording lives on the host → durable
                    ;; across device restart (replay into a fresh app), addressed by stable [loc sym].
                    states
                    (let [r (repl-eval/eval-form client iso-id '(cljd.flutter/read-state)
                              {:ns-lib-uri "cljd/flutter.dart"})]
                      (send! {:value (:value r) :ns (name @*current-ns)}))

                    epoch!
                    (let [r (repl-eval/eval-form client iso-id '(cljd.flutter/read-state)
                              {:ns-lib-uri "cljd/flutter.dart"})
                          edn (:value r)]
                      (swap! +cljd-state-log+ conj edn)
                      (send! {:value (str "epoch " (dec (count @+cljd-state-log+)) " recorded on host")
                              :ns (name @*current-ns)}))

                    epochs
                    (send! {:value (str (count @+cljd-state-log+) " epochs (host-recorded)")
                            :ns (name @*current-ns)})

                    restore-epoch
                    (let [i (second form)
                          edn (nth @+cljd-state-log+ i nil)
                          ;; parse HOST-side (device cljd has no read-string) → send the map as a
                          ;; compiled literal to write-state.
                          snap (when edn (try (read-string edn) (catch Throwable _ nil)))]
                      (if snap
                        (let [r (repl-eval/eval-form client iso-id (list 'cljd.flutter/write-state snap)
                                  {:ns-lib-uri "cljd/flutter.dart"})]
                          (send! {:value (str "directed device to epoch " i " (" (:value r) " atoms)")
                                  :ns (name @*current-ns)}))
                        (send! {:err (str "no epoch " i " (or unparseable state)") :ex "cljd.no-epoch"})))

                    ;; continuous recording: (record) starts a fresh timeline with a base keyframe
                    ;; (the full state now) + arms device atom-watchers; (record false) stops.
                    record
                    (let [on? (if (>= (count form) 2) (not (false? (second form))) true)]
                      (if on?
                        (let [r0 (repl-eval/eval-form client iso-id '(cljd.flutter/read-state)
                                   {:ns-lib-uri "cljd/flutter.dart"})
                              base (try (read-string (:value r0)) (catch Throwable _ {}))]
                          (reset! +cljd-change-log+ (mapv (fn [[id v]] {:id id :value v}) base))
                          (repl-eval/eval-form client iso-id '(cljd.flutter/arm-recording! true)
                            {:ns-lib-uri "cljd/flutter.dart"})
                          (send! {:value (str "recording on (" (count @+cljd-change-log+) " base atoms)")
                                  :ns (name @*current-ns)}))
                        (do (repl-eval/eval-form client iso-id '(cljd.flutter/arm-recording! false)
                              {:ns-lib-uri "cljd/flutter.dart"})
                            (send! {:value "recording off" :ns (name @*current-ns)}))))

                    changes
                    (send! {:value (str (count @+cljd-change-log+) " changes recorded") :ns (name @*current-ns)})

                    ;; (seek N): DIRECT the device to the state as of change N — replay 0..N into a
                    ;; snapshot (last value wins per id) → write-state. Continuous time-travel.
                    seek
                    (let [n (second form)
                          snap (reduce (fn [m c] (assoc m (:id c) (:value c))) {}
                                 (take (inc n) @+cljd-change-log+))]
                      (if (seq snap)
                        (let [r (repl-eval/eval-form client iso-id (list 'cljd.flutter/write-state snap)
                                  {:ns-lib-uri "cljd/flutter.dart"})]
                          (send! {:value (str "sought to change " n " (" (:value r) " atoms)")
                                  :ns (name @*current-ns)}))
                        (send! {:err (str "no changes up to " n " (record first?)") :ex "cljd.no-change"})))

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
        (send! {:status ["done" "error" "unknown-op"]}))))))

(defn start!
  "Start the nREPL server. Returns the nrepl server (has :port). Writes .nrepl-port."
  [{:keys [port] :or {port 0} :as cfg}]
  (let [server (nrepl-server/start-server :port port :handler (make-handler cfg))]
    (spit ".nrepl-port" (str (:port server)))
    server))
