;   Copyright (c) Baptiste Dupuch & Christophe Grand . All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljd.build
  (:require [cljd.compiler :as compiler]
            [cljd.repl.vmservice :as vmservice]
            [cljd.repl.eval :as repl-eval]
            [cljd.repl.errors :as errors]
            [cljd.repl.nrepl :as repl-nrepl]
            [clojure.edn :as edn]
            [clojure.tools.deps :as deps]
            [clojure.string :as str]
            [clojure.stacktrace :as st]
            [clojure.set :as set]
            [clojure.java.io :as io]))

(def ^:dynamic *ansi* false)
(def ^:dynamic *deps*)

(defn compile-core []
  (compiler/compile 'cljd.core))

(defn watch-dirs-until [stop? init dirs reload]
  (with-open [watcher (.newWatchService (java.nio.file.FileSystems/getDefault))]
    (let [reg1
          (fn [^java.io.File dir]
            (when (.isDirectory dir)
              [(.register (.toPath dir) watcher (into-array [java.nio.file.StandardWatchEventKinds/ENTRY_CREATE
                                                             java.nio.file.StandardWatchEventKinds/ENTRY_DELETE
                                                             java.nio.file.StandardWatchEventKinds/ENTRY_MODIFY
                                                             java.nio.file.StandardWatchEventKinds/OVERFLOW])
                 (into-array [com.sun.nio.file.SensitivityWatchEventModifier/HIGH]))
               (.toPath dir)]))
          reg*
          (fn [dir]
            (eduction (keep reg1) (tree-seq some? #(.listFiles ^java.io.File %) dir)))]
      (loop [ks->dirs (into {} (mapcat reg*) dirs) to-reload #{} state init]
        (if (stop? state)
          state
          (if-some [k (.poll watcher (if (seq to-reload) 10 1000) java.util.concurrent.TimeUnit/MILLISECONDS)]
            (let [events (.pollEvents k)] ; make sure we remove them, no matter what happens next
              (if-some [^java.nio.file.Path dir (ks->dirs k)]
                (let [[ks->dirs to-reload]
                      (reduce (fn [[ks->dirs to-reload] ^java.nio.file.WatchEvent e]
                                (let [f (some->> e .context (.resolve dir) .toFile)]
                                  (if (and (some? f) (not (.isDirectory f))
                                        (re-matches #"[^.].*\.clj[dc]" (.getName f)))
                                    [(into ks->dirs (some->> f reg*))
                                     (conj to-reload f)]
                                    [ks->dirs to-reload])))
                        [ks->dirs to-reload] events)]
                  (recur (cond-> ks->dirs (not (.reset k)) (dissoc ks->dirs k)) to-reload state))
                (do
                  (.cancel k)
                  (recur ks->dirs to-reload state))))
            (recur ks->dirs #{} (reload state to-reload))))))))

(defn title [s]
  (if *ansi*
    (str "\u001B[1m" s "\u001B[0m")
    (str "=== " s " ===")))

(defn bright [s]
  (if *ansi*
    (str "\u001B[1m" s "\u001B[0m")
    s))

(defn muted [s]
  (if *ansi*
    (str "\u001B[2m" s "\u001B[0m")
    s))

(defn inverted [s]
  (if *ansi*
    (str "\u001B[7m" s "\u001B[0m")
    s))

(defn green [s]
  (if *ansi*
    (str "\u001B[1;32m" s "\u001B[0m")
    s))

(defn red [s]
  (if *ansi*
    (str "\u001B[1;31m" s "\u001B[0m")
    s))

(defn success []
  (str (green "Compilation succeeded") " - "
    (rand-nth
      ["All clear! 👌"
       "You rock! 🤘"
       "Bravissimo! 👏"
       "Easy peasy! 😎"
       "I like when a plan comes together! 👨‍🦳"])))

(defn compilation-error-heading []
  (str (red "Compilation error") " - "
    (rand-nth
      ["Oh noes! 😵"
       "Something horrible happened! 😱"
       "$expletives 💩"
       "Keep calm and fix bugs! 👑"
       "What doesn’t kill you, makes you stronger. 🤔"
       "You’re gonna need a bigger boat! 🦈"])))

(defn- exception-chain [e]
  (take-while some? (iterate ex-cause e)))

(defn- bounded-str [s]
  (let [max-chars 2000]
    (if (<= (count s) max-chars)
      s
      (str (subs s 0 (- max-chars 3)) "..."))))

(defn- bounded-pr-str [form]
  (bounded-str
    (binding [*print-length* 20
              *print-level* 6]
      (pr-str form))))

(defn- emit-exception [chain]
  (some #(when (contains? (ex-data %) ::compiler/emit-stack) %) chain))

(defn print-exception [e]
  (let [chain (vec (exception-chain e))
        emit-error (emit-exception chain)
        root-error (peek chain)
        context (when-not (identical? e emit-error) (ex-message e))]
    (println (compilation-error-heading))
    (when context
      (println (bounded-str context)))
    (if emit-error
      (let [[form & parents] (::compiler/emit-stack (ex-data emit-error))
            cause-message (or (some-> emit-error ex-cause ex-message)
                            (ex-message emit-error))
            toplevel (last (take-while #(not (and (seq? %) (= 'ns (first %)))) parents))]
        (when cause-message
          (println (title (bounded-str cause-message))))
        (when form
          (if toplevel
            (do
              (println (title "Faulty subform and/or expansion"))
              (println " " (bounded-pr-str form))
              (println (title "While compiling"))
              (println " " (bounded-pr-str toplevel)))
            (do
              (println (title "Faulty form"))
              (println " " (bounded-pr-str form))))))
      (let [root-message (some-> root-error ex-message)
            {:clojure.error/keys [line column]}
            (some #(when (or (:clojure.error/line (ex-data %))
                           (:clojure.error/column (ex-data %)))
                     (ex-data %))
              chain)]
        (when (and root-message (not= root-message context))
          (println (title (bounded-str root-message))))
        (when line
          (println (str "at line " line (when column (str ", column " column)))))))))

(defn timestamp []
  (.format (java.text.SimpleDateFormat. "@HH:mm:ss" (java.util.Locale/getDefault)) (java.util.Date.)))

;; hot reload keeps stale :managed/:watch/defonce captures: warn to restart when their shape changes

(defn- read-cljd-forms
  "Reads every top-level form of a .cljd file; nil on any failure."
  [^java.io.File f]
  (try
    (binding [compiler/*current-ns* (or (compiler/peek-ns f) 'cljd.core)]
      (compiler/with-cljd-reader
        (with-open [r (clojure.lang.LineNumberingPushbackReader. (io/reader f))]
          (loop [forms []]
            (let [form (compiler/read {:eof ::eof :read-cond :allow :features #{:cljd}} r)]
              (if (identical? form ::eof)
                forms
                (recur (conj forms form))))))))
    (catch Throwable _ nil)))

(defn- binding-signature
  "Set of :managed/:watch binding vectors and defonce names in forms."
  [forms]
  (let [acc (volatile! #{})]
    (letfn [(scan-pairs [xs]
              (loop [xs (seq xs)]
                (when xs
                  (let [k (first xs) more (next xs)]
                    (when (and (or (= k :managed) (= k :watch))
                               more (vector? (first more)))
                      (vswap! acc conj (pr-str [k (first more)])))
                    (recur more)))))
            (walk [x]
              (cond
                (and (seq? x) (seq x))
                (do (when (and (= 'defonce (first x)) (symbol? (second x)))
                      (vswap! acc conj (pr-str [:defonce (second x)])))
                    (scan-pairs x)
                    (run! walk x))
                (vector? x) (do (scan-pairs x) (run! walk x))
                (map? x) (run! walk (interleave (keys x) (vals x)))
                (set? x) (run! walk x)
                :else nil))]
      (run! walk forms))
    @acc))

(defn- binding-signature-of [^java.io.File f]
  (some-> (read-cljd-forms f) binding-signature))

(defn- warn-binding-shape-change!
  "Prints a restart advisory when f's signature changed in the sigs atom; true iff warned."
  [sigs ^java.io.File f]
  (let [path (.getCanonicalPath f)
        new-sig (binding-signature-of f)
        old-sig (@sigs path)]
    (when new-sig (swap! sigs assoc path new-sig))
    (when (and new-sig old-sig (not= old-sig new-sig))
      (let [added (set/difference new-sig old-sig)
            removed (set/difference old-sig new-sig)]
        (newline)
        (println (bright (str ";;;; ⚠ binding-shape change in " (.getName f))))
        (println (bright ";;;; hot reload keeps stale :managed/:watch/defonce state — press R to hot-restart."))
        (doseq [s removed] (println (str "     - " s)))
        (doseq [s added]   (println (str "     + " s))))
      true)))

(defn exec
  "If first arg is a map, it's an option map.
   Supported options:
   :in, :out and :err are either nil, a File or a ProcessBuilder$Redirect -- default to ProcessBuilder$Redirect/INHERIT
   :env a map of extra environment variables (strings to strings)
   :dir the current directory
   :async a boolean (defaults to false), when true exec returns immediatly.
   When async, exec returns a Process.
   When not async exec returns nil (for exit code 0) or non-zero exit code"
  [& args]
  (let [{:keys [async in err out env dir] :as opts
         :or {env {}
              in java.lang.ProcessBuilder$Redirect/INHERIT
              err java.lang.ProcessBuilder$Redirect/INHERIT
              out java.lang.ProcessBuilder$Redirect/INHERIT}}
        (when (map? (first args)) (first args))
        [bin & args] (cond-> args opts next)
        pb (doto (ProcessBuilder. [])
             (-> .environment (.putAll env))
             (cond->
                 in (.redirectInput in)
                 err (.redirectError err)
                 out (.redirectOutput out)
                 dir (.directory (io/file dir))))
        os-is-windows (.startsWith (System/getProperty "os.name") "Windows")
        path (if os-is-windows
               (or (-> pb .environment (get "Path")) (-> pb .environment (get "PATH")))
               (-> pb .environment (get "PATH")))
        bins (if os-is-windows [(str bin ".exe") (str bin ".bat")] [bin])
        full-bin
        (or
          (first
            (for [bin bins
                  dir (cons ".fvm/flutter_sdk/bin" (.split path java.io.File/pathSeparator))
                  :let [file (java.io.File. dir bin)]
                  :when (and (.isFile file) (.canExecute file))]
              (.getAbsolutePath file)))
          (throw (ex-info (str "Can't find " (str/join " nor " bins) " on PATH.")
                   {:bin bin :path path})))
        process (.start (doto pb (.command (into [full-bin] args))))]
    (if-not async
      (let [exit-code (.waitFor process)]
        (when-not (zero? exit-code) exit-code))
      process)))

(defn del-tree [^java.io.File f]
  (run! del-tree
    (when-not (java.nio.file.Files/isSymbolicLink (.toPath f))
      (.listFiles f)))
  (.delete f))

(defn try-ensure-cljd-analyzer! [{:keys [pubspec analyzer-dep resource-name]}]
  (let [cljd-sha (get-in *deps* [:libs 'tensegritics/clojuredart :git/sha])
        parent-dir (-> (System/getProperty "user.dir") (java.io.File. ".clojuredart") (java.io.File. "cache")
                     (java.io.File. (or cljd-sha "dev")))
        parent-dir (doto parent-dir (cond-> (not cljd-sha) del-tree) .mkdirs)
        analyzer-dir (doto (java.io.File. parent-dir "cljd_helper") .mkdirs)
        analyzer-dart (-> analyzer-dir (java.io.File. "bin") (doto .mkdirs) (java.io.File. "analyzer.dart"))
        pubspec-yaml (-> analyzer-dir (java.io.File. "pubspec.yaml"))]
    (when-not (.exists pubspec-yaml)
      (with-open [out (java.io.FileOutputStream. pubspec-yaml)]
        (-> ^String pubspec
          (.getBytes java.nio.charset.StandardCharsets/UTF_8)
          java.io.ByteArrayInputStream.
          (.transferTo out))))
    (when-not (.exists analyzer-dart)
      ; to move beyond analyzer 7.0.0 we need to move the min version to at least 6.5.2
      ; (changes to our analyzer.dart are required and they would break compat with 6.2.0)
      ; we are willing to support 6.2 for now as it means supporting dartlang < 3.3 (3.3 mandatory starting 6.3.0)
      ; maybe we should consider having multiple copies of our own analyzer.dart
      (if (exec {:dir analyzer-dir} (some-> *deps* :cljd/opts :kind name) "pub" "add" analyzer-dep)
        ; `pub add` failure
        (del-tree analyzer-dir)
        ; `pub add` worked
        (with-open [out (java.io.FileOutputStream. analyzer-dart)]
          (-> (Thread/currentThread) .getContextClassLoader (.getResourceAsStream ^String resource-name) (.transferTo out)))))
    (when (.exists analyzer-dir)
      (.getPath analyzer-dir))))

(defn ensure-cljd-analyzer! []
  (some try-ensure-cljd-analyzer!
    [{:pubspec "name: cljd_helper\n\nenvironment:\n  sdk: '>=3.0.0 <4.0.0'\n"
      :analyzer-dep "analyzer:'>=6.2.0 <7.0.0'"
      :resource-name "analyzer.dart"}
     {:pubspec "name: cljd_helper\n\nenvironment:\n  sdk: '>=2.17.0 <4.0.0'\n"
      :analyzer-dep "analyzer:5.13.0"
      :resource-name "analyzer_legacy_dart2.dart"}]))

(defmacro with-taps [fns & body]
  `(let [fns# [~@fns]]
     (try
       (run! add-tap fns#)
       ~@body
       (finally
         (run! remove-tap fns#)))))

(defmacro ^:private daemon [& forms]
  `(doto (Thread. (fn [] ~@forms))
    (.setDaemon true)
    .start))

(defn bsearch
  "pred is monotonic false -> true through v.
   Returns the last element for which pred is false"
  [v pred]
  (cond
    (empty? v) nil
    (pred (first v)) nil
    (not (pred (peek v))) (peek v)
    :else
    (loop [i 0 j (count v)]
      #_(assert (not (pred (nth v i))))
      (let [m (quot (+ i j) 2)
            x (nth v m)]
        (cond
          (= i m) x
          (pred x) (recur i m)
          :else (recur m j))))))

(defn smap-search [lib-smap line col]
  (when-some [[dart-line dart-col {:keys [slug smap]}]
              (bsearch lib-smap
                (fn [[dart-line dart-col smap]]
                  (or
                    (< line dart-line)
                    (and (= line dart-line)
                      (or (nil? col) (< col dart-col))))))]
    (let [line (- line (dec dart-line)) ; lines are 1-based
          col (if (and col (zero? line)) (- col dart-col) col)]
      (->
        (bsearch smap
          (fn [[dart-line dart-col smap]]
            (or
              (< line dart-line)
              (and (= line dart-line)
                (or (nil? col) (< col dart-col))))))
        peek
        (assoc :slug slug)))))

(defn excerpt-and-highlight [{:keys [line column end-line end-column url]}]
  (when (= line end-line)
    (let [r (io/reader url)
          _ (dotimes [_ (dec line)] (.readLine r))
          src (.readLine r)]
      (str "\n👉" (subs src 0 (dec column))
        (inverted (subs src (dec column) (dec end-column)))
        (subs src (dec end-column))))))

(def smap-line
  (let [mk-smap-line
        (fn mk-smap-line []
          (let [{:keys [libs]} @compiler/nses
                libspat
                (->> libs
                  (keep (fn [[libname {:keys [smap]}]]
                          (when smap
                            (-> libname
                              (str/replace #"^lib/" "/")
                              java.util.regex.Pattern/quote))))
                  (str/join "|"))
                pat (re-pattern (str "\\w+:[^:]+?(" libspat "):(\\d+)(?::(\\d+))"))]
            (fn [line]
              (when (identical? libs (:libs @compiler/nses))
                (let [*source-info (volatile! nil)
                      line
                      (str/replace line pat
                        (fn [[match lib line col]]
                          (let [{:keys [ns smap]} (get libs (str "lib" lib))
                                {:keys [line column slug end-line end-column file url]
                                 :as source-info}
                                (smap-search smap (parse-long line) (some-> col parse-long))]
                            (vreset! *source-info source-info)
                            (str (bright ns) (subs slug 0 1) (bright (subs slug 1)) " " (bright file) ":" (bright line) ":" (bright column) " " (muted match)))))]
                  (cond-> line
                    @*source-info (str (excerpt-and-highlight @*source-info))))))))
        *smap-line (atom (mk-smap-line))]
    (fn [line]
      (if-some [line (@*smap-line line)]
        line
        (do
          (reset! *smap-line (mk-smap-line))
          (recur line))))))

(defn ensure-no-existing!
  "exit if active REPL"
  []
  (let [f (java.io.File. "REPL.lock")]
    (when (.exists f)
      (let [[port pid] (re-seq #"\S+" (slurp f))]
        (when (some-> pid parse-long
                java.lang.ProcessHandle/of
                ^java.lang.ProcessHandle (.orElse nil)
                .isAlive)
          (println (str "Another ClojureDart process is running (PID " pid ")"))
          (System/exit 1))))))

(defn compile-cli
  [& {:keys [watch namespaces flutter offline] :or {watch false}}]
  (let [user-dir (System/getProperty "user.dir")
        analyzer-dir (ensure-cljd-analyzer!)]
    ;; JVM nREPL for in-process compiler reload; port in .nrepl-port-jvm, not the device's .nrepl-port
    (when (or watch flutter)
      (try
        (let [port (:port ((requiring-resolve 'nrepl.server/start-server) :port 0))]
          (spit ".nrepl-port-jvm" (str port))
          (println (title "🧬 build JVM nREPL") "on port" port
            "— (require 'cljd.compiler :reload) to hot-reload the compiler"))
        (catch Throwable e
          (println "[build-nrepl] failed to start:" (.getMessage e)))))
    (if offline
      (println "Offline mode: No pub dependencies will be updated")
      (exec {:in nil :out nil} (some-> *deps* :cljd/opts :kind name) "pub" "get"))
    (with-taps
      [(fn [x]
         (case (::compiler/msg-kind x)
           :compile-ns
           (println " - compiling" (:ns x) (timestamp))
           :dump-ns
           (println " - writing compiled" (:ns x) "to" (:lib x) (timestamp))))]
      (binding [compiler/*hosted* true
                compiler/*dart-version* nil
                compiler/analyzer-info nil]
        (set! compiler/analyzer-info
          (compiler/mk-live-analyzer-info (exec {:async true :in nil :out nil :dir analyzer-dir}
                                            (some-> *deps* :cljd/opts :kind name)
                                            "pub" "run" "bin/analyzer.dart" user-dir)))
        (newline)
        (println (title (str "Compiling cljd.core to Dart "
                          (case compiler/*dart-version*
                            :dart2 "2"
                            :dart3 "3"))))
        (compile-core)
        (let [dirs (into #{} (map #(java.io.File. %))
                     (concat (:paths *deps*)
                       (mapcat (fn [{:keys [local/root paths]}]
                                 (when root paths))
                         (vals (:libs *deps*)))))
              dirty-nses (volatile! #{})
              *compiler-state (atom {:recompile-count 0
                                     :restart-count 0})
              ;; set once the VM-Service REPL connects: reports compile failures on the device
              *reload-error-sink (atom nil)
              ;; path->fragile-binding-signature baseline, for restart-needed advisories
              binding-sigs (atom {})
              compile-nses
              (fn [nses]
                (let [nses (into @dirty-nses nses)]
                  (vreset! dirty-nses #{})
                  (when (seq nses)
                    (newline)
                    (println (title "Compiling to Dart...") (timestamp))
                    (run! #(println " " %) (sort nses))
                    (try
                      (compiler/recompile nses (:recompile-count (swap! *compiler-state update :recompile-count inc)))
                      (println (success) (timestamp))
                      true
                      (catch Exception e
                        (vreset! dirty-nses nses)
                        (print-exception e)
                        ;; surface the failure on the device too (if the REPL is connected)
                        (when-some [sink @*reload-error-sink]
                          (try (sink (errors/format-compile e)) (catch Throwable _ nil)))
                        false)))))
              compilation-success (compile-nses namespaces)]
          ;; seed baselines so the first shape-changing edit warns
          (doseq [^java.io.File d dirs
                  ^java.io.File f (file-seq d)
                  :when (and (.isFile f) (.endsWith (.getName f) ".cljd"))]
            (swap! binding-sigs assoc (.getCanonicalPath f)
              (or (binding-signature-of f) #{})))
          (if (or watch flutter)
            (let [compile-files
                  (fn [^java.io.Writer flutter-stdin]
                    (fn [_ files]
                      (locking *compiler-state
                        (doseq [^java.io.File f files
                                :when (.endsWith (.getName f) ".cljd")]
                          (warn-binding-shape-change! binding-sigs f))
                        (when (some->
                                (for [^java.io.File f files
                                      :let [fp (.toPath f)]
                                      ^java.io.File d dirs
                                      :let [dp (.toPath d)]
                                      :when (.startsWith fp dp)
                                      :let [ns (compiler/peek-ns f)]
                                      :when ns]
                                  ns)
                                seq
                                compile-nses)
                          (when flutter-stdin
                            (locking flutter-stdin
                              (doto flutter-stdin (.write "r") .flush)))))))]
              (watch-dirs-until true? (or (boolean compilation-success) :first)
                dirs
                (fn [state changes]
                  (if (or (= :first state) (seq changes))
                    (boolean (compile-nses namespaces))
                    state)))
              (newline)
              (when flutter
                (println (title (str/join " " (into ["Launching flutter run"] flutter)))))
              (let [p (some->> flutter
                        (apply exec {:async true :in nil :out nil :env {"TERM" ""}} "flutter" "run"))]
                (try
                  (let [flutter-stdin (some-> p .getOutputStream (java.io.OutputStreamWriter. "UTF-8"))
                        flutter-stdout (some-> p .getInputStream (java.io.InputStreamReader. "UTF-8") java.io.BufferedReader.)
                        ansi *ansi*
                        q (java.util.concurrent.SynchronousQueue.)
                        true-out *out*
                        trigger-reload (fn ([] (.put q {:kind :reload}))
                                         ([done] (.put q {:kind :reload :done done})))
                        vm-uri-p (promise)   ; resolves with the app's VM-Service ws URI
                        ;; (restart!) op: same as typing R; replay is driven by the device's cljd.booted event, not here
                        trigger-restart (fn [] (when flutter-stdin
                                                 (locking flutter-stdin
                                                   (doto flutter-stdin (.write "R") .flush))))]
                    ; Unimplemented handling of missing static target
                    (when (and flutter-stdin flutter-stdout)
                      (daemon
                        ;; read-line is nil at EOF (non-interactive stdin); writing nil would NPE
                        (loop []
                          (when-some [s (read-line)]
                            (locking flutter-stdin
                              (doto flutter-stdin
                                (.write (case s "" "R" s))
                                .flush))
                            (recur))))

                      (daemon
                        (loop []
                          (when-some [line (some-> (.readLine flutter-stdout) smap-line)]
                            ;; capture the app's VM-Service URI for the vmservice REPL path
                            (when (and (not (realized? vm-uri-p))
                                    (re-find #"Dart VM Service.*available at:" line))
                              (when-some [[_ http] (re-find #"available at:\s*(http://\S+)" line)]
                                (let [base (str/replace http #"^http" "ws")
                                      base (if (str/ends-with? base "/") base (str base "/"))]
                                  (deliver vm-uri-p (str base "ws")))))
                            (.put q {:kind :line :line line})
                            (recur)))
                        (.put q {:kind :eof}))

                      ;; capture the compiler context here: dynamic bindings don't cross into daemon threads
                      (when (System/getenv "CLJD_VMREPL")
                        (let [analyzer compiler/analyzer-info
                              dartv compiler/*dart-version*]
                          (daemon
                            (when-some [uri (deref vm-uri-p 180000 nil)]
                              (Thread/sleep 8000)   ; let the app render a frame / settle
                              (binding [*out* true-out
                                        compiler/*hosted* true
                                        compiler/*dart-version* dartv
                                        compiler/analyzer-info analyzer
                                        compiler/dynamic-warning compiler/on-dynamic-warn
                                        compiler/*current-ns* 'cljd.core]
                                (try
                                  (let [client (vmservice/connect uri)
                                        iso (vmservice/main-isolate-id client)
                                        _ (vmservice/listen-streams! client)]
                                    ;; inject Future helpers into cljd.core; gates :await?
                                    (let [await-ok
                                          (try
                                            (:success
                                             (repl-eval/eval-form client iso
                                               '(do
                                                  (def +cljd-repl-fbox+ (atom nil))
                                                  ;; *1/*2/*3: plain vars, a dynamic's set! doesn't persist across evaluates
                                                  (def +cljd-repl-h1+ nil)
                                                  (def +cljd-repl-h2+ nil)
                                                  (def +cljd-repl-h3+ nil)
                                                  ;; *e: last error, set by the eval wrapper's catch
                                                  (def +cljd-repl-e+ nil)
                                                  ;; *env: picked widget's lexical scope, loaded by (picked)
                                                  (def +cljd-repl-env+ nil)
                                                  (defn +cljd-repl-remember [v]
                                                    (set! +cljd-repl-h3+ +cljd-repl-h2+)
                                                    (set! +cljd-repl-h2+ +cljd-repl-h1+)
                                                    (set! +cljd-repl-h1+ v) v)
                                                  (defn +cljd-repl-handle [v]
                                                    (if (dart/is? v dart-async/Future)
                                                      (do (reset! +cljd-repl-fbox+ nil)
                                                          (-> v
                                                              (.then (fn [x] (+cljd-repl-remember x) (reset! +cljd-repl-fbox+ (pr-str x))))
                                                              (.catchError (fn [e] (set! +cljd-repl-e+ e) (reset! +cljd-repl-fbox+ (str "__CLJD_ERR__ " e)))))
                                                          "__cljd_future_pending__")
                                                      (do (+cljd-repl-remember v) (pr-str v)))))
                                               {:ns-lib-uri "cljd/core.dart" :trigger-reload trigger-reload}))
                                            (catch Throwable e
                                              (println "[VMREPL] async init failed:" (.getMessage e)) false))
                                          ;; picker exists only in a debug build whose root went through f/run
                                          pick-ok
                                          (try
                                            ;; expression eval returns {:value …}, not :success
                                            (let [r (binding [compiler/*current-ns* 'cljd.flutter]
                                                      (repl-eval/eval-form client iso
                                                        'cljd.flutter/arm!
                                                        {:ns-lib-uri "cljd/flutter.dart" :trigger-reload trigger-reload}))]
                                              (boolean (and r (not (:error r)))))
                                            (catch Throwable e
                                              (println "[VMREPL] pick probe failed:" (.getMessage e)) false))
                                          server (repl-nrepl/start!
                                                   {:client client :iso-id iso :analyzer analyzer
                                                    :dart-version dartv :*current-ns (atom 'cljd.core)
                                                    :ns-lib-uri "cljd/core.dart" :port 0
                                                    :trigger-reload trigger-reload
                                                    :trigger-restart trigger-restart
                                                    :source-dirs dirs   ; for (edit-back): cljd loc → src file
                                                    :await? (boolean await-ok)
                                                    :pick? (boolean pick-ok)
                                                    :remember? (boolean await-ok)})]
                                      ;; heartbeat; re-resolve the isolate each tick, hot restart spawns a new one
                                      (daemon
                                        (loop []
                                          (try (when-some [i (vmservice/main-isolate-id client)]
                                                 (vmservice/call-ext client i "ext.cljd.ping" {}))
                                               (catch Throwable _ nil))
                                          (Thread/sleep 1000)
                                          (recur)))
                                      ;; report watch-compile failures on the device
                                      (let [rctx (repl-eval/context {:client client :iso-id iso
                                                                     :analyzer analyzer :dart-version dartv})]
                                        (reset! *reload-error-sink
                                          (fn [msg]
                                            (try (repl-eval/eval! rctx
                                                   (list 'cljd.flutter/report-error! "reload" msg nil)
                                                   {:ns 'cljd.flutter :ns-lib-uri "cljd/flutter.dart"})
                                                 (catch Throwable _ nil)))))
                                      (println (title "🔌 cljd VM-Service nREPL") "on port" (:port server)
                                        (str "(await " (if await-ok "on" "off")
                                             ", pick " (if pick-ok "on" "off")
                                             ", *1 " (if await-ok "on" "off") ")"))))
                                  (catch Throwable e
                                    (println "[VMREPL] error:" (.getMessage e)))))))))

                      (daemon
                        (binding [*ansi* ansi]
                          (loop [state :idle pending-reload false pending-done nil]
                            (cond
                              (and (= :idle state) pending-reload)
                              (do
                                (locking flutter-stdin
                                  (doto flutter-stdin
                                    (.write "r")
                                    .flush))
                                ;; keep pending-done armed until the reload completes or fails
                                (recur :idle false pending-done))

                              (= :restarting state)
                              (do
                                (println (bright "\n\n;;;; App restarting. Abandon all state!\n"))
                                (recur :waiting-end-of-restart pending-reload pending-done))

                              :else
                              (let [{:keys [kind line done]} (.take q)
                                    line (some-> line smap-line)
                                    ;; repl-hud prints "[* RDY)_" once the root mounts: end of hot restart
                                    is-ready-message (some-> line (.contains "[* RDY)"))]
                                (when (and line
                                           (not= :reload-failed state)
                                           (not is-ready-message))
                                  (println line))

                                (case kind
                                  :reload (recur state true (or done pending-done))
                                  :eof (do (some-> pending-done (deliver false)) nil)
                                  :line
                                  (let [line (.trim line)
                                        state'
                                        (case state
                                          :idle
                                          (condp = line
                                            "Performing hot reload..." :reloading
                                            "Performing hot restart..." (do
                                                                          (swap! *compiler-state
                                                                            update :restart-count inc)
                                                                          :restarting)
                                            state)
                                          :reloading
                                          (cond
                                            ;; must also match the no-change "Reloaded 0 libraries in …"
                                            (re-matches #"Reloaded .+ libraries in .+." line) :idle
                                            (= "Unimplemented handling of missing static target" line) :reload-failed)
                                          :reload-failed
                                          (when (re-matches #"Reloaded .+ libraries in .+." line)
                                            (newline)
                                            (println (bright "Hot reload failed, attempting hot restart!"))
                                            (locking flutter-stdin
                                              (doto flutter-stdin
                                                (.write "R")
                                                .flush))
                                            :restarting)
                                          :waiting-end-of-restart
                                          (when is-ready-message :idle))
                                        ;; signal eval-form's reload waiter
                                        reload-done? (and (not= state :idle)
                                                          (= (or state' state) :idle))
                                        reload-fail? (= state' :reload-failed)
                                        _ (when (and pending-done (or reload-done? reload-fail?))
                                            (deliver pending-done (boolean reload-done?)))
                                        pending-done (if (and pending-done (or reload-done? reload-fail?))
                                                       nil pending-done)]
                                    (recur (or state' state) pending-reload pending-done)))))))))

                    (watch-dirs-until (fn [_] (some-> p .isAlive not)) nil dirs (compile-files flutter-stdin))
                    (when p
                      (println (str "💀 Flutter sub-process exited with " (.exitValue p)))))
                  (finally
                    (some-> p .destroy)))))
            (or compilation-success (System/exit 1))))))))

(defn test-cli [& {:keys [namespaces dart-test-args]}]
  (when (compile-cli :namespaces namespaces)
    (newline)
    (println (title "Running tests..."))
    (let [bin (some-> *deps* :cljd/opts :kind name)]
      (System/exit (or (apply exec {:in nil #_#_:out nil} bin "test" dart-test-args) 0)))))

(defn gen-entry-point []
  (let [deps-cljd-opts (:cljd/opts *deps*)
        bin (some-> deps-cljd-opts :kind name)
        main-ns (or (:main deps-cljd-opts)
                  (throw (Exception. "A namespace must be specified in deps.edn under :cljd/opts :main or as argument to init.")))
        libdir (doto (java.io.File. compiler/*lib-path*) .mkdirs)
        dir (java.io.File. (System/getProperty "user.dir"))
        project-name (.getName dir)
        project_name (str/replace project-name #"[- ]" "_")
        entry-point (case bin
                      "flutter" (java.io.File. libdir "main.dart")
                      "dart" (java.io.File. "bin" (str project_name ".dart")))
        lib (compiler/relativize-lib (str/join "/" (map #(.toString %) (.toPath entry-point))) (compiler/ns-to-lib main-ns))]
    (spit entry-point (str "export " (compiler/with-dart-str (compiler/write-string-literal lib)) " show main;\n"))))

(defn init-project [bin-opts]
  (let [deps-cljd-opts (:cljd/opts *deps*)
        bin (or (some-> deps-cljd-opts :kind name)
              (throw (Exception. "A project kind (:dart or :flutter) must be specified in deps.edn under :cljd/opts :kind.")))
        main-ns (or (:main deps-cljd-opts)
                  (throw (Exception. "A namespace must be specified in deps.edn under :cljd/opts :main.")))
        dir (java.io.File. (System/getProperty "user.dir"))
        project-name (.getName dir)
        ;; aggressive project_name munging TODO make this smarter at some point
        project_name (-> project-name str/lower-case (str/replace #"([^a-z0-9_]+)" "_") (->> (str "cljd_")))]
    (with-open [w (io/writer ".gitattributes" :append true)]
      (binding [*out* w]
        (newline)
        (println "# Syntax highlighting for ClojureDart files")
        (println "*.cljd linguist-language=Clojure")))
    (doto (java.io.File. compiler/*lib-path*) .mkdirs)
    (println "Initializing" (bright project-name) "as a" (bright bin) "project!")
    (or
     (case bin
       "flutter"
       (apply exec bin "create" "--project-name" project_name
              (concat bin-opts [(System/getProperty "user.dir")]))
       "dart"
       (apply exec bin "create" "--force" (concat bin-opts [(System/getProperty "user.dir")])))
     (gen-entry-point)
     (with-open [w (io/writer ".gitignore" :append true)]
      (binding [*out* w]
        (newline)
        (run! println ["# ClojureDart"
                       ".cpcache/"
                       ".clojuredart/"
                       "lib/cljd-out/"
                       "test/cljd-out/"])))
     (println "👍" (green "All setup!") "Let's write some cljd in" main-ns))))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn parse-args [{opt-specs :options :keys [defaults] :as commands :or {defaults {}}} args]
  (let [[options & args]
        (if (false? opt-specs)
          (cons defaults args)
          (loop [args (seq args) options defaults]
            (if-some [[arg & more-args] args]
              (cond
                (#{"--" "++"} arg) (cons options args)
                (.startsWith ^String arg "--")
                (if-some [{:keys [long id parser rf init] :as opt-spec}
                          (some (fn [{:keys [long] :as spec}] (when (= long arg) spec)) opt-specs)]
                  (let [v (if parser
                            (parser (first more-args))
                            (:value opt-spec true))
                        more-args (cond-> more-args parser next)
                        id (or id (keyword (subs long 2)))
                        v (if rf (rf (options id init) v) v)]
                    (recur more-args (assoc options id v)))
                  (throw (Exception. (str "Unknown option: " arg))))
                (.startsWith ^String arg "-")
                (if-some [{:keys [short long id parser rf init] :as opt-spec}
                          (some (fn [{:keys [short] :as spec}] (when (.startsWith ^String arg short) spec)) opt-specs)]
                  (let [args (cond->> args (not= arg short)
                                      (cons (cond->> (subs arg 2) (not parser) (str "-"))))
                        v (if parser
                            (parser (first more-args))
                            (:value opt-spec true))
                        more-args (cond-> more-args parser next)
                        id (or id (keyword (subs long 2)))
                        v (if rf (rf (options id init) v) v)]
                    (recur more-args (assoc options id v)))
                  (throw (Exception. (str "Unknown option: " arg))))
                :else (cons options args))
              (cons options nil))))]
    (if (:help options)
      (list* options :help args)
      (if (some string? (keys commands))
        (if-some [[command & args] (seq args)]
          (if-some [subcommands (commands command)]
            (list* options command (parse-args subcommands args))
            (throw (Exception. (str "Unknown command: " command))))
          (list options :help))
        (list* options args)))))

(defn print-missing-config-warning [f-exists cmd]
  (when-not (or f-exists
              (contains? #{:help "init"} cmd))
    (newline)
    (println (title "WARNING: No cljd.edn file"))
    (println "Did you forget to run the following command?")
    (println (bright "clj -M -m cljd.build init <project_main_namespace>"))
    (newline)))

(defn print-help [{:keys [doc options] :as spec}]
  (when doc
    (newline)
    (println doc))
  (let [cmds (keep (fn [[cmd {:keys [doc]}]] (when (string? cmd) [cmd doc])) spec)]
    (when (and options (seq options))
      (newline)
      (println "Options")
      (doseq [{:keys [short long doc]} (sort-by #(or (:long %) (:short %)) options)]
        (println " " (some-> short bright) (some-> long bright) doc)))
    (when (seq cmds)
      (newline)
      (println "Actions")
      (doseq [[cmd doc] (sort-by first cmds)]
        (println " " (bright cmd))
        (some->> doc (println "   "))))))

(def latest-deps-url
  "https://github.com/Tensegritics/ClojureDart/releases/latest/download/deps.latest.edn")

(def ^:private latest-deps-asset-name "deps.latest.edn")
(def ^:private clojuredart-git-url
  "https://github.com/tensegritics/ClojureDart.git")

(defn- upgrade-failure [message & instructions]
  (ex-info
    (str message
      (when (seq instructions)
        (str "\n\n" (str/join "\n\n" instructions))))
    {:cljd/upgrade-error true}))

(defn- slurp-http [url]
  (let [connection ^java.net.HttpURLConnection (.openConnection (java.net.URL. url))]
    (try
      (.setConnectTimeout connection 10000)
      (.setReadTimeout connection 15000)
      (.setInstanceFollowRedirects connection true)
      (.setRequestProperty connection "Accept" "application/octet-stream")
      (.setRequestProperty connection "User-Agent" "ClojureDart-upgrader")
      (let [status (.getResponseCode connection)]
        (if (<= 200 status 299)
          (with-open [reader (io/reader (.getInputStream connection))]
            (slurp reader))
          (throw
            (upgrade-failure
              (if (= 404 status)
                "No published ClojureDart release was found."
                (str "GitHub returned HTTP " status " while downloading the latest ClojureDart release."))
              "Check the published releases at:\n  https://github.com/Tensegritics/ClojureDart/releases"
              "Retry when the release is available with:\n  clj -M:cljd upgrade"))))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw
          (upgrade-failure
            (str "Unable to download the latest ClojureDart release: " (ex-message e))
            "Check your network connection and retry with:\n  clj -M:cljd upgrade"
            (str "Release asset URL:\n  " url))))
      (finally
        (.disconnect connection)))))

(def ^:dynamic *latest-deps-reader* #(slurp-http latest-deps-url))

(defn- parse-release-version [tag]
  (when (string? tag)
    (when-some [[_ date suffix] (re-matches #"0\.9\.([0-9]{8})([a-z]?)" tag)]
      (try
        (let [day (java.time.LocalDate/parse date java.time.format.DateTimeFormatter/BASIC_ISO_DATE)
              suffix-index (if (str/blank? suffix)
                             0
                             (inc (- (int (first suffix)) (int \a))))]
          {:day day
           :suffix-index suffix-index
           :sort-key [(.toEpochDay day) suffix-index]})
        (catch java.time.format.DateTimeParseException _ nil)))))

(defn parse-latest-deps [text]
  (let [eof (Object.)
        data
        (try
          (with-open [reader (java.io.PushbackReader. (java.io.StringReader. text))]
            (let [data (edn/read {:eof eof} reader)
                  trailing (edn/read {:eof eof} reader)]
              (when (or (identical? eof data) (not (identical? eof trailing)))
                (throw (Exception. "Expected exactly one EDN form.")))
              data))
          (catch Exception e
            (throw
              (upgrade-failure
                (str "The latest ClojureDart release contains an invalid " latest-deps-asset-name ": "
                  (ex-message e))
                "The local deps.edn was not modified."
                "Please report the malformed release asset to the ClojureDart maintainers."))))
        {:keys [git/url tag sha] :as coordinate}
        (get-in data [:deps 'tensegritics/clojuredart])]
    (when-not (and (= clojuredart-git-url url)
                (parse-release-version tag)
                (string? sha)
                (re-matches #"[0-9a-f]{40}" sha))
      (throw
        (upgrade-failure
          (str "The latest ClojureDart release has an invalid dependency coordinate: "
            (pr-str coordinate))
          "Expected a UTC-dated tag such as 0.9.20260822 or 0.9.20260822a and a full 40-character Git SHA."
          "The local deps.edn was not modified.")))
    {:tag tag :sha sha}))

(defn- active-value-pattern [value]
  (re-pattern
    (str "(?<!#_)\"" (java.util.regex.Pattern/quote value) "\"")))

(defn- replace-active-value-with [text old-value replacement label]
  (let [pattern (active-value-pattern old-value)
        matches (count (re-seq pattern text))]
    (case matches
      0 (throw
          (upgrade-failure
            (str "The active ClojureDart " label " was not found in deps.edn: " old-value)
            "The dependency may be defined in another deps.edn file, alias, or command-line override."
            "The local deps.edn was not modified; update the coordinate manually."))
      1 (str/replace text pattern replacement)
      (throw
        (upgrade-failure
          (str "The active ClojureDart " label " occurs " matches " times in deps.edn.")
          "Automatic replacement would be ambiguous, so the local deps.edn was not modified."
          "Keep one active ClojureDart coordinate or update the occurrences manually.")))))

(defn- replace-active-value [text old-value new-value label]
  (replace-active-value-with text old-value
    (str \" new-value \" " #_" \" old-value \") label))

(defn- project-cljd-coordinate [text]
  (try
    (get-in (edn/read-string text) [:deps 'tensegritics/clojuredart])
    (catch Exception e
      (throw
        (upgrade-failure
          (str "The current deps.edn is not valid EDN: " (ex-message e))
          "The local deps.edn was not modified.")))))

(defn upgrade-deps-text [text current-coordinate latest-coordinate]
  (let [current-sha (or (:git/sha current-coordinate) (:sha current-coordinate))
        current-tag (or (:git/tag current-coordinate) (:tag current-coordinate))
        {:keys [tag sha]} latest-coordinate
        project-coordinate (project-cljd-coordinate text)
        project-url (:git/url project-coordinate)
        project-sha (or (:git/sha project-coordinate) (:sha project-coordinate))
        project-tag (or (:git/tag project-coordinate) (:tag project-coordinate))]
    (when-not (and (string? current-sha) (re-matches #"[0-9a-f]{40}" current-sha))
      (throw
        (upgrade-failure
          "The running ClojureDart dependency is not pinned to a full Git SHA."
          (str "Resolved coordinate: " (pr-str current-coordinate))
           "Projects using :local/root or custom Git coordinates must be updated manually.")))
    (when (and project-url (not= clojuredart-git-url project-url))
      (throw
        (upgrade-failure
          "The top-level ClojureDart dependency uses a custom Git URL."
          (str "Project Git URL: " project-url)
          "Automatic upgrading would retain that URL with an upstream commit that it may not contain."
          "The local deps.edn was not modified; update the custom coordinate manually.")))
    (when-not (= current-sha project-sha)
      (throw
        (upgrade-failure
          "The top-level ClojureDart coordinate in deps.edn does not match the running dependency."
          (str "Running SHA: " current-sha)
          (str "Project coordinate: " (pr-str project-coordinate))
          "The dependency may be supplied by an alias, override, or another configuration file."
          "The local deps.edn was not modified; update the intended coordinate manually.")))
    (when (and (parse-release-version current-tag)
            (pos? (compare (:sort-key (parse-release-version current-tag))
                    (:sort-key (parse-release-version tag)))))
      (throw
        (upgrade-failure
          (str "Refusing to downgrade ClojureDart from " current-tag " to " tag ".")
          "Check which GitHub Release is marked latest before changing deps.edn."
          "The local deps.edn was not modified.")))
    (let [sha-changed? (not= current-sha sha)
          tag-changed? (not= project-tag tag)
          text (if sha-changed?
                 (replace-active-value text current-sha sha "SHA")
                 text)
          text (cond
                 project-tag
                 (if tag-changed?
                   (replace-active-value text project-tag tag "tag")
                   text)

                 :else
                 (let [tag-key (if (contains? project-coordinate :git/sha)
                                 ":git/tag"
                                 ":tag")]
                   (replace-active-value-with text sha
                     (str \" sha \" " " tag-key " " \" tag \") "SHA")))]
      (if-not (or sha-changed? tag-changed?)
        {:text text :changed? false :tag tag :sha sha}
        (let [updated-coordinate (project-cljd-coordinate text)
              updated-sha (or (:git/sha updated-coordinate) (:sha updated-coordinate))
              updated-tag (or (:git/tag updated-coordinate) (:tag updated-coordinate))]
          (when-not (and (= sha updated-sha) (= tag updated-tag))
            (throw
              (upgrade-failure
                "The edited deps.edn did not update the top-level ClojureDart coordinate as expected."
                (str "Resulting coordinate: " (pr-str updated-coordinate))
                "The local deps.edn was not modified.")))
          {:text text :changed? true :tag tag :sha sha})))))

(defn- preserve-file-security-attributes! [source target]
  (let [options (make-array java.nio.file.LinkOption 0)
        source-posix (java.nio.file.Files/getFileAttributeView source
                       java.nio.file.attribute.PosixFileAttributeView options)
        target-posix (java.nio.file.Files/getFileAttributeView target
                       java.nio.file.attribute.PosixFileAttributeView options)
        source-acl (java.nio.file.Files/getFileAttributeView source
                     java.nio.file.attribute.AclFileAttributeView options)
        target-acl (java.nio.file.Files/getFileAttributeView target
                     java.nio.file.attribute.AclFileAttributeView options)
        source-dos (java.nio.file.Files/getFileAttributeView source
                     java.nio.file.attribute.DosFileAttributeView options)
        target-dos (java.nio.file.Files/getFileAttributeView target
                     java.nio.file.attribute.DosFileAttributeView options)]
    (if (and source-posix target-posix)
      (let [attrs (.readAttributes ^java.nio.file.attribute.PosixFileAttributeView source-posix)]
        (.setPermissions ^java.nio.file.attribute.PosixFileAttributeView target-posix (.permissions attrs))
        (.setGroup ^java.nio.file.attribute.PosixFileAttributeView target-posix (.group attrs))
        (.setOwner ^java.nio.file.attribute.PosixFileAttributeView target-posix (.owner attrs)))
      (let [source-owner (java.nio.file.Files/getFileAttributeView source
                           java.nio.file.attribute.FileOwnerAttributeView options)
            target-owner (java.nio.file.Files/getFileAttributeView target
                           java.nio.file.attribute.FileOwnerAttributeView options)]
        (when (and source-owner target-owner)
          (.setOwner ^java.nio.file.attribute.FileOwnerAttributeView target-owner
            (.getOwner ^java.nio.file.attribute.FileOwnerAttributeView source-owner)))))
    (when (and source-acl target-acl)
      (.setAcl ^java.nio.file.attribute.AclFileAttributeView target-acl
        (.getAcl ^java.nio.file.attribute.AclFileAttributeView source-acl)))
    (when (and source-dos target-dos)
      (let [attrs (.readAttributes ^java.nio.file.attribute.DosFileAttributeView source-dos)]
        (.setArchive ^java.nio.file.attribute.DosFileAttributeView target-dos (.isArchive attrs))
        (.setHidden ^java.nio.file.attribute.DosFileAttributeView target-dos (.isHidden attrs))
        (.setReadOnly ^java.nio.file.attribute.DosFileAttributeView target-dos (.isReadOnly attrs))
        (.setSystem ^java.nio.file.attribute.DosFileAttributeView target-dos (.isSystem attrs))))))

(defn- atomic-spit [file expected-text text]
  (let [file (.getCanonicalFile (io/file file))
        source (.toPath file)
        parent (.toPath (.getParentFile file))
        temp (java.nio.file.Files/createTempFile parent ".deps.edn-" ".tmp"
               (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (java.nio.file.Files/write temp (.getBytes ^String text java.nio.charset.StandardCharsets/UTF_8)
        (into-array java.nio.file.OpenOption
          [java.nio.file.StandardOpenOption/WRITE
           java.nio.file.StandardOpenOption/TRUNCATE_EXISTING]))
      (preserve-file-security-attributes! source temp)
      (when-not (= expected-text (slurp (.toFile source)))
        (throw
          (upgrade-failure
            "deps.edn changed while the upgrade was being prepared."
            "The concurrent edit was not overwritten. Review the file and run the upgrade again.")))
      (java.nio.file.Files/move temp source
        (into-array java.nio.file.CopyOption
          [java.nio.file.StandardCopyOption/ATOMIC_MOVE
           java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (java.nio.file.Files/deleteIfExists temp)))))

(defn upgrade-cljd
  ([] (upgrade-cljd (io/file "deps.edn")))
  ([file]
   (let [current-coordinate (get-in *deps* [:libs 'tensegritics/clojuredart])]
     (when-not current-coordinate
       (throw
         (upgrade-failure
           "The running basis does not contain the tensegritics/clojuredart dependency."
           "Run upgrade from a project that already uses ClojureDart:\n  clj -M:cljd upgrade")))
     (let [latest-coordinate (parse-latest-deps (*latest-deps-reader*))
           file (io/file file)]
       (when-not (.isFile file)
         (throw
           (upgrade-failure
             "No deps.edn file was found in the current directory."
             (str "Expected file: " (.getCanonicalPath file))
             "Change to the project directory and run:\n  clj -M:cljd upgrade")))
       (let [current-text
             (try
               (slurp file)
               (catch Exception e
                 (throw
                   (upgrade-failure
                     (str "Unable to read " (.getCanonicalPath file) ": " (ex-message e))
                     "Check that the file exists and is readable."
                     "The local deps.edn was not modified."))))
             {:keys [text changed? tag sha]}
             (upgrade-deps-text current-text current-coordinate latest-coordinate)]
         (if changed?
           (do
             (try
                (atomic-spit file current-text text)
                (catch Exception e
                  (if (:cljd/upgrade-error (ex-data e))
                    (throw e)
                    (throw
                      (upgrade-failure
                        (str "Unable to replace " (.getCanonicalPath file) ": " (ex-message e))
                        "Check file permissions and available disk space, then retry."
                        "The original deps.edn was left in place whenever the filesystem allowed it.")))))
             (println (str "ClojureDart upgraded to " tag " (" (subs sha 0 7) ")."))
             (println "The new version will be used on the next clj invocation."))
           (println (str "ClojureDart is already up to date at " tag " (" (subs sha 0 7) ")."))))))))

(defn- run-upgrade-cljd []
  (try
    (upgrade-cljd)
    (catch clojure.lang.ExceptionInfo e
      (if (:cljd/upgrade-error (ex-data e))
        (do
          (binding [*out* *err*]
            (println "Upgrade aborted:" (ex-message e)))
          (System/exit 1))
        (throw e)))))

(def help-spec {:short "-h" :long "--help" :doc "Print this help."})

(def commands
  {:doc "This program compiles Clojuredart files to dart files."
   :options [help-spec]
   "init" {:doc "Set up the current clojure project as a ClojureDart/Flutter project according to :cljd/opts in deps.edn."
           :options false
           #_#_#_#_
           :options [help-spec
                     {:short "-f" :long "--flutter" :id :target :value "flutter"}
                     {:short "-d" :long "--dart" :id :target :value "dart"}
                     ; TODO should be stored in a cljd.local.edn
                     #_{:short "-p" :long "--path"
                      :doc "Path to the flutter or dart install."}]
           :defaults {:target "flutter"}}
   "compile" {:doc "Compile the specified namespaces (or the main one by default) to dart.\n    Use the --offline option to not fetch dependencies."
              :options [help-spec {:long "--offline" :id :offline :value true}]}
   "clean" {:doc "When there's something wrong with compilation, erase all ClojureDart build artifacts.\nConsider running flutter clean too."}
   "help" {:doc (:doc help-spec)}
   "test" {:doc "Run specified test namespaces (or all by default)."}
   "upgrade" {:doc "Upgrade cljd to latest version."}
   "watch" {:doc "Like compile but keep recompiling in response to file updates.\n    Use the --offline option to not fetch dependencies."
            :options [help-spec {:long "--offline" :id :offline :value true}]}
   "flutter" {:options false
              :doc "Like watch but hot reload the application in the simulator or device. All options are passed to flutter run."}})

(defn sha256 [string]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes string "UTF-8"))]
    (apply str (map (partial format "%02x") digest))))

(defn find-pubspec [{:keys [:deps/root paths]}]
  (if root
    (let [f (java.io.File. root "pubspec.yaml")]
      (when (.exists f)
        (slurp f)))
    (some
      (fn [path]
        (let [f (java.io.File. path)]
          (if (.isDirectory f)
            (let [f (java.io.File. f "pubspec.yaml")]
              (when (.exists f)
                (slurp f)))
            (with-open [in (-> f io/input-stream java.util.jar.JarInputStream.)]
              (loop []
                (when-some [e (.getNextJarEntry in)]
                  (if (= "pubspec.yaml" (.getName e))
                    (slurp in) ; slurp closes `in` but it's ok it's the last action
                    (recur))))))))
      paths)))

(defn sync-pubspec! []
  (let [parser (org.yaml.snakeyaml.Yaml.)
        existing-deps (into {}
                        (for [[name {:strs [path]}] (get (.load parser (slurp "pubspec.yaml")) "dependencies")
                              :let [[_ sha] (some->> path (re-matches #"^\.clojuredart/deps/(.+)"))]
                              :when sha]
                          [[name sha] (-> path java.io.File. .exists)]))
        declared-deps (into {}
                        (for [pubspec (keep find-pubspec (vals (deps/resolve-deps *deps* {})))
                              :let [{:strs [name]} (.load parser pubspec)
                                    sha (sha256 pubspec)]]
                          [[name sha] pubspec]))
        ; the (filter existing-deps) is to remove "bridge" deps which don't exist on disk
        ; typically when getting an updated pubspec.yaml from scm (git)
        deps-to-remove (keys (transduce (filter existing-deps) dissoc existing-deps (keys declared-deps)))
        deps-to-add (transduce (filter existing-deps) dissoc declared-deps existing-deps)]

    (when-some [names (seq (map first deps-to-remove))]
      (apply exec {:in nil #_#_:out nil} (some-> *deps* :cljd/opts :kind name) "pub" "remove"
        names)
      (run! #(del-tree (java.io.File. ".clojuredart/deps" (second %))) deps-to-remove))

    (when-some [coords (seq (for [[name sha] (keys deps-to-add)]
                              (str name ":{\"path\":\".clojuredart/deps/" sha "\"}")))]
      (doseq [[[name sha] pubspec] deps-to-add
              :let [f (java.io.File. ".clojuredart/deps" sha)]]
        (.mkdirs f)
        (spit (java.io.File. f "pubspec.yaml") pubspec))
      (apply exec {:in nil #_#_:out nil} (some-> *deps* :cljd/opts :kind name) "pub" "add" "--directory=."
        coords))))

(defn ensure-test-dev-dep! []
  (let [parser (org.yaml.snakeyaml.Yaml.)]
    (when-not (get-in (.load parser (slurp "pubspec.yaml")) ["dev_dependencies" "test"])
      (exec {:in nil #_#_:out nil} (some-> *deps* :cljd/opts :kind name) "pub" "add" "--dev" "test"))))

(defn merge-cljd-opts [cljd-opts alias-cljd-opts]
  (reduce-kv
    (fn [cljd-opts k v]
      (if-some [[_ prefix base] (re-matches #"(replace|extra)-(.+)" (name k))]
        (let [k (keyword base)]
          (case prefix
            "replace" (assoc cljd-opts k v)
            "extra" (merge-with into cljd-opts {k v})))
        (let [v' (cljd-opts k v)]
          (when-not (= v' v)
            (throw (Exception. (str "Two different values provided in :cljd/opts for " k ": " (pr-str v) " and " (pr-str v')))))
          (assoc cljd-opts k v))))
    cljd-opts
    alias-cljd-opts))

(defn runtime-basis
  "Load the runtime execution basis context and return it."
  []
  (when-let [f (java.io.File. (System/getProperty "clojure.basis"))]
    (if (and f (.exists f))
      (let [{:keys [aliases basis-config] :as basis} (deps/slurp-deps f)]
        (assoc basis
          :cljd/opts
          (reduce merge-cljd-opts (:cljd/opts basis) (keep (comp :cljd/opts aliases) (:aliases basis-config)))))
      (throw (IllegalArgumentException. "No basis declared in clojure.basis system property")))))


(defn -main [& args]
  (binding [*ansi* (and (System/console) (get (System/getenv) "TERM"))
            compiler/*lib-path*
            (str (.getPath (java.io.File. "lib")) "/")]
    (binding [*deps* (runtime-basis)]
      (let [[options cmd cmd-opts & args] (parse-args commands args)]
        (case cmd
          ("compile" "watch" "flutter" "test") (sync-pubspec!)
          nil)
        (case cmd
          :help (print-help commands)
          "help" (print-help commands)
          "init" (init-project args)
          ("compile" "watch")
          (do
            (ensure-no-existing!)
            (compile-cli
             :offline (:offline cmd-opts)
             :namespaces (or (seq (map symbol args))
                           (some-> *deps* :cljd/opts :main list))
             :watch (= cmd "watch")))
          "test"
          (let [[nses [delim & dart-test-args]] (split-with (complement #{"--" "++"}) args)
                nses (map symbol nses)
                opts-dart-test-args (-> *deps* :cljd/opts :dart-test-args)]
            (ensure-no-existing!)
            (ensure-test-dev-dep!)
            (test-cli
              :dart-test-args (case delim
                                "++" (concat opts-dart-test-args dart-test-args)
                                (or dart-test-args opts-dart-test-args))
              :namespaces
              (or (seq nses)
                (let [wd (-> (java.io.File. ".") .getCanonicalFile .toPath)]
                  (for [root (:classpath-roots *deps*)
                        :when (-> root java.io.File. .getCanonicalFile .toPath (.startsWith wd))
                        ^java.io.File file (tree-seq
                                             some? #(.listFiles ^java.io.File %)
                                             (java.io.File. root))
                        :when (re-matches #"[^.].*\.clj[cd]" (.getName file))]
                    (compiler/peek-ns file))))))
          "upgrade"
          (run-upgrade-cljd)
          "flutter"
          (let [[args [delim & flutter-args]] (split-with (complement #{"--" "++"}) args)
                flutter-args (if delim flutter-args args)
                args (if delim args nil)
                opts-flutter-run-args (-> *deps* :cljd/opts :flutter-run-args)]
            (ensure-no-existing!)
            (compile-cli
              :namespaces
              (or (seq (map symbol args))
                (some-> *deps* :cljd/opts :main list))
              :flutter (vec (case delim
                              "++" (concat opts-flutter-run-args flutter-args)
                              flutter-args))))
          "clean"
          (do
            (del-tree (java.io.File. "lib/cljd-out"))
            (del-tree (java.io.File. "test/cljd-out"))
            (del-tree (java.io.File. ".clojuredart"))
            (sync-pubspec!)
            (println "ClojureDart build state succesfully cleaned!")
            (case (some-> *deps* :cljd/opts :kind)
              :flutter (println "If problems persist, try" (bright "flutter clean"))
              nil)))))))
