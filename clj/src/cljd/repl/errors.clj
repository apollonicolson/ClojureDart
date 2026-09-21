(ns cljd.repl.errors
  "Format Dart VM-Service runtime errors and cljd compile exceptions as REPL text."
  (:require [clojure.string :as str]))

(def ^:private token->char
  "Reverse of compiler/char-map, minus $UNDERSCORE_ (handled in demunge-name)."
  (array-map
   "$DOLLAR_" "$" "$DOT_" "." "$COLON_" ":" "$PLUS_" "+"
   "$GT_" ">" "$LT_" "<" "$EQ_" "=" "$TILDE_" "~" "$BANG_" "!" "$CIRCA_" "@"
   "$SHARP_" "#" "$PRIME_" "'" "$QUOTE_" "\"" "$PERCENT_" "%" "$CARET_" "^"
   "$AMPERSAND_" "&" "$STAR_" "*" "$BAR_" "|" "$LBRACE_" "{" "$RBRACE_" "}"
   "$LBRACK_" "[" "$RBRACK_" "]" "$SLASH_" "/" "$BSLASH_" "\\" "$QMARK_" "?"
   "$SPACE_" " " "$COMMA_" ","))

(defn demunge-name
  "Best-effort reverse of compiler/munge-str; U+0001 sentinel shields $UNDERSCORE_ from the _ -> - pass."
  [s]
  (let [sentinel (str (char 1))]
    (-> (str/replace s "$UNDERSCORE_" sentinel)
        (as-> $ (reduce (fn [a [tok ch]] (str/replace a tok ch)) $ token->char))
        (str/replace "_" "-")
        (str/replace sentinel "_"))))

(defn- cljd-fn-name
  "`ifn_pr_str_M__18695hm$1` -> \"pr-str\", or nil."
  [ident]
  (when-some [[_ munged] (re-find #"ifn_(.+)_M__\w+" (or ident ""))]
    (demunge-name munged)))

(defn- frame-line? [s] (re-find #"^#\d+\s" (str/trim s)))

(defn- user-frame?
  "A frame in compiled cljd code outside the cljd.* namespaces."
  [frame]
  (and (str/includes? frame "cljd-out/")
       (not (str/includes? frame "cljd-out/cljd/"))))

(defn- clean-frame
  "Rewrite a user Dart frame as `ns/fn (loc)`; RESOLVE-LOC maps a Dart `path:line` to cljd source, or nil."
  [frame resolve-loc]
  (let [fnname (or (cljd-fn-name frame)
                   ;; user frames are cljd-compiled: a bare `_` is a munged `-`
                   (some-> (second (re-find #"#\d+\s+(\S+)" frame)) demunge-name))
        loc    (second (re-find #"cljd-out/([^\s):]+\.dart:\d+)" frame))
        shown  (or (some-> loc resolve-loc) loc)
        ns'    (some-> loc (str/replace #"\.dart:\d+$" "") (str/replace "/" "."))]
    (cond
      (and ns' fnname) (str "  " ns' "/" fnname "  (" shown ")")
      shown            (str "  (" shown ")")
      :else            (str "  " (str/trim frame)))))

(defn- clean-trace
  "Split a Dart error blob into [message user-frames]."
  [blob resolve-loc]
  (let [lines (str/split-lines (or blob ""))
        [msg-lines frames] (split-with (complement frame-line?) lines)
        msg (->> msg-lines
                 (remove #(re-matches #"(?i)\s*unhandled exception:?\s*" %))
                 (str/join "\n") str/trim)
        user (->> frames (filter user-frame?) (map #(clean-frame % resolve-loc)))]
    [msg user]))

(defn format-runtime
  "Format a runtime `@Error` :message; RESOLVE-LOC optionally maps Dart frame locs to cljd source."
  ([message] (format-runtime message (constantly nil)))
  ([message resolve-loc]
   (let [[msg frames] (clean-trace message resolve-loc)]
     (str (if (str/blank? msg) "Unhandled exception" msg)
          (when (seq frames) (str "\n" (str/join "\n" frames)))))))

(defn- causes [^Throwable e]
  (take-while some? (iterate #(.getCause ^Throwable %) e)))

(defn format-compile
  "Format a cljd compiler exception: root-cause message, plus the offending form when not named."
  [^Throwable e]
  (let [data (ex-data e)]
    (if-some [verr (:error data)]
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
             (when (and (seq stack) (not (str/includes? msg (str head))))
               (str "\n  in: " (pr-str head))))))))
