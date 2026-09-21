(ns cljd.repl.nrepl
  "nREPL front for the VM-Service REPL; evals route to the running app's isolate via cljd.repl.eval.
   Runs inside the cljd.build process; the compiler context is re-bound per request."
  (:require [nrepl.server :as nrepl-server]
            [nrepl.transport :as transport]
            [clojure.string :as str]
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
  "cljd library uri suffix for a namespace, e.g. a.b.c -> a/b/c.dart."
  [ns-sym]
  (str (.replace (name ns-sym) "." "/") ".dart"))

(defn- unwrap-quote [x]
  (if (and (seq? x) (= 'quote (first x))) (second x) x))

(defn- msg-sym
  "Symbol string of an info/eldoc/complete message (:symbol or :sym), else \"\"."
  [msg] (or (:symbol msg) (:sym msg) ""))

(defn- msg-ns
  "The message's :ns as a symbol, else @CUR-NS (an atom)."
  [msg cur-ns] (or (some-> (:ns msg) symbol) @cur-ns))

(defn- ns-exists? [ns-sym]
  (boolean (and (symbol? ns-sym) (get @compiler/nses ns-sym))))

(defn- dart-info
  "{:name :file :line :doc} for SYM when it resolves to a Dart element in NS, via the compiler's analyzer."
  [ctx ns sym]
  (repl-eval/with-compiler-context ctx ns
    (let [[kind {:keys [lib element-name]}] (try (compiler/resolve-symbol sym {}) (catch Throwable _ nil))]
      (when (and (= :dart kind) lib element-name)
        (when-some [a (compiler/analyzer-info lib element-name)]
          {:name element-name :file (:file a) :line (:line a)
           :doc (some->> (:doc a) str/split-lines
                  (map #(str/replace % #"^\s*(///?|/\*\*|\*/|\*) ?" ""))
                  (str/join "\n") str/trim)})))))

(defn- sym-info
  "Look up SYM in @nses relative to CUR-NS, following :refer and aliases, falling back to cljd.core; nil if absent."
  [nses cur-ns sym]
  (let [sym  (get-in nses [cur-ns :mappings sym] sym)
        ns'  (if-let [n (namespace sym)]
               (or (some->> (get-in nses [cur-ns :clj-aliases n]) (get (:libs nses)) :ns) (symbol n))
               cur-ns)
        nm   (symbol (name sym))
        info (or (get-in nses [ns' nm]) (get-in nses ['cljd.core nm]))
        m    (:meta info)
        ;; :arglists is stored quoted
        al   (unwrap-quote (:arglists m))]
    (when info
      {:ns (name (:ns info)) :name (name (:name info))
       :arglists al :doc (:doc m) :macro? (boolean (:macro m))})))

(defn flatten-smap
  "Flatten the two-level lib smap to a vector of [abs-dart-line {:file :line :column}],
   sorted by line, keeping only real cljd positions (line>1)."
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
  "Nearest preceding real cljd position for DART-LINE over a flattened smap."
  [flat dart-line]
  (some (fn [[dl info]] (when (<= dl dart-line) info))
        (reverse flat)))

(defn find-lib
  "The lib entry whose key ends with the dart PATH."
  [libs ^String path]
  (some (fn [[k v]] (let [ks (str k)] (when (or (= ks path) (.endsWith ks (str "/" path))) v))) libs))

(defn- short-cljd-path
  "Strip file:// and everything up to the last /src/ from a source-map :file."
  [^String file]
  (let [f (if (.startsWith file "file://") (subs file 7) file)
        i (.lastIndexOf f "/src/")]
    (if (neg? i) f (subs f (+ i 5)))))

(defn resolve-wloc
  "Resolve a Dart wloc 'a/b.dart:249' to its .cljd loc 'a/b.cljd:78:12'; nil if unmapped."
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
  "Resolve a pick's wloc + ancestors to .cljd and push via ext.cljd.set-cljd; compilation-free, safe off-thread."
  [ctx data]
  (let [libs (:libs @compiler/nses)
        src  (:src data)
        ;; always push: "" tells the device the loc is non-cljd Dart
        cljd (resolve-wloc libs src)
        tree (apply str (interpose "\n" (map #(or (resolve-wloc libs %) "") (:ancestors data))))]
    (repl-eval/call! ctx "ext.cljd.set-cljd" {:src src :cljd (or cljd "") :tree tree})))

;; Evaluating this compiles a set!, so it needs the compiler's dynamic bindings.
(def ^:private focus-active-env-form
  '(set! cljd.core/+cljd-repl-env+
         (cljd.flutter/pick-env (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+)))))

;; getSourceReport must be per-script: a whole-isolate Coverage call crashes the on-device app.
(defn- dart-uri->cljd
  "cljd-out Dart uri:line -> cljd loc via the source map, or nil."
  [libs uri-line]
  (let [marker "cljd-out/" i (.indexOf ^String uri-line marker)]
    (when (>= i 0) (resolve-wloc libs (subs uri-line (+ i (count marker)))))))

(defn- cljd-coverage
  "Set of 'package-uri:line' that executed, aggregated over our scripts one getSourceReport at a time."
  [client iso-id]
  (let [scripts (->> (:scripts (try (vm/rpc client "getScripts" {:isolateId iso-id}) (catch Throwable _ nil)))
                     (filter (fn [s] (let [u (str (:uri s))] (and (str/includes? u "cljd-out/") (not (str/includes? u "cljd-out/cljd/")))))))]
    (into #{}
      (for [s scripts
            :let [rep (try (vm/rpc client "getSourceReport"
                             {:isolateId iso-id :reports ["Coverage"] :reportLines true :scriptId (:id s)})
                           (catch Throwable _ nil))
                  uri (str (:uri s))]
            rng (:ranges rep)
            line (get-in rng [:coverage :hits])]
        (str uri ":" line)))))

;; trace skip-list: wrapping a non-value position would break the form.
(def ^:private +trace-special+
  '#{quote fn fn* let let* loop loop* letfn letfn* if do def deftype deftype* defprotocol defprotocol*
     reify reify* try catch finally throw new set! . .. var recur case case* monitor-enter monitor-exit
     ns in-ns dart:async dart})
(defn- trace-member? [h] (and (symbol? h) (.startsWith (name h) ".")))
(defn- record-at [coord x]
  (list 'cljd.flutter/record! (apply str (interpose "," coord)) x))
(defn- instrument
  ([form] (instrument [] form))
  ([coord form]
   (let [f (try (compiler/macroexpand {} form) (catch Throwable _ form))
         top (fn [x] (if (empty? coord) (record-at [] x) x))]
     (cond
       (not (and (seq? f) (seq f) (symbol? (first f))))
       (top f)

       ;; match raw and expanded heads: macroexpand may not fire outside a full compile
       (contains? '#{let let* loop loop*} (first f))
       (let [[op bindings & body] f
             bindings' (vec (mapcat (fn [i [sym val]]
                                      [sym (instrument (conj coord (str "let" i)) val)])
                              (range) (partition 2 bindings)))
             body' (map-indexed (fn [i e] (instrument (conj coord (str "b" i)) e)) body)]
         (top (list* op bindings' body')))

       (= 'if (first f))
       (let [[_ test then else] f]
         (top (list 'if
                (instrument (conj coord "if?") test)
                (instrument (conj coord "then") then)
                (if (> (count f) 3) (instrument (conj coord "else") else) else))))

       (= 'do (first f))
       (top (list* 'do (map-indexed (fn [i e] (instrument (conj coord (str "do" i)) e)) (rest f))))

       (or (contains? +trace-special+ (first f)) (trace-member? (first f)))
       (top f)

       :else
       (record-at coord
         (cons (first f)
           (map-indexed (fn [i a] (instrument (conj coord (inc i)) a)) (rest f))))))))

(defn- tx-snapshot
  "Merge each tx's :delta in order into {id -> value}; later txs win."
  [txs]
  (reduce (fn [m tx] (merge m (:delta tx))) {} txs))

(defn- epoch-indices
  "Indices of the :epoch keyframe txs in TX-LOG."
  [tx-log]
  (vec (keep-indexed (fn [i tx] (when (= :epoch (:cause tx)) i)) tx-log)))

(defn- errors-view
  "The :error entries' data from the timeline."
  [timeline]
  (mapv :data (filter #(= :error (:kind %)) timeline)))

(defn- read-source-forms
  "Top-level forms of FILE with :line/:column/:end-* meta."
  [^String file]
  ;; resolve the file's aliases in its own ns, not the REPL's
  (binding [compiler/*current-ns* (or (compiler/peek-ns file) compiler/*current-ns*)]
    (with-open [r (clojure.lang.LineNumberingPushbackReader. (java.io.FileReader. file))]
      (compiler/with-cljd-reader
        (loop [acc []]
          (let [f (compiler/read {:eof ::eof :read-cond :allow :features #{:cljd}} r)]
            (if (= f ::eof) acc (recur (conj acc f)))))))))

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
  "Innermost collection form in FORMS whose span contains LINE:COL."
  [forms line col]
  (->> (mapcat #(tree-seq coll? seq %) forms)
       (filter #(and (coll? %) (loc-contains? (meta %) line col)))
       (sort-by #(let [m (meta %)] [(- (:line m)) (- (:column m))]))
       first))

(defn- child-locs
  "Direct children of FORM-TEXT's form as {:form :start :end}; reader positions, so bare literals too."
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
  "[start end) offsets in TEXT of PROP's value in FORM, leading whitespace trimmed; nil if absent."
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
  "Resolve a relative cljd path to an existing file under SOURCE-DIRS."
  [source-dirs cljd-path]
  (some (fn [d] (let [f (java.io.File. (str d) ^String cljd-path)]
                  (when (.exists f) (.getPath f))))
    source-dirs))

(def ^:private def-heads '#{def defn defn- defmacro defmulti defonce deftype defrecord defprotocol})

(defn- ns-def-names
  "Sorted public def names in NS-SYM, excluding generated names (containing __ or $)."
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
  "1-based line of the top-level def of NAME-SYM in FILE, or nil (nses keeps no source loc)."
  [file name-sym]
  (some (fn [f]
          (when (and (seq? f) (>= (count f) 2) (contains? def-heads (first f)) (= (second f) name-sym))
            (:line (meta f))))
        (read-source-forms file)))


;; defonce: this state and the live connection survive (require 'cljd.repl.nrepl :reload).
(defonce ^:private +cljd-tx-log+ (atom []))         ; :cause :epoch txs are keyframes
(defonce ^:private +cljd-recording?+ (atom false))  ; gates auto-replay after restart
(defonce ^:private +cljd-coverage-snap+ (atom #{}))
(defonce ^:private +cljd-timeline+ (atom []))
(defonce ^:private +state+ (atom nil))              ; per-connection context, set by make-handler

(defn- record-tl! [kind data]
  (swap! +cljd-timeline+
    (fn [tl] (vec (take-last 500 (conj tl {:kind kind :t (System/currentTimeMillis) :data data}))))))

;; Coalesces a consecutive same phase+message into :count; returns nil when it did.
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

(defn- write-snap! [snap]
  (let [{:keys [client *iso]} @+state+]
    (try (:value (repl-eval/eval-form client @*iso
                   (list 'cljd.flutter/write-state snap)
                   {:ns-lib-uri "cljd/flutter.dart"}))
         (catch Throwable _ nil))))

(defn- replay! []
  (let [snap (tx-snapshot @+cljd-tx-log+)]
    (when (seq snap) {:atoms (count snap) :wrote (write-snap! snap)})))

(defn- refresh-iso! []
  (let [{:keys [client *iso]} @+state+]
    (when-some [i (try (vm/main-isolate-id client) (catch Throwable _ nil))]
      (reset! *iso i))))

;; Runs on the WS-listener future, so it binds the compiler context itself.
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

(defn- stdout-sink [state stream text]
  (when-some [f @(:*eval-sink state)] (f stream text)))

;; Runs on the WS listener thread: anything that rpcs must go in a future (sync rpc deadlocks).
(defn event-sink [state kind data]
  (let [{:keys [ctx *eval-sink]} state]
       (cond
         (= kind "cljd.pick")
         (future
           (try
             (resolve-and-push! ctx data)
             ;; eval! (not eval-form): it binds the compiler context this future lacks
             (repl-eval/eval! ctx focus-active-env-form
               {:ns 'cljd.flutter :ns-lib-uri "cljd/flutter.dart"})
             (catch Throwable _ nil)))

         (= kind "cljd.error")
         (when-some [e (remember-error! {:phase (or (:phase data) "runtime")
                                         :message (:message data) :stack (:stack data)})]
           (when-some [f @*eval-sink]
             (f "Stderr" (str "⚠ [" (:phase e) "] " (:message e)
                              (when (seq (:stack e)) (str "\n" (:stack e))) "\n"))))

         (= kind "cljd.tx")
         (let [delta (try (read-string (:delta data)) (catch Throwable _ nil))
               cause (try (read-string (:cause data)) (catch Throwable _ nil))]
           (when (and (map? delta) (seq delta))
             (let [tx {:tx (count @+cljd-tx-log+) :t (System/currentTimeMillis) :delta delta :cause cause}]
               (swap! +cljd-tx-log+ conj tx)
               (record-tl! :tx tx))))

         (= kind "cljd.tap")
         (let [v (try (read-string (:edn data)) (catch Throwable _ ::unreadable))]
           (when (not= v ::unreadable) (record-tl! :tap v)))

         (= kind "cljd.log")
         (let [v (try (read-string (:edn data)) (catch Throwable _ ::unreadable))]
           (when (not= v ::unreadable) (record-tl! :log v)))

         (= kind "cljd.trace")
         (let [v (try (read-string (:value data)) (catch Throwable _ (:value data)))]
           (record-tl! :trace {:coord (:coord data) :value v}))

         ;; new isolate after hot restart: refresh the id before any eval or replay
         (= kind "cljd.booted")
         (future
           (try
             (refresh-iso!)
             (when @+cljd-recording?+ (replay-settle!))
             (catch Throwable _ nil))))))
(def ^:private no-picker
  {:err "picker unavailable (needs a debug build whose root went through f/run)" :ex "cljd.no-picker"})

(defn- flutter-eval
  "Evaluate FORM against cljd.flutter on the device."
  ([c form] (flutter-eval c form {}))
  ([{:keys [client iso-id]} form opts]
   (repl-eval/eval-form client iso-id form (merge {:ns-lib-uri "cljd/flutter.dart"} opts))))

(defn- read-state [c]
  (let [v (:value (flutter-eval c '(cljd.flutter/read-state) {:await? true}))]
    (try (read-string v) (catch Throwable _ {}))))

(defn- value-or-err [r ex]
  (if (:error r) {:err (:message r) :ex ex} {:value (:value r)}))

;; (cmd c form) -> {:value string} | {:err string :ex string}; c is the request state plus :iso-id and :switch-ns!
(def ^:private repl-commands
  {'in-ns
   (fn [{:keys [switch-ns!]} form]
     (let [target (unwrap-quote (second form))]
       (if (ns-exists? target)
         (do (switch-ns! target) {:value (str target)})
         {:err (str "No such namespace: " target " (only namespaces compiled into the app are available)")
          :ex "cljd.no-such-ns"})))

   'doc
   (fn [{:keys [*current-ns]} form]
     (let [sym (unwrap-quote (second form))]
       {:value (or (format-doc (sym-info @compiler/nses @*current-ns sym)) (str "nothing known about " sym))}))

   'dir
   (fn [_ form]
     (let [nsym (unwrap-quote (second form))]
       (if (ns-exists? nsym)
         {:value (str/join "\n" (ns-def-names @compiler/nses nsym))}
         {:err (str "No such namespace: " nsym) :ex "cljd.no-such-ns"})))

   'apropos
   (fn [_ form]
     (let [pat (str (unwrap-quote (second form)))
           nses @compiler/nses]
       {:value (pr-str (->> (keys nses) (filter symbol?)
                            (mapcat (fn [n] (map #(str n "/" %) (ns-def-names nses n))))
                            (filter #(.contains ^String % pat)) sort vec))}))

   'find-doc
   (fn [_ form]
     (let [pat (str (unwrap-quote (second form)))
           nses @compiler/nses]
       {:value (str/join "\n" (for [n (filter symbol? (keys nses))
                                    nm (filter symbol? (keys (get nses n)))
                                    :let [i (sym-info nses n nm)]
                                    :when (and i (or (.contains (str nm) pat)
                                                     (and (:doc i) (.contains ^String (:doc i) pat))))]
                                (format-doc i)))}))

   'source
   (fn [{:keys [*current-ns source-dirs]} form]
     (let [sym (unwrap-quote (second form))
           i (sym-info @compiler/nses @*current-ns sym)
           path (some-> (:ns i) (str/replace "." "/") (str ".cljd"))
           file (when path (resolve-source-file source-dirs path))
           txt (when (and file (:name i))
                 (try (def-source file (symbol (:name i))) (catch Throwable _ nil)))]
       {:value (or txt (str "source not found for " sym))}))

   'pick!
   (fn [{:keys [pick?] :as c} form]
     (if-not pick?
       no-picker
       {:value (:value (flutter-eval c (list 'cljd.flutter/arm! (if (>= (count form) 2) (not (false? (second form))) true))))}))

   ;; report the active pick, switch to its ns, load its scope into *env
   'picked
   (fn [{:keys [switch-ns!] :as c} _]
     (let [;; scope-loc first: the full pick's gensym env keys can defeat read-string
           loc-v (:value (flutter-eval c '(:ns (:scope-loc (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+))))))
           target (try (read-string loc-v) (catch Throwable _ nil))
           ;; omit env/:rect: not read-string-friendly
           full-r (flutter-eval c '(let [p (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+))]
                                     {:scope-loc (:scope-loc p) :type (:type p)
                                      :src (:src (:widget-loc p)) :cljd (:cljd p)
                                      :env-keys (cljd.core/vec (cljd.core/keys (cljd.flutter/pick-env p)))}))]
       (flutter-eval c focus-active-env-form)
       (when (and (symbol? target) (ns-exists? target)) (switch-ns! target))
       {:value (:value full-r)}))

   'picks
   (fn [{:keys [client iso-id]} _]
     (let [r (try (vm/call-ext client iso-id "ext.cljd.picks" {})
                  (catch Throwable e {:error (.getMessage e)}))]
       {:value (pr-str (:picks r r))}))

   ;; (edit-back! PROP VALUE) splices VALUE into the active pick's source form
   'edit-back!
   (fn [{:keys [source-dirs] :as c} form]
     (let [prop (second form)
           value-str (pr-str (nth form 2 nil))
           cljd-v (:value (flutter-eval c '(:cljd (cljd.core/peek (cljd.core/deref cljd.flutter/+cljd-picks+)))))
           loc (try (read-string cljd-v) (catch Throwable _ nil))
           [path ln col] (when (and (string? loc) (seq loc))
                           (let [ps (.split ^String loc ":")]
                             (when (= 3 (alength ps))
                               (try [(aget ps 0) (Long/parseLong (aget ps 1)) (Long/parseLong (aget ps 2))]
                                    (catch Throwable _ nil)))))
           file (when path (resolve-source-file source-dirs path))]
       (cond
         (not (and (string? loc) (seq loc)))
         {:err "no active pick with a resolved cljd loc — pick a cljd widget first" :ex "cljd.no-pick"}
         (not file)
         {:err (str "can't resolve source file for " path " under " (vec source-dirs)) :ex "cljd.no-file"}
         :else
         (let [text (slurp file)
               wform (form-at (read-source-forms file) ln col)
               span (and wform (prop-value-offsets text wform prop))]
           (if-some [[s e] span]
             (let [old (subs text s e)]
               (spit file (str (subs text 0 s) value-str (subs text e)))
               {:value (str path ":" ln "  " prop ": " old " → " value-str " (saved — watcher reloading)")})
             {:err (str "no property " (pr-str prop) " found in the picked form at " path ":" ln)
              :ex "cljd.no-prop"})))))

   'errors
   (fn [_ form]
     (when (= :clear (second form))
       (swap! +cljd-timeline+ (fn [tl] (vec (remove #(= :error (:kind %)) tl)))))
     {:value (pr-str (errors-view @+cljd-timeline+))})

   ;; (q FORM): eval FORM on the host with errors/timeline/txs/epochs/coverage bound
   'q
   (fn [_ form]
     (let [data {'errors (errors-view @+cljd-timeline+)
                 'timeline @+cljd-timeline+
                 'txs @+cljd-tx-log+
                 ;; each epoch as the state map at its cut-point
                 'epochs (mapv #(tx-snapshot (take (inc %) @+cljd-tx-log+)) (epoch-indices @+cljd-tx-log+))
                 'coverage @+cljd-coverage-snap+}]
       {:value (pr-str (try (eval (list 'let (vec (mapcat (fn [[k v]] [k (list 'quote v)]) data)) (second form)))
                            (catch Throwable e {:q-error (.getMessage e)})))}))

   ;; (picks-do FORM): run FORM with *env bound to each pick's env; returns a vector
   'picks-do
   (fn [{:keys [pick? remember?] :as c} form]
     (if-not pick?
       no-picker
       ;; :remember? makes eval rewrite *env to its holder
       (value-or-err (flutter-eval c (list 'cljd.core/mapv
                                           (list 'cljd.core/fn ['p]
                                                 (list 'set! 'cljd.core/+cljd-repl-env+ (list 'cljd.flutter/pick-env 'p))
                                                 (second form))
                                           '(cljd.core/deref cljd.flutter/+cljd-picks+))
                                   {:remember? remember?})
                     "cljd.eval-error")))

   ;; (cljd-src "a/b.dart" 249) -> "a/b.cljd:78:12"
   'cljd-src
   (fn [_ form]
     {:value (pr-str (or (resolve-wloc (:libs @compiler/nses) (str (second form) ":" (nth form 2))) "unresolved"))})

   ;; host-side: cljd macros are compile-time
   'macroexpand
   (fn [_ form] {:value (pr-str (compiler/macroexpand {} (unwrap-quote (second form))))})

   'macroexpand-1
   (fn [_ form] {:value (pr-str (compiler/macroexpand-1 {} (unwrap-quote (second form))))})

   ;; (dart-of '(...)): the Dart the compiler emits for a form
   'dart-of
   (fn [_ form]
     {:value (try (compiler/form->dart-expr (unwrap-quote (second form)))
                  (catch Throwable e (str "compile error: " (errors/format-compile e))))})

   ;; (trace 'form): sub-expression values stream to (timeline :trace)
   'trace
   (fn [{:keys [client iso-id *current-ns trigger-reload]} form]
     (let [f (unwrap-quote (second form))]
       (value-or-err (repl-eval/eval-form client iso-id (try (instrument f) (catch Throwable _ f))
                       {:ns-lib-uri (ns->lib-uri @*current-ns) :trigger-reload trigger-reload})
                     "cljd.trace-error")))

   'states
   (fn [c _] {:value (:value (flutter-eval c '(cljd.flutter/read-state) {:await? true}))})

   'epoch!
   (fn [c _]
     (let [state (read-state c)]
       (swap! +cljd-tx-log+ conj {:tx (count @+cljd-tx-log+) :t (System/currentTimeMillis) :delta state :cause :epoch})
       {:value (str "epoch " (dec (count (epoch-indices @+cljd-tx-log+))) " recorded (" (count state) " atoms → tx-log)")}))

   'epochs
   (fn [_ _] {:value (str (count (epoch-indices @+cljd-tx-log+)) " epochs (markers into the tx-log)")})

   'restore-epoch!
   (fn [_ form]
     (let [i (second form)]
       (if-some [idx (nth (epoch-indices @+cljd-tx-log+) i nil)]
         {:value (str "directed device to epoch " i " (" (write-snap! (tx-snapshot (take (inc idx) @+cljd-tx-log+))) " atoms)")}
         {:err (str "no epoch " i) :ex "cljd.no-epoch"})))

   'record!
   (fn [c form]
     (if (if (>= (count form) 2) (not (false? (second form))) true)
       (let [base (read-state c)]
         (reset! +cljd-tx-log+ [{:tx 0 :t (System/currentTimeMillis) :delta base :cause :record/base}])
         (reset! +cljd-recording?+ true)
         (flutter-eval c '(cljd.flutter/arm-recording! true))
         {:value (str "recording on (" (count base) " base atoms)")})
       (do (reset! +cljd-recording?+ false)
           (flutter-eval c '(cljd.flutter/arm-recording! false))
           {:value "recording off"})))

   'txs
   (fn [_ _] {:value (str (count @+cljd-tx-log+) " transactions recorded")})

   'seek!
   (fn [_ form]
     (let [n (second form)
           snap (tx-snapshot (take (inc n) @+cljd-tx-log+))]
       (if (seq snap)
         {:value (str "sought to tx " n " (" (write-snap! snap) " atoms)")}
         {:err (str "no transactions up to " n " (record! first?)") :ex "cljd.no-tx"})))

   'replay!
   (fn [_ _]
     (if-some [res (replay!)]
       {:value (str "replayed " (:atoms res) " atoms → " (:wrote res) " written")}
       {:value "nothing to replay (record! first?)"}))

   'restart!
   (fn [{:keys [trigger-restart]} _]
     (if trigger-restart
       (do (trigger-restart)
           {:value (if @+cljd-recording?+
                     "hot restart requested (state will replay on re-mount)"
                     "hot restart requested (recording off → clean slate)")})
       {:err "no restart trigger wired (flutter not running?)" :ex "cljd.no-restart"}))

   ;; (ran): cljd locs newly executed since the last (coverage) snapshot
   'coverage
   (fn [{:keys [client iso-id]} _]
     (let [cov (cljd-coverage client iso-id)]
       (reset! +cljd-coverage-snap+ cov)
       {:value (str (count cov) " dart lines covered — snapshot taken; interact then (ran)")}))

   'ran
   (fn [{:keys [client iso-id]} _]
     (let [now (cljd-coverage client iso-id)
           fresh (remove @+cljd-coverage-snap+ now)]
       (reset! +cljd-coverage-snap+ now)
       {:value (pr-str (->> fresh (keep #(dart-uri->cljd (:libs @compiler/nses) %)) distinct sort vec))}))

   'taps
   (fn [_ _] {:value (pr-str (mapv :data (filter #(= :tap (:kind %)) @+cljd-timeline+)))})

   'timeline
   (fn [_ form]
     (let [k (second form)]
       {:value (pr-str (if k (filterv #(= k (:kind %)) @+cljd-timeline+) @+cljd-timeline+))}))})

(defn- user-def?
  "True when SYM resolves to a def outside the cljd.* namespaces; such a def wins over a REPL command of the same name."
  [sym]
  (let [[tag e] (try (compiler/resolve-symbol sym {}) (catch Throwable _ nil))]
    (and (= :def tag) (some? (:ns e)) (not (str/starts-with? (name (:ns e)) "cljd.")))))

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
        "complete"
        (let [prefix (or (:prefix msg) (:symbol msg) "")
              ns-sym (msg-ns msg *current-ns)
              nses   @compiler/nses
              ;; defs are symbol keys of the ns map; :mappings holds referred/aliased names
              names  (mapcat (fn [n]
                               (let [m (get nses n)]
                                 (concat (filter symbol? (keys m)) (keys (:mappings m)))))
                             [ns-sym 'cljd.core])
              cands  (->> names (map name) distinct
                          (filter #(.startsWith ^String % prefix))
                          sort (take 100)
                          (mapv (fn [c] {:candidate c :ns (name ns-sym)})))]
          (send! {:completions cands :status ["done"]}))
        ("info" "lookup")
        (let [sym  (msg-sym msg)
              i    (sym-info @compiler/nses (msg-ns msg *current-ns) (symbol sym))
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
            (if-let [d (dart-info ctx (msg-ns msg *current-ns) (symbol sym))]
              (send! (cond-> {:name (:name d) :ns "dart" :arglists-str "" :doc (or (:doc d) "")
                              :status ["done"]}
                       (:file d) (assoc :file (:file d))
                       (:line d) (assoc :line (:line d))))
              (send! {:status ["done" "no-info"]}))))
        "eldoc"
        (let [i (sym-info @compiler/nses (msg-ns msg *current-ns) (symbol (msg-sym msg)))]
          (if (and i (:arglists i))
            (send! {:name (:name i) :ns (:ns i) :type "function"
                    :eldoc (mapv (fn [al] (mapv str al)) (:arglists i))
                    :status ["done"]})
            (send! {:status ["done" "no-eldoc"]})))
        "eval"
        ;; nREPL semantics: a message :ns scopes this batch only; without it, in-ns persists in the session
        (let [msg-ns (let [n (some-> (:ns msg) symbol)]
                       ;; clients default to user (JVM's initial ns); cljd has none, so it means the session ns
                       (when-not (and (= 'user n) (not (ns-exists? 'user))) n))
              *batch-ns (atom (or msg-ns @*current-ns))]
         (if (and msg-ns (not (ns-exists? msg-ns)))
          (send! {:ns (name msg-ns) :status ["done" "error" "namespace-not-found"]})
        ;; one binding frame for the whole batch (in-ns set!s in it); use eval-form inside, not eval!
        (repl-eval/with-compiler-context ctx @*batch-ns
          (reset! *eval-sink (fn [stream text]
                               (send! {(if (= stream "Stderr") :err :out)
                                       (.replaceAll text "(?m)^flutter: " "")})))
          (let [errored (volatile! false)
                switch-ns! (fn [ns-sym]            ; keep atom + per-batch dynamic binding in sync
                             (reset! *batch-ns ns-sym)
                             (set! compiler/*current-ns* ns-sym))]
            (try
              (doseq [form (read-forms code)]
                (let [head (and (seq? form) (first form))
                      ;; deref per form: the isolate changes on hot restart
                      iso-id @*iso]
                  (if-some [cmd (when-some [cmd (get repl-commands head)] (when-not (user-def? head) cmd))]
                    (let [r (cmd (assoc state :iso-id iso-id :switch-ns! switch-ns! :*current-ns *batch-ns) form)]
                      (if (:err r)
                        (do (vreset! errored true) (send! r))
                        (send! {:value (:value r) :ns (name @*batch-ns)})))
                    (let [r (repl-eval/eval-form client iso-id form
                                                 {:ns-lib-uri (ns->lib-uri @*batch-ns)
                                                  :trigger-reload trigger-reload
                                                  :await? await?
                                                  :remember? remember?})]
                      (case (:kind r)
                        :reload (do (when (= 'ns head) (switch-ns! (second form)))
                                    (send! {:value (str "#reloaded " (pr-str (:report r))) :ns (name @*batch-ns)}))
                        :eval   (if (:error r)
                                  (do (vreset! errored true)
                                      (let [msg (errors/format-runtime (:message r)
                                                  (fn [dl] (resolve-wloc (:libs @compiler/nses) dl)))
                                            phase (if (= "LanguageError" (:dart-kind r)) "compile" "runtime")]
                                        (remember-error! {:phase phase :message msg :stack nil})
                                        (send! {:err msg :ex "dart.runtime-exception"})))
                                  (send! {:value (:value r) :ns (name @*batch-ns)})))))))
              (send! {:status (if @errored ["done" "error"] ["done"])})
              (catch Throwable e
                (let [msg (errors/format-compile e)]
                  (remember-error! {:phase "compile" :message msg :stack nil})
                  (send! {:err msg :ex (str (class e)) :status ["done" "error"]})))
              (finally
                (reset! *eval-sink nil)
                (when-not msg-ns (reset! *current-ns @*batch-ns))))))))
        (send! {:status ["done" "error" "unknown-op"]}))))

(defn make-handler
  "cfg: {:client :iso-id :analyzer :dart-version :*current-ns :ns-lib-uri :trigger-reload :trigger-restart :source-dirs :await? :pick? :remember?}"
  [{:keys [client iso-id analyzer dart-version *current-ns ns-lib-uri trigger-reload trigger-restart source-dirs await? pick? remember?]
    :or {ns-lib-uri "cljd/core.dart"}
    :as cfg}]
  (let [;; current main isolate id; changes on hot restart (see refresh-iso!)
        *iso (atom iso-id)
        ctx (repl-eval/context (assoc cfg :*iso *iso :default-ns 'cljd.core))
        *eval-sink (atom nil)
        state {:client client :*iso *iso :ctx ctx :*eval-sink *eval-sink
               :*current-ns *current-ns :ns-lib-uri ns-lib-uri
               :trigger-reload trigger-reload :trigger-restart trigger-restart
               :source-dirs source-dirs :await? await? :pick? pick? :remember? remember?}]
    (reset! +state+ state)
    ;; #'var trampolines: (require ... :reload) swaps code while the connection stays live
    (vm/set-sink! client (fn [stream text] (#'stdout-sink @+state+ stream text)))
    (vm/set-event-sink! client (fn [kind data] (#'event-sink @+state+ kind data)))
    (fn [msg] (#'handle-request @+state+ msg))))

(defn start!
  "Start the nREPL server. Returns the nrepl server (has :port). Writes .nrepl-port."
  [{:keys [port] :or {port 0} :as cfg}]
  (let [server (nrepl-server/start-server :port port :handler (make-handler cfg))]
    (spit ".nrepl-port" (str (:port server)))
    server))
