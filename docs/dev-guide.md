# Developer guide: recipes, test workflow, pitfalls

Practical knowledge for the next session. This covers *how* to change things without breaking
invariants; see [AGENTS.md](../AGENTS.md) for the rules and the rest of `docs/` for the *what*.

## Where things live (quick map)

| Concern | File |
|---|---|
| All record/measurement models, canonical order | `core/.../model/Records.kt` (inputs), `model/Outputs.kt` (trust, estimate) |
| Trust checks | `core/.../trust/TrustEvaluator.kt` (`TrustConfig` has every threshold) |
| EKF | `core/.../estimator/BaselineDrEstimator.kt` (`BaselineConfig`; state indices in its companion) |
| Compass (iron fit, alignment, gates, verdict) | `core/.../estimator/Compass.kt` (`CompassConfig`) |
| Motion (yaw rate, stationary, forward axis, re-mount, shake metric) | `core/.../motion/MotionTracker.kt` |
| Pipeline (reorder, ticks, snapshots, rollback) | `core/.../pipeline/MeasurementPipeline.kt` |
| Simulator | `core/.../sim/DriveSimulator.kt` (`SimConfig`: every knob) |
| Scenarios / variants / metrics / compass report | `core/.../replay/{Scenario,ReplayRunner,Metrics,CompassReport}.kt` |
| ELM327 protocol (JVM, testable) | `core/.../obd/Elm327.kt` |
| SQLite schema | `recording/src/main/sqldelight/gpes/recording/db/Drive.sq` |
| Android sources | `app/.../source/*Source.kt` |
| Service wiring (sources → sink → pipeline → recorder/UI/mock) | `app/.../service/DriveService.kt` |
| UI + strings | `app/.../ui/MainActivity.kt`, `res/values{,-uk}/strings.xml` |

## Recipes

### Add a new recorded measurement type
1. Add an `@Serializable @SerialName("snake_name") data class X(override val tNs: Long, …) : Measurement`
   in `Records.kt`. Use `DriveRecord` instead if it is not a pipeline input (like `ObdExchange`).
   **No property may be named `type`**: that is the JSONL discriminator (see pitfall P3).
2. Give it a **unique rank** in `RecordOrder` (never share a rank between kinds).
3. Schema: add a table with `CREATE TABLE IF NOT EXISTS …`, plus `insertX` and `selectX ORDER BY t_ns, rowid`,
   in `Drive.sq`. Additive tables need no schema bump.
4. `DriveWriter.insert`: a branch (the compiler forces it, because `when` over the sealed type is
   exhaustive).
5. `DriveReader`: `fun x() = tolerant { … }` (old bundles lack the table), and add it to `measurements()`
   or `allRecords()`.
6. `Exporters.csv`: add a `table(...)` (optional but expected).
7. `RoundTripTest`: add an instance to the `extra` list; `PipelineTest.jsonl round trip`: add one too.
8. Android: produce it in a `MeasurementSource` and add that source to `Session.sources` in
   `DriveService`.
9. Docs: `data-model.md`, `recording-format.md`, and progress.

### Add a trust check
- Write a private `checkX(m, hits)` in `DefaultTrustEvaluator`, call it in the GNSS/FUSED branch, and
  put the thresholds in `TrustConfig`. Add a `TrustReason` (Outputs.kt).
- Any new evaluator state goes into `Snap` (snapshot/restore), or rollback breaks.
- If the reason should allow reset-after-consistent-stream, add it to `resettable`. Never add
  reasons that indicate physical impossibility.
- Measure false rejections on `clean` and detection on the spoof scenarios (`TrustTest`, matrix).
- Update the check table in `estimation-algorithm.md` §2.

### Add EKF evidence or state
- New state: bump `N`, add an `IDX_*`, extend the initial `p` diagonal and Q, and check
  `snapshot()` (arrays are copied generically).
- Scalar measurement with a general row: `updateH(h, innov, r)`. A "touch only this state" update:
  `updateLocal(idx, z, r)` (Schmidt-style, D-028). Angular: `update1(idx, z, r, angular = true)`.
- Beware **correlated repeated measurements** (compass, coarse fixes, ZUPT): they must not average
  down. See D-020, D-021 and D-028 for the patterns used.

### Add a replay variant or scenario
- Variant: `Variant.standard()` in `ReplayRunner.kt` (name, estimator, extra steps, configs).
  Keep the ladder names stable; results in `progress.md` refer to them.
- Scenario step: `ScenarioStep` subclass in `Scenario.kt`, handled in `ScenarioApplier.apply`
  (transform) or `generate` (synthetic from truth). Add a JSON file under `scenarios/` if it is
  standard.

### Add a simulator feature
- Put the knob in `SimConfig` with a default that **reproduces the old behaviour exactly**, and
  **draw no random numbers when the feature is off** (pitfall P1).

### Add UI text
- `values/strings.xml` and `values-uk/strings.xml` (with Ukrainian plural forms via `<plurals>`).
  In composables use `stringResource`/`pluralStringResource`, never `LocalContext.current.getString`
  (lint error). Recorded values stay locale-independent (D-023).

## Test and measurement workflow

- Fast loop: `./gradlew :core:test :recording:test` (42 tests, about 1–2 min). Then check the
  **exit code** before committing.
- **Throwaway debug harness** (used a lot, and effective): create
  `core/src/test/kotlin/gpes/core/dbg/D.kt` with a `@Test` that prints `DBG …` lines, then run
  `./gradlew :core:test --tests 'gpes.core.dbg.*' -q -i | grep DBG`. Delete the `dbg` directory
  before committing.
- Standard matrix (10-min sim):
  `replay simulate --out $S/drive.db --network-period 20` then
  `replay matrix --drive $S/drive.db --truth $S/drive.truth.json --scenario scenarios --out $S/out`.
- 1-hour realistic sim (gyro scale error, bias walk): make a SimConfig JSON with the default loop
  ×6, `"gyroScaleError":0.01,"gyroBiasWalk":0.0002,"networkPeriodS":20.0`, then
  `replay simulate --config`. This is what R-004/R-006 used. Use it for anything about long outages
  or the compass. The 10-min drive clips the 10-min and 1-h scenarios, and its ideal gyro hides
  heading drift.
- Compass assessment: `replay compass-report --drive x.db [--truth t.json] --out dir`.
- Plots: `python3 tools/plot/plot_replay.py <run dir>` (needs matplotlib; use a venv).
- Sanity check any accuracy claim on ≥ 3 seeds (`SimConfig(seed = …)`): single-seed thresholds
  have already been flaky.

## Emulator recipes (no Bluetooth, static IMU and magnetometer)

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk        # -g grants runtime permissions
adb shell appops set gpes.patron android:mock_location allow
adb shell cmd locale set-app-locales gpes.patron --locales uk       # "" to reset
adb emu geo fix <lon> <lat> 150 9 <knots>                           # feed GNSS (lon first!)
# Tap by visible text (Compose has no resource ids):
adb exec-out uiautomator dump /dev/tty | python3 -c '…find text="Start" bounds…'   # then: adb shell input tap X Y
adb shell dumpsys location | grep 'last location'                   # verify mock output (look for "mock")
adb pull /storage/emulated/0/Android/data/gpes.patron/files/drives/<id>.db   # pick *.db, not *.db-wal
```

- Emulator GNSS vs static IMU → GNSS is QUESTIONABLE (correct). Use the "fuse QUESTIONABLE" toggle
  only for plumbing.
- The emulator fires GnssStatus about 200 Hz (it is throttled to ≤ 5 Hz in `GnssRawSource`).
- Stop the session before pulling a `.db` (WAL checkpoint on close).

## Pitfalls already hit (don't repeat)

- **P1 RNG draws shift the whole simulation.** A new sim option that calls `rnd.gauss()` even when
  disabled changes every later random number, and seed-sensitive tests flip. Guard the draws
  with `if (enabled)`.
- **P2 Piped Gradle hides failures.** `./gradlew … | grep` exits 0. Redirect to a log and check
  `$?`.
- **P3 `type` property vs JSONL discriminator.** `SensorInfo.type` crashed JSONL export on real
  drives (the sim never produced it). Use `@SerialName("sensorType")`. Round-trip tests include
  every kind.
- **P4 Equal RecordOrder ranks** make SQLite-vs-live ordering differ. Every kind needs a unique
  rank.
- **P5 Correlated evidence overconfidence:** compass (D-020), coarse fixes (D-021), ZUPT lever
  (D-028). If `within95` drops, look for repeated or correlated updates.
- **P6 "The idea sounds right" is not evidence.** The deviation-card compass (70° p95), the naive
  shake gate (worse with GRV), and hard-iron vs radius gates were all decided by measuring. Use the
  debug harness before committing to a design.
- **P7 Docs edit scripts:** validate *all* patterns first, then write (a partial apply was
  committed once).
- **P8 AGP 9** uses built-in Kotlin (no `kotlin-android` plugin). The Compose BOM needs compileSdk 37.
  CI needs explicit SDK packages (setup-android's default `tools` package no longer exists).
- **P9 Debug signing:** `GPES_DEBUG_KEYSTORE` controls it. AGP ignored a restored
  `~/.android/debug.keystore` on CI.

## User context

- The user writes in Ukrainian. Replies are in Ukrainian; code, docs and commits are in English.
- The user's OBD adapter is a cheap "Mini Bluetooth ELM327 v1.5/v2.1" clone (Bluetooth Classic).
- No real drive has been recorded yet (as of 2026-09-28). Next step: real drives (RECORD_ONLY, with
  network + OBD), then tuning with `compass-report` and the matrix.
