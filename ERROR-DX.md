# ERROR-DX — Dart/Flutter → ClojureDart error loopback

> Map of how errors are emitted, transported, and how they should map back to a Clojure
> dialect. Companion to `DEVTOOLS-DX-VISION.md`. Evidence tagged by rank; recall marked.

## 0. Problem (rank 2/3 — read from source)

cljd's error DX today (`cljd.repl.errors` + wiring at `nrepl.clj:325/331`) has two properties that
make it "notoriously bad":

1. **Eval-only.** Errors reach cljd *only* inside the `evaluate` loop — a runtime `@Error` result or a
   host compiler `ExceptionInfo`. Grep confirms **nothing** consumes framework errors, async/uncaught
   errors, or debugger exception events. A `FlutterError` during `build()`, a gesture assertion, an
   async throw → console/`build.log`, invisible to the REPL.
2. **Text-parsing, not structured.** `format-runtime` scrapes the formatted console blob
   (`#\d+` frame regex over `"Unhandled exception:\n…"`) instead of consuming the structured error
   data the VM Service already carries. Brittle by construction.

Plus cljd's *own* compiler errors are inconsistent: mostly `ex-info` with thin ex-data (`{:form x}`),
but e.g. `compiler.cljc:3477` throws a bare `Exception.` with none. No phase tags, no loc schema.

## 1. The channels — how errors are emitted (rank 3, cited)

| # | Channel | Transport | Format | cljd today |
|---|---|---|---|---|
| A | Console / stderr | flutter stdout → `build.log` | text (`Unhandled exception:`, `══╡ EXCEPTION CAUGHT BY ══`) | runtime: text-parsed; framework: **ignored** |
| B | Compile failure of eval'd code | JSON-RPC **error 113** "Expression compilation error" | JSON-RPC `error` (not `result`) | handled (`format-compile` `:error`→`:data :details`) |
| C | Runtime throw in eval'd code | `evaluate` **`@Error` result** | JSON: `kind`+`message`(+`exception`/`stacktrace` refs) | **only `:message`** used; refs discarded |
| D | Debugger exception | **`Debug` stream `PauseException` event** | JSON: `exception`(@Instance)+`topFrame` | **unused** |
| E | Flutter framework error | **`Extension` stream `Flutter.Error` event** | JSON: `DiagnosticsNode` tree | **unused** ← biggest gap |
| F | Host cljd compile | thrown `clojure.lang.ExceptionInfo` | JVM ex + `:cljd.compiler/emit-stack` | handled (`format-compile`) |

**Key leverage:** channel E rides the *same* VM-Service Extension stream cljd already listens to for
`cljd.pick` (`vmservice.clj` dispatches Extension events by `extensionKind`; `nrepl.clj` branches on
`"cljd.pick"`). Consuming Flutter errors is a **new branch** `(= kind "Flutter.Error")`, not new infra.

## 2. What the errors ARE — taxonomy

**Host side (cljd, before push):** reader errors · macroexpansion errors · compile/emit errors
(`Unknown symbol`, `Can't resolve type`, dynamic member-access on untyped receiver, `Unsupported dart
literal`, assign-to-immutable) · the dynamic-warning.

**Device side (Dart/Flutter):**
- **VM `ErrorKind`** (rank 3, `service.md` v4.22): `UnhandledException` (Dart throw; carries `exception`
  + `stacktrace` `@Instance`), `LanguageError` (compile/parse), `InternalError` (VM bug),
  `TerminationError` (isolate killed).
- **Flutter categories** (rank 3, api.flutter.dev): build-phase (`context = ErrorDescription('building
  X')`), layout/constraint (`RenderBox`, many `assert`), gesture/pointer (`GestureBinding
  assert(!_debugLocked)` = the `'!locked'` one), general debug `assert` → `AssertionError`. Asserts
  compile out in profile/release.
- **Uncaught async:** `PlatformDispatcher.onError` (catch-all incl. async) / `runZonedGuarded` — only
  `FlutterError.onError` flows through `structuredErrors`; async/zone escapes need explicit wiring.

## 3. Do they push via API/JSON? — yes, mostly structured

- **B** RPC error 113 — JSON-RPC error object. **C** `@Error` — JSON result (`kind`+`message`, plus
  `exception`/`stacktrace` `@Instance` refs you resolve via `getObject`). **D** `PauseException` — JSON
  Debug-stream event (`exception` @Instance, `topFrame Frame`), gated by `ExceptionPauseMode`
  None/Unhandled/All via `setIsolatePauseMode`. **E** `Flutter.Error` — JSON Extension-stream event;
  payload = `FlutterErrorDetails.toDiagnosticsNode().toJsonMap()`: a recursive tree, keys `description`
  `type` `name` `level` `style` `properties` `children` + error extras `errorsSinceReload`
  `renderedErrorText`.
- **A** console text is the lossy fallback (rendered *from* the same DiagnosticsNode tree via
  `TextTreeRenderer`). Parsing it is strictly worse than consuming B–E.

`FlutterErrorDetails` fields (rank 3): `exception:Object`, `stack:StackTrace?`, `library:String?` (the
`CAUGHT BY <library>` banner), `context:DiagnosticsNode?` ("thrown during …"), `informationCollector`
(lazy hints), `silent:bool`.

## 4. How they should map back to cljd — one model

**Target: every error becomes one Clojure-native `ex-info` with a uniform ex-data schema**, so a single
renderer handles all six channels:

```clojure
(ex-info message
  {:cljd.error/phase   ; :read :macroexpand :compile :push :runtime :flutter :async
   :cljd.error/loc     ; {:ns … :line … :col …}  — cljd source, via resolve-wloc
   :cljd.error/dart    ; {:loc "kora/x.dart:42" :kind "UnhandledException" | flutter-library}
   :cljd.error/stack   ; [ {:cljd "ns/fn (path:line)"} … ]  — demunged, noise-dropped
   :cljd.error/raw})   ; the source object (blob / @Error / DiagnosticsNode map) for drill-down
```

- **Reuse** `resolve-wloc` (Dart→cljd loc) + `demunge-name`/`clean-frame` (already in `errors.clj`) for
  every channel's frames — don't re-parse per source.
- **Per-channel pipeline:** B→`:compile`; C→read `kind` (LanguageError→`:compile`, UnhandledException→
  `:runtime`) + `getObject` the `exception`/`stacktrace` refs instead of scraping `message`;
  D→`:runtime`/`:async` (debugger-gated); E→`:flutter`, walk the DiagnosticsNode tree to
  `{summary, thrown-during (context), stack, hints (informationCollector), children}`.
- **Rendering the DiagnosticsNode tree** is itself the PROPS/TREE data-lens the inspector already does —
  the same recursive-node renderer serves errors.

## 5. cljd's own errors — per Clojure/dialect

**Clojure's model** (the target dialect behaviour): errors are `ex-info`/`ex-data`; `clojure.main`
triages via `:clojure.error/phase` — `:read-source :macro-syntax-check :macroexpansion
:compile-syntax-check :compilation :execution` — and formats with `ex-triage`/`ex-str`; `spec`
surfaces `explain-data`. Phase drives *what* the message says and *where* it points.

**cljd should mirror this:** give every compiler throw a consistent `ex-info` with
`:cljd.error/phase` + source loc + the offending form (fix the bare `Exception.` at `compiler.cljc:3477`;
thicken thin ex-data). Then host-compile errors and device-runtime errors flow through the **same**
schema and renderer as §4 — the dialect's errors read like Clojure's, and one code path serves both
sides of the boundary.

## 6. Implementation order — cheapest / highest-value first

1. **`Flutter.Error` branch** in the existing Extension event-sink → framework errors surface in the
   REPL as structured data. Uses plumbing that already exists. *Closes the biggest gap for the least code.*
2. **Structured `@Error`** — in `eval.clj`, read `:kind` and `getObject` the `exception`/`stacktrace`
   refs instead of text-parsing `:message`. Deletes the regex scraper.
3. **Uniform `ex-data` schema + one renderer** (§4) replacing the two string formatters.
4. **Phase-tag the compiler** (§5); fix `compiler.cljc:3477`.
5. **(optional) `PauseException`** subscription (debugger-gated) + `PlatformDispatcher.onError` wiring for
   async/uncaught that escapes `structuredErrors`.

**Test for the design:** does an error arrive as *data* (a map with phase + loc + stack + raw), rendered
by the same node-renderer as the inspector — or as a string someone parses? If the latter, it's the old
system.

## 7. Consolidated intake — SHIPPED (rank 1, validated on device)

The consolidation from §6 is built: **one intake, one event kind, one store — cljd owns it** rather than
consuming each vendor channel separately.

- **Device (`cljd.flutter`):** `report-error!` — the single push entry any cljd code (or any Dart
  extension) calls to surface an error; it `postEvent`s a normalized `cljd.error` event
  (`{phase, message, stack}`). `register-cljd-devtools!` **chains onto `FlutterError.onError`** (calling
  the previous handler, so DevTools' `structuredErrors`/red-screen still work), so every framework error
  auto-funnels into the same `report-error!` → `cljd.error`.
- **Host (`cljd.repl.nrepl`):** the existing Extension event-sink gained one `cljd.error` branch →
  `remember-error!`, which appends a `:kind :error` entry onto the **unified `+cljd-timeline+`** (there is
  no separate error store); it **live-forwards** to the active eval's `:err` and is read back as data via
  the **`(errors)`** op — a derived `errors-view` over the timeline's `:error` entries (`(errors :clear)`
  drops them).
- **Validated:** a `report-error!` probe and a synthetic `FlutterError.reportError` both flowed through
  and appear in `(errors)` as `{:phase … :message … :stack …}`. "Any extension pushes errors" = adopt the
  `cljd.error` convention (or call `report-error!`).

### 7a. Unification round 2 — SHIPPED (rank 1, validated)

Everything now flows into the **one timeline** with a consistent `{:phase :message :stack :count}` shape:
- **eval-path folded in** — runtime `@Error` and compile errors now go through `remember-error!` onto the
  unified timeline too (not just the synchronous `:err`), so `(errors)` is the single source of truth for
  *every* error. Validated: `runtime`, `compile`, `flutter`, and pushed errors coexist in `(errors)`.
- **phase from `@Error` kind** — `eval.clj` carries `:dart-kind`; a `LanguageError` tags `:compile`,
  else `:runtime`.
- **compiler consistency** — the Unknown-symbol throw (`compiler.cljc:3539`) is `ex-info` with
  `:cljd.error/phase :compile` (→ clean `"Unknown symbol: …"` in `(errors)`), not a bare `Exception.`.
- **async hook** — `PlatformDispatcher.onError` → `report-error! "async"` (returns `false`, non-invasive).
- **coalescing** — identical consecutive errors collapse to one entry with `:count N` (a reassemble
  burst of the `'!locked'` gesture assertion no longer floods; duplicate live-forwards suppressed).
  Validated: 5× identical → `{:count 5}`.
- **on-device error list** — `report-error!` coalesces onto `+cljd-errors-local+` (a bounded vector,
  same `:count` coalescing as the host); the DevTools surface renders those errors inline as a list
  below the picks (tap the header to clear). (No `⚠ N` counter badge was built — the inline list
  superseded that design; see §7b's "errors render inline below picks" note.)

### 7b. resolve-wloc frames — SHIPPED (rank 1, validated)

Eval-path runtime error stacks now source-map to cljd: `errors/clean-frame` takes a resolver
(`dart path:line` → `cljd path:line:col` via the compiler source map), and the nREPL eval-path passes
`resolve-wloc`. Validated: a thrown `kora.nav` fn reports `kora.nav/kora_boom (kora/nav.cljd:138:3)` —
the cljd source line, not the raw `.dart`. (Device-pushed framework/async stacks stay raw — they're
mostly framework frames that don't source-map, and the framework frames are the useful part there.)

**Deliberately NOT done (checked — no payoff):**
- **structured `@Error` via `getObject`** — the `@Error` `:message` already carries msg+stack, parsed and
  now source-mapped; `getObject` on the exception/stacktrace refs returns the same strings +2 RPCs.
- **compiler phase-tags on all throw sites** — nothing reads the compiler-internal `:cljd.error/phase`
  (the nREPL compile-catch already tags `:phase "compile"`); tagging ~15 sites = a full recompile for an
  unread field. (`:3539` bare-`Exception`→`ex-info` was worth it for consistency; the rest are `ex-info`.)

**Done since:** top-level-defn fn-name demunge (`kora_boom`→`kora-boom`, validated); on-device error
*list* (errors render inline below picks, no tabs). **Line-granular coverage** also shipped
(`(coverage)`/`(ran)` — per-script `getSourceReport`; whole-isolate crashes the app, see memory
`getsourcereport-per-script`). Next execution-visibility step is value-flow tracing — see
DEVTOOLS-DX-VISION "Value-flow tracing `(trace 'form)`".

**Optional not done:** `PauseException`/`setIsolatePauseMode` (trap *every* throw, not just framework);
`dart:developer log` → Logging stream into the unified timeline.

## Sources
VM Service: `dart-lang/sdk runtime/vm/service/service.md` v4.22 (`Error`, `ErrorKind`, `evaluate`,
`PauseException`, `ExceptionPauseMode`, `getObject`, `getStack`). Flutter: api.flutter.dev
(`FlutterErrorDetails`, `FlutterError.presentError`, `DiagnosticsNode.toJsonMap`,
`WidgetInspectorServiceExtensions`), flutter/flutter PR #72446. cljd: `cljd/repl/errors.clj`,
`eval.clj`, `nrepl.clj`, `compiler.cljc` (this repo). `Flutter.Error` event kind + `postEvent` marked
`[recalled]` by the research pass — confirm against a live `Flutter.Error` event before relying on the
exact kind string.
