# CLJD-DX-AUDIT — what stings in this dialect, and how to fix each

> ClojureDart's developer-facing deviations from JVM Clojure, ranked so each is tackleable.
> Evidence: `file:line` = rank 3 (code on disk in this fork); `doc/differences.md`/README = rank 4;
> "lived" = hit repeatedly building the dev toolkit (rank 1–2). Not runtime-measured.
> **Encouraging finding: most are DX-fixable (a core fn, a lint, a better error) — not deep divergence.**

## Tackle order (pain × frequency)

| # | Sting | Fix | Cost |
|---|---|---|---|
| 1 | **Void-return trap** — void call in value position → `"void can't be returned from Null"` compile error (lived, ~6×; `.add`/`.cancel`/`postEvent`/`reassembleApplication`/`.visitChildElements`) | compiler: coerce void→nil in statement/tail position, OR a lint that flags it. Erases the #1 papercut. | med (compiler) / low (lint) |
| 2 | **`format` absent** — very common, use `str`/interp today | add `cljd.core/format` (or a `cljd.string/format`) | low |
| 3 | **Untyped member access hard-errors** — `.split`/`.-name`/`.-exception` on untyped receiver; needs a hint you can't infer from the form (`compiler.cljc:392,3624`; warn by default, **fail under `^:no-dynamic`**, which the toolkit path hits) | better error: "unresolved member `X` — add a type hint `^T`?" (cljd already improved error DX; this is next) | low–med |
| 4 | **`instance?` is inline-only** — throws as a HOF, so `(partial instance? T)` breaks (`core.cljd:420`); why we reach for `dart/is?` | document + optionally a runtime fallback arity | low |
| 5 | **Macro source-map meta loss** — the def-level precision wall (`compiler.cljc:1408-1410,1344-1349,3792-3796`: `propagate-hints` copies `:tag`/`:annotations`, **not `:line`/`:column`**) | **the coord primitive** (`(trace 'form)`, form-tree paths) sidesteps it; the naive "propagate line/col" was tried and falsified — macros reconstruct forms without inner meta | see DEVTOOLS-DX-VISION §coord |

## By category

### A. Interop papercuts (daily tax)
- **Void-return trap** — see #1. Trailing `nil` is the manual fix; invisible until compile.
- **Untyped member access** — see #3.
- **No `#dart {}` map literal** — `emit-dart-literal` handles only vectors(lists)/seqs(records) (`compiler.cljc:1917-1926`); forced `jsonEncode`→`jsonDecode` round-trips for every `postEvent` Map. *Fix:* support `#dart {}` → a Dart map literal.
- **`catch` needs an extra stacktrace binding** `(catch E e st …)`; **records need 3 ctor args** `(R. a nil {} -1)`; **`super` needs `^super`** on `this` (`doc/differences.md`). Shape papercuts — document prominently.
- **Building Dart collections** — `#dart ^T []` + `.add` for growable lists; the idiom isn't discoverable.

### B. Core / semantic gaps vs JVM Clojure
- **`format` absent** — #2.
- **`instance?` inline-only** — #4.
- **No device `read-string`** — it's `cljd.edn/read-string`, not core (lived; bit `write-state`). *Fix:* alias or document.
- **No device `eval`/`resolve`/`macroexpand`/`slurp`/`spit`** — no runtime var namespace on device (host-only). Expected; document the host/device table.
- **Lazy `def` init** (`differences.md`) — defs initialize **by-need, not top-to-bottom** (tree-shaking); order-dependent top-level side effects silently break. Semantic gotcha — document loudly.

### C. Reload / tooling (all lived)
- **`:watch`/`:managed` binding changes and `defonce` removals don't rebind on hot-reload** — element keeps the stale subscription (surfaced as `ISeqable for int`); needs a restart, no signal. *Fix:* extend reload-legibility to detect binding-shape edits and warn "restart needed".
- **Host code needs a full restart; device `.cljd` hot-reloads** — non-obvious; cost many needless restarts. *Fix:* the reload-legibility warning above + doc.
- **Compile errors on hot-reload silently keep old code** — **mitigated** (reload-legibility surfaces the failure on-device now).
- **VM-Service RPCs aren't sandboxed** — a bad one crashes the whole app (`getSourceReport` whole-isolate). *Rule:* probe any RPC narrowly before firing it broadly (see memory `getsourcereport-per-script`).
- **Orphaned builds keep watching** and recompile mid-edit saves → confusing stale errors. *Fix:* kill-all before relaunch.

### D. The two-runtime model (mental-model tax)
- **Compiler dynamic bindings don't cross into `future`s** — a bare `future` silently drops `binding [compiler/*hosted* …]`. **Fixed** in the toolkit via `eval!`/`with-compiler-context`/`call!` — but it was a silent landmine; upstream cljd could bundle the binding into the eval entry.
- **Macros run on the JVM host** (`^:macro-support` to be reachable) — not self-hosted; a fixed constraint to know.

## Notes
- `doc/differences.md` is partly stale (says "no `instance?`", lists multimethods as flat-missing); `core.cljd` (rank 3) shows `instance?` exists inline-only and multimethods are partial. Trust the code.
- Unverified: whether the fork's REPL eval path exposes a device `read-string`/`eval` beyond `cljd.edn` — read the host `.clj` fn names, not the runtime wiring.
