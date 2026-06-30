(ns cljd.repl.errors
  "Turn raw Dart VM-Service errors and cljd compiler exceptions into REPL-useful
   text, the way JVM Clojure does: surface the real message, demunge Dart symbols
   back to cljd names, and drop library/plumbing noise from stack traces.

   Two sources:
   - runtime: the `@Error` `:message` from VM-Service `evaluate` — an
     \"Unhandled exception:\\n<msg>\\n#0 …#N …\" blob with a Dart stack trace.
   - compile: a `clojure.lang.ExceptionInfo` thrown by the cljd compiler while
     turning the form into Dart (\"Error while compiling NO_SOURCE_PATH <form>\"
     plus `:cljd.compiler/emit-stack` in ex-data)."
  (:require [clojure.string :as str]))

;; --- demunging (reverse of compiler/char-map + munge-str) --------------------

(def ^:private token->char
  "Reverse of compiler/char-map's $WORD_ escapes (excluding $UNDERSCORE_, handled
   specially in demunge-name so the later _->- pass can't clobber it)."
  (array-map
   "$DOLLAR_" "$" "$DOT_" "." "$COLON_" ":" "$PLUS_" "+"
   "$GT_" ">" "$LT_" "<" "$EQ_" "=" "$TILDE_" "~" "$BANG_" "!" "$CIRCA_" "@"
   "$SHARP_" "#" "$PRIME_" "'" "$QUOTE_" "\"" "$PERCENT_" "%" "$CARET_" "^"
   "$AMPERSAND_" "&" "$STAR_" "*" "$BAR_" "|" "$LBRACE_" "{" "$RBRACE_" "}"
   "$LBRACK_" "[" "$RBRACK_" "]" "$SLASH_" "/" "$BSLASH_" "\\" "$QMARK_" "?"
   "$SPACE_" " " "$COMMA_" ","))

(defn demunge-name
  "Best-effort reverse of compiler/munge-str, for display in stack traces.
   munge maps - -> _ and _ -> $UNDERSCORE_; reverse via a transient sentinel
   (U+0001, never on disk) so $UNDERSCORE_ survives the plain _ -> - pass."
  [s]
  (let [sentinel (str (char 1))]
    (-> (str/replace s "$UNDERSCORE_" sentinel)
        (as-> $ (reduce (fn [a [tok ch]] (str/replace a tok ch)) $ token->char))
        (str/replace "_" "-")
        (str/replace sentinel "_"))))

(defn- cljd-fn-name
  "Pull the cljd fn name out of a generated Dart identifier, or nil.
   `ifn_pr_str_M__18695hm$1` -> \"pr-str\"; `ifn_map_M__…` -> \"map\"."
  [ident]
  (when-some [[_ munged] (re-find #"ifn_(.+)_M__\w+" (or ident ""))]
    (demunge-name munged)))

;; --- stack-trace cleaning ----------------------------------------------------

(defn- frame-line? [s] (re-find #"^#\d+\s" (str/trim s)))

(defn- user-frame?
  "A frame in compiled cljd code that is NOT the cljd.core library or the eval
   plumbing — i.e. the user's own namespaces."
  [frame]
  (and (str/includes? frame "cljd-out/")
       (not (str/includes? frame "cljd-out/cljd/"))))

(defn- clean-frame
  "Rewrite a user Dart frame into cljd terms: demunge the fn name and show the
   ns path + line.  e.g. `#3 ifn_foo_M__1$1.$_invoke$2 (package:kora/cljd-out/kora/app.dart:42:3)`
   -> `kora.app/foo (kora/app.dart:42)`."
  [frame]
  (let [fnname (or (cljd-fn-name frame)
                   (second (re-find #"#\d+\s+(\S+)" frame)))
        loc    (second (re-find #"cljd-out/([^\s):]+\.dart:\d+)" frame))
        ns'    (some-> loc (str/replace #"\.dart:\d+$" "") (str/replace "/" "."))]
    (cond
      (and ns' fnname) (str "  " ns' "/" fnname "  (" loc ")")
      loc              (str "  (" loc ")")
      :else            (str "  " (str/trim frame)))))

(defn- clean-trace
  "From a multi-line Dart error blob, return [message user-frames] — the message
   lines (sans the \"Unhandled exception:\" banner) and only the user's frames,
   demunged.  cljd.core / dart: / async-zone / Eval-IIFE frames are dropped."
  [blob]
  (let [lines (str/split-lines (or blob ""))
        [msg-lines frames] (split-with (complement frame-line?) lines)
        msg (->> msg-lines
                 (remove #(re-matches #"(?i)\s*unhandled exception:?\s*" %))
                 (str/join "\n") str/trim)
        user (->> frames (filter user-frame?) (map clean-frame))]
    [msg user]))

;; --- public formatters -------------------------------------------------------

(defn format-runtime
  "Format a runtime `@Error` :message into clean REPL text."
  [message]
  (let [[msg frames] (clean-trace message)]
    (str (if (str/blank? msg) "Unhandled exception" msg)
         (when (seq frames) (str "\n" (str/join "\n" frames))))))

(defn- causes [^Throwable e]
  (take-while some? (iterate #(.getCause ^Throwable %) e)))

(defn format-compile
  "Format a cljd compiler exception (thrown while turning the form into Dart).
   cljd's own cause message is usually good (\"Unknown symbol: x\"); the noise is
   the \"Error while compiling NO_SOURCE_PATH …\" / \"(no source location)\" REPL
   wrapper and the raw ex-data map. Strip those, walk to the most specific cause,
   and append the offending form only when it adds information."
  [^Throwable e]
  (let [data (ex-data e)]
    (if-some [verr (:error data)]
      ;; a VM-Service rpc error (e.g. the generated Dart didn't compile): the real
      ;; Dart message is buried in :data :details — surface it, trimmed of the
      ;; org-dartlang synthetic-expression banner.
      (str "Dart eval error: "
           (-> (or (get-in verr [:data :details]) (:message verr) "unknown")
               (str/replace #"org-dartlang-debug:synthetic_debug_expression:\d+:\d+:\s*" "")
               str/trim))
      (let [strip  (fn [m] (some-> m
                             (str/replace #"^Error while compiling \S+\s*" "")
                             (str/replace #"\s*\(no source location\)" "")
                             (str/replace #"\bNO_SOURCE_PATH\b\s*" "")
                             str/trim))
            root   (last (causes e))
            rootm  (strip (.getMessage ^Throwable root))
            topm   (strip (.getMessage e))
            stack  (:cljd.compiler/emit-stack data)
            head   (first stack)
            msg    (or (not-empty rootm) (not-empty topm) "compile error")]
        (str msg
             ;; the innermost offending form, when the message doesn't already name it
             (when (and (seq stack) (not (str/includes? msg (str head))))
               (str "\n  in: " (pr-str head))))))))
