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
7. ✅ **delete old machinery** — DONE 2026-07-01 (branch `repl-cleanup-dead-socket`),
   device-validated on the Pixel. Corrects this step's earlier re-scoping: the reload
   state-machine + `r`/`R` stdin forwarding ARE load-bearing and were KEPT; the socket
   transport and the in-app execution chain the new path reused-but-didn't-need were
   removed. Net −420 lines across 5 files. See "Dead-code removal" below.
8. ✅ **polish** — async Future-await ✅, ns-context ✅, var-redef ✅, `pick!` ✅ (all below).

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

## Dead-code removal — DONE 2026-07-01, device-validated (branch `repl-cleanup-dead-socket`)

**Corrected understanding (the original delete list was wrong).** The dead code was two
layers, not one, and the second was NOT actually dead:

1. **Socket transport** — genuinely unused; the nREPL is its own transport.
   Removed: `clojure.core.server` require, `eval-to-repl`, `repl`, `restartable-repl`,
   the `server/start-server` listener, `*repl-states`, `*repl-port`, the `REPL.lock`
   writer.
2. **In-app execution chain** — `recompile-form` (called by the NEW path, `eval.clj:94`)
   emitted `form-exec`/`dispatch-to-repl!`, so this chain RAN on every reload. But it was
   *functionally redundant*: reload only handles `def`-like forms (they take effect by
   being redefined, not re-executed); the nREPL gets values via `evaluate` and output via
   VM-Service streams. So `recompile-form` was rewired to emit the **bare form**, orphaning
   and removing: `form-exec`/`ReplHackContrib` + `dispatch-to-repl!`/`spawn-repl!`/`*repls`/
   `*-repl-control-*`/`PrefixingStringSink` (whole files `flutter/repl_impl.cljd` +
   `flutter/repl.cljd` deleted), `parse-repl-line`, the daemon's socket output-routing,
   and `ReplState.reassemble`'s `repl-exec` post-frame + `scheduleFrame`.

**KEPT (load-bearing):** the reload/restart state-machine daemon (`trigger-reload` → "r"
→ "Reloaded N libraries" → done-promise), `r`/`R` stdin forwarding, `smap-line`, the HUD
picker machinery (`repl-hud`/`ReplState`/`ReplPointWidget`). The `[* RDY)_` restart marker
is now matched by a plain `.contains` instead of `parse-repl-line`.

**Device-validated (rank 1, Pixel 8 Pro):** expression eval, collection literals, `defn`
reload + call via the decoupled `recompile-form` (`(cube 4)`→64, body redefined→12),
var-redef (`vx`→2→102), `println`→`:out` via VM-Service streams, `pick!` arms
("picker ON"/"picker off" — HUD hook intact). Net −420 lines across 5 files.

**Known pre-existing fragility (NOT introduced here):** defining into the default
`cljd.core` triggers recompilation of dependents (incl. `cljd.flutter`) via
`recompile-form`'s `nses-to-recompile`, which WIPES runtime-injected helpers like
`+cljd-repl-pick!` → `(pick!)` then fails to resolve until re-injected. Avoid by
`(in-ns 'kora.…)` to a leaf ns before defining. Fix later: exclude the REPL-injected
defs from dependent recompilation, or re-inject after a core reload.

## Inert after this change
The `ensure-no-existing!` REPL.lock double-launch guard now reads a lock nothing writes
(the socket wrote it), so it never fires. Left in place (harmless); either drop it or have
the nREPL write `REPL.lock` to restore the guard.

## Feature recovery — reimplement over the nREPL (from the deleted socket REPL)
The deleted code implemented real REPL/dev features for the OLD in-app-execution model.
They were dead-in-context under the new nREPL (never populated for `evaluate`d
expressions), so removing them lost no working capability — but the good ones are portable
and should be re-added against the new "JVM-compiles / device-executes" model. Grounded in
the deleted `spawn-repl!` (flutter.cljd) + `flutter/repl.cljd`.

**Tier 1 — core REPL affordances (cheap; injected-helper pattern, like `+cljd-repl-handle`):**
- `*1 *2 *3` result history — inject `+cljd-repl-remember` (`(set! *3 *2)(set! *2 *1)(set! *1 v) v`),
  wrap each eval expr; keeps `*1` the real live on-device value. ~15 lines. Vars already in cljd.core.
- `*e` / `*st` last error + stacktrace — set in the `@Error` / `catchError` paths. ~10 lines.
- Bounded printing `*print-length* 40` / `*print-level* 6` — bind in the eval wrapper. Trivial.
- Prompt/`*ns*` feedback — ns already tracked host-side + returned as `:ns`; prompt is cosmetic.

**Tier 2 — on-device dev features (moderate):**
- `mount!` — hot-swap the picked widget with a REPL value (or reset). Re-wire to the new
  `+cljd-repl-picked+` atom + `ReplState`'s existing `:child` override (`setState`). The
  deleted `repl.cljd` had the working logic to adapt.
- `ancestors` — widget ancestry chain via `debugGetDiagnosticChain` of the picked element. Cheap.
- `*env` scope binding (was the Tier-3 TODO) — resolve the picked widget's lexical locals in
  eval by passing the runtime env to VM-Service `evaluate`'s `scope` param. HIGH value, harder.

**Tier 3 — transport / architecture:**
- Second REPL front reusing the eval core — `cljd.repl.eval/eval-form` is transport-agnostic
  (that's why the socket removal was clean). A terminal / socket / web front is a thin adapter
  over `eval-form` + `vmservice`; only the front differs. Cheap to add another.
- Multi-session eval state (per-session `*current-ns`, `*1` history) — the nREPL `clone` op
  already mints sessions; state is currently global. LOW priority.

## On-device HMR toolset — research synthesis + build order (2026-07-01)
Three parallel research passes (Flutter capabilities / live-programming prior art / exact
runtime symbols). Convergent findings:

**Architectural split (organizing principle).** Cheap, in-app runtime flags + our picker go
in the **overlay**; heavy analysis (CPU sampling, real heap snapshots, interactive tree/
layout explorer, editor completion) runs **host-side on the JVM over the VM Service**. Don't
attempt the heavy tools in-app.

**Foundation already built.** Both the Flutter-capability and prior-art passes independently
land on: the picker + captured `+cljd-repl-picked+` scope IS the 80%. Formalizing captured
picks into addressable scope (à la `sc.api`) is the multiplier everything composes on.

**Symbols verified on-disk (Flutter 3.44.4, `src/rendering/debug.dart` etc.):** the 6 paint
flags (`debugPaintSizeEnabled`, `debugPaintBaselinesEnabled`, `debugPaintPointersEnabled`,
`debugPaintLayerBordersEnabled`, `debugRepaintRainbowEnabled`, `debugRepaintTextRainbowEnabled`)
+ 3 `debugDisable{Clip,PhysicalShape,Opacity}Layers` are top-level bools needing
`WidgetsBinding.instance.reassembleApplication()` after `set!` (a hot reload does it).
`timeDilation` (scheduler.dart, double) and `WidgetsBinding.instance.debugShowWidgetInspectorOverride`
(ValueNotifier-backed) self-trigger — no reassemble. Tree dumps `debugDumpApp/RenderTree/
LayerTree/SemanticsTree` are zero-arg calls → REPL stdout. Gotcha: profile flags split libs
(`widgets/debug.dart` vs `rendering/debug.dart`).

**Prioritized build order:**
- **Phase 0 — REPL muscle memory** (host/injection, no UI): `*1/*2/*3/*e` + bounded printing
  (the Tier-1 helper). Cheapest, unblocks daily use.
- **Phase 1 — overlay debug-flag strip** (near-zero effort, verified symbols): one button
  template over the 9 paint/disable bools (`set!` + `reassembleApplication`), a `timeDilation`
  slider, a `debugShowWidgetInspectorOverride` toggle (Flutter's own inspector, free full
  diagnostics tree — complements our picker), tree-dump buttons → stdout.
- **Phase 2 — picker → workbench**: inspect-to-source (tap → jump to the `f/widget` form; the
  SAFE anchor if scope-capture doesn't transfer), then `*env` scope binding (VM-Service
  `evaluate` `scope` param), `mount!` hot-swap, in-inspector action buttons.
- **Phase 3 — value inspector + probes**: navigable EDN/Dart value tree (Portal/Reveal style)
  in the overlay; live probes (pinned exprs recomputed per rebuild); moldable per-type views
  (Verse/Prayer/datalog).
- **Phase 4 — host-side heavy tools**: frame-timing/jank chart (`SchedulerBinding.addTimingsCallback`),
  editor-parity nREPL ops (`complete`/`info`/`eldoc` from `@nses`), CPU/memory profiling
  (VM Service, host-rendered).

**Two gating spikes before committing Phase 2:**
1. **Scope-capture feasibility on cljd** — ✅ SPIKED GREEN 2026-07-01 (rank 3, read
   `expand-repl-point` flutter.cljd:1024). Every `f/widget` emits a live `get_envmap` closure
   capturing each lexical local by name (`(fn [] (apply hash-map #dart ['sym sym …]))`), and
   source-loc (`:ns/:line/:column`) is captured beside it. So picking gives `{sym → live
   value}` + jump-to-source, for free. To bind a bare local in eval, two mechanisms:
   (A) compile-side `let`-wrap using the reported env-keys + a stored envmap — no vmservice
   change, no objectId lifetime issues [RECOMMENDED]; (B) VM-Service `evaluate` `scope` param
   (`Map<name→objectId>`) — our `evaluate` (vmservice.clj:77) doesn't pass `:scope` yet; needs
   an objectId fetch per value. Phase 2 spine is sound; device confirm = 1 relaunch cycle.

## Rejected path — hybrid JVM-Clojure-on-ART REPL (researched 2026-07-01)
Explored: embed a real JVM Clojure runtime on ART (in-process with Flutter, reached via
`package:jni`) to get true `eval`/`resolve`/`reflect`/runtime-macros on device — the "full
JVM-like REPL." **Verdict: not worth it for live Flutter dev; a detour.** Why (adversarial):
- The embedded JVM Clojure is a **separate island** — two heaps, two var tables. Its full
  semantics operate on JVM/Android objects, **not** cljd vars or Flutter widgets. It can't
  hold or mutate a Dart object; it can only poke Dart via a marshaled callback.
- **JVM→Dart is the crux and it's costly.** Clojure runs on its own threads → calls into Dart
  take the cross-thread "post-message + block-and-wait" path (jnigen `threading.md`), marshaling
  each crossing, deadlock risk. So driving Flutter from it is **slower** than the VM-Service
  nREPL we already have, which reaches the Dart heap natively.
- Cost: 0.7–3.5s Clojure bootstrap on ART, a runtime `d8→DEX` dexer for `eval`, APK/dex bloat.
  Clojure-on-Android is effectively abandoned tooling (peaked ~2015; `xlisp/clojure-android` is
  a lone modern fork). **UNVERIFIED**: no prior art of JVM Clojure *inside a Flutter app* (the
  combination is inference); modern-ART cold-start numbers rest on 2015 benchmarks.
- Pays off ONLY under a different goal (on-device `java.*` libs / Android-SDK reflection /
  self-modifying JVM code) — and then it's a JVM Clojure sandbox sharing a process, not a
  Flutter-REPL enhancement.

Correction folded in: an earlier claim that on-device full-JVM-semantics is "structurally
impossible" was wrong — it's *possible* (you can embed the runtime), just walled off from the
Dart/Flutter heap and slower to bridge than the existing nREPL. The right target for live app
dev remains the **parity layer** (`complete`/`info`/`eldoc`/`*1/*2/*3` from `@nses`), which
makes the cljd nREPL indistinguishable-from-JVM for *interactive* work.

## The interactive REPL surface is ALREADY in the hybrid we have (host compiler + `@nses`)
Key reframe (2026-07-01). The "hybrid" that matters is the one we built: **`evaluate` +
Flutter hot reload**. It is NOT device-only — it is *host-compiles / device-executes*, and the
host is a complete cljd/Clojure runtime holding the full var table (`@compiler/nses`). So the
introspection + metaprogramming surface of a JVM Clojure REPL is already *computed* on the host;
it's just not yet *exposed* as nREPL ops. The "parity layer" is therefore not a consolation for
missing JVM semantics — it is **surfacing what the hybrid already knows.**

Where each JVM-REPL affordance resolves in the current hybrid:

| Feature | Resolves via | Status |
|---|---|---|
| interactive `eval` (type form → runs) | device (`evaluate` / reload) | **have** |
| `macroexpand` / `macroexpand-1` | host compiler | trivial — expander is in-process |
| `resolve` / `find-var` / `ns-publics` / `ns-map` | host `@nses` | wire an op |
| `complete` / `info` / `doc` / `eldoc` | host `@nses` (arglists, docstrings) | wire an op |
| `clojure.reflect`-style object/type inspection | VM-Service `getObject` / `getClass` | possible, different shape |
| `*1 *2 *3 *e` result/error history | injected helper (`+cljd-repl-remember`) | ~15 lines |
| `source` | host disk + captured `:line/:column` | mostly have |

**Sole genuine exclusion:** programmatic runtime `eval` *from inside app Dart code* (app calling
`(eval …)` at runtime) — needs an on-device interpreter (the rejected JVM-on-ART path). No
Flutter app needs it. Everything an interactive Clojure REPL user reaches for is host- or
VM-Service-answerable through the hybrid as built.

**Immediate wiring targets (expose what the host already computes):** `resolve`, `macroexpand`,
`complete`, `info` — the obvious first four nREPL ops, all backed by `@compiler/nses`.

## Slice 1 — DONE 2026-07-01, device-validated (Pixel 8 Pro)
- **`*1/*2/*3` history** — plain holder vars `+cljd-repl-h1/2/3+` injected into cljd.core (the
  ^:dynamic *1/*2/*3 can't hold cross-`evaluate` state — set! doesn't persist without a shared
  binding frame). `+cljd-repl-remember` shifts them; folded into the always-on `+cljd-repl-handle`
  (async path), since `await?` is globally on so the sync-wrap never fires. Reads of *1/*2/*3 in
  user forms are `postwalk-replace`d to the holders (eval.clj `history-reads`). Proven: `424242`
  →`*1`=424242; after 10,20 →`*2`=10.
- **`macroexpand`/`macroexpand-1`** — intercepted in the nREPL eval loop, answered host-side via
  `compiler/macroexpand{,-1}`. Proven: `(when true 42)` → `(if true (do 42))`.
- **`complete`** — nREPL op over `@nses`: defs are direct symbol keys of the ns map (NOT
  `:mappings`, which holds referred/aliased names) — gather both for the ns + cljd.core. Proven:
  "map"→map/mapv/mapcat/…, "redu"→reduce/reduced/…
## Slice 1b + 2a — DONE 2026-07-01, device-validated (Pixel 8 Pro)
- **`info` / `eldoc` / `lookup`** — host-side from `@nses`. A def's info is `(get-in @nses [ns sym])`
  with `:meta` carrying `:doc` + `:arglists` (the latter stored *quoted*, `'(...)`, so unwrap).
  Proven: `info map` → arglists + the lazy-seq docstring; `eldoc assoc` → `[["map" "key" "val"]
  ["map" "key" "val" "&" "kvs"]]`.
- **`*env` scope access (Slice 2a, mechanism-A-lite)** — the picker already captures the widget's
  live `get_envmap`; the pick callback now stores `:env`, `(picked)` copies it into the cljd.core
  holder `+cljd-repl-env+`, and `*env` in user forms is `postwalk`-rewritten to that holder (same
  mechanism as *1/*2/*3). Proven: pick→tap→`(picked)` → `(some? *env)`=true, `(count *env)`=9
  (nav widget's `selected-index`/`current-index`/…). `(get *env "selected-index")` now resolves.

Device-instability note: the Pixel dozes → hot-restarts the app → the nREPL's cached isolate
goes stale (evals return nil). Fix during validation: `adb shell svc power stayon true` +
`cmd statusbar collapse` to keep it awake and foreground. (A durable fix is reconnect-on-restart
in the nREPL — deferred.)

## Slice 3 capability — PROVEN via REPL 2026-07-01 (no code needed)
The verified debug flags toggle live from the REPL today — the overlay is *sugar over evals
that already work*. Proven on device: `(in-ns 'cljd.flutter)` then
`(set! rendering/debugPaintSizeEnabled true)` → true (reads back true);
`(.reassembleApplication (widgets/WidgetsBinding.instance))` → executes; same for
`debugRepaintRainbowEnabled`; reset to false. So the "debug-flag toolbar" is a UI convenience
over `set!`+reassemble, not new capability. (Tree-dump via `dart:developer/log` needs the alias
required in the eval ns — minor.)

## Picker rounded-edge awareness — DONE 2026-07-01 (compile + picker validated; visual unverified)
The picker highlight painter now `canvas.clipRRect`s to a rounded rect the size of the screen
(`+screen-corner-radius+`, a tunable 42.0 constant — no stable Flutter corner-radius API; a
platform channel / Android 12+ `WindowInsets.getRoundedCorner` would give the exact per-device
value). Clipping the canvas (not the Listener) keeps full-screen hit-testing. Proven: app
compiles, pick→tap→`(picked)` still captures (count *env=9). NOT verified: the visual curve —
`adb screencap` returns black for the Flutter hardware surface, so overlay *appearance* needs a
human looking at the device.

## Constraint for the overlay UI (Slices 3–4)
The Flutter-rendered surface is **not screen-capturable** (hardware-composited → black). So any
visual UI (draggable panel, inspector) can be validated by me for *compiles + no crash + logic*,
but its *appearance/UX* requires the user's eyes. Build the logic headless; confirm look with a human.

## Remaining
- **Slice 2b** — bare-local resolution (`(with-picked …)` let-wrap over the reported env-keys),
  `mount!` (live widget hot-swap — riskiest), `ancestors` (widget diagnostic chain).
- **Slices 3–4 (overlay UI)** — the draggable in-app panel: buttons wiring the (proven) debug-flag
  toggles + `pick!` + a navigable value inspector + live probes. This is the one genuinely large
  new-code piece (cljd.flutter widget work + several device cycles) — best as its own focused
  effort now that every underlying capability it needs is proven.

## Durable follow-ups surfaced during Slices
- nREPL reconnect-on-hot-restart (the isolate goes stale on restart → evals return nil; worked
  around with `svc power stayon true`).
- `info`/`complete` currently only reach nses actually compiled into the app.
2. **Editor-parity audit** (the 4th research pass): our nREPL handles `{clone, ls-sessions,
   describe, interrupt, close, eval}` only — missing `complete`/`info`/`eldoc`/`lookup`/
   `load-file`, the ops CIDER/Calva/clojure-mcp use for completion/docs/jump. Answerable
   host-side from `@nses`. Highest existing-user DX unlock.

## Lives in
the cljd build JVM (has compiler+analyzer+nses); VM-service client + nREPL front added
here. Upstreamable as `feat/repl` done right.

## pick! — widget picker over the nREPL, PROVEN on a live device 2026-06-30
The HUD picker + repl-point instrumentation already ship in any `kDebugMode` app whose
root went through `f/run` (kora does). The existing `cljd.flutter.repl/pick!` isn't loaded
and its callback needs the old socket-repl's `*-repl-control-*`, so we built an nREPL-native
picker reusing the live machinery:
- startup injects into cljd.flutter a `+cljd-repl-picked+` atom + `+cljd-repl-pick!` that arms
  the HUD's `hud-enabled` hook with a callback storing the tapped widget's `:loc`
  (`{:ns :line :column}`) + `:env-keys` into the atom. Gated → banner "(… pick on)".
- nREPL recognises `(pick!)` / `(pick! false)` (toggle) and `(picked)` (report last pick AND
  jump `*current-ns*` into the picked widget's ns).
PROVEN on the physical Pixel: `(pick!)` → tap → `(picked)` returned the navigation widget's
env (`selected-index`, `current-index`, …) at `kora.nav:182:5` and switched the REPL to kora.nav.
Works with real touch or `adb shell input tap` — so YES, pick! works on live devices.
Tier-3 TODO: resolve bare widget LOCALS (e.g. `selected-index`) to their live values in eval —
needs binding the runtime env via VM-Service `evaluate`'s `scope` param (env-keys are reported
so you know what's there; ns-level vars already resolve after the jump).
