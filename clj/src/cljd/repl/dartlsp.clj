(ns cljd.repl.dartlsp
  "Dart Analysis Server (LSP over stdio) lookups for Dart interop symbols.
   Started lazily on first find-element and reused; one query at a time."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
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
                            :workspaceFolders [{:uri root-uri :name (.getName (io/file project-root))}]}})
    (await-id in 1)
    (send-msg out {:jsonrpc "2.0" :method "initialized" :params {}})
    (Thread/sleep 6000)   ; first-index settle
    st))

(defn- ensure! [project-root]
  (or @state (locking state (or @state (reset! state (start! project-root))))))

(defn- uri->file [^String uri] (if (.startsWith uri "file://") (subs uri 7) uri))

(defn- doc-comment
  "The `///` block at FILE line LINE0 (0-based; LSP ranges start at the doc comment), or nil."
  [file line0]
  (try
    (with-open [r (io/reader file)]
      (let [lines (vec (line-seq r))
            block (when (< line0 (count lines))
                    (->> (subvec lines line0)
                         (take-while #(str/starts-with? (str/triml %) "///"))
                         (map #(-> ^String % str/triml (subs 3) str/triml))))]
        (when (seq block) (str/join "\n" block))))
    (catch Throwable _ nil)))

(defn find-element
  "Look up Dart element NAME via the analysis server rooted at PROJECT-ROOT.
   Returns {:name :file :line (1-based) :kind :doc} for the best match, or nil."
  [project-root name]
  (try
    (let [{:keys [out in id]} (ensure! project-root)]
      (send-msg out {:jsonrpc "2.0" :id (swap! id inc) :method "workspace/symbol"
                     :params {:query name}})
      (when-let [syms (:result (await-id in @id))]
        ;; prefer an exact-name match
        (let [exact (filter #(= (:name %) name) syms)
              best  (first (concat exact syms))]
          (when best
            (let [file (uri->file (get-in best [:location :uri]))
                  line (get-in best [:location :range :start :line] 0)]
              {:name (:name best)
               :file file
               :line (inc line)
               :kind (:kind best)
               :doc  (doc-comment file line)})))))
    (catch Throwable _ nil)))

(defn stop! []
  (when-let [{:keys [proc]} @state] (.destroy proc) (reset! state nil)))
