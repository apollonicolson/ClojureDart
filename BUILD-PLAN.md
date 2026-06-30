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
8. 🟡 **polish** — async Future-await ✅ + ns-context ✅ + var-redef characterised (below).
   `pick!` (widget inspection) still TODO.

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

## ns-context, async, var-redef — done/characterised 2026-06-30
Upstream check first: `feat/repl` is a stale 2024 VM-Service spike (850 commits behind, bundled
jars, no integrated REPL source). No upstream fix exists for any of these. So:

- **ns-context** ✅ — nREPL now tracks `*current-ns*` and derives the `evaluate` target library
  from it (`ns->lib-uri`), so a ns's own defs + required aliases resolve. `(in-ns 'x)` switches
  context (no recompile) for any ns present in the compiler's `nses`; `(ns …)` goes via reload
  then switches. PROVEN: `(in-ns 'kora.core)` works. Caveat: only nses actually in `@compiler/nses`
  are reachable (e.g. `kora.navigation` wasn't) — a separate nses-coverage question, not the
  mechanism. `(require …)` standalone still isn't a thing in cljd (put requires in the `ns` form).

- **async Future-await** ✅ — the eval path is Future-aware (`form->dart-await-expr`): detect with
  `(dart/is? v dart-async/Future)` (helper injected into cljd.core at REPL start, gated → "(await
  on)"), schedule `.then`/`.catchError` into `cljd.core/+cljd-repl-fbox+`, return a sentinel, and
  poll the box to the resolved value. PROVEN: `(Future.value 42)`→42, `(Future.delayed (Duration
  .seconds 2) …)`→42 after ~2.2s, error futures → clean `#error` via catchError. Non-futures fall
  straight through to pr-str (no overhead change in result). Top-level `(await …)` is still not
  supported (needs an async IIFE); the supported pattern is "form returns a Future".

- **var redefinition** ✅ SOLVED. The codegen probe showed cljd vars are mutable `name$vN` statics
  and `(set! name v)` compiles to a direct assignment. So eval-form now routes a `(def name init)`
  whose name already resolves (`resolve-symbol` → `:def`) through `(set! name init)` — an INSTANT
  evaluate, no reload. (A plain `def` reload wouldn't take: Flutter hot reload never re-runs an
  existing static's initializer — only hot *restart* would, wiping state.) `defn` redefinition
  already worked via reload (fn bodies reload); new vars still reload to create the static.
  PROVEN: `(def vx 1)`→1; `(def vx 2)`→2; `(def vx (+ vx 100))`→102; `(set! vx 7)`→7 — all instant.
  Also fixed an async-wrapper bug found here: `form->dart-await-expr` referenced the form's value
  3× and cljd inlined the local, re-emitting `set!`'s lifted temp ("already declared"). Now it
  passes the value ONCE to an injected `cljd.core/+cljd-repl-handle` helper.

## Delete list (the simplification)
`parse-repl-line`, the `[id mode)…_` protocol, `form-exec`/`repl-exec`/`ReplHackContrib`,
the dispatch daemon + state machine + `Reloaded N libraries` regexes, `r`/`R` stdin
forwarding, the `clojure.core.server` socket REPL. The 5 prior fixes are transitional.

## Lives in
the cljd build JVM (has compiler+analyzer+nses); VM-service client + nREPL front added
here. Upstreamable as `feat/repl` done right.
