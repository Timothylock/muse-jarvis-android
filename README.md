# Muse Desk (JARVIS)

A full-screen, landscape Android desk display that **is itself a Muse Gadget**. An OpenGL ES orb idles on the desk.
You hold it (push-to-talk) to talk to Muse over a realtime voice call, and Muse can put structured content beside
the orb by calling the display commands this gadget registers (`display.card`, `display.stats`, `display.list`,
`display.timer`, `display.clear`).

Target device: Samsung Galaxy Tab A7 Lite (SM-T220, MT8768, 3 GB) on an Android 15 GSI, landscape 1340×800.
The app runs on any arm64 Android 8+ device, but performance budgets are tuned for that tablet.

> Status: personal project, sideloaded. Simulation mode and everything up to the radio are verified on the
> tablet; the live paths that need a real Muse pairing (link, voice calls, display round trips) are implemented
> but have not yet been exercised against Muse.

## Highlights

- **GLES 3.0 orb**: concentric rings, ticks, a particle field and an analytic glow (no blur passes); a state
  machine (idle, connecting, listening, thinking, speaking, standby, offline, unpaired, pairing, error) driven by
  transport events, push-to-talk and audio levels. Quality tiers A–D with a frame-time and thermal governor.
- **HUD panels**: up to three native Compose panels docked beside the orb, with validation, rate limiting,
  lifetimes, pinning, in-place updates by `id`, and GL leader lines.
- **Push-to-talk**: hold or tap-latch the orb (or the bottom bar), multi-turn standby, barge-in, and a text
  fallback when realtime admission fails.
- **Upstream SDK, unmodified**: the Muse Gadget Python SDK runs in-process via Chaquopy (`:transport:sdk`).
  Pairing uses a Kotlin BLE GATT peripheral as a byte pipe into the SDK's own handshake code. Credentials are
  stored with an Android Keystore AES-GCM key, and logs are redacted.
- **Simulation mode**: a scripted `SimTransport` (18 scenarios, fault injection, a debug drawer, an ADB control
  surface) exercises every screen and failure without a pairing. Simulation is always visibly marked (violet
  frame, chips, orb ring, panel edges, `SIM ›` captions).

## Repository layout

| Module | What it holds |
|---|---|
| `:app` | Activity, foreground service, wiring, ADB receiver, call controller |
| `:core:model`, `:core:transport-api`, `:core:store` | Pure Kotlin: models, the `GadgetTransport` interface, MVI reducer, panel manager |
| `:core:secure` | Keystore-backed encrypted credential files, log redactor |
| `:transport:sdk` | Chaquopy bridge (`jarvis_bridge.py`) + the vendored SDK (see `transport/sdk/VENDORED.md`) |
| `:transport:sim` | Simulator, scenario engine, bundled scenarios (`src/main/assets/scenarios/*.json`) |
| `:audio` | Mic capture, playback, level analysis, earcons |
| `:render:orb` | GLES renderer, shaders, quality governor |
| `:ui:hud` | Compose HUD: status bar, panels, captions, screens, theme tokens, fonts |
| `:feature:pairing` | Pairing flow screens and the BLE GATT peripheral |

The UI never touches the SDK: everything goes through `GadgetTransport`, and `:app` picks the real or simulated
transport at runtime. The two are never instantiated together.

## Building

Toolchain: JDK 17, Gradle 8.9 (system `gradle`, no wrapper), AGP 8.7.3, Kotlin 2.0.21, Android SDK 35, and a
host **Python 3.11** (Chaquopy needs the same minor version on the build host as on the device).

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleDebug          # app/build/outputs/apk/debug/app-debug.apk (arm64-v8a only, ~33 MB)
gradle testDebugUnitTest      # JVM unit tests
```

`dev/nas.sh` wraps the remote Docker build host used during development (sync, build, install, adb, screenshot).

## Running

First launch offers **Pair with Muse**, **Simulation mode**, or **Import pairing**.

- **Pairing** needs a gadget token (`mgst_…`, created at gadgets.muse.ai), Bluetooth, and the Muse app on a
  phone nearby. The tablet advertises as the SDK's setup window name for 10 minutes.
- **Import pairing** follows the SDK's proxy-pairing flow: pair the exported identity on any Linux box with BLE,
  then push `identity.json`, `pairing.json` and `sdk_token` to the app's `files/import/` directory. The app
  validates them, encrypts them, and deletes the cleartext copies.
- **Simulation** needs nothing. Open the drawer from the violet `SIMULATION` chip or with a three-finger
  long-press.

## ADB control surface (development)

A broadcast receiver protected by `android.permission.DUMP` (held by the ADB shell only):

```sh
adb shell am broadcast -a dev.timlock.jarvis.SIM --es cmd <cmd> [args]
```

Each command logs one JSON line to logcat tag `JARVIS_SIM`. Most commands only work in simulation mode:
`scenario --es id <id> [--ef timescale F] [--el auto_ptt_ms N]`, `state --es value <orb state|auto>`,
`ptt --es value down|up|tap`, `display --es name display.card --es json_b64 <base64 params>`,
`fault --es kind offline|revoke|send_fail|admit_timeout|session_dead`, `preset`, `stop`, `pause`, `reload`,
`list`, `hangup`, `pairflow`. Read-only `diag` and `perfhud` work in both modes.
Real-mode pairing helpers: `export_identity`, `import_pairing`, `provision_token`, `unpair`, `pair_selftest`
(a headless loopback of the SDK setup protocol), and `ble_smoke`.

Test hooks (both modes; real mode also needs the developer flag and an online link):
`dev_setting --es key test_input --ez on true|false` (default off; `--es key test_thread_id --es value <id>` sets an
optional test thread), `inject_wav --es name <wav> [--es thread_id <id>] [--ez hangup false] [--el timeout_ms N]`
feeds `files/test_wav/<wav>.wav` (mono 16-bit PCM, any rate, resampled to 24 kHz) as the mic input of one
push-to-talk turn and logs the orb path and call stats; the HUD shows an amber `TEST INPUT` chip meanwhile.
`make_wav --es name <wav> --es text "<words>"` synthesizes a WAV with on-device TTS, prefixed with the spoken
words "Test input." (the reference tablet's system image has no TTS engine; push WAVs with
`adb push x.wav /sdcard/Android/data/dev.timlock.jarvis/files/test_wav/` instead). `list_wav` lists them.
`diag` includes `testInput`, `callStats` (the open call) and `lastCall` (admission and first-audio latency,
frames sent and received, end reason).

Launch extras: `am start -n dev.timlock.jarvis/.MainActivity --es jarvis.mode sim|real [--es jarvis.scenario <id>]`.
`dev/sim.sh` and `dev/scenarios.sh` (run every scenario unattended and summarize its assertions) build on this.

## Privacy and secrets

No tokens, pairing files, identities or keystores are ever committed (see `.gitignore`). On the device they live
only in app-private, Keystore-encrypted files with backup disabled. Log lines go through a redactor and never
include server message text.

## Licenses

- Fonts: Rajdhani and IBM Plex, SIL Open Font License (`third_party/fonts/`).
- Muse Gadget SDK: Apache-2.0, vendored unmodified (`transport/sdk/src/main/python/musegadget/LICENSE-upstream`).
