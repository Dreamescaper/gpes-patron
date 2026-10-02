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
- **P10 Chip DR looks like GNSS.** On the Pixel 8, `gps` fixes keep coming after the car enters a
  car park, with hAcc 3–10 m and plausible speed, and no satellites at all. Check NMEA `$GPGGA`
  field 6 (quality 6 = dead reckoning) and raw C/N0 before trusting real-drive "truth" near tunnels
  and car parks.
- **P11 Recorded OBD was in every replay rung** (fixed 2026-09-28, D-033: `drop_vehicle_speed`).
  Real-drive numbers before that date for gyro-only / phone-only / phone+network include OBD, and the
  `+synthObd` rungs had two speed sources.
- **P15 Without GNSS, speed and speedometer scale are one unknown.** The EKF sees only v·(1+s). Two
  speed sources that disagree by a few percent (or one with a latency the model ignores) let v drift:
  1.9× truth on R-007. It was invisible while the heading was unknown (position did not follow v) and
  exploded once the heading bank made the EKF dead-reckon. When a change makes a state *used*, re-check
  it: print est vs truth speed (`ticks.csv` has both now).
- **P16 Check the test premise, not only the code.** "No heading without speed" was wrong: fixes line up
  along the direction of travel. The failing test was right to fail, but the assertion was wrong.
- **P13 Under jamming, C/N0 lies.** Short false locks report 40–50 dB-Hz. Check the tracking state
  (`state & 8` = TOW decoded) and AGC before reading anything into C/N0. On the Pixel 8 the chip's
  `$PGLOR,3,AGC` NMEA sentence carries AGC even when Android measurements stop.
- **P14 (fixed by D-037) Android GRAVITY, LINEAR_ACCEL and the rotation vectors swallow sustained lateral
  acceleration.** In turns the fused "up" leans towards the apparent gravity, so the centripetal part
  mostly disappears from the horizontal projection (R-007, correlation of a_lat with v·ω: GRAVITY 0.36,
  GAME_RV 0.44, ROTATION_VECTOR 0.45, slope ≈ 0.12–0.18). With "up" = mean of raw ACCEL over ±60 s
  (the mount is fixed) it is 0.93. Fine for projecting the yaw rate; **wrong for anything that measures
  horizontal acceleration** — including `MotionTracker.learnMount` (see roadmap). The simulator's
  rotation vector is ideal, so sim tests cannot catch this.
- **P17 The EKF skips most coarse fixes once it is confident** (≥ 15 s and ≥ 150 m apart, D-021). A test
  with network fixes every 13 s silently dropped every other fix, including the injected outlier. Space
  test fixes ≥ 16 s apart, or check that the fix was actually fused.
- **P18 OSM can lag reality.** A ramp that the driver used was still mapped as construction (R-015b),
  so the matcher detoured. `osm_match.py` only uses built roads; cut the truth or add constraints where
  the driver knows OSM is wrong.
- **P19 A state that the estimator ignores can lock itself out.** QUESTIONABLE GNSS is not fused, so the
  prediction never converges to it, so it stays QUESTIONABLE (D-038). When a check's output feeds back
  into what it compares against, give it a way out (a consistent-stream rule), and test a return after
  an outage.
- **P20 Do not trust a sensor's absolute scale.** The Pixel 8 accelerometer reads ‖a‖ 9.0–10.9 m/s² when
  quiet; gate on the phone's own long-term mean, not on 9.80665 (D-037).
- **P21 A heading learned from GNSS is GNSS evidence.** Using the EKF heading to check network fixes
  let a Doppler-consistent spoofer get honest network fixes rejected, even a minute after GNSS itself
  was rejected (the heading stays wrong). Checks on other sources must not depend on state GNSS could
  have steered recently (D-039: 180 s without trusted GNSS).
- **P23 A matcher must not see the pose it corrects.** Matching roads on the road-constrained EKF pose
  confirmed whatever road it had snapped to (R-007: p50 56 → 115 m). Match on an estimator that never
  uses the road (D-042).
- **P24 Repeated pseudo-measurements shrink the covariance for free.** Road updates every 40 m are one
  piece of evidence; without a floor (prior > R) and the road-free radius, within95 fell to 0.18–0.76.
  Cross-track updates on streets of different orientation also shrink the *along-track* variance.
- **P26 Street names do not identify a carriageway.** Kyiv side carriageways (дублери) carry the main
  road's name; a name-based "right road" metric hid minutes on the wrong one (R-020b). Compare geometry.
- **P27 A truth built from the same sensor shares its errors.** The OSM truths placed the car by OBD
  distance, so they shared the adapter's 0.8 s lag with the EKF and hid it (R-021). Check a truth
  against independent evidence (gyro turns, network fixes) before tuning against it.
- **P28 The public Overpass server is often overloaded (HTTP 504).** Download nearest tiles first, retry
  later, and publish the network after every tile; never wait for the whole batch. `adb install -r`
  restarts the app and ends a running session.
- **P29 Raw IMU samples carry the car's vibration.** 0.5–0.9 m/s² per axis and 0.06–0.08 rad/s in single
  samples: any gate or learner fed with one raw sample (forward axis, up gate) silently stops working on a real
  car. Use smoothed signals (D-050).
- **P30 The simulator is quieter than a car.** Thresholds tuned on it (stop detection) fail on real drives,
  and real-car thresholds see a simulated moving car as stopped. Guard real-car rules with physics (a car
  cannot stop without braking) until the simulator has realistic vibration (roadmap).
- **P31 The scratch directory is not durable.** It was wiped between sessions, with the Overpass extracts,
  the road tiles and the regression baselines. Keep what replay needs (tiles) under `recordings/`, and rebuild
  a baseline from a commit in a git worktree.
- **P25 OSM-based truth flatters OSM-based estimates.** The jammed drives' truth is OSM-matched with
  along-track tied to OBD; judge the road constraint on GNSS drives with drop scenarios.
- **P22 Weighting by claimed accuracy rewards liars.** Network hAcc is sometimes far too small (R-007:
  38 m claimed, 180 m off). Inverse-variance votes let one such fix overrule the rest; floor the σ
  (D-040) so weighting only discounts vague fixes.
- **P12 Real phones are not the emulator:** GnssStatus was silent on the Pixel 8, the Location
  `satellites` extra is always 0, GYRO_UNCAL arrives at twice the requested rate, and the wireless
  charger holder triples the field (≈ 120 µT vs 51 µT). The `compass-report` verdict is the one at the
  *end* of the drive; read `compass_timeline.csv` for what happened while driving. Check each table's row count first
  (`select count(*)` per table) before analysing a new device.

## User context

- The user writes in Ukrainian. Replies are in Ukrainian; code, docs and commits are in English.
- The user's OBD adapter is a cheap "Mini Bluetooth ELM327 v1.5/v2.1" clone (Bluetooth Classic).
- The user's phone is a Pixel 8 on a wireless-charging holder. First real drive recorded 2026-09-28
  (R-007). Recordings go to `recordings/` (not in git). A second drive under jamming (no GNSS fix at
  all) was recorded the same day (R-008). Kyiv has persistent GNSS jamming, so real jammed data is
  easy to get.
