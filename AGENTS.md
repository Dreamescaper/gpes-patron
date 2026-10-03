# AGENTS.md — working on gpes-patron

An experimental Android location provider for driving under persistent GNSS jamming or spoofing.
It records complete drives, evaluates GNSS trust, estimates position from all available evidence,
publishes the result as Android mock location, and replays recorded drives offline with injected
GNSS faults so algorithms can be compared.

Primary research question: *how accurately can a phone keep useful vehicle localization during
prolonged GNSS denial or spoofing, and how much does each added constraint (network location,
road graph, OBD speed, planned route) improve it?*

## Read first

| Doc | What it holds |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Modules, data flow, threading, feedback-loop guards |
| [docs/estimation-algorithm.md](docs/estimation-algorithm.md) | Trust evaluator, motion tracker and baseline EKF, as implemented |
| [docs/decisions.md](docs/decisions.md) | Decision log: accepted and rejected options, with reasons |
| [docs/roadmap.md](docs/roadmap.md) | Planned future work (Phase 2+), open questions |
| [docs/road-constraint.md](docs/road-constraint.md) | Phase 2 design plan: road matcher + road pseudo-measurements |
| [docs/progress.md](docs/progress.md) | Completed work, verified status, known limitations, results log |
| [docs/data-model.md](docs/data-model.md) | Measurement and output models, units, timebase |
| [docs/recording-format.md](docs/recording-format.md) | SQLite drive bundle, JSONL/CSV/GnssLogger exports |
| [docs/replay-and-scenarios.md](docs/replay-and-scenarios.md) | Replay CLI, scenario format, variants, metrics |
| [docs/research.md](docs/research.md) | Existing projects evaluated, and what was reused or rejected |
| [docs/visualization/index.html](docs/visualization/index.html) | Explanatory **video** (Ukrainian, for non-mathematicians): open in a browser. A teaching JS model, not the real code (D-060). Changed a subtitle? `EDGE_TTS=<path to edge-tts> node docs/visualization/make-audio.js` (D-068) |
| [docs/visualization/formal.html](docs/visualization/formal.html) | **Formal** math video (formulas, spectra, filters, EKF…): KaTeX + Plotly from CDN (D-067). Voice: `node docs/visualization/make-audio.js formal` |
| [docs/dev-guide.md](docs/dev-guide.md) | **Recipes** (add a measurement, trust check, EKF state, variant, sim feature, UI text), test and emulator workflow, **pitfalls already hit** |

## Documentation rules (keep docs in sync during implementation)

These are part of "done". A change is not finished until the docs reflect it.

1. **Decisions** → append to `docs/decisions.md` whenever you choose between real alternatives
   (algorithm, library, format, threshold philosophy, API), *including rejected options*. Use the
   template there. Never delete entries; mark superseded ones as `Superseded by D-NNN`.
2. **Algorithm changes** (trust checks, estimator math, motion tracking, config defaults that change
   behaviour) → update `docs/estimation-algorithm.md` in the same change.
3. **Completed work** → add a dated entry to `docs/progress.md` saying what was built, how it was
   verified (tests, replay, emulator, device), and what remains uncertain. Keep "Known limitations"
   current.
4. **Measured results** → when a change affects accuracy, rerun the replay matrix (below) and
   paste the key numbers into the results log in `docs/progress.md`, with the date and git commit.
   Don't claim an improvement without before/after numbers.
5. **Future work** → new ideas, deferred items and open questions go to `docs/roadmap.md`. Move
   them to `progress.md` when done.
6. **Formats** → any schema, model or scenario change updates `docs/data-model.md`,
   `docs/recording-format.md` or `docs/replay-and-scenarios.md`. Bump `DRIVE_SCHEMA_VERSION` for
   incompatible SQLite changes.
7. Use absolute dates (YYYY-MM-DD). Be honest about what was and was not verified on a real device.
   **Current state: four real drives recorded on a Pixel 8 in RECORD_ONLY and analysed offline (R-007,
   R-008, R-014; 2026-09-28/29). Live estimation, mock output and the app's road map have not run in a
   car yet.**

8. Before committing, run the tests and check the **exit code** (don't pipe Gradle through
   `grep`/`head` in a way that hides failures).

9. **Pitfalls** you hit that a future session could repeat → add them to `docs/dev-guide.md`.

10. **UI text** goes in `app/src/main/res/values/strings.xml` *and* `values-uk/strings.xml`
   (with Ukrainian plural forms for counts). Never hardcode user-visible strings in Kotlin. Never
   localize values that are written to recordings.

11. **Real-drive cases become tests.** Recordings are not in the repository (`recordings/` is local and
   gitignored), so a fix or improvement found on a real drive is lost to CI unless you capture it. For
   every such case add a unit test that reproduces the situation in miniature: synthetic measurements or
   a small hand-built road network with the same geometry and numbers as the real case (for example "a
   network fix 107 m to the side of a straight road, plausible for its hAcc 88 m", "a parallel side
   carriageway 20 m away", "a 20° bend taken 25 m earlier than the matched point"). Name the real case
   in a comment (drive id, time, D-/R- number), and check that the test fails without the change. Never
   copy real coordinates or traces into tests; use local offsets from an arbitrary origin.

## Invariants — do not break

- **GNSS is untrusted evidence.** Never treat it as ground truth inside the estimator.
- **No feedback loop.** Our mock output must never re-enter as a measurement. Inputs with `isMock`
  or the `gpes.synthetic` extra are always REJECTED. The publisher is an output sink only.
- **Deterministic core.** `:core` never reads the wall clock and has no Android dependencies. The
  same input stream must produce byte-identical output (a test enforces this). Replay must go
  through the same `MeasurementPipeline` class as the app.
- **Honest uncertainty.** A coarse fix stays coarse. Do not shrink covariance without evidence.
  Replay reports calibration (`within68` / `within95`); keep them near 0.68 / 0.95.
- **OBD is optional (D-036).** The app must work without it; with OBD present, the accelerometer is only
  auxiliary information.
- **No open-loop accelerometer integration.** Never integrate the accelerometer on its own into speed or
  distance (R-014: 32–44 km/h speed error within 1–5 min). Accelerometer-derived speed may enter only
  inside the estimator, with its error bounded by other evidence: ZUPT at stops, centripetal speed
  (a_lat/ω) in turns, coarse fixes and the odometry chord, and GNSS/OBD when present (D-036).
- **Timebase:** all `tNs` are `elapsedRealtimeNanos`. Keep original timestamps; never resample
  recorded data.
- **Canonical ordering:** sort records with `RecordOrder.comparator`, not by `tNs` alone.

## Layout

```
core/        pure Kotlin/JVM: models, geo, motion, trust, estimator, road (OSM tiles, matcher), pipeline, sim, replay, future/ interfaces
recording/   SQLDelight schema (Drive.sq), DriveWriter/Reader, JSONL/CSV/GnssLogger exporters
replay-cli/  `replay` CLI: simulate | export | run | matrix | compass-report | roads
app/         Android: sources (location, raw GNSS, sensors, cell, Wi-Fi, power, OBD/ELM327), DriveService (foreground), MockLocationPublisher, Compose UI
scenarios/   standard fault-injection scenarios (JSON)
tools/plot/  plot_replay.py (matplotlib) for replay run directories
.github/     CI workflow (tests, lint, APK artifact, releases on v* tags)
docs/        see table above
```

## Commands

```bash
./gradlew :core:test :recording:test          # unit tests (JVM, fast)
./gradlew :app:assembleDebug :app:lintDebug   # Android build + lint
./gradlew :replay-cli:installDist             # CLI → replay-cli/build/install/replay/bin/replay

# Synthetic end-to-end without a device
R=replay-cli/build/install/replay/bin/replay
$R simulate --out /tmp/sim/drive.db --network-period 20
$R matrix --drive /tmp/sim/drive.db --truth /tmp/sim/drive.truth.json --scenario scenarios --out /tmp/sim/out
cat /tmp/sim/out/comparison.md
$R compass-report --drive /tmp/sim/drive.db --truth /tmp/sim/drive.truth.json --out /tmp/sim/compass

# Device / emulator
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set gpes.patron android:mock_location allow
adb pull /storage/emulated/0/Android/data/gpes.patron/files/drives/<id>.db
adb pull /storage/emulated/0/Android/data/gpes.patron/files/roads tiles/   # road tiles, for replay --roads
```

CI: `.github/workflows/android.yml` runs tests, lint, the debug APK and the replay CLI on every
push/PR. The APK is an artifact; tags `v*` publish a GitHub Release with the APK. The secret
`DEBUG_KEYSTORE_B64` is decoded and passed as `GPES_DEBUG_KEYSTORE` (read by `app/build.gradle.kts`),
so CI signs with the same debug key as local builds (`GPES_DEBUG_KEYSTORE=~/.android/debug.keystore`).
The version is computed from git (`version.json` + commit count, D-059): never edit it by hand. `./gradlew -q :app:printVersion` prints it.

Toolchain: JDK 21 (bytecode target 17), AGP 9.4 (built-in Kotlin), Kotlin 2.4, Gradle 9.8,
compileSdk 37, targetSdk 36, minSdk 29. Emulator GNSS is physically inconsistent with its static
IMU, so expect GNSS to be QUESTIONABLE there. Use the "fuse QUESTIONABLE GNSS" toggle for plumbing
tests only.
