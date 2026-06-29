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

## Steps (each runnable)  — status 2026-06-29
1. ✅ **JVM VM-Service client** — `cljd.repl.vmservice` (JDK WebSocket + JSON-RPC,
   +data.json): connect/rpc/evaluate/reloadSources/library-id. Loads; JSON verified.
2. ✅ **`form->dart-expr`** in `cljd.compiler` — committed; emits Dart IIFEs (verified
   on self-contained forms; real forms need the live bootstrapped context).
4. ✅ **classifier** — `cljd.repl.eval/emits-new-toplevel?` + `eval-form` orchestration;
   classifier verified (expr→eval, def/defn/deftype/ns/do-with-def→reload).
3. ⏳ **wire into build.clj** — capture the app's VM-Service URI from flutter stdout
   ("A Dart VM Service … available at: http://…" → ws://…/ws), `vm/connect`, get the
   main isolate id, and call `eval/eval-form` from the REPL path. *Needs the live
   bootstrapped build + a stable device to validate — the remaining integration.*
5. ⏳ **structured out/err** — VM `Stdout`/`Stderr` streams + `@Error` handling (eval
   already returns `@Error`; streams TODO).
6. ⏳ **nREPL front** (bencode) → `eval-form`. Editors/clojure-mcp jack in; retires the
   external `tools/cljd-nrepl` bridge.
7. ⏳ **delete** parse-repl-line / FormExec / repl-exec / dispatch daemon / reload regex
   state-machine / socket text REPL.
8. ⏳ **polish** — `pick!` via `evaluate(widget-obj,…)`, async Future-await, var-table redefine.

## Delete list (the simplification)
`parse-repl-line`, the `[id mode)…_` protocol, `form-exec`/`repl-exec`/`ReplHackContrib`,
the dispatch daemon + state machine + `Reloaded N libraries` regexes, `r`/`R` stdin
forwarding, the `clojure.core.server` socket REPL. The 5 prior fixes are transitional.

## Lives in
the cljd build JVM (has compiler+analyzer+nses); VM-service client + nREPL front added
here. Upstreamable as `feat/repl` done right.
