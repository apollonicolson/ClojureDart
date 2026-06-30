# VM-Service REPL for ClojureDart — build plan

Branch `repl-vm-service-harvest`. Replace the hot-reload-screen-scrape REPL with a
VM-Service-grounded one: **expressions** eval via `evaluate` (clean structured values,
no frame, no regex); only **new code** (def/defn/deftype/…) uses `reloadSources`.
Ends with *less* code than today.

## Proven (rank 1, 2026-06-29)
- cljd form → Dart expression on the JVM: `(with-dart-str (write (emit (fn* [] form) {}) expr-locus {}))` wrapped `(…)()` → IIFE. `(fn* [] 42)`→`((){return 42;})()`.
- Dart expression → clean value via VM-Service `evaluate`: `Int 42`, String, List.

## The whole eval core
```
eval(code):
  form = macroexpand(read(code))
  if emits-new-toplevel?(form):  reloadSources(); await ReloadReport; ret #'ns/sym
  else:                          ret evaluate(iso, ns-lib, form->dart-expr (pr-str form))
```

## Steps (each runnable)  — status 2026-06-30
1. ✅ **JVM VM-Service client** — `cljd.repl.vmservice` (JDK WebSocket + JSON-RPC,
   +data.json): connect/rpc/evaluate/reloadSources/library-id/listen-streams!/set-sink!.
2. ✅ **`form->dart-expr`** in `cljd.compiler` — emits a SINGLE-LINE self-invoking
   closure `(() { <lifted stmts>; return <value>; })()`. Two device-proven subtleties:
   cljd's `(fn* [])` emits paren-less `(){…}()` (Dart rejects); and the evaluator parses
   only the first line, so newlines must be stripped. Collections/nested/let all eval.
4. ✅ **classifier** — `cljd.repl.eval/emits-new-toplevel?` + `eval-form` orchestration.
3. ✅ **wire into build.clj** — captures the app's VM-Service URI from flutter stdout,
   connects, gets the isolate, runs the self-test battery and/or the nREPL front.
6. ✅ **nREPL front** → `eval-form`. PROVEN end-to-end via a real nREPL client into the
   running Pixel: `(+ 100 23)`→123, nested coll literals clean, `(defn cube …)` hot-
   reloads and `(cube 4)`→64 (new fn live). Retires the external `tools/cljd-nrepl`.
   - reload path: drives Flutter's own hot reload (`trigger-reload` → "r" → frontend
     recompile → reloadSources) with a done-promise; raw `reloadSources` fails on Flutter.
5. 🟡 **structured out/err** — IMPLEMENTED (vmservice `listen-streams!`/`:sink` decode
   Stdout/Stderr WriteEvents; nREPL forwards to `:out`/`:err` during eval). `@Error`
   already handled. **UNVERIFIED on device** — Pixel dropped off wifi-adb before the
   `(println …)` round-trip test; needs one validation run when the device is back.
7. ⏳ **delete** parse-repl-line / FormExec / repl-exec / dispatch daemon / reload regex
   state-machine / socket text REPL. *Deferred: high-risk surgery on the live build;
   the new path coexists with the old. Do only with a stable device to re-validate.*
8. ⏳ **polish** — `pick!` via `evaluate(widget-obj,…)`, async Future-await, var-table redefine.

## Delete list (the simplification)
`parse-repl-line`, the `[id mode)…_` protocol, `form-exec`/`repl-exec`/`ReplHackContrib`,
the dispatch daemon + state machine + `Reloaded N libraries` regexes, `r`/`R` stdin
forwarding, the `clojure.core.server` socket REPL. The 5 prior fixes are transitional.

## Lives in
the cljd build JVM (has compiler+analyzer+nses); VM-service client + nREPL front added
here. Upstreamable as `feat/repl` done right.
