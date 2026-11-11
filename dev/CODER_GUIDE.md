# Coder guide (read fully before writing code)

You are implementing part of "Muse Desk" (internal name JARVIS), a landscape Android desk-display app
that is a Muse Gadget. The spec of record is `~/workspace/jarvis-app-design/DESIGN.md`; section numbers
below (§x.y) refer to it. Read the sections named in your task before coding.

## Hard rules
- Repo is PUBLIC. Never add secrets, tokens, keystores, pairing/identity files, personal data.
- Never `git commit` or `git push` — the lead engineer commits. Never touch `.git`.
- Gradle CANNOT run on this VM. Build only via `dev/nas.sh` (it syncs to the NAS and runs Docker there):
  - `dev/nas.sh build` → assembleDebug. Prints compiler errors; full log in `dev/out/build.log`.
  - `dev/nas.sh test` → unit tests (JVM). `dev/nas.sh gradle <tasks>` for anything else.
  - Builds take minutes; batch your edits, then build. Do not run two builds concurrently.
  - Install/screenshot only if your task says so: `dev/nas.sh install`, `dev/nas.sh shot <png>`.
- Your task is done only when `dev/nas.sh build` ends with `BUILD SUCCESSFUL` (and `dev/nas.sh test`
  if you wrote tests). Report the final result honestly, including anything you skipped.
- Shell is zsh: unmatched globs abort commands. /tmp is small; write scratch files under `dev/out/`.

## Toolchain (pinned — do not change versions without being asked)
- Gradle 8.9 (system `gradle`, no wrapper), AGP 8.7.3, Kotlin 2.0.21, JDK 17 target.
- Groovy DSL (`build.gradle`), versions live in the root `build.gradle` `ext { }` block.
- Compose via BOM `2024.12.01` + `org.jetbrains.kotlin.plugin.compose` 2.0.21. Material3 only for
  basic primitives; our look is custom (no Material theming visible to the user).
- Coroutines 1.9.0; kotlinx.serialization-json 1.7.3 (pure-Kotlin modules parse JSON with it).
- compileSdk 35, targetSdk 35, minSdk 26, `abiFilters "arm64-v8a"` (app). applicationId `dev.timlock.jarvis`.
- Allowed dependencies without asking: androidx core-ktx, activity-compose, lifecycle, datastore-preferences,
  compose ui/foundation/material3, kotlinx coroutines/serialization, junit4 + kotlinx-coroutines-test.
  Anything else: ask in your report instead of adding it.

## Module map (adapted from §6.1)
```
:app                  MainActivity, TransportFactory, mode persistence (DataStore), receivers, FGS
:core:model           pure Kotlin/JVM: OrbState, Mode, LinkState, TransportKind, display/panel models
:core:transport-api   pure Kotlin/JVM: GadgetTransport, CallHandle, TransportEvent, PcmSource/PcmSink
:core:store           pure Kotlin/JVM: AppStore (MVI reducer, StateFlow), panel manager, orb derivation
:transport:sim        Android lib: SimTransport, scenario engine, fault injector
:ui:hud               Android lib + Compose: theme tokens, fonts, status bar, panels, screens, SimStyling
:render:orb           Android lib: GLES 3.0 orb renderer (from M2)
:audio                Android lib (from M5): MicSource, Player, envelope/bands
:transport:sdk        Android lib + Chaquopy (from M6) — ONLY module that knows Python exists
:core:secure          Android lib (from M6): Keystore-backed encrypted file store
:feature:pairing      Android lib (from M7): pairing screens + BLE GATT peripheral
```
Dependency rule: `:ui:*` and `:render:*` depend on `:core:*` only. `:app` is the only module that sees
both transports. Pure-Kotlin modules use `id 'org.jetbrains.kotlin.jvm'` and have no Android imports.
Package root: `dev.timlock.jarvis.<module>` (e.g. `dev.timlock.jarvis.core.model`, `dev.timlock.jarvis.ui.hud`).

## Design tokens
All colors from §2.1 live in ONE Kotlin object `JarvisColors` in `:ui:hud` (Compose `Color`) and the
renderer mirrors them as float constants. `sim.violet` (#B48CFF) is referenced ONLY from `SimStyling`
(ui:hud) and the orb renderer's sim ring (§7.6 rule 8). Fonts are in `ui/hud/src/main/res/font/`:
rajdhani_{light,medium,semibold}, plex_sans_{light,regular}, plex_mono_{light,regular,medium}; type
roles per §2.2 live in `JarvisType`. Sizes in the spec are px at 1340×800: convert with a single helper
`Px.dp(px)` based on `px / density` measured at runtime (do NOT hardcode a density).

## Code conventions
- Kotlin, idiomatic, small files. No reflection-heavy libs, no DI framework (manual wiring in :app).
- State: unidirectional. UI reads `StateFlow<AppState>` and sends intents; never mutates state directly.
- No allocation in per-frame loops (renderer). No blocking on the main thread.
- Logging: `android.util.Log` with tags `JARVIS_STORE`, `JARVIS_SIM`, `JARVIS_PERF`, `JARVIS_UI`.
  Never log tokens or server message text.
- Unit tests for pure logic go in the pure-Kotlin modules' `src/test/kotlin` (JUnit4).
- Keep the app buildable. If you can't finish something, leave a compiling stub plus a `TODO(Mx):` note
  and say so in your report.
