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
| [docs/progress.md](docs/progress.md) | Completed work, verified status, known limitations, results log |
| [docs/data-model.md](docs/data-model.md) | Measurement and output models, units, timebase |
| [docs/recording-format.md](docs/recording-format.md) | SQLite drive bundle, JSONL/CSV/GnssLogger exports |
| [docs/replay-and-scenarios.md](docs/replay-and-scenarios.md) | Replay CLI, scenario format, variants, metrics |
| [docs/research.md](docs/research.md) | Existing projects evaluated, and what was reused or rejected |

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

8. Before committing, run the tests and check the **exit code** (don't pipe Gradle through
   `grep`/`head` in a way that hides failures).

9. **UI text** goes in `app/src/main/res/values/strings.xml` *and* `values-uk/strings.xml`
   (with Ukrainian plural forms for counts). Never hardcode user-visible strings in Kotlin. Never
   localize values that are written to recordings.

## Invariants — do not break

- **GNSS is untrusted evidence.** Never treat it as ground truth inside the estimator.
- **No feedback loop.** Our mock output must never re-enter as a measurement. Inputs with `isMock`
  or the `gpes.synthetic` extra are always REJECTED. The publisher is an output sink only.
- **Deterministic core.** `:core` never reads the wall clock and has no Android dependencies. The
  same input stream must produce byte-identical output (a test enforces this). Replay must go
  through the same `MeasurementPipeline` class as the app.
- **Honest uncertainty.** A coarse fix stays coarse. Do not shrink covariance without evidence.
  Replay reports calibration (`within68` / `within95`); keep them near 0.68 / 0.95.
- **No accelerometer double-integration** for distance. Speed comes from GNSS, OBD or ZUPT only.
- **Timebase:** all `tNs` are `elapsedRealtimeNanos`. Keep original timestamps; never resample
  recorded data.
- **Canonical ordering:** sort records with `RecordOrder.comparator`, not by `tNs` alone.

## Layout

```
core/        pure Kotlin/JVM: models, geo, motion, trust, estimator, pipeline, sim, replay, future/ interfaces
recording/   SQLDelight schema (Drive.sq), DriveWriter/Reader, JSONL/CSV/GnssLogger exporters
replay-cli/  `replay` CLI: simulate | export | run | matrix
app/         Android: sources (location, raw GNSS, sensors, cell, Wi-Fi), DriveService (foreground), MockLocationPublisher, Compose UI
scenarios/   standard fault-injection scenarios (JSON)
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

# Device / emulator
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set gpes.patron android:mock_location allow
adb pull /storage/emulated/0/Android/data/gpes.patron/files/drives/<id>.db
```

Toolchain: JDK 21 (bytecode target 17), AGP 9.4 (built-in Kotlin), Kotlin 2.4, Gradle 9.8,
compileSdk 37, targetSdk 36, minSdk 29. Emulator GNSS is physically inconsistent with its static
IMU, so expect GNSS to be QUESTIONABLE there. Use the "fuse QUESTIONABLE GNSS" toggle for plumbing
tests only.
