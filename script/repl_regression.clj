#!/usr/bin/env bb
;; Live regression check for the VM-Service nREPL, run against an app started with
;; `CLJD_VMREPL=1 clojure -M:cljd flutter -d <device>` (it writes .nrepl-port and .nrepl-port-jvm).
;;
;;   bb /path/to/ClojureDart/script/repl_regression.clj <app-ns>
;;
;; <app-ns> is any loaded namespace of the app, e.g. my.app.core. Exits non-zero on failure.
(require '[bencode.core :as b] '[clojure.string :as str])
(import '[java.net Socket] '[java.io PushbackInputStream])

(defn- conn [port]
  (let [s (Socket. "127.0.0.1" (int port))]
    {:out (.getOutputStream s) :in (PushbackInputStream. (.getInputStream s))}))

(defn- ->str [x] (if (bytes? x) (String. ^bytes x "UTF-8") x))

(defn- ev [{:keys [out in]} code & [ns]]
  (b/write-bencode out (cond-> {"op" "eval" "code" code "id" (str (random-uuid))} ns (assoc "ns" ns)))
  (loop [acc {:value [] :err []}]
    (let [m (b/read-bencode in)
          acc (cond-> acc
                (get m "value") (update :value conj (->str (get m "value")))
                (get m "err") (update :err conj (->str (get m "err"))))]
      (if (some #(= "done" (->str %)) (get m "status")) acc (recur acc)))))

(let [app-ns (or (first *command-line-args*) (do (println "usage: repl_regression.clj <app-ns>") (System/exit 2)))
      vm (conn (parse-long (str/trim (slurp ".nrepl-port"))))
      jvm (conn (parse-long (str/trim (slurp ".nrepl-port-jvm"))))
      fails (atom 0)
      check (fn [label ok? detail]
              (println (if ok? "ok  " "FAIL") label (if ok? "" (str "— " detail)))
              (when-not ok? (swap! fails inc)))
      importers (fn []
                  (-> (ev jvm (str "(let [n @cljd.compiler/nses lib (get-in n ['" app-ns " :lib])]"
                                   " (vec (for [[k {:keys [imports]}] n :when (and (symbol? k) (get imports lib))] (str k))))"))
                      :value first))
      probe "cljd-repl-regression-probe"]
  (let [r (ev vm (str "(defn " probe " [] 1)") app-ns)]
    (check "define a probe in the app ns" (empty? (:err r)) (:err r)))
  (let [r (ev vm (str "(" app-ns "/" probe ")"))]
    (check "call it fully qualified from the default ns" (= ["1"] (:value r)) (or (seq (:err r)) (:value r))))
  (let [imp (importers)]
    (check "evaluating did not add an import to cljd.core" (not (str/includes? (str imp) "\"cljd.core\"")) imp))
  (let [r (ev vm (str "(defn " probe " [] 2)") app-ns)]
    (check "redefine it after the qualified call" (empty? (:err r)) (:err r)))
  (let [r (ev vm (str "(" probe ")") app-ns)]
    (check "the new definition is live" (= ["2"] (:value r)) (or (seq (:err r)) (:value r))))
  (System/exit (if (zero? @fails) 0 1)))
