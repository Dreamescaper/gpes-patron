# Progress, status and results

Newest first. Each entry: what was done, how it was verified, and what remains uncertain.

## Current status (2026-09-28)

| Area | Status | Verified by |
|---|---|---|
| Core models, geo, canonical ordering | ✅ done | unit tests |
| Motion tracker (yaw via gravity, stationary) | ✅ done | sim tests (90° turn ±4° with a tilted mount) |
| Trust evaluator (12 checks, hysteresis, reset) | ✅ done | unit + replay tests |
| Baseline EKF + passthrough reference | ✅ done | replay tests, matrix |
| Pipeline: reorder, history, snapshots, rollback | ✅ done | determinism / late-delivery / rollback tests |
| Simulator (IMU/GNSS/network with tilted mount) | ✅ done | tests, CLI |
| Recording (SQLDelight), JSONL/CSV/GnssLogger export | ✅ done | round-trip test, CLI, emulator pull |
| Replay CLI, 13 standard scenarios, 5 variants, metrics | ✅ done | matrix on a simulated drive |
| Plot script (track, error vs r68/r95, trust timeline) | ✅ done | run on simulated outputs |
| Android acquisition (gps/network/fused, raw GNSS, 11 sensors) | ✅ done | emulator API 37 |
| Foreground service, RECORD/ESTIMATE/MOCK modes | ✅ done | emulator |
| Mock output (fused), feedback guard | ✅ done | emulator: fused last location = our mock; our input rejected as SYNTHETIC_INPUT |
| Real-device drive | ⏳ not yet | — |
| Platform `gps` override keeps raw GNSS flowing | ⏳ unverified | needs a real device |

## Known limitations

- **No real-world data yet.** All accuracy numbers come from the simulator.
- **Slow Doppler-consistent spoofing is essentially undetected** by phone sensors alone (R-002).
- Trust thresholds and stationary-detection thresholds are untuned for real car vibration.
- A slow drift or ramp capture (≤ 2 m/s) is only partly detected (about 50% missed); a patient,
  consistent spoofer without contradicting network evidence is accepted after 120 s (D-011).
- Without GNSS or OBD, speed is a random walk. The 10-min outage p95 is about 1.4 km
  (phone-only, simulated).
- Coarse-fix fusion under directed motion can be overconfident (`gnss_absent_from_start` +
  synthObd: within95 = 0.81).
- The emulator is only useful for plumbing: its GNSS is inconsistent with its static IMU
  (D-014).
- `DriveReader` loads whole drives into memory. On-phone JSONL export of multi-hour drives may OOM
  (share the `.db` instead).
- The GnssLogger export is a best-effort subset (no carrier-phase derived fields).

## Log

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
