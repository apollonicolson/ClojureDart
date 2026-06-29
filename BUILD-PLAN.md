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

## Steps (each runnable)
1. **JVM VM-Service client** — `java.net.http.WebSocket` + JSON-RPC (no extra deps).
   `getVM`/`getIsolate`/`evaluate`/`reloadSources`/`streamListen`. Verify `evaluate "1+1"`.
2. **`form->dart-expr` in compiler context** (nses+analyzer bootstrapped). Verify
   `(pr-str (+ 1 2))` compiles to a Dart IIFE.
3. **`eval-expression`** = 1∘2 → `(+ 1 2)` returns structured `"3"` from the live app.
4. **classifier + reload path** — `reloadSources` for def-forms.
5. **structured out/err** — VM `Stdout`/`Stderr` streams + `ErrorRef`.
6. **nREPL front** (bencode) → the above. Editors/clojure-mcp jack in; retires the
   external `tools/cljd-nrepl` bridge.
7. **delete** parse-repl-line / FormExec / repl-exec / dispatch daemon / reload regex
   state-machine / socket text REPL.
8. **polish** — `pick!` via `evaluate(widget-obj,…)`, async Future-await, var-table redefine.

## Delete list (the simplification)
`parse-repl-line`, the `[id mode)…_` protocol, `form-exec`/`repl-exec`/`ReplHackContrib`,
the dispatch daemon + state machine + `Reloaded N libraries` regexes, `r`/`R` stdin
forwarding, the `clojure.core.server` socket REPL. The 5 prior fixes are transitional.

## Lives in
the cljd build JVM (has compiler+analyzer+nses); VM-service client + nREPL front added
here. Upstreamable as `feat/repl` done right.
