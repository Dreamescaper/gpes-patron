# Roadmap and open questions

Items move to [progress.md](progress.md) when done. Priorities: **P1** next, **P2** soon, **P3**
later or speculative.

## Phase 1 follow-ups (finish the baseline and the dataset)

- **P1 Real drives.** Record urban drives with good GNSS in RECORD_ONLY (with network enabled),
  then run the standard matrix. Real data is needed to tune the stationary thresholds, trust
  thresholds and random-walk constants. Target: at least 5 drives × 30 min, with at least 2
  different phones.
- **P1 Verify on a real device** that raw GnssStatus/GnssMeasurement keep flowing while the
  platform `gps` provider is overridden by our test provider (D-009).
- **P1 Offline cell/Wi-Fi positioning** (`CellWifiResolver` as another coarse source):
  - Cell database: OpenCelliD (CC BY-SA 4.0) and/or BeaconDB exports, filtered to the region and
    stored on the phone (check licence terms before bundling).
  - Model: serving-cell position + timing-advance ring (LTE ≈ 78 m steps, GSM ≈ 550 m), with
    neighbour cells via signal weighting. Wi-Fi from a self-built BSSID map (learned from our own
    drives with good GNSS) or public data.
  - Compare against Google `network` in replay (a new variant rung). Output is a
    `LocationMeasurement(source = NETWORK-like)` with an honest covariance, possibly a ring/sector
    rather than a disk (a Phase 2 road-candidate generator can use rings directly).
- **P1 Validate the compass in real cars:** measure deviation-fit quality, anomaly gate hit rate,
  and the magnetic-holder case (expect a huge hard iron; check that the ellipse still fits or that
  the gates drop it). Compare against GNSS course.
- **P1 Tune compass verdict thresholds on real mounts**: run `replay compass-report` on real drives
  with different holders (vent clip, dashboard suction, magnetic, wireless charging) and compare
  the verdicts with the held-out GNSS error.
- **P2 Compass follow-ups:** persist the per-car calibration across sessions (keyed by mount
  orientation); add a tilt-compensation check using GRV vs accelerometer; add a replay scenario that
  drops or corrupts the magnetometer.
- **P1 Calibrate trust on real data.** Measure false-rejection rates per check, and set C/N0 and
  AGC thresholds from recorded jamming, if any is observed.
- **P2 Automatic rollback.** When a spoof is detected (for example by network disagreement), roll
  back to the last trustworthy snapshot and re-process without the suspect GNSS
  (`rollbackAndReplay` already exists).
- **P2 Trust metrics per reason** in replay (a confusion matrix per check).
- **P2 Spoof detection against consistent spoofers**: compare GNSS speed with OBD speed; compare
  GNSS track curvature with gyro over long windows (minutes, not seconds); use raw-measurement
  consistency (AGC/C/N0 patterns and clock drift jumps) once real data is available.
- **P2 Mock output forward-prediction** to "now" at publish time (it currently republishes the
  last tick with the current timestamp).
- **P3 Streaming reader** for multi-hour drives (the current reader loads everything into memory).
- **P3 On-device settings** for sensor rates, NMEA on/off and full-tracking on/off.

## Phase 2 — road-constrained estimation (the main line)

Goal: estimate *which road segment and where along it*, with multiple hypotheses.

- **P1 OSM road graph.** Build an offline extract (for example a city PBF) and a `RoadGraph`
  implementation with a spatial index. Candidates: GraphHopper's OSM import (Apache-2.0), or
  Barefoot's road map (Apache-2.0). Decide and log in decisions.md.
- **P1 `RoadStateEstimator`** as a particle filter over `(segmentId, distanceAlong, direction,
  speed)`:
  - init: candidates within the coarse-fix uncertainty (for example ±800 m → roads A/B/C);
  - propagate along the graph with speed (OBD, synthetic, or unknown with a prior), branching at
    intersections;
  - weight by gyro turn events vs intersection geometry (a 90° turn kills straight-road particles),
    by coarse fixes, and by trusted GNSS;
  - output: weighted `Hypothesis` list; the mock output uses the best cluster with an honest radius.
- **P2 HMM alternative** (Newson & Krumm style, adapted to turn-event observations), compared in
  the matrix.
- **P2 Turn-event detector** as its own component (angle, duration, start/end times) from the
  motion tracker.

## Phase 3 — more evidence

- **OBD follow-ups:**
  - **P1** real-car test with the user's ELM327 clone: rate, latency, protocol, and the scale
    estimate vs GNSS.
  - **P2** BLE adapters (GATT transport).
  - **P2** persist the per-car speed scale across sessions.
  - **P3** CAN wheel speeds (model-specific, 50–100 Hz; also gives yaw from wheel-speed
    difference).
- **Planned route prior**: align the observed turn sequence with the planned manoeuvres (DTW/HMM),
  as a prior on particle weights. Deviation must stay possible.
- **EKF-GSF yaw estimator** (from PX4) for absolute heading without a magnetometer, from any GNSS
  velocity or road alignment.
- **Phone-to-vehicle mounting estimation** (KF-GINS style) to use lateral acceleration and full
  NHC.
- **Other coarse sources**: cell ID + TA via our own lookup, Wi-Fi scans (offline database), and
  barometer for bridges or tunnels.

## Open research questions

1. How often and how badly do real network fixes disagree with truth in the target city? Is
   inflating them by 1.5× honest?
2. Can car vibration spectra give a usable speed estimate without OBD?
3. What is the minimum evidence to *safely* re-accept GNSS after a long outage while a spoofer
   might be active?
4. How quickly does a road-state particle filter converge from a ±800 m start in a dense grid
   city vs on a highway?
