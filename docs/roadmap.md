# Roadmap and open questions

Items move to [progress.md](progress.md) when done. Priorities: **P1** next, **P2** soon, **P3**
later or speculative.

## Suggested order for the next sessions (as of 2026-09-28)

1. **Get real data** (user). RECORD_ONLY with network on, OBD adapter selected, phone in its usual
   holder; ideally several 30–60 min urban drives with good GNSS. *First drive done 2026-09-28 (R-007);
   for the compass, also record one drive without the wireless charger.*
2. **Check the real recording**:
   - sample rates, timebase annotation and `obd_raw` latency;
   - neighbour cells and TA present?
   - Wi-Fi throttling;
   - `replay compass-report` verdict;
   - OBD speed-scale estimate vs GNSS.
   Fix the device quirks you find, and add them to dev-guide pitfalls.
3. **Run the standard matrix on the real drives**, record them as R-00x, then tune trust,
   stationary and compass thresholds against false rejection and calibration.
4. **Phase 2 kickoff**: an OSM road graph plus a road-state particle filter (see below). This is
   the main accuracy lever for hours-long outages.

## Phase 1 follow-ups (finish the baseline and the dataset)

Found on the jammed drive (R-008, 2026-09-28):

- **P1 Heading without GNSS and compass.** *Done 2026-09-28 as the heading bank (D-032, R-011).
  Follow-ups: P1 coarse-fix error model (below); P2 re-run the bank when the EKF heading degrades during
  a long coarse-only stretch; P2 reverse detection feeding the bank; P3 use the bank as an offline
  smoother for replay truth on jammed drives.* History: On R-008 the heading was never known, so OBD was wasted.
  The path shape from gyro + OBD is accurate enough to fit to network fixes (`shape_fit.py`: causal
  p50 62 m vs 138 m for the current EKF on R-007 with GNSS absent). Implement properly: a bank of
  heading hypotheses (EKF-GSF, already listed below as a compass alternative) or a particle filter
  weighted by network fixes; also use it as an offline smoother for replay. Phase 2 map matching
  will constrain the same shape even more.
  **Update 2026-09-28 (R-010): un-parked.** With the two script bugs fixed (bias learning on OBD = 0,
  Android "up"), the causal 300-s fit gives p50 29 m / p95 131 m on R-007 vs 138 / 810 m for the
  current EKF with GNSS absent. The gyro drifts only −0.12°/min, so the heading offset can be learned
  from many past fixes. Next: put it in the estimator (heading bank / EKF-GSF, or a rigid-fit heading
  measurement into the EKF).
  Refit-per-fix artefact (R-008 09:16:10): every new fix refits the rotation; the current point is
  ~1 km from the centroid of the window's fixes, so a 3° change moved it 81 m in 2 s. Thinning fixes
  by distance (60–150 m) did not help (bigger jumps, same accuracy). Exponential smoothing of the
  rotation cut the max jump 56 → 27 m but hurt accuracy badly (R-007 p50 30 → 79 m at α = 0.15),
  because a fixed gain converges too slowly from a poor first fit. It needs an uncertainty-weighted
  gain, i.e. the heading as a filter state (Kalman/GSF), not ad-hoc smoothing.
  *Earlier (parked by the user on 2026-09-28):* against the Waze route both variants looked poor: causal is bad
  in general, and even the centred fit breaks on complex paths (end of R-008: parking search and a
  3-point U-turn). Known defects of the script: (1) it learns the gyro bias whenever OBD reads 0 km/h,
  but OBD rounds slow motion to 0, so turning at a crawl was learned as bias and the heading ran away
  (an apparent "440° loop" that the raw gyro does not show); use ZUPT from the motion tracker
  instead; (2) OBD speed has no sign, so reversing is integrated as forward motion; (3) in the last
  150 s the centred window has no future, so it is effectively causal; (4) one rotation per 300 s
  window cannot follow complex paths. Prerequisites if resumed: reverse detection (longitudinal accel
  at pull-away, or a gear PID if the car has one), shorter/adaptive windows or per-segment rotation.
- **P1 Speed without OBD (D-036).** OBD is optional; the app must work without it. Plan: speed as an EKF
  state driven by the accelerometer along the forward axis, never open-loop, bounded by ZUPT (stops),
  centripetal speed |a_lat|/|ω| in turns, coarse-fix spacing vs the odometry chord, and GNSS/OBD when
  present; forward/reverse from accelerometer + gyro. Measure on all four drives with the `phone+network`
  rung (no OBD) against the jammed-drive truths (R-013b, R-015b) and GNSS truth (R-007, B): today p50
  44–50 m without OBD vs 14–16 m with it. Prerequisites: the gravity estimate and stop detection below.
- **P1 Bug: mount forward-axis learning *and the compass anomaly gate* use a tilted "up" on real phones.**
  *Done 2026-10-01 (D-037, R-016).* Follow-ups: compensate known vehicle acceleration (OBD dv/dt, v·ω)
  before the accelerometer correction; a sim option for an Android-like leaning rotation vector.
  History:
  *Prototype measured 2026-10-01 and reverted (not requested at the time):* gyro-carried up with a
  slow (τ 30 s) gated accelerometer correction. Centripetal correlation 0.97–0.98 (Android 0.26–0.31,
  ±60 s accel mean 0.92–0.95, the latter not causal); compass verdict on drive B UNUSABLE 619 → 18 ticks
  and GNSS-aligned p50 ~4°; estimator p95 geo-mean ×0.86 (R-007) / ×0.96 (B), but some runs worse
  (R-007 10-min drop with network+OBD p50 1.7 → 61 m). Suspect: the correction gate |‖a‖ − g| < 0.3 m/s²
  passes only ~50% of samples on this phone (‖a‖ p10 9.0–9.3, p90 10.5–10.9), so the gyro drifts
  between corrections; gate on ‖a_lp‖ against its own long-term mean instead of 9.80665, and compensate
  known vehicle acceleration (OBD dv/dt, v·ω) before using the accelerometer.
  R-014: the compass was UNUSABLE on both drives without a charger because the 3-µT vertical-field gate
  saw Android's up leaning in turns × a 130–150 µT holder field; with a slow up the disturbed fraction
  drops 0.72 → 0.45 and 0.71 → 0.24. Same fix for both. `MotionTracker.learnMount`
  takes horizontal accel with `up()` from GAME_RV/ROTATION_VECTOR, which absorb most of the
  centripetal acceleration in turns (dev-guide P14). Use a slow "up" for horizontal-accel work (mean of
  raw ACCEL over tens of seconds while the mount epoch is unchanged), add a sim option that makes the
  simulated rotation vector lean like Android's, and re-check the learned forward axis on R-007/R-008.
- **P1 Stationary detection is tuned for the simulator, not an idling car.** R-008 69-s stop at a
  traffic light (845–914 s): STATIONARY in 3 of 80 ticks. Accel-norm std at idle was 0.14–0.26 m/s²
  against the 0.12 threshold. Real 1-s statistics (both drives), accel std p50: stopped 0.15–0.18,
  crawling 1–10 km/h 0.20–0.23, > 10 km/h 0.51–0.67; gyro-norm mean p50: stopped 0.009–0.011 rad/s,
  crawling 0.023–0.032, > 10 km/h 0.056–0.076. Accel std does not separate a stop from a crawl; the
  gyro does better. Retune (gyro-led, accel as a loose upper bound), and never infer a stop from
  OBD = 0 alone (R-008 reversed at OBD = 0).
- **P1 Coarse fixes vs "how far have we driven".** *Magnitude part done 2026-09-28 (D-031, R-009):
  catches far jumps and some stale fixes, small effect. Direction part done 2026-10-01 (D-039, R-017):
  vector form once the heading is known without GNSS. Open: stale fixes that stay within tolerance, and
  the case with GNSS (heading possibly spoofed).* Original note: After that stop, Google network fixes stayed near
  the stop for ~25 s (939 s: 44 m *behind* the previous fix, hAcc 100 m, while OBD says the car had
  driven ~280 m nearly straight — gyro heading change < 10°). Without a heading the EKF models the
  displacement as a growing disk, so it accepted the stale fix and pulled the estimate back. Gyro +
  OBD actually give the displacement *magnitude* on a straight road (a ring of radius ≈ distance, only
  the direction unknown): a ring/annulus constraint (natural in a particle filter or heading bank)
  would reject such fixes. Minimum version: reject a coarse fix whose distance from the last fused
  one is far below the OBD distance while the gyro says the path was straight, or far above it.
- **P1 Forward/reverse detection** (OBD speed has no sign; needed by any DR with OBD). Measured on
  R-007/R-008 (2026-09-28, throwaway scripts): two tests, both immune to braking because braking acts
  along the direction of travel:
  1. *In turns:* lateral accel vs v·ω (centripetal a = ω × v): sign(a_lat) = sign(ω)·sign(v). With our
     own gravity (±60 s mean of raw ACCEL) the correlation is 0.93 / 0.89 and the sign agrees in
     97–100% of 1-s bins with |v·ω| > 0.3 m/s² during forward driving, both drives.
  2. *At speed changes:* longitudinal accel vs d|v|/dt from OBD: same sign = forward (braking forward:
     both negative), opposite sign = reverse. Clean at pull-away and stops; single-bin flips appear
     at transitions (OBD lag and integer km/h).
  Neither test sees straight driving at constant speed. Direction can only change at a standstill,
  so decide per segment between stops and accumulate evidence from both tests.
  **Validated on a known manoeuvre** (user-confirmed): R-008 1218–1236 s (09:20:04–09:20:22), a
  3-point U-turn. Forward left turn to 1226 s; **reverse at 1229.5–1232.5 s** (yaw +9…11°/s left while
  a_lat was −0.03…−0.28 m/s², i.e. opposite to forward driving; |v| ≈ 1 m/s from a_lat/ω; then
  +0.4…0.6 m/s² longitudinal = braking the reverse); forward left again from 1233.5 s. The final
  U-turn at 1319–1340 s was confirmed by the user to have **no** reverse, and both tests said forward.
  **OBD read 0 km/h for the whole reverse leg** (this car reports no speed in reverse or at a crawl), so:
  stops must be detected from the IMU, not from OBD = 0; a ZUPT/bias update must not trigger on
  OBD = 0 while the gyro shows turning; the reverse speed must come from a_lat/ω or be treated as
  unknown-small. Needs more manoeuvres (straight reversing, parking) to set thresholds.
- **P2 Heading sources in manoeuvres (R-008, 1310–1370 s, a 3-point U-turn).** Gyro: −181° net
  (a left U-turn), clean, flat once stopped. Android GAME_ROTATION_VECTOR (no magnetometer): −158°.
  Magnetometer (our raw projection and GEOMAG_ROTATION_VECTOR agree): jumps of up to 190° in 4 s
  during the manoeuvre and ends at +238°, i.e. −122°, about 60° off; it also compressed ordinary turns
  to ~0.4–0.6× (1230–1300 s: gyro −64°, magnetometer −27°). Android ROTATION_VECTOR (mag + gyro)
  ends at −125°, pulled off by the magnetometer. Our compass verdict (UNUSABLE) was right to ignore it.
- **P1 Jamming indicator from AGC.** Real values: clean L1 ≈ 25–34 dB, jammed 1–12 dB; GLONASS clean
  ≈ 45–55, jammed 14–24. Parse `$PGLOR,3,AGC` on Broadcom chips (it kept flowing when Android
  measurements stopped) and Android AGC; feed a JAMMED flag to trust (e.g. do not accept a sudden
  first fix after jamming without a consistency check) and to the UI.
- **P2 Raw measurements stopped at 272 s** under jamming (NMEA continued; the chip entered search
  mode and ended a SUPL session). Check logcat on the device; consider re-registering the
  measurement callback when it goes silent while NMEA flows.
- **P2 Treat C/N0 without TOW decode as noise** in any future raw-measurement check (false locks of
  40–50 dB-Hz appear under jamming).

Found on the first real drive (R-007, 2026-09-28):

- **P1 Chip dead-reckoning fixes.** Pixel `gps` fixes continue for ~110 s after all satellites are
  lost (GGA quality 6, `sats` 00) with hAcc 3–10 m for the first ~50 s. Mark them in the pipeline
  (from NMEA GGA quality, or from the absence of raw measurements with C/N0 > ~20 dB-Hz), give them a
  trust reason (e.g. `CHIP_DEAD_RECKONING`, QUESTIONABLE), and **exclude them from replay truth**.
  Open question: are they useful evidence? On R-007 they agreed with our own DR within 3–28 m and with
  Wi-Fi fixes within 15–50 m.
- **P1 Truth for jammed drives.** *Done for R-008 with driver corrections (R-013b; constraints file).*
  Next: match from an estimator-independent
  track (offline shape fit + OBD) to remove the circularity, and record a jammed drive with a dashcam
  or a second phone with clean GNSS for a real truth.
- **P1 Coarse-fix error model (calibration).** After the heading bank, the dominant error on real drives
  is wrong or correlated network fixes (R-007: 290–862 m errors for 2 min; one 381 m off at hAcc 122 m),
  and within95 is 0.79. Options: heavier-tailed R (Student-t / robust NIS gating), an error that decays
  with distance driven (correlation length ~ hundreds of m), per-area inflation learned from our own
  drives with GNSS.
  Visible symptom (R-008 09:05:39–09:06:12, after the heading bank): a sawtooth. Between fixes the DR
  runs smoothly; each coarse fix snaps the estimate 30–98 m sideways. The first snap came from a bad but
  accepted fix (358 s, right distance, wrong direction), which also rotated the heading by 9°, so the DR
  then drifted ~2–3 m/s sideways until the next fix. Robust (Huber/Student-t) weighting of coarse fixes
  should shrink both. *Robust weighting done (D-034, R-012): the 358-s snap only 42 → 37 m at the
  default threshold (18 m at 5.99); the heading still rotates. Next: a heavier-tailed error model
  instead of the ×1.5 inflation, which dilutes outlier detection (the 358-s fix is only ~3.2σ).*
  Evidence for it (R-018): the Huber-like R × NIS/9.21 still let a fix 1117 m off (drive A 699 s,
  NIS ≈ 37) move the estimate 100 m before D-040 rejected it in trust; a redescending weight
  (Student-t / Cauchy) would give it almost none.
- **P2 Output smoothing for the mock location.** An EKF correction is an instantaneous jump; navigation
  apps show it as a zigzag. Blend corrections into the published position over a few seconds (PX4-style
  output predictor) while keeping the EKF state and the honest r68 unchanged.
- **P1 Replay ladder on real drives.** *Done 2026-09-28 (D-033).* Add a `drop_vehicle_speed` step (or make `drop_source`
  cover OBD) so gyro-only / phone-only really have no speed source; make `+synthObd` rungs drop the
  recorded OBD first; add `phone+obd` and `phone+network+obd` rungs that use the recorded OBD.
- **P1 Vehicle parked / phone left the car.** OBD `NO DATA` after a working session means ignition
  off; a large shock plus an orientation change means the phone left the holder. Either should stop
  pull-away (currently v = 8 m/s with σ 10 m/s; R-007 drifted 577 m in 100 s after parking). Consider
  emitting an "ignition off" measurement from `ObdSource`.
- **P1 GnssStatus on the Pixel 8 (Android SDK 37)**: `gnss_status` was empty for the whole drive
  while NMEA and raw measurements worked. Check logcat / GPSTest on the device; until fixed, derive
  satellites-used from NMEA GSA.
- **P1 OBD latency** (raised from P2 by R-014: with GNSS, adding OBD worsened tracking p95 1.6 → 9.5 m).
  *Original P2 note:* The real adapter lags GNSS by ~0.8 s (not the 0.15 s the synthetic OBD
  assumes). Estimate the lag online (cross-correlation against trusted GNSS speed) and time-shift
  speed updates; update the synthetic OBD to match.
- **P2 Calibration on real data.** Clean within95 is 0.82 (0.86 even during plain GNSS tracking);
  phone+network is 0.26 / 0.66 in long drops (network errors up to 860 m). Revisit GNSS R in tracking
  and `networkInflation` / a heavy-tailed network model.
- **P3 Small recorder fixes:** store neighbour-cell TA as null instead of 0; take satellites used
  from GnssStatus/NMEA rather than the Location extras (always 0 on the Pixel); compute the
  compass-report tilt-rate RMS only while the phone is mounted (0.9 rad/s here was the phone being
  handled after the drive).
- **P2 `compass-report` summary:** it prints the verdict at the *end* of the drive (UNKNOWN on R-007,
  after post-drive re-mounts), not the verdict while driving (MARGINAL). Report verdict time
  fractions per mount epoch, and the reasons per epoch.
- **P2 Re-mount detection vs ramps:** car-park ramps (pitch change > 20° in 3 s) triggered two false
  re-mounts on R-007. Distinguish a pitch change of the whole car (gravity tilts, and the forward axis
  stays the same in the car frame; slow, while moving) from a re-mount (usually stationary or at low
  speed, with yaw/roll in the phone frame and a shock).

- **P1 Real drives.** Record urban drives with good GNSS in RECORD_ONLY (with network enabled and
  the OBD adapter selected), then run the standard matrix. Real data is needed to tune the stationary thresholds, trust
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
- **P2 Spoof detection against consistent spoofers** (GNSS-vs-OBD speed is done, D-030; with OBD
  it still misses about 80% on the 1-h drive): compare GNSS track curvature with the gyro over long
  windows (minutes, not seconds); use raw-measurement consistency (AGC/C/N0 patterns and clock drift
  jumps) once real data is available; escalate a sustained SPEED_OBD_MISMATCH to REJECTED.
- **P2 Mock output forward-prediction** to "now" at publish time (it currently republishes the
  last tick with the current timestamp).
- **P3 Streaming reader** for multi-hour drives (the current reader loads everything into memory).
- **P3 On-device settings** for sensor rates, NMEA on/off and full-tracking on/off.

## Phase 2 — road-constrained estimation (the main line)

*Motivating real case (R-008, 09:05:32–09:05:41):* the DR track had exactly the road's shape but ran
~40 m to the right of it; a coarse fix then corrected it and rotated the heading, and the track zigzagged.
Limiting heading corrections made it worse (D-035). A road constraint fixes a parallel offset directly.

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
