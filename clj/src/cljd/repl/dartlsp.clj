(ns cljd.repl.dartlsp
  "A backend to the Dart Analysis Server (`dart language-server`, LSP over stdio), so the
   repl's `info`/go-to-def can answer for Dart INTEROP symbols — the authority on Dart/Flutter
   elements, which cljd's compile-time analyzer (type-only) and @nses (cljd defs only) can't.

   Lazy: the server (a ~6s first-index subprocess) starts on the first `find-element` and is
   reused. One query at a time (the repl's info op is serial); a synchronous read skips
   interleaved notifications until the matching response id. Structured — no string-matching."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.lang ProcessBuilder]))

(defonce ^:private state (atom nil))   ; {:proc :out :in :id (atom)} or nil

(defn- send-msg [out m]
  (let [b (.getBytes ^String (json/write-str m) "UTF-8")]
    (locking out
      (.write out (.getBytes (str "Content-Length: " (count b) "\r\n\r\n") "UTF-8"))
      (.write out b) (.flush out))))

(defn- read-line* [in]
  (loop [bs []]
    (let [c (.read in)]
      (cond (= c -1) nil
            (= c 13) (do (.read in) (apply str (map char bs)))   ; consume \n
            :else (recur (conj bs c))))))

(defn- read-msg [in]
  (loop [len nil]
    (let [line (read-line* in)]
      (cond
        (nil? line) nil
        (= line "") (let [buf (byte-array len)]
                      (loop [off 0] (when (< off len)
                                      (let [n (.read in buf off (- len off))]
                                        (if (neg? n) nil (recur (+ off n))))))
                      (json/read-str (String. buf "UTF-8") :key-fn keyword))
        (.startsWith ^String line "Content-Length:") (recur (Long/parseLong (.trim (subs line 15))))
        :else (recur len)))))

(defn- await-id [in want]
  (loop [n 0]
    (when (< n 500)
      (let [m (read-msg in)]
        (cond (nil? m) nil (= (:id m) want) m :else (recur (inc n)))))))

(defn- start! [project-root]
  (let [proc (-> (ProcessBuilder. ["dart" "language-server" "--client-id" "cljd-repl"])
                 (.directory (io/file project-root)) (.start))
        out (.getOutputStream proc) in (.getInputStream proc) id (atom 0)
        root-uri (str "file://" project-root)
        st {:proc proc :out out :in in :id id}]
    (send-msg out {:jsonrpc "2.0" :id (swap! id inc) :method "initialize"
                   :params {:processId nil :rootUri root-uri :capabilities {}
                            :workspaceFolders [{:uri root-uri :name "kora"}]}})
    (await-id in 1)
    (send-msg out {:jsonrpc "2.0" :method "initialized" :params {}})
    (Thread/sleep 6000)   ; first-index settle
    st))

(defn- ensure! [project-root]
  (or @state (locking state (or @state (reset! state (start! project-root))))))

(defn- uri->file [^String uri] (if (.startsWith uri "file://") (subs uri 7) uri))

(defn find-element
  "Look up a Dart element by NAME in the analysis server; return the best-matching
   declaration {:name :file :line :kind} (0-based LSP line +1), or nil. PROJECT-ROOT is the
   dir the server indexes (has the Flutter/dart deps)."
  [project-root name]
  (try
    (let [{:keys [out in id]} (ensure! project-root)]
      (send-msg out {:jsonrpc "2.0" :id (swap! id inc) :method "workspace/symbol"
                     :params {:query name}})
      (when-let [syms (:result (await-id in @id))]
        ;; prefer an exact-name class/constructor over partial matches
        (let [exact (filter #(= (:name %) name) syms)
              best  (first (concat exact syms))]
          (when best
            {:name (:name best)
             :file (uri->file (get-in best [:location :uri]))
             :line (inc (get-in best [:location :range :start :line] 0))
             :kind (:kind best)}))))
    (catch Throwable _ nil)))

(defn stop! []
  (when-let [{:keys [proc]} @state] (.destroy proc) (reset! state nil)))
