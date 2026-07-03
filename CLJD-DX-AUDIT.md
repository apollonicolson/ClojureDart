# CLJD-DX-AUDIT — what stings in this dialect, and how to fix each

> ClojureDart's developer-facing deviations from JVM Clojure, ranked so each is tackleable.
> Evidence: `file:line` = rank 3 (code on disk in this fork); `doc/differences.md`/README = rank 4;
> "lived" = hit repeatedly building the dev toolkit (rank 1–2). Not runtime-measured.
> **Encouraging finding: most are DX-fixable (a core fn, a lint, a better error) — not deep divergence.**

## Tackle order (pain × frequency)

| # | Sting | Fix | Cost |
|---|---|---|---|
| 1 | ✅ **FIXED** (`8abfe31`) **Void-return trap** — void call in value position → `"This expression has type void and can't be used"` compile error (lived, ~6×; `.add`/`.cancel`/`postEvent`/`reassembleApplication`/`.visitChildElements`) | `magicast` now coerces a consumed void expr to `(dart/let [[nil expr]] nil)` — run as statement, yield null. Guarded on actual=void so non-void is untouched; `#dart` fixed-list elements routed through magicast too. Measured on device across arg/vector/map/direct positions. | med (compiler) |
| 2 | ✅ **FIXED** (`9bc402c`) **`format` absent** — very common, used `str`/interp before | `cljd.core/format`: Formatter-accurate subset over Dart primitives; output validated byte-identical to `java.util.Formatter` across a 25-case battery (measured device vs JVM). | low |
| 3 | ✅ **FIXED** (`8072cba`) **Untyped member access hard-errors** — `.split`/`.-name`/`.-exception` on untyped receiver; needs a hint you can't infer from the form (warn by default, **fail under `^:no-dynamic`**, which the toolkit path hits) | the "can't resolve member" diagnostic now appends an actionable tail via `dynamic-member-hint`: names the cause (dynamic receiver) + the fix (`^Type` hint), at both member-access sites. Validated on device. | low–med |
| 4 | ✅ **FIXED** (`8c7a16b`) **`instance?` is inline-only** — throws as a HOF, so `(partial instance? T)` breaks (`core.cljd:420`); why we reach for `dart/is?` | HOF failure now explains the Dart constraint (no runtime subtype test) and points at the fixes (`dart/is?`, protocol/multimethod). A runtime fallback is deliberately not added — exact-`runtimeType` would silently break subtype semantics. | low |
| 5 | **Macro source-map meta loss** — the def-level precision wall (`compiler.cljc:1408-1410,1344-1349,3792-3796`: `propagate-hints` copies `:tag`/`:annotations`, **not `:line`/`:column`**) | **the coord primitive** (`(trace 'form)`, form-tree paths) sidesteps it; the naive "propagate line/col" was tried and falsified — macros reconstruct forms without inner meta | see DEVTOOLS-DX-VISION §coord |

## By category

### A. Interop papercuts (daily tax)
- ✅ **Void-return trap** — see #1. Fixed in `magicast`; the manual trailing-`nil` workaround is no longer needed.
- ✅ **Untyped member access** — see #3. Diagnostic now carries a type-hint suggestion.
- ✅ **No `#dart {}` map literal** — FIXED (`8072cba`): `emit-dart-map-literal` emits `Map<K,V>.fromEntries([MapEntry …])`, ending the `jsonEncode`→`jsonDecode` round-trip for interop Maps. `^{:tag [K V]}` types it.
- **`catch` needs an extra stacktrace binding** `(catch E e st …)`; **records need 3 ctor args** `(R. a nil {} -1)`; **`super` needs `^super`** on `this` (`doc/differences.md`). Shape papercuts — document prominently.
- **Building Dart collections** — `#dart ^T []` + `.add` for growable lists; the idiom isn't discoverable.

### B. Core / semantic gaps vs JVM Clojure
- ✅ **`format` absent** — #2. Added `cljd.core/format` (Formatter-accurate subset).
- ✅ **`instance?` inline-only** — #4. HOF failure is now self-explaining.
- **No device `read-string`** — it's `cljd.edn/read-string`, not core (lived; bit `write-state`). *Fix:* alias or document.
- **No device `eval`/`resolve`/`macroexpand`/`slurp`/`spit`** — no runtime var namespace on device (host-only). Expected; document the host/device table.
- **Lazy `def` init** (`differences.md`) — defs initialize **by-need, not top-to-bottom** (tree-shaking); order-dependent top-level side effects silently break. Semantic gotcha — document loudly.

### C. Reload / tooling (all lived)
- ✅ **`:watch`/`:managed` binding changes and `defonce` removals don't rebind on hot-reload** — element keeps the stale subscription (surfaced as `ISeqable for int`); needs a restart, no signal. **Warning shipped** (`3175320`): `build.clj` seeds each file's fragile-binding signature at launch and prints a restart-needed advisory (with before/after diff) when a reload-triggering edit changes it. The reload *semantics* are unchanged — the gap is now legible, not silent.
- ✅ **Host code needs a full restart; device `.cljd` hot-reloads** — non-obvious; cost many needless restarts (the single biggest velocity drag, ~60s each). **In-process compiler hot-reload shipped:** `build.clj` starts a JVM nREPL on the build process (port in `.nrepl-port-jvm`), and `compiler/nses` (the whole symbol table — the only stateful def) is now `defonce`, so `(require 'cljd.compiler :reload)` via clojure-mcp updates every function while preserving the table (measured: **479ms**, `nses` identity-stable, live in the device compile path). Workflow: edit `compiler.cljc` → `:reload` → touch a `.cljd` (watcher recompiles with the new compiler + device reload). Caveat: only *logic* edits — a change to the *shape* of data stored in `nses` needs a manual `(reset! nses …)` + full recompile. `nrepl.clj`/`build.clj` still need a relaunch (their state lives in the running server's closures).
- **Compile errors on hot-reload silently keep old code** — **mitigated** (reload-legibility surfaces the failure on-device now).
- **VM-Service RPCs aren't sandboxed** — a bad one crashes the whole app (`getSourceReport` whole-isolate). *Rule:* probe any RPC narrowly before firing it broadly (see memory `getsourcereport-per-script`).
- **Orphaned builds keep watching** and recompile mid-edit saves → confusing stale errors. *Fix:* kill-all before relaunch.

### D. The two-runtime model (mental-model tax)
- **Compiler dynamic bindings don't cross into `future`s** — a bare `future` silently drops `binding [compiler/*hosted* …]`. **Fixed** in the toolkit via `eval!`/`with-compiler-context`/`call!` — but it was a silent landmine; upstream cljd could bundle the binding into the eval entry.
- **Macros run on the JVM host** (`^:macro-support` to be reachable) — not self-hosted; a fixed constraint to know.

## Notes
- `doc/differences.md` is partly stale (says "no `instance?`", lists multimethods as flat-missing); `core.cljd` (rank 3) shows `instance?` exists inline-only and multimethods are partial. Trust the code.
- Unverified: whether the fork's REPL eval path exposes a device `read-string`/`eval` beyond `cljd.edn` — read the host `.clj` fn names, not the runtime wiring.
