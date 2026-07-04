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
| **frame-safe `write-state`** — host-directed reset!s defer to a post-frame callback (a mid-frame reset! → :watch → setState → "Build scheduled during frame" + torn rebuild) | ✅ |
| **in-process compiler hot-reload** — build JVM nREPL (`.nrepl-port-jvm`) + `defonce nses`; `:reload` the compiler in **479ms** vs a ~60s relaunch, symbol table preserved | ✅ |

**Dev-velocity note (lived).** The device hot-reload loop is already as fast as CLJS/Flutter (~1s). The
friction was concentrated in the *host* layer: compiler/tooling edits meant a full ~60s kill+relaunch,
and non-composable introspection ops (`(errors)`/`(timeline)` are bare host forms, not values). The
in-process compiler reload removes the first for `compiler.cljc` logic edits; the second is now closed by
`(q FORM)` (host-eval over the collected stores as values). **`nrepl.clj` now hot-reloads too** (shipped):
the durable stores are top-level `defonce`, the per-connection context lives in a `defonce +state+`, and the
sinks + request dispatch are `#'var` trampolines into top-level `handle-request`/`event-sink` — so
`(require 'cljd.repl.nrepl :reload)` on the build's JVM nREPL swaps every op's code while the WS
connection, isolate, and socket stay live (measured: **93 ms**, state preserved, op behaviour swapped on
device — no relaunch). Only `build.clj` still relaunches (its watch loop / triggers own the flutter stdin),
and a `nrepl.clj` edit that adds a NEW cfg-shape key needs a relaunch to thread it through `+state+`.

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
  (resolve id → live atom → `reset!`). The **host** holds ONE timeline — the change-log — and epochs
  are named cut-points into it: `(epoch!)` drops a full-state keyframe + marks its index, `(restore-epoch! N)`
  seeks to that mark, `(states)` reads current state. No device-side store and no parallel snapshot
  store — the durable memory is the host, so the recording survives device restart, and ids re-resolve
  against whatever atoms are live. Validated: `(states)` returns the whole app state (nav index +
  journal entries + `#inst` dates as EDN); epoch → change → restore reverts; `(q epochs)` resolves each
  cut-point to its state map. Limits: only EDN-round-trippable values restore; a read taken *mid-rebuild*
  races (settle first); Riverpod providers still out of scope.
  **Transaction stream — SHIPPED:** `(record!)` add-watches every atom (re-armed on the heartbeat to
  catch newly-mounted repl-points); changes accumulate into a per-turn buffer and flush as ONE
  `cljd.tx` event on a microtask — all changes from one event handler / host eval = one transaction
  (re-frame/redux style, not per-atom). Host `+cljd-tx-log+` entries are `{:tx :t :delta :cause}`;
  `(seek! N)` merges deltas 0..N → `write-state`; `(txs)` counts them. A microtask (not a post-frame
  callback) always runs, so recording never depends on the render loop. `+cljd-replaying+` suppresses
  the echo while a host `write-state` applies (a restore must not re-record itself). Validated:
  two resets in one eval → one 2-entry tx; host write-state adds no tx; record → mutate → `(seek! 0)`
  reverts; restart replays a non-default target and re-arms recording. So the state channel is a
  Datomic-style transaction log — discrete epochs AND continuous record/seek are the SAME tx-log
  (epochs = markers), all host-recorded, id-addressed. Value-flow tracing (once framed as a
  "compile-with-coordinates" emit change) is SHIPPED as `(trace 'form)` v1 — a host-side form-rewrite
  that deliberately avoids emit surgery (see below), so no emit-level spike is needed.
- **#5 form-level edit-back — SHIPPED (2026-07-04).** `(edit-back! .prop VALUE)` writes VALUE back to
  source as the named property of the *active pick's* widget form, then the watcher recompiles + hot-
  reloads. It's a **consolidation** — the pick already resolves widget → `.cljd file:line:col`
  (`resolve-wloc`); edit-back extends that arrow one hop to write. Reuses the compiler reader for
  positions: `form-at line:col` finds the picked form, and `child-spans` reads spans from the reader's
  *own* position so **bare literals** (numbers/strings/keywords, not `IMeta`) are spanned too — then a
  value's span → char-offset → text splice. Validated on device: picked an InkWell → resolved to
  `nav.cljd:47:5` (a ListView) → `(edit-back! .padding (m/EdgeInsets.all 40.0))` rewrote its `.padding`
  in source → hot-reloaded in **1.08 s** → reverted byte-identical. Only unconstrained *gestural* edit-
  back (drag → infer arbitrary source change) stays research; named-property edit-back is deterministic.

Also shipped this line: **reload-legibility** (a watch-compile failure now `report-error!`s onto the
device — inline + amber handle + exact loc — instead of silently keeping old code); **`(dart-of 'form)`**
(the emitted Dart for a form, host-side); **tap-to-pick** (§8, below).
### Explored & ready to build (rank 3/5 — designed via the 2026-07-03 exploration)

- **Line-granular coverage — SHIPPED.** `(coverage)` snapshots which cljd forms executed;
  `(ran)` diffs since the snapshot → cljd locs newly run, via `getSourceReport(Coverage,reportLines)`
  **per-script** (whole-isolate crashes the on-device app — see memory `getsourcereport-per-script`),
  mapped through `resolve-wloc`. Zero instrumentation; the line-level layer *below* the coord primitive.

- **Value-flow tracing `(trace 'form)` — SHIPPED (v1, rank 1).** Validated: `(trace '(+ (* 2 3)
  (- 10 4)))` → `12`, `(timeline :trace)` shows coord `"1"`→6, `"2"`→6, `""`→12. A **host-side
  form-rewrite**, NOT emit surgery: a compile-time walk wraps each sub-expression in `(record! coord expr)` (returns
  the value, side-effects `postEvent "cljd.trace" {coord,value}`), emits the rewritten form, registers
  the form once. `emit` (`compiler.cljc:3716`) stays untouched — decisive de-risk. Coord = form-tree
  path (`"2,1"`), reusing FlowStorm's `{form,coord}` display. **Load-bearing correctness = the skip-list**
  (don't wrap binding-vec symbols, `quote`/`fn*` params, `.`-member symbols, `recur`/`set!` targets,
  type tags). **v1 (`90e6c3c`)** widens past fn-calls into the value positions of `let`/`let*`/`loop`/
  `loop*` (binding values + body), `if` (test + both branches), and `do` — validated on device:
  `(trace '(let [x 5 y (* x 2)] (+ x y)))` streams `let1`=10, `b0`=15, `""`=15. On-demand/opt-in first;
  measure overhead before any always-on emit pass. **The real fix for the def-level source-map wall**
  (CLJD-DX-AUDIT #5) at sub-expression granularity.

- **Composable introspection `(q FORM)` — SHIPPED (`90e6c3c`).** The stores (`errors` `timeline`
  `changes` `epochs` `coverage`) live host-side, so `(q FORM)` evaluates FORM there with them bound as
  plain values: `(q (frequencies (map :kind timeline)))`, `(q (filter #(= "flutter" (:phase %)) errors))`.
  Closes the "ops aren't values" gap — no more dump-and-grep.

- **Cross-restart state replay — SHIPPED.** `(restart!)` hot-restarts the app from the REPL; when
  recording, state auto-replays into the fresh app so you keep your place. The hard part was that a hot
  restart spins a NEW isolate — the host's cached isolate id went stale and *all* eval died. Fix: the
  isolate id lives in an atom, refreshed on a device `cljd.booted` Extension event (survives the
  restart; app stdout doesn't reach `flutter run` under `CLJD_VMREPL`). `replay-settle!` retries the
  change-log replay until every live id matches (startup pumps frames), then re-arms recording.
  `write-state` became phase-aware (synchronous when idle — reliable on an idle app — deferred only
  mid-frame). The change-log being host-durable (it always was, by design) is what makes this possible.

- **Clojure-native observability — SHIPPED (`tap>`/`log!`, rank 1).** `cljd.core` defines
  `add-tap`/`tap>` (`core.cljd:9370`). Device→host: `(add-tap #(post-event! "cljd.tap" {:edn (pr-str %)}))`
  + a `cljd.tap` host branch + `(taps)` — ~15 lines (cljd `tap>` runs inline, so keep the fn cheap).
  **Portal** (djblue/portal) in the host JVM (`add-tap #(portal/submit %)`) = near-free data browser,
  zero device change. **datafy/nav** = highest value/line (opaque Dart objects → maps). mulog: port the
  event-map + `with-context` idea, skip its runtime (the Extension stream is the transport).

- **One unified timeline — SHIPPED.** `cljd.tap`/`cljd.log`/`cljd.error`/`cljd.state-change` (and
  `cljd.trace`) fold into a single `+cljd-timeline+` keyed by `:t`, tagged `:kind`; `(timeline)` /
  `(timeline :kind …)`. Causal ordering across taps, logs, errors, state, and traces — one event log.

- **Cross-restart auto-replay ("hard rollback") — SHIPPED (see above).** Built 2026-07-03: the tx-log
  survives device restart (host-side); `(restart!)` + the device `cljd.booted` event trigger
  `replay-settle!`, which refreshes the isolate id, retries `write-state` until every live id matches,
  and re-arms recording — opt-in via `(record!)`. Still can't restore: nav-stack, native/platform,
  in-flight async, focus/scroll (not `[loc sym]` atoms).

- **Riverpod into the state channel — DEFERRED (low value / core cost).** A dev-mode `ProviderObserver`
  (kora is on Riverpod 2.6.1: `didUpdateProvider(provider, prev, new, container)`) could stream provider
  changes as transactions. But kora has only **3 named-able `StateProvider`s** (journal.cljd), and
  capturing them means adding external reader/writer registries to `read-state`/`write-state` — re-
  complicating the just-cleaned core state fns, or coupling them to Riverpod, for 3 providers. Deferred
  until provider usage grows or journal time-travel is specifically needed. Constraint if built:
  **addressability** — the providers must be `.name`d to get a stable id (`["riverpod" name]`).

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

- **ANSWERED (2026-07-03):** hot-reload (reassemble) preserves State — so `:managed` atoms survive,
  while `:let`/build-locals reset. A hot *restart* (R) resets everything (new isolate); that's what
  cross-restart replay handles. Nav-stack is not a `[loc sym]` atom, so it doesn't restore either way.
- **ANSWERED (2026-07-03):** the `:managed` atom *identity* is stable across a reassemble (measured:
  same atom object 425123511 before/after), and `write-state` re-walks live-state each call so it
  targets whatever atom the live widget currently watches — identity stability isn't even required.
- **MEASURED (2026-07-05, rank 1, Pixel 8 Pro @ dpr 2.25, 1008×2244):** `toImageSync` of the full
  `::app-snap` boundary costs **~0.24–0.44 ms/capture** (noisy; sub-ms, ~2–3% of a 60 Hz frame — time is
  NOT the blocker, the loupe already captures every frame). The binding constraint is **memory: 8.6 MB per
  full-res frame** (W·H·4), so a scrub ring is memory-bound — 1 s @ 15 fps ≈ 129 MB, 2 s @ 30 fps ≈ 518 MB.
  Surprising: **lowering `pixelRatio` does NOT cut capture time** (quarter-res measured 0.41–1.16 ms ≥
  full) — a non-native ratio forces a re-raster that swamps the pixel savings; it only cuts *memory*
  (quarter ≈ 0.54 MB/frame, so a 60-frame ring ≈ 32 MB) at a fidelity cost (252×561).
  **Verdict: DON'T build it — the pixel ring is largely redundant with the state tx-log.** We already
  record the *cause* of each frame (the `[loc sym]` transactions); `seek!`/`replay!` reset the atoms and
  the app RE-RENDERS the real widgets — scrubbable visual time-travel at *bytes/transaction*, queryable
  and editable, none of which a bitmap is. Recording 8.6 MB pixels/frame when the state that regenerates
  them is already logged is the waste. The ONLY thing pixels add is *un-modeled* visual state (mid-flight
  animation, native/platform, scroll/focus — the "can't restore" slice of §5), a narrow cosmetic niche not
  worth the memory. Revisit only if that specific niche becomes a felt need; the tx-log is the right
  time-travel primitive.
- **RESOLVED (2026-07-04):** for anything you can *pick*, the source-map is invertible enough — the
  pick resolves widget → `.cljd file:line:col`, and `form-at` + the reader's position tracking locate
  the exact form and every child's span (literals included). So named-property edit-back is deterministic
  (spiked end-to-end). Only *gestural* reverse-inference (drag → arbitrary source edit) stays research.
- Ancestor TREE tap-to-pick — SHIPPED: `capture-ancestors` retains each ancestor's live `:el`
  (guarded by mounted at tap time), TREE rows are tappable (`detail-section` wraps a row that carries an
  on-tap), and a tap `pick-element!`s that ancestor. Compiles clean; needs an on-device tap to confirm
  the interaction end-to-end (can't synthesize a tap from the REPL).
