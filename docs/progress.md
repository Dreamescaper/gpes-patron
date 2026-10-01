# Progress, status and results

Newest first. Each entry: what was done, how it was verified, and what remains uncertain.

## Current status (2026-09-28)

| Area | Status | Verified by |
|---|---|---|
| Core models, geo, canonical ordering | ✅ done | unit tests |
| Motion tracker (yaw via gravity, stationary) | ✅ done | sim tests (90° turn ±4° with a tilted mount) |
| Trust evaluator (14 checks incl. velocity consistency and GNSS-vs-OBD, hysteresis, reset) | ✅ done | unit + replay tests |
| Baseline EKF + passthrough reference | ✅ done | replay tests, matrix |
| Pipeline: reorder, history, snapshots, rollback | ✅ done | determinism / late-delivery / rollback tests |
| Simulator (IMU/GNSS/network, tilted mount, GRV, magnetometer with car/holder distortions and anomalies, saturation, wobble, wireless charging, gyro scale/bias walk) | ✅ done | tests, CLI |
| Recording (SQLDelight), JSONL/CSV/GnssLogger export | ✅ done | round-trip test, CLI, emulator pull |
| Replay CLI, 13 standard scenarios, 7-variant ladder, metrics | ✅ done | matrix on a simulated drive |
| Plot script (track, error vs r68/r95, trust timeline) | ✅ done | run on simulated outputs |
| Android acquisition (gps/network/fused, raw GNSS, 11 sensors) | ✅ done | emulator API 37 |
| Foreground service, RECORD/ESTIMATE/MOCK modes | ✅ done | emulator |
| Mock output (fused), feedback guard | ✅ done | emulator: fused last location = our mock; our input rejected as SYNTHETIC_INPUT |
| Compass (iron fit, alignment, gates) + mount forward axis + re-mount detection | ✅ done | sim tests (7 new), R-003/R-004; emulator: WMM reference recorded |
| Compass self-assessment (verdict + reasons), shake gate, power recording, `replay compass-report` | ✅ done | 6 new sim tests (holder magnet, saturation, shielding, wireless, wobble); emulator: power table + UI line |
| OBD (ELM327 Bluetooth) source, speed-scale state, OBD spoof check, obd_raw recording | ✅ done | fake adapter tests; **real ELM327 v2.1 clone, 1 drive** (≈7 Hz, 0 failed requests, 2026-09-28) |
| Raw cell + Wi-Fi recording | ✅ done (recorded only; not used yet) | emulator: NR serving cell with identity/signal, Wi-Fi AP scans; CSV export |
| Ukrainian localization (UI, notification, errors; per-app language on Android 13+) | ✅ done | emulator with the app locale set to `uk`; lint: no missing translations |
| CI (GitHub Actions: tests, lint, APK artifact, releases) | ✅ done | green runs; CI APK signature = local debug key |
| Heading bank (heading without GNSS/compass from coarse fixes + gyro + speed) | ✅ done (D-032) | 3 tests; R-011 on both real drives |
| Real-device drive | 🟡 2 drives recorded (RECORD_ONLY), analysed offline | Pixel 8, 2026-09-28: 27 min with GNSS (R-007); 23 min under jamming, no GNSS fix at all (R-008). Live ESTIMATE/MOCK not yet run in a car |
| Platform `gps` override keeps raw GNSS flowing | ⏳ unverified | needs a real device |

Tests: 42 JVM tests (core + recording) as of 2026-09-28.

## Known limitations

- **Almost no real-world data.** Two drives (Pixel 8, R-007 with GNSS, R-008 jammed); all other
  numbers come from the simulator. Live ESTIMATE/MOCK modes have not run in a car yet.
- **Chip dead-reckoning fixes pass as GNSS.** The Pixel 8 keeps emitting `gps` fixes with hAcc
  3–10 m for ~50 s after losing all satellites (NMEA GGA quality 6). The trust evaluator TRUSTS them
  and replay truth includes them.
- Without GNSS and compass the heading now comes from the heading bank (D-032) after ~100–170 s of
  driving; before that the output is the network fixes. After hand-over the calibration is optimistic
  when network fixes are correlated or wrong (R-007 GNSS absent: within95 0.79).
- Real-drive numbers before 2026-09-28 for gyro-only / phone-only / phone+network include the
  recorded OBD (D-033).
- **Pull-away assumes the phone is in a moving car.** After the engine is off and the phone is
  handled, the estimator drives off at 8 m/s (R-007: 577 m in 100 s).
- `gnss_status` was empty on the Pixel 8 (the GnssStatus callback delivered nothing); NMEA GSA/GSV
  and raw measurements were recorded.
- **Slow Doppler-consistent spoofing is essentially undetected** by phone sensors alone (R-002).
- Trust thresholds and stationary-detection thresholds are untuned for real car vibration (on the
  first real drive, 7% of truth-quality clean fixes were not TRUSTED, mostly in the first 9 min of
  poor urban sky).
- A slow drift or ramp capture (≤ 2 m/s) is only partly detected (about 50% missed); a patient,
  consistent spoofer without contradicting network evidence is accepted after 120 s (D-011).
- Without GNSS or OBD, speed is a random walk. The 10-min outage p95 is about 1.4 km
  (phone-only, simulated).
- OBD is validated on one real drive only. The real adapter reads 2.9% *low* and lags GNSS by about
  0.8 s; latency is not modelled. The replay ladder keeps the recorded OBD in every rung, so on
  real drives "gyro-only"/"phone-only" include OBD and the `+synthObd` rungs double-count it.
- The compass is validated only in simulation. Real in-car distortion, magnetic holders and
  EV/hybrid motor fields are unknown.
- The emulator is only useful for plumbing: its GNSS is inconsistent with its static IMU
  (D-014).
- `DriveReader` loads whole drives into memory. On-phone JSONL export of multi-hour drives may OOM
  (share the `.db` instead).
- The GnssLogger export is a best-effort subset (no carrier-phase derived fields).

## Log

### 2026-10-01 — Weighted coarse-odometry votes (D-040)
- Votes weigh 1/(σ₁²+σ₂²), σ floored at 50 m. Test: `vague voters cannot outvote a precise one` (and
  the old one-vote rule accepts the same fix). All unit tests pass. Measured: R-018.

### 2026-10-01 — Coarse-odometry check in vector form (D-039)
- `Odometry` carries the gyro-frame displacement and the relative bearing at the end; the trust check
  rotates it to the predicted heading when that heading is good and not GNSS-derived (180 s without
  trusted GNSS).
- Tests: `CoarseOdometryTest` — sideways fix rejected with a known heading; accepted without a heading,
  with a 40° heading std, or with GNSS trusted 57 s earlier; fixes in the right direction accepted.
  All unit tests pass.
- Measured: R-017. Map of drive A regenerated (current fix highlighted at the cursor; local only).

### 2026-10-01 — Own gravity estimate; questionable-stream reset (D-037, D-038)
- `MotionTracker`: gyro-carried up with a slow, gated accelerometer correction (self-calibrated ‖a‖).
- Trust: a consistent QUESTIONABLE (innovation-gate-only) GNSS stream after an outage is accepted
  after 10 s; found when D-037 exposed a 500-s lock-out.
- `CompassQualityTest` saturation case: the compass may now give honest uncorrected readings before
  the clipping is first seen (the forward axis is learned earlier); asserts silence after detection and
  readings within 2σ. All tests pass.
- Measured: R-016. Not verified live in the app.

### 2026-10-01 — Two more real drives without the wireless charger (R-014)
- `20260929-103343` (A): 16 min, 8.0 km by OBD, jammed (GGA quality 0 throughout), no charging.
  `20260929-190523` (B): 15 min, GNSS throughout; OBD ends at 704 s (engine off; the recording ran on
  while the driver walked, so metrics use the OBD window only).
- `compass-report`: `compass_timeline.csv` gains `reasons` and `mount_epoch`.
- Found: the compass is UNUSABLE even without charging (OFTEN_DISTURBED); OBD latency hurts GNSS
  tracking. Details in R-014.

### 2026-09-28…10-01 — OSM route as truth for the jammed drive
- `tools/truth/osm_match.py`: offline HMM map matching onto OSM roads (Overpass download of the
  bounding box only, 6.4 MB), OBD-distance transitions, one-way rules with a penalized fallback,
  ambiguous intervals excluded. Verified only by consistency (below); the user's Waze screenshot is the
  pending check.

### 2026-09-28 — Robust coarse updates (D-034)
- Coarse fixes above NIS 9.21 are candidates with inflated R; a stream of 3 odometry-consistent
  candidates resets the position (and the heading search after 60 s without GNSS). Replaces the
  NIS > 50 → reset rule.
- Tests: `RobustCoarseTest` (a 400-m outlier: error 5 → 22 m, plain 5 → 378 m; a 500-m drift with
  correct fixes: recovered to 22 m within 70 s). All core tests pass.
- Measured: R-012. Five alternatives rejected by measurement (D-034).

### 2026-09-28 — Heading bank; honest OBD in the ladder (D-032, D-033)
- `HeadingBank` (Gaussian-sum filter over heading) in the baseline EKF; hand-over of heading, position
  and joint covariance; correlated along-track speed error in the bank.
- Replay: `drop_vehicle_speed` step; ladder rungs drop recorded OBD except `phone+obd` and
  `phone+network+obd`; `ticks.csv` gains est/truth speed and heading.
- Tests: `HeadingBankTest` (straight drive converges within 10°; start without GNSS/compass: p95
  −30% or better and within95 > 0.6; without speed: no harm), `ScenarioStepTest`; `CompassTest`
  isolates the compass (bank off). All core tests pass.
- Found on the way: the double-OBD ladder bug (v → 1.9× truth) and that the heading is observable
  without speed (a test premise was wrong).
- Measured: R-011. Not verified live in the app.

### 2026-09-28 — Coarse fixes vs distance driven (D-031)
- `MotionTracker.odometry(t1, t2)`: OBD speed integrated along the gyro bearing → distance and chord
  (heading-free). Exposed to trust through `MotionView.odometry`.
- New network-fix check `COARSE_ODOMETRY_MISMATCH` with voting over the last 3 trusted fixes.
- Tests: 5 new (`CoarseOdometryTest`: chord through a 90° turn within 3%, null without speed; stale
  fix rejected; far jump rejected; a bad first reference does not lock the source out; skipped
  without speed). 44 core tests pass, including determinism.
- Measured on both real drives (R-009): precise but catches little; the first single-reference
  version cascaded and was replaced by voting.
- Not verified live in the app.

### 2026-09-28 — Second real drive: persistent jamming (Pixel 8, RECORD_ONLY)
- Recording `20260928-085946.db` (not in git; 98 MB): 23 min, 11.2 km by OBD, urban Kyiv, **no GNSS
  fix at all** (NMEA GGA quality 0 for the whole drive). Details: R-008.
- Jamming signature confirmed in two independent places: Android AGC (first 272 s) and the chip's
  proprietary `$PGLOR,3,AGC` NMEA sentence (whole drive). Tracking states show almost no time decoding
  (TOW decoded 0.4% vs 30% on R-007), so the occasional C/N0 of 40–50 dB-Hz are false/short locks,
  not spoofing.
- Raw GNSS measurements (and AGC) stopped at 272 s while NMEA continued; this coincided with the
  chip switching to search mode and ending a SUPL session (`$PGLOR` PWR/SPS). Cause unknown.
- The trust evaluator had nothing to judge (no GNSS fixes were produced, so nothing was spoofed).
- The compass was UNUSABLE for the whole drive (NOISY_FIT 17°, OFTEN_DISTURBED 72%, hard iron
  118 µT, wireless charger), so there was no absolute heading source at all.
- Offline experiment `tools/experiments/shape_fit.py`: the gyro + OBD path shape, rigidly fitted to
  network fixes, recovers the heading (fitted rotation stable at −24…−30° for most of the drive).
  Validated on R-007 against GNSS; numbers in R-008. Not in the pipeline yet (roadmap P1).

### 2026-09-28 — First real drive analysed (Pixel 8, RECORD_ONLY)
- Recording `20260928-103211-with-gps.db` (not in git; 140 MB): 27 min, 14.2 km by OBD, urban, ending
  with ~80 s (~340 m) inside an underground car park. After that the car was parked, the phone was
  taken off the holder (13 g shock at 1486 s), the engine turned off (OBD `NO DATA` from 1489 s), and
  it was carried for 2 min. Details and numbers: R-007.
- Verified on the device: all 11 sensors (IMU 100 Hz; GYRO_UNCAL arrives at ~200 Hz), raw GNSS
  measurements + AGC + NMEA, gps/network/fused locations, cell (LTE serving with TA, ~5 cells per
  scan), Wi-Fi (a scan every ~14 s, 1222 BSSIDs), power (wireless charging), WMM, the real ELM327 (setup
  `ATSP0` → protocol A6 CAN, 11 395 speed requests, mean latency 139 ms, 0 failures), timebase offset
  66 ms (not corrected, as designed).
- Found (all recorded in the roadmap and dev-guide pitfalls):
  - Pixel chip DR fixes (GGA quality 6) look like good GNSS and become replay truth;
  - two natural GNSS runaways in the urban part (Pixel GNSS speed up to 44 m/s while OBD said
    9–12 m/s, hAcc 1–9 m). Both were REJECTED by INNOVATION_GATE + SPEED_OBD_MISMATCH: the first real
    evidence for the OBD cross-check;
  - pull-away after the engine is off → 8 m/s phantom motion;
  - `gnss_status` empty; `sats_used`/`satellites` extras are always 0 on this phone;
  - the compass worked, but weakly: MARGINAL for the whole drive and available ~10% of the time
    (see R-007). The report's final verdict UNKNOWN is misleading: it is the state at the end of the
    recording, after the phone was taken off the holder and the fit was reset;
  - neighbour-cell TA is recorded as 0 rather than null.
- Not verified: live estimation, mock output, the `gps` override, jamming (none was observed).

### 2026-09-28 — CI
- GitHub Actions: tests + lint + debug APK + replay CLI on push/PR; APK artifact; Release on `v*`
  tags; stable debug signing via a repo secret; the run number is the versionCode.

### 2026-09-28 — OBD vehicle speed
- `Elm327` client (core) with a scripted fake in tests; `ObdSource` (Bluetooth Classic SPP, clone
  fallbacks, reconnect); OBD card in the UI (pick a paired adapter) and a status line (state,
  version, protocol, km/h, samples, speedometer scale), en/uk (D-027).
- EKF speed-scale state (D-029); GNSS-vs-OBD trust check (D-030); a realistic synthetic OBD in the
  replay ladder (integer km/h, 0.15 s delay, +3%).
- **Bug found and fixed:** the ZUPT position "teleport" after long outages (D-028).
- Also fixed: checkbox rows were only clickable on the box itself; the whole row now toggles.
- Emulator: UI only (no Bluetooth). Real adapter: pending the user's test drive.
- Process slip: docs landed in two follow-up commits after the code commit (89be853), because a
  docs script failed on a text mismatch. Docs scripts now validate every pattern before writing.

### 2026-09-28 — Is the magnetometer on this holder useful? (self-assessment, shake gate, power)
- `Compass.quality()` gives a verdict and reason codes (D-024); UNUSABLE silences it, MARGINAL
  inflates σ. The shake gate depends on the rotation vector (D-025).
- `PowerState` is recorded (D-026). The simulator gained saturation, holder wobble (continuous or
  bursts) and a wireless-charging coil.
- `replay compass-report` gives the verdict, metrics, held-out heading accuracy per mode, and a
  timeline CSV. Live compass line in the app (en/uk).
- Measured verdicts: see R-005. The shake-gate numbers are in D-025.
- Not verified: thresholds on real mounts. The emulator's magnetometer is static, so its verdict
  there stays UNKNOWN.

### 2026-09-28 — Ukrainian localization
- All UI text now lives in `res/values/strings.xml` (English) and `res/values-uk/strings.xml`, with
  Ukrainian plurals. `locales_config.xml` enables per-app language selection on Android 13+.
- Localized: modes, trust states, estimator modes, annotation buttons, notification, mock errors
  and raw GNSS status.
- Deliberately *not* localized: trust reason codes (`IMPOSSIBLE_VELOCITY`, …), source names, record
  type names, and the annotation labels written to recordings (D-023).

### 2026-09-28 — Compass and mount estimation
- `MotionTracker`: vehicle forward axis from centripetal acceleration in turns; re-mount detection;
  gravity τ 5 s. `Compass`: ellipse/circle iron fit binned by bias-corrected gyro heading;
  GNSS/forward/uncorrected alignment modes; vertical-component, radius and gyro gates. EKF
  integration with the correlated-error rule (D-019, D-020).
- Found and fixed on the way:
  - the harmonic deviation-card model failed (p95 70°);
  - gyro drift faked heading coverage;
  - smoothing lag (τ 0.5 → 0.2 s);
  - a single-turn circle fit gave confident 44° errors;
  - repeated compass updates made heading overconfident;
  - the network σ-rule blocked averaging of coarse fixes (D-021).
- Process slip: one commit (094ced4) went in with a failing seed-sensitive test, because a piped
  `grep` hid the Gradle exit code. The sim now draws no random numbers for disabled options, and
  the test was re-checked over 6 seeds. Rule: check the Gradle exit code before committing.
- App: `GeomagneticReference` from `android.hardware.GeomagneticField` at the first real fix and
  every 50 km. Emulator: declination 8.8°, inclination 67.7°, 51.1 µT for Kyiv.
- Simulator: GRV, magnetometer model, optional gyro scale error and bias walk (D-022).
- Not verified: any real car. Magnetic holders, EV/hybrid motors and dashboard placement may break
  the constant-distortion assumption; the gates should then drop the compass rather than mislead.

### 2026-09-28 — Raw cellular and Wi-Fi recording
- `CellSource` (GSM/WCDMA/TD-SCDMA/LTE/NR/CDMA → `CellObs`, TA, modem timestamp) and `WifiSource`
  (requested scans plus system broadcasts, new APs only, no SSID). Live panel shows the serving
  cell and AP count (D-017).
- Emulator (API 37): 10 cell scans (NR 310-260, TAC 8514, NCI 100500, PCI 555, NR-ARFCN 9000,
  RSRP −78…−83), 4 Wi-Fi scans (1 AP). Old bundles without these tables still read and export.
- **Bug found and fixed:** JSONL export crashed on real drives (`SensorInfo.type` vs the
  discriminator, D-018). The test now covers all record kinds.
- Not yet verified: neighbour-cell and timing-advance availability on a real phone and network
  (the emulator reports only a serving NR cell and no TA). Wi-Fi throttling behaviour for a
  foreground-service app on a real device.

### 2026-09-28 — Competent-spoofer scenarios, plotting
- Added `consistentVelocity` to the offset/drift transforms, and 2 new scenarios (D-016); results in R-002.
- Added `tools/plot/plot_replay.py` (matplotlib), verified on simulated runs.

### 2026-09-28 — Android app, emulator verification, docs
- App: `AndroidLocationSource`, `GnssRawSource` (status throttled to ≤ 5 Hz; raw measurements with
  full tracking on API 31+, AGC on 34+), `SensorSource` (timebase self-check annotation),
  `DriveService` (single IO thread, 500 ms WAL flush), `MockLocationPublisher` (fused/gps/network),
  Compose UI (modes, live trust/estimate panel, annotations, share .db / export).
- Verified on the emulator (API 37, Google APIs):
  - The recording contains all tables.
  - The sensor timebase offset is 6 ms, so no correction was applied.
  - In MOCK_OUTPUT the fused provider returned our estimate flagged `mock`, and our own pipeline
    rejected it (`SYNTHETIC_INPUT`).
  - Stopping releases mock mode.
  - The recorded `.db` replays through the CLI.
- Found: emulator GnssStatus fires about 200 Hz, so it is now throttled. Emulator GNSS is rejected by
  trust (static IMU vs a moving fix), so a toggle was added (D-014).
- Build: lint clean (0 errors). compileSdk bumped to 37 for the Compose BOM (D-015).

### 2026-09-28 — Replay matrix and two estimator/trust fixes
- The first matrix run exposed the overconfident post-stop speed (D-012) and the reset onto the
  Lima spoof (D-011). Both were fixed; see before/after in the decisions.
- Added the velocity–position consistency check (D-013).

### 2026-09-27 — Core, recording, replay CLI
- Research doc, core module, SQLDelight recording, replay CLI, 11 scenarios. 24 unit tests.

## Results log

### R-018 (2026-10-01, working tree on 296c86e) — weighted votes (D-040), on top of R-017
Variants file (FUSED dropped, real OBD) on R-007 and B (≤ 700 s) × all scenarios and both jammed
drives: unweighted vs floor 0 / 30 / 50 / 80 m.

| Run | unweighted | floor 0 | floor 50 (default) |
|---|---|---|---|
| Jammed A p50 / p95 m | 16.1 / 76.7 | 15.1 / 74.8 | 15.1 / 74.8 |
| Jammed 2026-09-28 p50 / p95 m | 17.3 / 51.7 | 17.3 / 51.7 | 17.3 / 51.7 |
| R-007 ramp_capture p95 m | 166 | 362 | 166 |
| R-007 ramp_capture_doppler_consistent p95 m | 154 | 371 | 154 |
| R-007 teleport_country p50 m | 53 | 1 | 53 |
| R-007 jump_5km p95 m | 68 | 34 | 68 |

Floors 30 and 80 m give the same numbers as 50 m. p95 geo-mean vs unweighted: floor 50 ×1.000 (R-007, B,
2026-09-28), ×0.975 (A); floor 0 ×1.047 (R-007). Newly rejected with floor 50: A 699 s (1117 m off, hAcc
157 m). Within95 unchanged (A 1.00, 2026-09-28 0.97).

### R-017 (2026-10-01, working tree on 296c86e) — vector coarse-odometry check (D-039)
Same set as R-016 (R-007 and B full ladders × all scenarios, B up to 700 s; both jammed drives
against the driver-checked OSM truths), before = 296c86e.

| Run | p50 m | p95 m | within95 |
|---|---|---|---|
| Jammed 2026-09-28 net+obd | 16.8 → 17.3 | 51.3 → 51.7 | 0.97 → 0.97 |
| Jammed A net+obd | 16.1 → 16.1 | 76.7 → 76.7 | 1.00 → 1.00 |
| R-007 GNSS absent from start, net+obd | 54.4 → 56.4 | 373 → 365 | 0.69 → 0.69 |
| R-007 GNSS drop 1 h, net+obd | 66.8 → 66.8 | 383 → 375 | 0.65 → 0.65 |
| B GNSS absent from start, net+obd | 27.9 → 21.4 | 293 → 293 | 1.00 → 1.00 |

p95 geo-mean over all runs: ×1.000 (R-007), ×1.000 (B), ×1.002 / ×1.000 (jammed). Network fixes newly
rejected: A 632 s (true error 400 m, hAcc 136 m), 2026-09-28 939 s (306 m, hAcc 100 m) and 358 s (no
truth there); none of them a good fix. Tuning: K_v = 2 rejected good fixes (A 622 s, 760 s; A p95 77 → 97
m); without the 180-s GNSS condition B `ramp_capture_doppler_consistent` net+obd p95 657 → 940 m (60 s:
860 m), because the spoofer had steered the EKF heading. Accuracy hardly moves: robust weighting
(D-034) already gave these fixes little weight; the gain is that they no longer count as trusted.

### R-016 (2026-10-01) — own gravity estimate + questionable-stream reset vs `main` (4491791)

Regression check against the committed version, built separately and run identically: all 13 scenarios ×
9 rungs on the two GNSS drives (B only within the OBD window, ≤ 700 s) and all rungs on the two jammed
drives against their driver-checked truths.

| drive | runs | p95 geo-mean new/main | better > 10% | worse > 10% |
|---|---|---|---|---|
| R-007 (GNSS) | 113 | 0.860 | 26 | 9 |
| B (GNSS, ≤ 700 s) | 113 | 0.957 | 13 | 4 |
| R-008 (jammed) vs truth | 5 | 0.998 | 0 | 0 |
| A (jammed) vs truth | 5 | 0.976 | 0 | 0 |

Worse runs in the rungs that matter (network and/or OBD): R-007 ramp capture with network only
170 → 204 m; clean with network + OBD 9.1 → 10.3 m; GNSS absent from start with network + OBD p50 49 → 54 m,
within95 0.77 → 0.70. Jammed with network + OBD: R-008 p50 16.3 → 16.8 m (p95 51.9 → 51.3), A p50
14.1 → 16.1 m (p95 76.7 → 76.7). The other worse runs are synthetic-network or no-network/no-OBD rungs
(e.g. ramp capture gyro-/phone-only 737 → 1548 m). Missed spoof detection unchanged or better except
those rungs. A first version of D-038 shared the stream fields with the older rejected-stream rule and
changed when that rule fired (R-007 overconfident noise with network + OBD 35 → 103 m, B Doppler-
consistent drift 489 → 549 m); with separate fields both are back to `main`.

Compass (`compass-report`, OBD windows):

| drive | before: verdicts | now: verdicts | GNSS-aligned readings vs GNSS course |
|---|---|---|---|
| R-007 (charger) | MARGINAL 1219, readings 141, p95 ~10–30° | MARGINAL 1231 | 240 readings, p50 3.8°, p95 14.4°, 100% within 2σ |
| R-008 (charger, jammed) | UNUSABLE 1275 | MARGINAL 1259, UNUSABLE 22 | none (nothing to align with) |
| A (no charger, jammed) | UNUSABLE 825 | MARGINAL 431, UNUSABLE 394 | 6 forward-aligned |
| B (no charger) | UNUSABLE 619 | MARGINAL 481, UNUSABLE 48 | 110 readings, p50 3.4°, p95 57.5°, 98% within 2σ |

### R-015b (2026-10-01) — drive A against the OSM route **corrected by the driver**, up to +12:40

Corrections: start on Yevhena Chykalenka st, right turn (gyro: 50–55 s) onto Taras Shevchenko blvd
(gyro: straight until the next right turn at 100–105 s); no embankment at the end; plus one assumption
of mine (no one-point hops onto service roads mid-route). Path search now also avoids excluded names.
The driver cut the truth at **+12:40 (760 s)**: the exit from the Paton bridge onto Mykolaichuka st that
was really used is not in OSM yet (still mapped as construction), so the route after it is unreliable.
Truth for 736 s.

| version | p50 m | p95 m | max m | within68 / within95 |
|---|---|---|---|---|
| raw network fixes | 36 | 383 | 1116 | 0.61 within own hAcc |
| Google fused | 36 | 383 | 1116 | 0.52 within own hAcc |
| EKF initial (no heading bank, plain coarse) | 54 | 524 | 805 | 0.78 / 0.96 |
| EKF + heading bank | 14 | 190 | 387 | 0.88 / 0.96 |
| EKF + heading bank + robust coarse (current) | 14 | 77 | 174 | 0.94 / 1.00 |
| current, without OBD | 50 | 263 | 626 | 0.66 / 0.91 |

### R-015 (2026-10-01) — drive A (jammed) against its OSM route, up to +13:10 (first, uncorrected)

Route matched from the current `phone+network+obd` track (8.72 km vs 8.01 km by OBD; a few one-point
hops onto service roads), not corrected by the driver except: no truth after +13:10 (790 s, parking).
Truth for 765 s (gaps at 68–73, 98–103, 735–740, 745–750 s). Circularity caveat as in R-013 applies more
here (no driver corrections yet).

| version | p50 m | p95 m | max m | within68 / within95 |
|---|---|---|---|---|
| raw network fixes | 35 | 374 | 1116 | 0.61 within own hAcc |
| Google fused | 35 | 355 | 1116 | 0.54 within own hAcc |
| EKF initial (no heading bank, plain coarse) | 52 | 502 | 805 | 0.79 / 0.96 |
| EKF + heading bank | 15 | 102 | 387 | 0.87 / 0.96 |
| EKF + heading bank + robust coarse (current) | 15 | 80 | 154 | 0.92 / 1.00 |
| current, without OBD | 49 | 244 | 619 | 0.67 / 0.90 |

### R-014 (2026-10-01) — drives A (jammed) and B (GNSS), no wireless charging

**Compass.** UNUSABLE for most of both drives (A 825 of 937 s, B 619 of 690 s while OBD worked), reason
OFTEN_DISTURBED (the 3-µT vertical-field gate fired > 60% of moving time); B also NOISY_FIT and
GNSS_DISAGREES. Raw |B| 127–154 µT with no charger (vs 119 µT with it): the holder's iron/magnet, not
the coil, dominates; Earth's horizontal field here is ~19 µT. Emulating the gate with a slow "up"
(±60 s mean of raw ACCEL) instead of Android GRAVITY: disturbed fraction A 0.72 → 0.45, B 0.71 → 0.24.
Most "anomalies" are Android's up leaning in turns (dev-guide P14) times the strong holder field.

**B (GNSS), OBD window only (0–704 s):**

| scenario | rung | p50 m | p95 m | max m | within95 |
|---|---|---|---|---|---|
| clean | phone+network | 0.3 | 1.6 | 4 | 0.96 |
| clean | phone+network+obd | 0.7 | **9.5** | 13 | **0.76** |
| drop 2 min | phone+network+obd | 0.8 | 31 | 64 | 0.78 |
| drop 10 min | phone+network | 25 | 289 | 593 | 0.90 |
| drop 10 min | **phone+network+obd** | **11.5** | **33** | 65 | 0.95 |
| drop 10 min | phone+obd | 184 | 269 | 270 | 0.95 |
| GNSS absent from start | phone+network+obd | 25 | 290 | 1544 | 1.00 |

With GNSS, adding OBD makes tracking worse (p95 1.6 → 9.5 m): the ~0.8 s OBD latency is not modelled,
so the EKF lags ~12 m at 15 m/s (roadmap P2 → should be P1).

**A (jammed, no truth):** heading bank hand-over at 132 s; DEAD_RECKONING 794 of 960 ticks; path
8.58 km vs 8.01 km by OBD; 3 steps > 100 m. Without OBD: hand-over at 202 s, path 11.74 km, 13 steps.

### R-013b (2026-10-01) — R-008 against the OSM route **corrected by the driver**

The driver checked the first route against Waze and corrected three places (constraints file): straight
on Holosiivskyi prospekt at Demiivska square (no right exit; 355–395 s), main carriageway, not the
parallel local road named the same (590–640 s), no cloverleaf at Odeska square (1070–1100 s). Route
11.29 km vs OBD 11.16 km (+1.2%), no breaks, truth for 1343 of 1355 s (none at 113–118, 358–363 s).

| version | p50 m | p95 m | max m | within68 / within95 |
|---|---|---|---|---|
| raw network fixes | 30 | 155 | 997 | 0.60 within own hAcc |
| Google fused | 30 | 137 | 997 | 0.59 within own hAcc |
| EKF initial (no heading bank, plain coarse, no odometry check) | 57 | 243 | 547 | 0.68 / 0.96 |
| EKF + heading bank | 16 | 52 | 108 | 0.90 / 0.97 |
| EKF + heading bank + robust coarse (current) | 16 | 52 | 108 | 0.90 / 0.97 |
| current, without OBD | 44 | 199 | 558 | 0.69 / 0.87 |

The circularity caveat below still applies, but much less: the route is now fixed by the driver's
corrections plus OBD distance; where the matcher disagreed with the driver, the estimator track had
pulled it onto wrong branches.

### R-013 (2026-10-01, working tree on 33d0f49) — R-008 scored against the OSM route (first, uncorrected)

Truth: OSM route matched from the current `phone+network+obd` track; route 12.45 km vs OBD 11.16 km
(loops at two interchanges); 1322 of 1355 s covered (no truth at 113–118, 373–383, 1072–1077,
1087–1092, 1147–1152 s). A second route matched from raw network fixes only (sparser, 15.2 km) follows
the same roads: distance between the two p50 28 m, p90 64 m, max 149 m.

| version | p50 m | p95 m | max m |
|---|---|---|---|
| raw network fixes | 32 | 298 | 997 |
| Google fused | 31 | 137 | 997 |
| EKF initial (no heading bank, plain coarse updates, no odometry check) | 59 | 230 | 547 |
| EKF + heading bank | 18 | 51 | 77 |
| EKF + heading bank + robust coarse (current) | 18 | 51 | 72 |
| current, without OBD | 47 | 195 | 558 |

**Caveat:** the truth was matched from the current version's track, so its numbers are optimistic;
the raw-fix and initial-EKF rows are independent of it. Raw network fixes were within their own hAcc
59% of the time (Google fused 57%).

### R-012 (2026-09-28, working tree on 33d0f49) — robust coarse updates (default: NIS 9.21, 3-fix stream)

R-007, p95 m, plain (old NIS > 50 reset) → robust; unchanged scenarios omitted:

| scenario | phone+network+obd | phone+network (no OBD) |
|---|---|---|
| clean | 9 → 9 | 192 → 64 |
| drop 30 s / 2 min | 10 → 10 / 82 → **17** | 192 → 59 / 190 → 139 |
| drop 10 min / 1 h | 106 → 106 / 387 → 381 | 136 → 149 / 561 → 561 |
| GNSS absent from start | 380 → 373 | 561 → 561 |
| jump 5 km / teleport | 133 → 128 / 133 → 128 | 178 → 141 / 133 → 149 |
| overconfident noise | 36 → 34 | 195 → 158 |
| drift gradual / Doppler-consistent | 280 → 305 / 288 → 305 | 261 → 317 / 178 → 324 |
| ramp capture / Doppler-consistent | 106 → 165 / 255 → 185 | 193 → 168 / 202 → 384 |
| geo-mean ratio | 0.89 | 0.89 |

- R-008 09:05:39–09:06:12: the snap after the bad fix at 358 s 42 → 37 m (5.99 threshold: 18 m); the
  later snaps (78 m at 383 s) are corrections of the heading error, unchanged. Snaps > 50 m over the
  drive: 7 → 7.
- Simulation: single 400-m outlier 5 → 22 m (plain 378 m); 500-m drift recovered in 40–70 s.

### R-011 (2026-09-28, working tree on 33d0f49) — heading bank, corrected ladder

R-007, scenario **GNSS absent from start**, bank off → on (only this scenario changes; drops after GNSS
already have a heading):

| rung | p50 m | p95 m | max m | within95 |
|---|---|---|---|---|
| phone+network+obd (recorded network + OBD) | 138 → **47** | 799 → **380** | 1795 → 661 | 0.87 → 0.79 |
| phone+network (no OBD) | 123 → 68 | 647 → 561 | 1566 → 974 | 1.00 → 0.88 |
| phone+synthNetwork+synthObd (σ 500 m) | 705 → 252 | 1163 → 622 | 1420 → 1067 | 0.87 → 1.00 |
| phone+synthNetwork (σ 500 m) | 417 → 288 | 906 → 744 | 1164 → 1067 | 1.00 → 1.00 |

- Heading error after hand-over mostly 1–17°. Worst stretch (1183–1291 s) followed network fixes that
  were 290–862 m off (some honestly hAcc 300–800 m; one 381 m off at hAcc 122 m).
- R-008 (jammed, no truth), phone+network+obd: heading handed over at **115 s**; DEAD_RECKONING for
  1225 of 1368 ticks (before: COARSE_ONLY throughout); steps > 100 m: 1 (before 22–40); path
  11.74 km vs 11.16 km by OBD; distance to the causal shape fit p50 42 m / p95 95 m. Without OBD
  (phone+network): hand-over at 167 s, but speed stays unknown (DR only 201 ticks, path 15.5 km).
- Simulation (`HeadingBankTest`): start without GNSS/compass with network σ 40 m + OBD, p95 improved by
  > 30%; without speed p95 212 → 124 m, within95 1.00 → 1.00.

### R-010 (2026-09-28) — heading from the path shape, corrected (`tools/experiments/shape_fit.py`)

R-007, error against GNSS, past data only (real-time usable), n = 127 points (the fit needs ≥ 6 fixes
spanning ≥ 150 m, so the first ~2 min are not covered):

| method | p50 m | p95 m | max m |
|---|---|---|---|
| raw network fixes | 35 | 290 | 862 |
| EKF phone+network, GNSS absent from start (all ticks) | 138 | 810 | 1804 |
| first shape-fit version, causal 300 s | 62 | 506 | 943 |
| **corrected, causal 300 s** | **29** | **131** | 361 |
| corrected, causal 600 / 900 s | 42 / 48 | 154 / 170 | 335 |
| corrected, all past fixes | 59 | 115 | 335 |

- Gyro bearing vs GNSS course over 21 min: drift −0.12°/min; offset stable at −11…−16° after the
  first 6 min; scatter p50 2.3°, p95 19° (GNSS course lags in turns).
- Caveats: the OBD scale and lag were measured on R-007 itself; OBD has no sign (reverse); not yet an
  estimator, just a fit. The user judged the offline fit on R-008 to be close to the real route near
  the bus station (visual comparison with Waze).

### R-009 (2026-09-28, working tree on 33d0f49) — coarse-odometry check, real drives

`phone+network` (drop FUSED) with the check off / K = 3 (default) / K = 2.

| drive · scenario | metric | off | K = 3 | K = 2 |
|---|---|---|---|---|
| R-007 · clean | network fixes rejected (error vs GNSS) | – | 1 (290 m) | 3 (290, 198, 161 m) |
| R-007 · GNSS absent from start | p50 / p95 / max m | 138 / 810 / 1804 | 138 / 799 / 1795 | 139 / 932 / 1795 |
| R-007 · drop 1 h | p50 / p95 / max m | 58 / 380 / 670 | 57 / 387 / 679 | 58 / 357 / 616 |
| R-007 · drop 10 min, 2 min, noise | p95 m | 106 / 82 / 36 | same | 107 / 82 / 36 |
| R-008 · clean (no truth) | rejected fixes | – | 345 s | 345, 939, 1260 s |
| R-008 | path km (OBD 11.16) / jumps > 200 m / max jump m | 11.85 / 22 / 578 | 11.33 / 21 / 535 | 11.29 / 21 / 560 |

- R-008 bus-station case: the 1.1-km fix at 345 s is rejected (K = 3 and 2); the next bad fix at 358 s
  is at the right distance in the wrong direction and passes. Traffic-light case: the stale fix at
  939 s (hAcc 100 m) is rejected only with K = 2.
- First version (single reference, no voting) on R-008: rejected 345, 369 (a good fix) and 954 (the
  correct fix after the stale one); max jump 578 → 908 m. Hence voting.
- Calibration within68/within95 changed by ≤ 0.04 everywhere (largest: GNSS absent from start, within95 0.84 → 0.88 with K = 2).

### R-008 (2026-09-28, commit 33d0f49 + uncommitted docs) — jammed drive, Pixel 8, no truth

Drive `20260928-085946` (23 min, 11.2 km by OBD). No GNSS fix, so there is no truth and no error
metrics; below are consistency checks and the jamming signature.

| AGC (dB, higher = quieter) | GPS L1 | GLONASS G1 | BeiDou B1 | L5/E5a |
|---|---|---|---|---|
| R-007, open road (1000 s) | 33 | 53 | 52 | 25 |
| R-008, `$PGLOR` over the drive | 1–12 | 14–24 | 20–44 | 3–26 |
| R-008, Android AGC mean (0–272 s) | 6.5 | 18.7 | 26.1 | 21.6 |

- Tracking (first 272 s): code lock in 15% of measurements (R-007: 64%), TOW decoded 0.4% (30%).
- `phone+network` (real network + real OBD): COARSE_ONLY 98% of ticks, r68 30–156 m; distance to the
  network fixes p50 19 m, p95 92 m; to Google fused p50 33 m, p95 219 m. Path length 11.9 km vs 11.2 km
  by OBD (fused: 17.8 km, jumpy). `phone-only` and `gyro-only` never initialise (no position source).
- **Heading from the path shape** (`shape_fit.py`, 300 s window). On R-008: residual of network fixes
  to the fitted shape p50 43 m, p95 189 m (network hAcc p50 42 m). Validated on R-007 against GNSS:

  | method (R-007) | p50 m | p95 m |
  |---|---|---|
  | raw network fixes | 35 | 290 |
  | EKF phone+network, GNSS absent from start (matrix) | 138 | 811 |
  | shape fit, causal (past 300 s only) | 62 | 506 |
  | shape fit, centred (offline smoothing) | 22 | 123 |

  The script is crude (bias learned only at stops; the fitted rotation drifted ~70° over R-007), so
  these are indicative, not a design result.

### R-007 (2026-09-28, commit 33d0f49) — first real drive, Pixel 8, standard matrix

Drive `20260928-103211-with-gps` (27 min, urban, real OBD, real network). Truth = TRUSTED GNSS with
hAcc ≤ 10 m, so it **includes ~45 s of chip DR in the car park** and excludes the two GNSS runaways.
Scenario times are from the start; the car park starts at ~1372 s, so 10-min and 1-h drops start at
120 s and cover most of the drive. **Caveat:** the recorded OBD is present in every baseline rung (see
Known limitations). The compass gave readings (σ ≈ 20–38°) but never updated the EKF, whose heading
σ stayed smaller, so gyro-only = phone-only to the last digit.

| scenario | variant | p50 m | p95 m | max m | within68 / within95 | false rej. | missed det. |
|---|---|---|---|---|---|---|---|
| clean | hold-last-fix | 5.7 | 10.7 | 30 | 1.00 / 1.00 | 0.007 | – |
| clean | phone-only (+real OBD) | 0.9 | 7.8 | 34 | 0.66 / 0.82 | 0.068 | – |
| drop 2 min | phone-only (+real OBD) | 0.9 | 12 | 99 | 0.65 / 0.83 | – | – |
| drop 10 min | phone-only (+real OBD) | 1.5 | 682 | 768 | 0.78 / 0.91 | – | – |
| drop 10 min | phone+network | 1.7 | 107 | 178 | 0.47 / 0.72 | – | – |
| drop 1 h (rest of drive) | phone-only (+real OBD) | 780 | 3691 | 3782 | 0.92 / 0.98 | – | – |
| drop 1 h (rest of drive) | **phone+network** | 58 | 381 | 670 | 0.26 / 0.66 | – | – |
| absent from start | phone+network | 138 | 811 | 1804 | 0.48 / 0.85 | – | – |
| jump 5 km | phone+network | 46 | 133 | 271 | 0.34 / 0.61 | 0.576 | 0.000 |
| Doppler-consistent drift | phone-only (+real OBD) | 1.1 | 550 | 681 | 0.57 / 0.78 | 0.079 | 0.004 |

Other measurements on this drive:
- **OBD vs GNSS speed** (600–1370 s, v > 3 m/s, 624 pairs): OBD = 0.971 × GNSS (reads 2.9% low),
  best alignment when OBD lags 0.8 s (RMS 0.21 m/s vs 0.38 m/s at zero lag).
- **Recorded network** vs truth (62 fixes): p50 35 m, p95 290 m, max 862 m; 63% within the reported
  hAcc. In the car park, Wi-Fi-based fixes had hAcc 16–30 m and agreed with the chip DR within 15–50 m.
- **Car park without GNSS** (custom scenario: drop GNSS from 1372 s): our DR stayed within 3–28 m of
  the chip DR for 340 m of driving (phone-only and phone+network). After the engine was off, phone-only
  drifted 577 m in 100 s at a clamped 8 m/s (pull-away), r68 330 m; phone+network ended 26 m from the
  last chip DR fix.
- **Compass (`compass-report`, 0–1372 s):** verdict MARGINAL (WIRELESS_CHARGING), with readings
  (GNSS_ALIGNED) in 141 of 1352 s. Heading error vs GNSS course over 79 readings: mostly 1–12°,
  with 18–30° at 234–246 s; the reported σ (20–38°) covered all of them. Causes of the low
  availability: raw |B| is 113–139 µT (p5–p95) against 51 µT from WMM (holder/charger iron), and
  the vertical component jumps (1-min std up to 14 µT), so the 3 µT anomaly gate leaves ~47% of the
  time clean. Re-mount detection fired 15 times: at 10 and 13 s (mounting), **twice on the car-park
  ramps (1396, 1400 s, a false re-mount that reset the fit exactly when the compass was needed)**,
  and 11 times while the phone was handled after the drive.
- **Findings for calibration:** within95 0.82 on clean data is overconfident: 0.69 in the first
  9 min (poor urban sky, GNSS anomalies, 63 DR ticks) and still 0.86 in 540–1372 s of plain GNSS
  tracking (r68 ≈ 1–1.5 m is smaller than the fix-to-fix noise of the truth itself); phone+network is badly overconfident in long drops (0.26 / 0.66)
  because network errors up to 860 m are not covered by `networkInflation` 1.5.

### R-006 (2026-09-28) — 1-hour simulated drive after D-028/D-029, realistic synthetic OBD

| scenario | variant | p50 m | p95 m | heading p95° | within95 | missed det. |
|---|---|---|---|---|---|---|
| drop 1 h | phone-only | 582 | 1396 | 6.3 | 0.99 | – |
| drop 1 h | phone+synthNetwork | 227 | 501 | 7.0 | 0.99 | – |
| drop 1 h | gyro+synthNetwork+synthObd | 180 | 426 | 21 | 1.00 | – |
| drop 1 h | **phone+synthNetwork+synthObd** | **88** | **152** | 7.3 | 1.00 | – |
| absent from start | phone+synthNetwork+synthObd | 191 | 490 | 16 | 1.00 | – |
| Doppler-consistent drift | +synthObd | 1.4 | 289 | 7.2 | 0.90 | 0.80 (vs 0.996 without OBD) |

### R-005 (2026-09-28) — compass verdicts for simulated holders (`replay compass-report`, 10-min drive)

| Mount | Verdict | Reasons | Key metric | Held-out p95 (GNSS-aligned) |
|---|---|---|---|---|
| clean | USABLE | – | radius/expected 1.03 | 3.2° |
| magnet in holder (≈ 440 µT offset) | MARGINAL | LARGE_HARD_IRON | calibrated out | similar to clean |
| wireless charger (60 µT coil) | MARGINAL | OFTEN_DISTURBED, WIRELESS_CHARGING | 50% of time disturbed | 7.3° (28% availability) |
| steel shielding plate (×0.2) | UNUSABLE | WEAK_FIELD | radius/expected 0.19 | silenced |
| clipped sensor (saturation) | UNUSABLE | SATURATED | – | silenced (0 readings) |
| continuous 3° wobble (with GRV) | USABLE | – | tilt-rate 0.69 rad/s | still works |

### R-004 (2026-09-28) — 1-hour simulated drive, realistic gyro (1% scale error, bias walk 2e-4)

Setup: default city loop ×6 (3408 s, net +225° per loop), network σ 500 m every 20 s. The GNSS
outage runs from 120 s to the end (~55 min).

| variant | p50 m | p95 m | heading p95° | within95 |
|---|---|---|---|---|
| hold-last-fix | 1547 | 3311 | 176 | 1.00 |
| gyro-only (no compass) | 1520 | 2927 | **105** | 1.00 |
| phone-only (gyro + compass) | 1199 | 2685 | **7.6** | 0.95 |
| phone+network | 367 | 747 | 6.5 | 0.93 |
| phone+synthNetwork | 265 | 628 | 8.3 | 0.98 |
| gyro+synthNetwork+synthObd | 192 | 480 | 23 | 1.00 |
| **phone+synthNetwork+synthObd** | **89** | **158** | 8.0 | 1.00 |

Start without GNSS (same drive): synthNetwork+synthObd gives p50 690 m without the compass and
179 m with it (p95 999 → 528 m).

Reading: over long outages the compass bounds heading (105° → 8°). Combined with coarse network
and speed, error stays below about 160 m (p95) for an hour in simulation. Without speed, the
position error is dominated by distance and not heading, so the compass alone helps little (p95
2.9 → 2.7 km).

### R-003 (2026-09-28) — 10-min drive after compass + network spacing (ideal gyro)

Key changes vs R-001:
- 10-min outage: synthNetwork+synthObd p95 252 → 117 m; synthNetwork p95 911 → 826 m.
- 2-min outage: synthNetwork p95 165 → 109 m.
- Start without GNSS + synthNetwork+synthObd: p95 735 m, and 1138 m without the compass.

With an *ideal* constant-bias gyro and heading known from GNSS, the compass adds nothing (heading
p95 1.8° gyro-only vs 4.2°). That result motivated D-022.

### R-002 (2026-09-28) — naive vs Doppler-consistent spoofing, simulated drive (same setup as R-001)

| scenario | variant | p95 m | missed detection | detection latency s |
|---|---|---|---|---|
| drift 2 m/s (naive) | phone-only | 734 | 0.516 | 26 |
| drift 2 m/s (Doppler-consistent) | phone-only | 760 | 0.993 | 26 |
| drift 2 m/s (Doppler-consistent) | +synthNetwork | 572 | 0.993 | 26 |
| drift 2 m/s (Doppler-consistent) | +synthNetwork+synthObd | 572 | 0.749 | 26 |
| ramp 1 km/2 min (naive) | +synthNetwork | 989 | 0.571 | 7 |
| ramp 1 km/2 min (Doppler-consistent) | +synthNetwork | 989 | 0.878 | 7 |
| ramp 1 km/2 min (Doppler-consistent) | +synthNetwork+synthObd | 988 | 0.646 | 7 |

Reading: with a competent spoofer the Phase 1 trust evaluator is largely blind. Independent speed
helps somewhat. Early "detections" (latency 7–26 s) are isolated QUESTIONABLE verdicts, not
sustained rejection.

### R-001 (2026-09-28, commit after e4b3484) — simulated drive, standard matrix

Setup:
- `replay simulate --network-period 20`, seed 1: a 587 s city loop with 90° turns and stops, a
  tilted phone mount, GNSS σ = 3 m at 1 Hz, and network σ = 500 m every 20 s.
- The truth is the simulator truth.
- The drive is only about 10 min long, so the 10 min and 1 h outage scenarios are clipped to
  about 467 s and give identical results.
- Variants: `hold-last-fix` (passthrough), `phone-only` (no network or fused), `+synthNetwork`
  (σ 500 m / 20 s), `+synthObd` (σ 0.3 m/s, 1% scale error).

Errors are over the whole drive (m). within95 is the fraction of ticks where the error was within
the reported 95% radius.

| scenario | variant | p50 | p95 | max | within95 | recovery s | false rej. | missed det. |
|---|---|---|---|---|---|---|---|---|
| clean | hold-last-fix | 13.6 | 19.5 | 24.0 | 1.00 | – | 0.000 | – |
| clean | phone-only | 1.3 | 3.1 | 8.0 | 0.99 | – | 0.000 | – |
| drop_30s | phone-only | 1.3 | 3.7 | 37.6 | 0.98 | 1 | 0.000 | – |
| drop_30s | +synthObd | 1.5 | 3.3 | 6.2 | 0.97 | 1 | 0.000 | – |
| drop_2min | hold-last-fix | 14.9 | 684.5 | 1000.8 | 1.00 | 2 | 0.000 | – |
| drop_2min | phone-only | 1.5 | 225.6 | 395.4 | 0.99 | 2 | 0.000 | – |
| drop_2min | +synthNetwork | 1.5 | 165.3 | 268.6 | 0.99 | 2 | 0.000 | – |
| drop_2min | +synthNetwork+synthObd | 1.7 | 19.6 | 35.1 | 0.98 | 2 | 0.000 | – |
| drop_10min (≈467 s) | hold-last-fix | 1013 | 2145 | 2475 | 1.00 | – | – | – |
| drop_10min (≈467 s) | phone-only | 312 | 1375 | 2260 | 0.98 | – | – | – |
| drop_10min (≈467 s) | +synthNetwork | 154 | 911 | 1014 | 0.93 | – | – | – |
| drop_10min (≈467 s) | +synthNetwork+synthObd | 51.5 | 251.6 | 424.1 | 0.99 | – | – | – |
| absent_from_start | +synthNetwork | 458 | 995 | 1075 | 1.00 | – | – | – |
| jump_5km | phone-only | 1.6 | 319.4 | 506.1 | 0.98 | 19 | 0.036 | 0.000 |
| jump_5km | +synthNetwork+synthObd | 1.7 | 28.6 | 43.6 | 0.98 | 19 | 0.036 | 0.000 |
| teleport_country | phone-only | 11.6 | 646.8 | 654.8 | 0.99 | 19 | 0.059 | 0.000 |
| drift_gradual (2 m/s) | phone-only | 251 | 734 | 887 | 0.45 | 122 | 0.418 | 0.516 |
| drift_gradual (2 m/s) | +synthNetwork | 8.1 | 566 | 611 | 0.55 | 17 | 0.052 | 0.516 |
| ramp_capture (1 km / 2 min) | +synthNetwork | 146 | 989 | 1000 | 0.49 | 18 | 0.056 | 0.571 |
| noise_overconfident (40 m) | phone-only | 1.9 | 103.3 | 154.6 | 0.92 | 14 | 0.029 | 0.012 |

Reading (simulation only; needs real drives):
- **Vehicle speed is the largest single gain** for outages: 10-min p95 1375 → 252 m, and 2-min
  p95 226 → 20 m.
- **Coarse network** bounds error at about 1 km without speed, and helps trust recovery a lot
  (drift false rejection 0.42 → 0.05) through the 15 s network-corroborated reset.
- Sudden jumps and teleports are rejected within 1 s. Slow drift and ramp capture remain the weak
  spot (roughly 50% of manipulated fixes are accepted).
- Uncertainty is mostly honest (within95 of 0.93–0.99) except in the manipulation scenarios, where
  accepted spoofed fixes make the filter confidently wrong. That is expected.

Full per-run outputs: `replay matrix … --out <dir>` (`comparison.md`, `summaries.json`, and
per-run `ticks.csv`, `trust.csv`, `error_vs_time.csv`).
