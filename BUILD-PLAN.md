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
5. ✅ **structured out/err** — vmservice `listen-streams!`/`:sink` decode Stdout/Stderr
   WriteEvents; nREPL forwards to `:out`/`:err` during eval. `@Error` already handled.
   PROVEN on device: `(println …)`/`(dotimes … println)` output arrives as `:out`,
   interleaved correctly with `:value`. (Lines carry Flutter's own `flutter: ` stdout
   prefix — strippable polish, left as honest passthrough for now.)
7. ⛔ **delete old machinery** — RE-SCOPED. The original list assumed the VM-Service
   path would replace reload too. It doesn't: the new reload REUSES Flutter's hot-reload
   daemon (`trigger-reload` → "r" → "Reloaded N libraries" state machine → done-promise),
   which was the right call (don't reinvent Flutter reload). So the dispatch daemon +
   reload state-machine + `r`/`R` stdin forwarding are now LOAD-BEARING, not deletable.
   Only the legacy `clojure.core.server` socket REPL + `parse-repl-line` + in-app
   `form-exec`/`repl-exec` remain candidates — but they're tangled into the same daemon,
   so removal means editing the working reload path + a full device re-validation, for
   cleanliness not function. Deferred deliberately; the old front is harmless dead weight.
8. ⏳ **polish** — `pick!` via `evaluate(widget-obj,…)`, async Future-await, var-table redefine.

## Error DX (`cljd.repl.errors`) — done 2026-06-30
Turn raw Dart/compiler errors into JVM-Clojure-grade messages. PROVEN on device:
- runtime `@Error` → just the message (`(nth [] 99)` → "Invalid argument(s): No item 99 …"),
  cljd.core / dart: / async-zone / `Eval`-IIFE stack frames dropped; user frames (app nses)
  demunged to `ns/fn (path:line)` via the reverse of `compiler/char-map` (unit-tested).
- compile error → cljd's real cause surfaced (`undefined-sym` → "Unknown symbol: undefined-sym"),
  stripped of the "Error while compiling NO_SOURCE_PATH … / (no source location)" wrapper and the
  raw `:cljd.compiler/emit-stack` map; offending form appended only when it adds info.
- VM-Service rpc errors (bad generated Dart) → the Dart compiler `:details`, banner stripped.
- consistency: errors now set `:status ["done" "error"]` + `:ex`.

## Next DX gap — REPL namespace context
The eval/compile context is pinned to `cljd.core`: `(require …)` and app-qualified symbols
(`kora.data.temporal/after?`) fail with "Unknown symbol" because those nses aren't in the eval
compile's analyzer view. Need `in-ns`/`ns`-aware eval so the REPL can work inside app namespaces
(also unblocks device-verifying the user-frame demunger). Distinct from error formatting.

## Delete list (the simplification)
`parse-repl-line`, the `[id mode)…_` protocol, `form-exec`/`repl-exec`/`ReplHackContrib`,
the dispatch daemon + state machine + `Reloaded N libraries` regexes, `r`/`R` stdin
forwarding, the `clojure.core.server` socket REPL. The 5 prior fixes are transitional.

## Lives in
the cljd build JVM (has compiler+analyzer+nses); VM-service client + nREPL front added
here. Upstreamable as `feat/repl` done right.
