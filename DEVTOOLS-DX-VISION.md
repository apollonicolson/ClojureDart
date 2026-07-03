# ClojureDart On-Device DevTools — Vision & Design

> The on-device developer tooling built into `cljd.flutter` (`cljd.repl.*`): an on-device widget
> picker with a magnifier loupe, a compiled-Dart→ClojureDart source map, a VM-Service nREPL that
> hot-reloads and evaluates into the running app, and an inspector that renders the running UI as
> **values**. This is the north star + design reference + competitive record.

**Stamp:** 2026-07-02 · branch `repl-cleanup-dead-socket` · proven on physical Android (Pixel 8 Pro,
USB) [rank 2]. Evidence ranks inline (1 measured · 2 observed · 3 code · 4 cited docs · 5 design-intent).

---

## 1. Thesis

The best DX here is not "a better inspector." It is treating the running app as a **live image** —
the Lisp/Smalltalk lineage the nREPL comes from — where **the picker chooses *which values* you point
at, and everything after is `map` / `reduce` / `assoc` / `eval`.** The inspector is a **data REPL over
live UI**. Everything below is a view or an op on the same handful of primitives (§2); no feature is
its own subsystem.

**The deeper frame:** Dart/Flutter's own tooling is, underneath, just **data queries** — VM-Service
RPCs (`getObject`, `getProperties`, `getLayoutExplorerNode`, `getCpuSamples`, `evaluate`, `screenshot`,
`ext.flutter.*`…: data in, data out) plus the analyzer's static model (`dart-libs-info` /
`resolve-symbol` / `dart-member-lookup`). **DevTools is one fixed GUI over those queries.** A Clojure
REPL over the app — with the compiler translating cljd↔Dart both ways (source-map ↩, resolve/infer ↪)
— can express the same queries, *compose* them (`(->> (tree) (filter over-budget?) (map props))` — no
DevTools button for that), and render whichever it cares about, on-device, in cljd. So the running app
is a **Clojure-queryable database of its own runtime and tooling**; each "panel" is `(render (rpc …))`,
and the inspector is the saved queries. What isn't free: the *render* (a flame chart is real drawing),
and some RPCs need a *stream* enabled. The *capability* is already data — it's a query away.

## 2. Primitives — the small set everything reduces to

1. **Pick + loupe** — select *which live values* you mean. Tap = deepest app widget; long-press =
   refine (scrub with a magnifier loupe for small targets). One store: `+cljd-picks+` (a vector).
2. **Source map (Dart ↔ cljd)** — the host compiler holds the form↔Dart map; a pick's compiled-Dart
   wloc resolves to `kora/x.cljd:line:col` (normalized short paths). Provenance both directions.
3. **Live compiler + VM-Service nREPL** — the compiler runs in the host JVM as a queryable oracle;
   the nREPL hot-reloads and **evaluates arbitrary forms** into the running isolate.
4. **Everything is a value** — picks are a vector of maps; a widget's scope (`:env`) is a `{'sym val}`
   map; app state is atoms holding immutable values. Inspection = rendering these; querying = `group-by`/
   `filter`; multi-apply = `mapv`.
5. **Code as data** — effects are forms you construct at runtime and splice/eval (`picks-do`); edits are
   `update-in` on a form. Homoiconicity makes both one-liners.
6. **Reactive atoms** — every view `:watch`es the relevant atom, so a `swap!` from device *or* host
   rebuilds it; no `reassemble` hammer.

Props, ancestor tree, scope, multi-apply, time-travel, edit-back are all **views/ops on 1–6**.

### The one lens (consolidation target) — rank 5

The bridges that lift Dart → cljd (env-capture, source-map, form-provenance) are **one thing**: the
compiler answering *"what cljd produced this running Dart?"*, keyed by the pick's location.

```
pick → Dart wloc → (source-map) → cljd loc ──┬─→ FORM   (read source: the widget + its props, as written)
                                              └─→ SCOPE  (env-capture: bindings in scope there)
```

One host resolve — `pick → {form, env, loc}` — and every inspector section is a **projection** of it:
PROPS = the form's args *in cljd* (`.color kora-green`, not Dart's `color: Color(0xFF…)`); SOURCE =
loc; SCOPE = env; TREE = enclosing/ancestor forms resolved the same way. This collapses three code
paths into one and makes app-widget data uniformly cljd. **Proven feasible** [rank 2]: reading the
source at a pick's loc returns the real `(m/ListView .padding … .children […])` form.

Boundaries: (1) **precision** — the source map lands on the nearest preceding form (often the def
body), so pinning the exact picked leaf needs finer mapping (descend by column / richer smap) — the
crux; (2) **value fusion** — the form shows intent (`p/space-md`); live values need each arg eval'd in
the env (`picks-do`-shaped); (3) **framework floor** — pure-Flutter widgets have no cljd form → Dart
diagnostics stay the fallback. The same lens is the **edit-back** substrate: read the form to show it,
`update-in` + re-emit to change it (§5 #5) — consolidating the read and enabling the write are one move.

## 3. Why Clojure raises the ceiling (the moat) — rank 5

- **History is nearly free** — state is immutable values; keeping every past state is holding old
  references (structural sharing, O(1)/step), not deep-copying a mutable graph. Time-travel is cheap
  *here* and expensive with mutable widgets (Dart/Swift/Kotlin).
- **Effect-as-value** — `picks-do` splices a form into `(mapv (fn [p] (set! *env (:env p)) <form>) picks)`.
- **Scope-as-value** — `:env` is a map: `select-keys`, `data/diff` two picks' scopes.
- **Selection-as-dataset** — `(picks)` is a vector: `group-by`/`filter`/`frequencies`.
- **In-language self-instrumentation** — repl-points + env capture are ordinary macros
  (`f/widget` → `expand-repl-point`), not a bolted-on compiler pass.

Honest boundary: immutability+hot-load is Erlang; live image is Smalltalk; eval-a-string is JS. The
Clojure sweet spot is the **intersection** — homoiconic forms **and** persistent values **and** macro
self-instrumentation **and** a compiler-in-the-image, together.

## 4. Design — the current system (rank 2/3)

**On-device HUD** (`cljd.flutter`, kDebugMode only): a draggable/collapsible panel — a tools row
(pick · paint · repaint · baseline · pointers) pinned to width, a picks list, a live hover readout, and
a **magnifier loupe** during long-press. The drag handle carries a **REPL-connection colour** (green
live / red stale, both states) — an HMR-style indicator driven by a host heartbeat (see below).
Rendered above `MaterialApp`; highlights + loupe are canvas overlays (no `BackdropFilter` →
backend-independent, incl. emulators).

**Pick** → `+cljd-picks+` (one store; the earlier parallel single-pick system was removed, resolving a
hook conflict). Each pick captures `{:scope-loc :widget-loc :type :widget :ancestors :state :expanded?
:resolved? :cljd :rect}` — **no cached env**: the retained `:state` (the enclosing repl-point's Flutter
`State`, whose `.widget` the framework updates each rebuild) lets `pick-env` re-read the lexical scope
**live**, so value locals track the widget's latest build, not pick time. `:expanded?` is per-pick (each
pick expands independently — no separate expand atom to keep in sync).

**Inspector disclosure** (tap a pick to expand) — the data-lens, all rendered from captured values:
- **box** — size @ position (`RenderBox` rect, at pick time).
- **PROPS** — the widget's Flutter diagnostics, tapped from `toDiagnosticsNode().getProperties()`
  **on-device** (no inspector RPC); `null` props filtered.
- **TREE ↑** — app-authored ancestor lineage (type · cljd source), captured via `visitAncestorElements`
  + `createdByLocalProject` at pick time.
- **SCOPE** — the pick's lexical scope re-read **live** via `pick-env` (from `:state`), gensyms
  filtered, atoms deref'd (`counter @42`). The panel `:watch`es the connection heartbeat, so it
  re-renders ~1/s and the scope stays current as the app runs (was frozen at pick time).

**Host / REPL** (`cljd.repl.*`): VM-Service nREPL; Dart→cljd resolve (`resolve-wloc`, normalized via
`short-cljd-path`); **de-truncation** — string results paged past `evaluate`'s 128-char cap via
`getObject` (`get-string-full`). Ops: `(pick!)` (arms via `arm!`), `(picks)` (catalog as JSON via the
`ext.cljd.picks` service extension), `(picked)` (active = `(peek +cljd-picks+)`; loads the pick's live
env into `*env`, jumps ns), `(picks-do FORM)` (runs FORM in every pick's scope), `(cljd-src …)`. `*env`
gives eval-in-scope: `(get *env 'sym)`.

**The host↔device boundary** (`cljd.repl.eval`): every crossing goes through one bundled `context`
and three sanctioned forms, so no call site re-writes the compiler binding block or threads
`client`/`iso-id`: `(eval! ctx form opts)` — a self-contained device eval, **safe from any thread/future**
(it establishes the compiler dynamic bindings itself; a bare `future` dropping them was the recurring
silent bug); `(with-compiler-context ctx ns …)` — the binding frame, for the one multi-form eval batch
that changes `*current-ns*` mid-way; `(call! ctx method params)` — a structured service-extension call.

**Connection heartbeat**: the host pings `ext.cljd.ping` ~1/s → device sets `+cljd-connected+` and
re-arms a 3 s watchdog; if pings stop (process died / VM detached) the watchdog flips it red. Because
cljd atoms notify watchers on every `reset!` (even to an equal value), this doubles as a free ~1 s
refresh tick for the live inspector — no polling timer of its own.

**Reactivity**: `repl-selections`/`repl-highlights` `:watch` `+cljd-picks+`/`+cljd-refine+` (+ the
heartbeat tick for live scope) — device toggles, host pushes, and app-state changes all rebuild
reactively. `:expanded?` moved onto each pick, so there's no separate expand atom to keep consistent.

**Leave vs tap — the integration rule:** *tap Dart's own runtime diagnostics on-device; leave the
aggregate / perf / desktop surfaces to DevTools.*

| Flutter/Dart surface | Decision |
|---|---|
| `toDiagnosticsNode().getProperties()` | **tap** (on-device, no RPC) — PROPS |
| `getSelectedWidget` + `createdByLocalProject` | **tap** — source map + app filter |
| `visitAncestorElements` / `debugGetDiagnosticChain` | **tap** — TREE |
| `RenderBox` rect / size / constraints | **tap** — box model |
| VM Service `evaluate` / `reloadSources` / `getObject` | **tap** — nREPL + de-truncation |
| `getRootWidgetTree` / summary tree / `getLayoutExplorerNode` | **leave** to DevTools |
| `screenshot` / `trackRebuild`/`RepaintWidgets` / `structuredErrors` | **leave** (perf/error tooling) |
| dart MCP (`hot_reload`, `widget_inspector`, `get_runtime_errors`) | **leave** as the *agent* lens; nREPL is primary |

## 5. Roadmap — collapses (done) and the frontier

Most of the competitive field (§7) collapses onto §2. Done this line of work:

| Capability | Status |
|---|---|
| on-device pick · **loupe** (unique) · Dart→cljd source · arbitrary eval | ✅ |
| eval-in-scope (`*env`) · multi-apply (`picks-do`) | ✅ |
| structured data-lens: box · PROPS · TREE · SCOPE | ✅ |
| de-truncation (full values over nREPL) | ✅ |
| one pick store (consolidation) | ✅ |
| **live scope** — env re-read from `:state`, inspector refreshes ~1/s (no frozen snapshot) | ✅ |
| **REPL-connection indicator** — host heartbeat → handle colour (HMR-style) | ✅ |
| **host↔device boundary** — one `context`; `eval!`/`with-compiler-context`/`call!` (future-safe) | ✅ |
| state reduction — per-pick `:expanded?` (−1 atom), `armed` boolean (was fn-or-nil hook) | ✅ |

Frontier (each cheaper *here* than the non-Lisp baseline, but with real dependencies):

- **#3 agent surface — done.** The ops are plain nREPL evals against the VM-Service nREPL the build
  writes to `.nrepl-port`; an agent (clojure-mcp/CIDER/Calva, or a raw bencode client) connects there
  and gets `(pick!)`/`(picked)`/`(picks)`/`(picks-do FORM)`/`(cljd-src …)` and `*env` directly —
  verified by driving them over bencode this line of work. The socket-REPL bridge in `tools/cljd-nrepl`
  is the **legacy** path (pre-dates the embedded VM-Service nREPL); the canonical agent surface is the
  `.nrepl-port` the `cljd.build flutter` process writes (needs `CLJD_VMREPL=1`).
- **#4 state time-travel — SHIPPED, HOST-recorded (rank 1).** The device exposes state as **data
  addressed by a stable id** `[loc sym]`: `read-state` → `{[loc sym] → value}` (a pure READ of every
  live repl-point atom — `live-state` walks from `::app-root`), `write-state` DIRECTs the app back
  (resolve id → live atom → `reset!`). The **host** holds the timeline (`(epoch!)` records a
  read-state, `(restore-epoch N)` replays it; `(states)` reads current state). No device-side epoch
  store — the durable memory is the host, so the recording survives device restart, and ids re-resolve
  against whatever atoms are live. Validated: `(states)` returns the whole app state (nav index +
  journal entries + `#inst` dates as EDN); record → change → restore reverts. Limits: only
  EDN-round-trippable values restore; a read taken *mid-rebuild* races (settle first); Riverpod
  providers still out of scope.
  **Continuous stream — SHIPPED:** `(record)` add-watches every atom (re-armed on the heartbeat to
  catch newly-mounted repl-points) so each change streams to the host (`cljd.state-change`) as an
  ordered timeline; `(seek N)` replays 0..N → `write-state`. Validated: record → mutate → `(seek 0)`
  reverts. So the state channel is complete — discrete epochs AND continuous record/seek, all
  host-recorded, id-addressed. Remaining primitive: **compile-with-coordinates** (value-flow tracing)
  — a compiler-emit change, its own spike.
- **#5 form-level edit-back** — `update-in` a form + re-emit (compiler owns provenance); reverse-
  *inference* (Sketch-n-Sketch style) is research-grade.

Also shipped this line: **reload-legibility** (a watch-compile failure now `report-error!`s onto the
device — inline + amber handle + exact loc — instead of silently keeping old code); **`(dart-of 'form)`**
(the emitted Dart for a form, host-side); **tap-to-re-target** (§8, below).
### Explored & ready to build (rank 3/5 — designed via the 2026-07-03 exploration)

- **Line-granular coverage — SHIPPED.** `(coverage)` snapshots which cljd forms executed;
  `(ran)` diffs since the snapshot → cljd locs newly run, via `getSourceReport(Coverage,reportLines)`
  **per-script** (whole-isolate crashes the on-device app — see memory `getsourcereport-per-script`),
  mapped through `resolve-wloc`. Zero instrumentation; the line-level layer *below* the coord primitive.

- **Value-flow tracing `(trace 'form)` — the marquee, now de-risked.** A **host-side form-rewrite**,
  NOT emit surgery: a compile-time walk wraps each sub-expression in `(record! coord expr)` (returns
  the value, side-effects `postEvent "cljd.trace" {coord,value}`), emits the rewritten form, registers
  the form once. `emit` (`compiler.cljc:3716`) stays untouched — decisive de-risk. Coord = form-tree
  path (`"2,1"`), reusing FlowStorm's `{form,coord}` display. **Load-bearing correctness = the skip-list**
  (don't wrap binding-vec symbols, `quote`/`fn*` params, `.`-member symbols, `recur`/`set!` targets,
  type tags). On-demand/opt-in first; measure overhead before any always-on emit pass. **This is the
  real fix for the def-level source-map wall** (CLJD-DX-AUDIT #5) at sub-expression granularity.

- **Clojure-native observability — cheap, `tap>` already exists.** `cljd.core` defines
  `add-tap`/`tap>` (`core.cljd:9370`). Device→host: `(add-tap #(post-event! "cljd.tap" {:edn (pr-str %)}))`
  + a `cljd.tap` host branch + `(taps)` — ~15 lines (cljd `tap>` runs inline, so keep the fn cheap).
  **Portal** (djblue/portal) in the host JVM (`add-tap #(portal/submit %)`) = near-free data browser,
  zero device change. **datafy/nav** = highest value/line (opaque Dart objects → maps). mulog: port the
  event-map + `with-context` idea, skip its runtime (the Extension stream is the transport).

- **One unified timeline — consolidation.** Fold `cljd.tap`/`cljd.log`/`cljd.error`/`cljd.state-change`
  into a single `+cljd-timeline+` keyed by `:t`, tagged `:kind`; `(timeline)` / `(timeline :kind …)`.
  Causal ordering across taps, logs, errors, and state — one event log for the four channels built so far.

- **Cross-restart auto-replay ("hard rollback").** The change-log survives device restart (host-side);
  wire replay-on-reconnect: detect a fresh app via **isolate-id** (a heartbeat gap only *triggers the
  check* — it can't distinguish restart from backgrounding/GC), **settle-poll** the `[loc sym]` id-set
  across ~2 reads before `write-state` (mid-rebuild reads tear), **opt-in**. Can't restore: nav-stack,
  native/platform, in-flight async, focus/scroll (not `[loc sym]` atoms).

- **Riverpod into the state channel.** A dev-mode `ProviderObserver` (Riverpod 3.0: single
  `ProviderObserverContext`) streams provider changes as `cljd.state-change` → `read-state`/`write-state`/
  `record`/`seek` cover providers. Constraint is **addressability**: named `StateProvider`/`Notifier`
  are clean & symmetric (write via `.notifier`); anonymous/derived/async providers are read-only.

**Suggested sequence:** `tap>` + unified timeline (cheapest, consolidates the channels) → `(trace 'form)`
(marquee, retires the source-map wall) → cross-restart + Riverpod (design-ready).

- **Out of scope (do not collapse):** network inspection, perf/recomposition profiling — separate
  instrumentation, arguably a different tool. Design-canvas previews and running-incomplete-programs:
  not our lane.

**Test for any new feature:** does it treat the app as values under `map`/`reduce`/`assoc`/`eval`? If
yes it collapses to a few lines and composes. If it needs a new subsystem, it's fighting the grain.

## 6. Applies to real devices AND VMs — rank 3/5

Device-agnostic by construction: the stack rides the **Dart VM Service** (same on physical, emulator,
simulator), the **Flutter debug runtime** (the app's own code), and the **host JVM compiler** (never
touches the device). **The real boundary is debug-vs-release** — release/AOT strips the VM Service,
`track-widget-creation`, and asserts. Debug/JIT → everything works everywhere.

| Concern | Physical | Emulator / simulator |
|---|---|---|
| VM-service reach | adb forward; iOS fussier | localhost, trivial |
| Rendering backend | Impeller | may be software GL — **de-risked** (loupe uses `toImageSync`, not `BackdropFilter`) |
| Perf realism | truthful | **not trustworthy** — only the perf front cares |

Emulator for fast iteration; physical device when perf must be real.

## 7. References / competitors — rank 4 (cited, 2024–2026; not measured)

The four-agent investigation (Flutter · RN/Expo · native iOS+Android · frontier). **No shipping tool
combines on-device pick + loupe + source-map-to-your-language + arbitrary-form REPL on the device
itself.** Three beat us on a single axis; none has a picking **loupe**.

**Flutter:** DevTools Widget Inspector (select mode; overlay on-device, tree tethered) · Layout/Flex
Explorer (transient) · **Property Editor** (writes source; IDE-only) · Stateful Hot Reload · VM Service
`evaluate`/`evaluateInFrame` (our eval primitive) · track-widget-creation · DTD · Dart&Flutter MCP · IDE plugins.

**React Native / Expo:** React Native DevTools (Hermes console REPL, Components panel) · in-app Element
Inspector (tap→`_source`) · **Radon IDE** (editor-embedded device; click-to-source, break-on-tap,
**Replays** time-travel; closest competitor) · Fast Refresh · Expo Dev Client · react-native-dev-inspector
· Reactotron · Flipper (deprecated).

**Native:** Xcode View Debugger (frozen 3D snapshot) · SwiftUI `#Preview` (canvas) · **Reveal** (live
property editing) · Android Studio Layout Inspector (live, jump-to-source) · **Compose Live Edit** /
**SwiftUI Previews** (fast source→UI) · Compose Preview.

**Frontier:** Hazel (runs incomplete programs) · **Sketch-n-Sketch** (bidirectional edit-back) · Compose
Hot Reload/HotSwan (state-preserving) · **re-frame-10x** (CLJS epoch time-travel) · LiveView Native
(server-driven native) · Codux/Tempo (two-way React↔source) · Play (SwiftUI, live on-device preview) ·
**shadow-cljs/Krell** (CLJS-RN REPL-into-device — our direct lineage; no picker/loupe/source-map).

**Beats us on an axis:** Property Editor (edit-back, narrow) · Radon Replays (time-travel) · Compose
Live Edit / SwiftUI Previews (reload latency). **We alone have:** an on-device pick + **magnifier loupe**
+ compiled→cljd source-map + arbitrary-form REPL, in one tool on the device.

Sources: docs.flutter.dev/tools/devtools/inspector · /tools/property-editor · dart.dev VM service.md ·
reactnative.dev/docs/react-native-devtools · radon.swmansion.com · developer.android.com/studio/debug/layout-inspector
· /develop/ui/compose/tooling/iterative-development · developer.apple.com/documentation/xcode · revealapp.com
· hazel.org · ravichugh.github.io/sketch-n-sketch · github.com/day8/re-frame-10x · github.com/vouch-opensource/krell
· github.com/JetBrains/compose-hot-reload · createwithplay.com

## 8. Open questions — rank 5

- Does hot-reload preserve nav-stack + input state, or only widget-tree state? (gates how #4 leans on it)
- #4 restore: are the atom *identities* harvested from repl-point envs stable across an epoch restore
  (so `reset!` targets the atom the live widget still watches), or does a rebuild swap them out?
- `toImageSync` per-frame cost for a frame ring-buffer on a real device — throttle/shrink region?
- Reverse-edit (#5) needs the source-map invertible enough to locate the exact form; unproven.
- Ancestor TREE tap-to-re-target — SHIPPED: `capture-ancestors` retains each ancestor's live `:el`
  (guarded by mounted at tap time), TREE rows are tappable (`detail-section` wraps a row that carries an
  on-tap), and a tap `pick-element!`s that ancestor. Compiles clean; needs an on-device tap to confirm
  the interaction end-to-end (can't synthesize a tap from the REPL).
