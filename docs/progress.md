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
| OBD (ELM327 Bluetooth) source, speed-scale state, OBD spoof check, obd_raw recording | ✅ code + tests (fake adapter) | **not yet tested with a real adapter/car** |
| Raw cell + Wi-Fi recording | ✅ done (recorded only; not used yet) | emulator: NR serving cell with identity/signal, Wi-Fi AP scans; CSV export |
| Ukrainian localization (UI, notification, errors; per-app language on Android 13+) | ✅ done | emulator with the app locale set to `uk`; lint: no missing translations |
| CI (GitHub Actions: tests, lint, APK artifact, releases) | ✅ done | green runs; CI APK signature = local debug key |
| Real-device drive | ⏳ not yet | — |
| Platform `gps` override keeps raw GNSS flowing | ⏳ unverified | needs a real device |

Tests: 42 JVM tests (core + recording) as of 2026-09-28.

## Known limitations

- **No real-world data yet.** All accuracy numbers come from the simulator.
- **Slow Doppler-consistent spoofing is essentially undetected** by phone sensors alone (R-002).
- Trust thresholds and stationary-detection thresholds are untuned for real car vibration.
- A slow drift or ramp capture (≤ 2 m/s) is only partly detected (about 50% missed); a patient,
  consistent spoofer without contradicting network evidence is accepted after 120 s (D-011).
- Without GNSS or OBD, speed is a random walk. The 10-min outage p95 is about 1.4 km
  (phone-only, simulated).
- OBD is validated only against a scripted fake adapter and synthetic speed.
- The compass is validated only in simulation. Real in-car distortion, magnetic holders and
  EV/hybrid motor fields are unknown.
- The emulator is only useful for plumbing: its GNSS is inconsistent with its static IMU
  (D-014).
- `DriveReader` loads whole drives into memory. On-phone JSONL export of multi-hour drives may OOM
  (share the `.db` instead).
- The GnssLogger export is a best-effort subset (no carrier-phase derived fields).

## Log

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
