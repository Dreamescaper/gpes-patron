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

- Road position/turn constraints (R-020): need reliable speed (OBD or GNSS); D-077 adds a heading-only local axis cue
  when speed is uncertain, with little real-drive effect (R-033). It cannot locate the vehicle along a street or
  select a parallel carriageway. A road missing from OSM next to a mapped parallel
  street can still pull the track until P(off-road) rises; the reported radius is the road-free one
  (conservative); along-track error between turns is only partly corrected (M4 fires a few times per
  drive); not yet run in the app or a car.

- **Limited real-world data.** Six Pixel 8 recordings, including two hand-held drives recorded with mock output on
  2026-10-03 (build 0.1.6). Four have usable GPS truth for the current replay comparison; 153540 has only 50 interpolated
  truth ticks in seven windows. D-075/D-076/D-077 are verified offline; none has run in a car. D-078…D-082
  are offline experiments kept on branch `experiment/stop-motion`, not in main (D-083).
- **Fused is ignored while we mock `gps` (D-086)**, including the first 10 s of each probe window; the grace time is a guess.
- **Chip dead-reckoning fixes pass as GNSS.** The Pixel 8 keeps emitting `gps` fixes with hAcc
  3–10 m for ~50 s after losing all satellites (NMEA GGA quality 6). The trust evaluator TRUSTS them
  and replay truth includes them.
- Without GNSS and compass the heading now comes from the heading bank (D-032) after ~100–170 s of
  driving; before that the output is the network fixes. After hand-over the calibration is optimistic
  when network fixes are correlated or wrong (R-007 GNSS absent: within95 0.79).
- Real-drive numbers before 2026-09-28 for gyro-only / phone-only / phone+network include the
  recorded OBD (D-033).
- **Hand-held phone (D-073):** detected after ~1 min of driving from the tilt rate; the accelerometer speed then helps only
  briefly after anchors, and the speed is often wrong by 5–10 m/s; without GNSS the radius is honest at 95 % (D-075) but
  within68 is 0.48–0.72 with D-076 on the two hand-held drives. D-076 admits heading error during non-yaw phone motion,
  but recovery takes multiple fixes, pure yaw phone rotations remain invisible, and 140822 has a new two-tick GPS
  recovery error of 391–408 m (R-032). The simulated holder cannot validate hand movement or that regression.
- **Stop/departure flicker is unresolved in defaults (R-034/R-035).** D-078 bridges unknown-axis stops locally,
  but regresses later outages and is disabled by default; quietness alone cannot distinguish a stop from smooth cruising.
  D-079 retains a conditional stopped trajectory and improves the light segment without GNSS/OBD, but has
  overly broad radii and remaining regressions (R-037). D-080 removes the two demonstrated 190523 pull-away
  delays (R-038), but requires a usable axis/bias and stable mount; jammed 085946's +0.60 m p95 remains.
  Its ordinary branch still uses strict ZUPT;
  this is not an exhaustive mutually exclusive motion-mode filter. Both experiments remain default-null.
  D-081 retains an exclusive moving path and compares motion legs (R-039), but has new missed-stop and
  accuracy regressions; `trajectoryScoring` is false by default. Fewer false stops alone do not establish
  stability. Its 5/10-baseline cost without/with roads has not been profiled on a phone.
  D-082 fixes selected-stop/departure reinitialization and expired-zero traps (R-040), without p95/max
  regressions versus D-081, but missed-stop ticks/travel still worsen on some holder runs. It does not
  remove all D-081 regressions versus D-080 or establish stable mode detection; `motionTransitions=false`.
  All of D-078…D-082 is on branch `experiment/stop-motion`, not in main (D-083).
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

### 2026-10-05 — Fused echo of our mock is not evidence (D-086, R-042)
- `DefaultTrustEvaluator`: FUSED fixes are UNAVAILABLE (`ECHO_OF_OUR_OUTPUT`, UI text en/uk) while a platform provider is
  overridden and 10 s after a restore; no FUSED history; `sourceState(FUSED)` UNAVAILABLE.
- Verified: `FusedEchoTest` (miniature of drive 20261005-104833; fails with the guard disabled), all JVM tests, Android
  build and lint; replay R-042. Not run on a device: whether Fused also echoes during a probe window's first seconds
  (the 10-s grace is a guess) is unmeasured.

### 2026-10-05 — Drive 20261005-104833: a wrong first network fix held the estimate (D-085, R-041)
- First drive recorded in MOCK_OUTPUT by 0.1.7+f2f41b8 (Pixel 8, OBD, 13 min, platform `gps` mocked the whole drive,
  no real GNSS fix). Analysis: the first network fix was ≈1 km off, the second ≈190 m; OBD only from 12 s. The correct
  third fix was rejected by the coarse-odometry check on the word of the second; error ≈950 m at 92 s. The EKF itself
  did move fully to every accepted fix (≤ 45 m); between fixes it stands still while the heading is unknown (until 144 s).
  Current main reproduced the live decisions exactly.
- D-085: unconfirmed voters only dispute (`COARSE_ODOMETRY_DISPUTED`, QUESTIONABLE, UI text in en/uk).
- Verified: miniature of the real case (`CoarseOdometryTest`, fails with the old rule via the config flag); three older
  tests rewritten (they asserted rejection by a single unconfirmed reference) with confirmed references added where the
  rejection is the point; all JVM tests, Android build and lint. Replay R-041. Not run in a car.
- Also found: Google Fused echoes our mock `gps` back as non-mock `fused` fixes (all 763 identical to our estimate, 81
  TRUSTED). The EKF ignores FUSED, but trust state uses it; left for a separate change.
- Map: local `recordings/20261005-104833-network-map.html` (network fixes, fix-pair distance vs OBD, live vs replay).

### 2026-10-05 — CI artifacts without zip (D-084)
`android.yml` uploads the debug APK and the release AAB with `actions/upload-artifact@v7` and `archive: false`, so
they download as the `.apk`/`.aab` file itself. Test/lint reports stay a zip (several directories). Verified: the
input exists in v7.0.1 `action.yml`; the workflow run itself is checked after push.

### 2026-10-05 — D-077 in main, stop/motion experiment parked on a branch (D-083)
- Committed the full working tree (D-077…D-082) as `experiment/stop-motion`. Main carries only D-077
  (`RoadHeadingConsensus`, uncertain-speed road heading), `ticks.csv` hypothesis columns and
  `tools/plot/compare_motion.py`. D-077 counts its updates in `uncertainHeadingStats`, not `roadStats`.
- Verified: core + recording tests exit 0. Default replay output of main and of the branch (flags off) compared
  file by file (`summary.json`, `ticks.csv`, `trust.csv`): synthetic seed-1 matrix, all 471 files identical; six
  recordings × 13 scenarios with `--roads`, 504 runs completed in both before a 30-min job limit (87 with roads),
  all identical. The remaining real runs were not compared. No car/device validation (unchanged).

### 2026-10-05 — Conditional reinitialization and pending departure (D-082, R-040)

- Added opt-in transition from selected stop to a fresh possible moving leg at that pose, rather than
  resuming the never-stopped trajectory. Retain broad velocity and historical position uncertainty,
  independent moving-bias calibration and the stopped origin even without strict IMU ZUPT. Distinguish
  axis availability from usable bias; preserve pending departure when fresh zero initially vetoes the
  fallback. A new displaced NETWORK pair can restart broad motion. No raw acceleration odometry.
- Ten miniature tests include 103211/190523/103343 stop/departure geometry, readiness, conditional bias,
  fresh zero, state restore and query purity. Five corrected rules were individually disabled and their
  reproductions failed. Final 188 core +6 recording tests, Android debug assembly/lint all exit 0.
- Six drives ×13 scenarios: no p95 or whole-drive max increase versus D-081. Simulation three seeds
  ×nine paired rungs ×13: all 351 summaries identical (339 scored, 12 unscored). All 429 `trust.csv`
  pairs byte-identical; disabled transition reproduces 78/78 saved D-081 summaries. Real variants
  exclude FUSED/OBD and use identical recorded NETWORK/road tiles; jammed truth is explicitly reconstructed.
- 153540 absent p95 127.43→118.49 m; original 136–200-s segment near-zero ticks 35→37/64, predicted
  travel 213.60→195.59 m. 103211 drop_10min p95 138.77→131.49; local 303.54-s error 244.80→147.55 m.
  The latter still exceeds D-080 119.86 m. 190523 departure trap at 126–144 s was removed from the
  intermediate prototype; final false-stop ticks 16→16 and p95 113→113 m in drop_10min.
- Stop detection remains incomplete: 085946 absent missed-stop ticks 153→99, but 103211 103→104,
  103343 80→85; predicted stop travel worsens there. Synthetic identity means D-081's earlier spoof/
  outage regressions are still present. Keep default-null/off; no stable detector or global gain claim.
  Updated 153540 v8 and 103211 D-082 map, preserving causal history and reference-source labels.
  Reports/configs/source hashes, negative proofs and rejected intermediate runs: ignored
  `recordings/analysis/d082/`. Nothing committed; no car/device or on-phone cost validation.

### 2026-10-04 — Exclusive moving path, trajectory comparison and stop/departure evaluation (D-081, R-039)

- Built an opt-in moving branch that survives quiet-IMU ZUPT, alongside the stopped path. Accepted NETWORK
  fixes compare both predicted motion legs before fusion. Common coarse-position offset cancels; separate
  conservative speed/heading leg-error bounds avoid fake precision. Cached exact endpoints cannot renew
  mode evidence. A broad moving likelihood component prevents wrong heading from declaring displacement
  a stop. This is a prototype, not a complete IMM or calibrated mode probability.
- A separate D-080 control preserves source trust and motion/up-learning feedback. All 429 paired
  `trust.csv` files are byte-identical; reproduction matches 78/78 saved D-080 summaries. Defaults unchanged.
- Added nine CI miniatures: quiet cruising, pre-fix fit, covariance, common offset, wrong heading, cached/
  synthetic fixes, outliers, rollback/query purity and control feedback. Quiet-cruising test demonstrably
  fails with the flag false. Final 178 core +6 recording tests pass; Android debug assembly/lint exit 0.
  Reusable `tools/plot/compare_motion.py` evaluates false/missed stops and departure release against
  recorded OBD excluded from estimator inputs, or sparse clean GNSS truth where available.
- Compared six drives ×13 scenarios and three synthetic seeds ×nine paired rungs ×13 scenarios.
  Final candidate has 23/78 real and 36/339 truth-scored simulated p95 regressions (>0.01 m), with
  12 further synthetic pairs unscored. 153540 absent p95 110.83 →127.43 m; 103211 drop_10min
  130.72 →138.77; 085946 109.68 →118.64 against reconstructed truth. 190523 absent false-stop
  ticks improve 98 →19, but missed stops worsen 16 →33; 103211 missed stops 17 →103.
- Rollout rejected: `trajectoryScoring=false`, `stopMotion=null` remain defaults. Stop/departure persistence,
  reinitialization, cached-fix observability and calibration remain open. No car/device validation or phone
  performance measurement. Updated 153540 v7 and 103211 regression map; reports/configs/source hashes,
  negative proof and intermediate rejected runs under ignored `recordings/analysis/d081/`. Nothing committed.

### 2026-10-04 — Past stop evidence invalidated by a bounded departure pulse (D-080, R-038)

- Diagnosed 190523 replay 475–487 / 547–569 s: the holder had a usable forward axis and bounded bias,
  with sustained ~0.6–1.4 m/s² acceleration. Nearby NETWORK pairs described the previous stop but held
  the conditional stopped output through the subsequent acceleration. This was a temporal evidence error.
- Opt-in motion alternatives now invalidate old positive stop evidence after 0.5 s of usable forward
  residual >0.5 m/s² and tilt RMS <0.07. A later-arriving pair that began before the pulse cannot reassert
  a stop. No acceleration-to-speed/distance integration; selected coordinates come from the existing branch.
- Five new miniature tests cover holder departure, straddling/later fix pairs, bumps/hand motion and
  snapshot/restore. Pre-change stop-evidence miniature fails; final 169 core +6 recording tests pass.
  Android debug assembly/lint both exit 0. No device/car validation.
- All six drives ×13 scenarios and three synthetic seeds ×nine paired rungs ×13 scenarios measured:
  no p95 or whole-drive maximum increase versus D-079 where truth exists (78 real /339 simulated pairs;
  12 further simulated pairs have no truth-scored output); all trust
  summaries identical. D-079 reproduction (`departureHoldS=null`) matches all 78 saved summaries.
  190523 drop_10min p95 120.70 →113.00 m; errors at 487/569 s 45.89 →9.77 /161.42 →45.63 m.
  153540 light interval unchanged: 37/64 near-zero ticks, estimated travel 193.53 m without GNSS/OBD.
- Kept default `stopMotion=null`: absence of a reliable axis, slow creep, cached NETWORK, wide ambiguity
  radii and nonexclusive ordinary ZUPT remain unresolved. Maps v6/190523 D-080, exact configs, source hashes,
  reproduction, negative proof and reports are under ignored `recordings/analysis/d080/`.

### 2026-10-04 — Retained stopped trajectory and selected-branch output (D-079, R-037)

- Built opt-in stop alternative beside ordinary navigation, bounded IMU/coarse-displacement support,
  selection hysteresis, two output hypotheses and uncertainty around the selected branch. A later switch
  uses the already propagated alternative's coordinate; historical metrics/publications stay causal.
- No GNSS/OBD requirement or learned-axis requirement. Without GNSS/OBD, 153540 session 136–200 s has
  near-zero speed on 37/64 ticks versus 13; predicted speed integral 370 →194 m. Whole-drive p95/max unchanged.
- Separated source-trust prediction from display ambiguity after a first prototype weakened spoof detection;
  final trust summaries are identical in all 78 real and 351 synthetic pairs. Ambiguous speed cannot teach up.
- Final six-drive matrix: most accuracy unchanged; 190523 10-min outage p95 113 →121 m, jammed 085946
  109.08 →109.68 m. Within68 153540 absent 0.48 →0.98 is too pessimistic, not ideal calibration.
  Keep `stopMotion=null` in app/default replay. Seventeen new miniatures, total 164 core +6 recording
  tests and Android assembly/lint exit 0; disabled prototype fails stop miniature (0 expected, 8 actual).
  All 78 default summaries match saved D-077; three simulated seeds' worst p95 increase <0.000001 m.
- Local map v5 shows chosen trajectory and both branch positions at the slider time, with absent-GNSS
  as default and the stop interval selected. Evidence/config/hash/negative proof/report under
  `recordings/analysis/d079/`. No car validation, no global accuracy improvement claim.

### 2026-10-04 — Holder stops with OBD removed (R-036)
- Checked all four holder recordings through the current/default pipeline (D-078 disabled), recorded NETWORK,
  no FUSED/OBD. Recorded `obd:elm327` speed is reference only. On 103211/190523 also compared GNSS present/absent.
- Same failure exists: during stable OBD stops the no-GNSS runs assign 8 m/s 8/2/1/1 times on
  103211/190523/085946/103343. Estimate exceeds 3 m/s for 6.3/4.0/18.4/17.7 s of scored stops respectively.
  With recorded GNSS the first two have only 4.4/0.9 s above 3 m/s, though detector flicker remains.
- Before axis learning, loose stops are unavailable; after learning, holder vibration and a nominal speed ≥3
  can still block them. A mount alone does not solve stop/departure inference. No algorithm/default changed.
- Verified observer matches all 7342 overlapping saved replay tick positions/speeds. Reference uses OBD <0.5
  for ≥8 s, trimming 2 s at each edge. Detailed local report `recordings/analysis/d078/holders/report.md`.

### 2026-10-04 — Stop/departure experiment tested and left disabled (D-078, R-035)
- Built stop continuation without a forward axis, bounded 1.5-s gap holding, and immediate release on measured
  moving speed/available acceleration. Held gaps do not teach biases or apply zero-speed measurements.
- R-034 interval recognizes stops for 25.7/64.0 s vs 12.0, exits 7 vs 12. Removing the moving-car prior altogether
  trapped departures; extending holds to expired anchors with known axes also regressed. Retained narrow opt-in.
- Final six-recording/13-scenario comparison finds regressions: 153540 absent p95 111 → 178 m; 140822 absent
  407 → 418 m; 103211 consistent spoof ramp 349 → 400 m; jammed 085946 109 → 123 m. Default remains `stopHoldS=null`.
- Verified: 147 core + 6 recording tests, Android debug assembly/lint, exit 0. Ten stop miniatures cover cruising,
  pull-away, GNSS/OBD evidence, gyro bias and rollback. Disabling the candidate makes the light-stop test fail.
  All 78 real default/null summaries match the saved D-077 candidate exactly (variant name excluded); 117
  synthetic pairs also measured for the opt-in candidate. No car verification or accepted global improvement.
- Updated local `20261003-153540-map-v4.html`: current vs experimental, traffic-light button and later regression
  button, three scenarios, explicit disabled banner. Saved reproducible evidence under `recordings/analysis/d078/`.

### 2026-10-04 — Diagnosed intermittent stops on 153540, session 136–200 s (R-034)
- Diagnostic replay of the current D-077 candidate found 12.0 s of recognized stops out of 64.0 s and 12
  `stationary → moving` transitions that assign the unmeasured pull-away prior of 8 m/s. No estimator change.
- Forward axis is unknown until session 189.287 s: `accelReady` is false, which disables loose ZUPT despite
  8.18 s of `stillLoose` evidence. The strict rule flickers under hand motion/vibration. GNSS at 176.254 s
  reports 0.749 m/s; the estimator accepts it, but after another detector interruption resets to 8 m/s again.
- Verified with a Java observer around the existing estimator through the same `MeasurementPipeline`, using
  recorded NETWORK, dropping FUSED/OBD, roads enabled. All 199 overlapping tick positions/speeds match the saved
  clean replay exactly. Local evidence: `recordings/analysis/d077/stop-136-200/`. No production source changes
  or new driving verification; sparse fixes do not prove complete standstill throughout the user's interval.

### 2026-10-04 — Road-axis heading without OBD or reliable odometry (D-077)
- Built `RoadHeadingConsensus`: nearby parallel roads can provide a shared travel axis without choosing a road
  identity. Scores use the road-free twin's pose ellipse and heading uncertainty; competing axes and an off-road
  alternative reduce support. No HMM odometry/confident-distance requirement for this separate cue.
- Apply only after a recent accepted position anchor and quiet motion; correct heading locally, leaving speed,
  biases and position untouched at the observation. Existing reliable-speed position/turn gates remain.
- Verified: 137 core + 6 recording tests, Android debug build and lint (exit 0). `RoadWithoutSpeedTest` has 11
  cases; correction fails with the cue disabled. Four real drives × three scenarios × three variants; two bad-map
  comparisons; full standard simulation and 13 synthetic road scenarios without/with OBD (R-033).
- Visualization follow-up: `recordings/20261003-140822-map-v3.html`, six conditions, per-tick GPS error and
  a shortcut to the worst spoof-regression tick (session 298.8 s, error 19.6 → 25.1 m). Replay/session shift
  +33.200184324 s verified from SQLite against `trust.csv`; original GPS remains a visual reference under spoofing.
- Uncertain: no useful correction of the original 153540 detour; essentially unchanged real p95. A modest 190523
  median improvement is insufficient to validate full road localization without OBD. No driving validation on device.
  Local evidence retained under `recordings/analysis/d077/`; joint road/speed hypotheses remain in the roadmap.
- Follow-up (same date): updated and browser-checked `recordings/20261003-153540-map-v3.html`, with before/after/free
  layers, three scenario choices and a largest-change shortcut (4.6 m separation at session 125.1 s). Extended
  comparison to all six recordings and all 13 scenarios: 140822 has three p95 regressions under injected spoofing,
  worst 37.7 → 41.9 m (+11.4%). Two originally jammed recordings improve against reconstructed OSM/OBD truth;
  this evidence is not independent of the map. Details in R-033 below; no algorithm retuning in this follow-up.

### 2026-10-04 — Hand movement contributes heading process noise (D-076)
- Diagnosed the left detour on 20261003-153540: the gyro integrated phone movement as a vehicle turn, while its heading
  covariance stayed too narrow for correct network fixes to turn it back. Built `handYawNoise` 0.5 above a 0.2 rad/s
  tilt-rate floor, with snapshot state and `HandYawTest` (three cases). The gyro mean is retained.
- Verified: 126 core + 6 recording tests, Android debug assembly and lint (exit 0); the correction test fails with noise
  disabled (36.3° heading error vs the 25° bound). Four real-drive replay matrices, three simulation seeds (R-032).
  Simulation has 0 changed summary pairs out of 117 per seed. No validation in a car of these working-tree changes.
- Updated map: `recordings/20261003-153540-map-v2.html`, before = D-073 + D-075, after also D-076. Network and actual GPS
  are shown; the GPS colors describe recorded 0.1.6 trust, while the readout separately shows replay trust. Replay
  295.1 s is session/map 301.1 s. The original map is retained. Local replay evidence is under `recordings/analysis/d076/`.
- Limitations: the false turn initially persists, 153540 absent within68 is only 0.48, and 140822 regresses (clean max
  2.1 → 408 m; absent p95 380 → 407 m). Kept explicitly in the results and roadmap; pre-window medians are supplementary.

### 2026-10-04 — Drive 20261003-153540 analysed; wider speed walk in pure dead reckoning (D-075)
- Fourth real drive in the hand (16.5 min, no OBD, 11 probe windows, recorded with 0.1.6: only 13 of 61 real GPS fixes TRUSTED).
  D-073 alone made it worse without GNSS (within68 0.60 → 0.20): the fallback random-walk speed is overconfident in a city.
- Built: `speedRandomWalkDr` (1.5 m/s/√s when no GNSS fix of any trust for 5 s), `DrSpeedWalkTest`. Map with three versions:
  `recordings/20261003-153540-map.html`.
- Verified: `:core:test :recording:test` pass; four real drives and the simulated matrix (R-031). Not verified: in a car.
- Uncertain: the 295 s window of 153540 (estimate 166 m off at a claimed 80 m; speed 9 m/s at a true 4) is unexplained; within68
  there is 0.36. Only 7 short windows of truth on that drive.

### 2026-10-04 — Hand-held speed run-away and a trust lock-out fixed (D-073, D-074)
- Traced the remaining problems of drive 20261003-140822 (speed 67–73 m/s, 1919 m before a window, the 1459→1460 "teleport",
  "worse with GNSS") to one cause: a hand-held phone's accelerometer bias wanders faster than the model allowed, and centripetal
  speeds corrupted by tilt taught the wrong value. The teleport and the S-shaped detour were consequences of that speed.
- Built: unsteady-mount scale from `tiltRateRms` (bias walk ×k, centripetal tilt term), `UnsteadyMountTest`; trust run keeps its
  outage status (`runAfterOutage`), new case in `QuestionableResetTest` (fails without the fix). Checked whether coarse position
  corrections inflate the speed (user's question): ±1–2 m/s typical, ≤ 10 m/s; not limiting them is better (D-073).
- Verified: `:core:test :recording:test` pass; replay of the three real drives with truth (R-030); simulated 10-min matrix: 0
  cells changed vs the code before. Map: `recordings/20261003-140822-map-v2.html` (before/after, same conditions).
- Not verified: in a car; hand detection on other phones/holders (one hand-held drive only). Uncertain: speed in the hand is now
  often too low and within68 without GNSS fell 0.64 → 0.47 on that drive.

### 2026-10-04 — Up vector corrected quickly at stops (D-072)
- Diagnosed the speed run-up on the hand-held drive: tilt (up) error 4–15° at stops, long-term mean longitudinal acceleration ±1–2 m/s²;
  the forward axis itself drifted little. `MotionConfig.upStillTauS` 3 s with stop-rule bridging, speed hint from the pipeline, onset
  guard. `UpAtStopTest` (2 tests); `:core:test :recording:test` pass. Debug harness (`dbg/D.kt`) deleted.
- Not verified: in a car. Up still drifts between stops; long stop-free stretches are unaddressed (R-029).

### 2026-10-04 — Innovation gate rejects only beyond 5 km (D-071)
- `gateRejectMinDistM` 5000 m, `gateDowngraded` waits in the questionable-stream branch; test with the 1347 s case (1272 m, accepted
  after 10 s) and a 6 km case (rejected). `:core:test :recording:test` pass. Replay matrix in R-028. Not verified in a car.

### 2026-10-04 — GPS passthrough (D-070)
- `GnssProbeController` gets a PASSTHROUGH phase (2 recovered windows in a row → provider stays removed; ends on 5 s without a TRUSTED
  fix, unhealthy chip, hard rejection). App: `HOLD` action, annotations, hero card "Real GPS passed through" (UK/EN), "last passthrough
  ended" line. 6 new `GnssProbeTest` tests; `:core:test :recording:test :app:assembleDebug :app:lintDebug` pass.
- Not verified: in a car or on the device (what Waze does when `gps` goes from mock to real and back; behaviour at tunnel exits).
  Replay ignores probe annotations, so there is no before/after number.

### 2026-10-04 — Drive 20261003-140822: trust held back real GPS (D-069)
- Analysed the third real drive (Pixel 8 in hand, no OBD, MOCK_OUTPUT on `gps`, 37 min, 29 probe windows; map:
  `recordings/20261003-140822-map.html`). Found: our mock fixes polluted the GNSS trust history (see D-069, P35); the estimate was
  12–1272 m off before each window (median 87 m, 14/29 within the claimed radius); estimator speed ran up to 69 m/s (182 of 2185
  estimates > 35 m/s, real ≈ 17), not yet explained (hand-held phone, no OBD).
- Built: overridden/synthetic fixes no longer change evaluator state; `agreeWithEstimateM` rule; `AGREES_WITH_ESTIMATE` reason with
  UK/EN strings; `AgreementTrustTest` (5 tests: mock track 300 m ahead, 95 m innovation gate, 130 m still gated, recovery skip,
  network fix in another country still rejects, fresh OBD keeps the gate). `:core:test :recording:test` pass.
- Verified: replay of the real drive (R-027); simulated matrix (R-027). Not verified: in a car with the new build.
- Uncertain: the rule weakens slow-drift detection without OBD (R-027); speed run-up and overconfident covariance are open.

### 2026-10-03 — Launcher icon 20 % smaller (D-066)
- Group scale in `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` 0.82 → 0.656; Play icon and feature graphics regenerated (the feature graphic keeps the dog's size through
  `icon-feature.svg`).
- Verified: build and lint pass; the new icon in the emulator's launcher is clearly smaller with a wider margin; the 512 px icon and the feature graphic were viewed. **Not verified**: the
  Pixel 8 launcher, the themed (monochrome) icon, other masks.

### 2026-10-03 — Road tiles are refreshed after 90 days (D-065)
- `RoadTileFiles.isStale` (core, 3 tests), `RoadMapManager` downloads missing and stale tiles, writes through a temporary file and rebuilds the network after each.
- Verified: tests and lint pass; on the emulator a temporary one-minute threshold made the nearby tiles download again (new time, same size, no `.tmp`),
  distant tiles untouched, then the threshold was restored to 90 days (checked in the diff). **Not verified**: a real 90-day-old file, a failed refresh offline,
  the Pixel 8.

### 2026-10-03 — Route preview of recordings (D-064)
- `RoutePreview` (core, 6 tests: straight line, corner, jitter versus detour, length, cap, short input), `DriveReader.route()` (recording, 3 tests:
  estimate wins, fixes without our mock output and vague fixes, empty), `TripSummaries`, `RoadCache`, `RouteThumb`, `TripMapScreen`, shared map parts
  (`MapParts.kt`), strings en/uk. The Recordings rows now show the thumbnail, the duration and the length; a tap opens the route map.
- Verified: tests and lint pass; on the emulator, with one real recording and three synthetic ones, the list showed the thumbnails and stats (the
  record-only one dropped our mock fixes and the vague ones), the route map fitted each route and drew roads from the cached tiles with start/end markers
  and the attribution, system Back returned to the list, a long press still selected. Found and fixed on the way: the map painted over its header (no clip),
  a parked recording drew its dot in the corner. **Not verified**: a real long drive, a 100+ MB recording (time to read), the Pixel 8, TalkBack.

### 2026-10-02 — Better voice-over (D-068)
- Both videos re-voiced with Edge neural voice Ostap (`EDGE_TTS=<venv>/bin/edge-tts node docs/visualization/make-audio.js [formal]`). Lengths now ≈ 18 min (popular) and ≈ 21 min (formal). Verified only that the files load and play; no listening test.

### 2026-10-02 — Formal math video (D-067)
- `docs/visualization/formal.html`: 12 scenes, ~19.5 min, Ukrainian voice-over (Lesya): problem statement (state, Bayes recursion, EKF);
  sensor signals and PSD (real FFT: white noise vs random walk, crossover ≈ 0.036 Hz); first-order filters (H(s), H(z), Bode, the six
  time constants in the code); the up vector as a complementary filter; EKF prediction (F, Q, growth laws t^1.5 and t², Monte Carlo
  vs the covariance recursion); the update (K, Joseph form, NIS ~ χ², gates 9.21/13.8/25/50); observability, ZUPT and the local
  (Schmidt) update; robust coarse updates and the odometry chord (rotation invariance, acceptance annulus); compass harmonics (DC +
  first harmonic) and Kåsa/ellipse least squares; heading bank (Gaussian sum, log-sum-exp, circular mean); road HMM (forward
  recursion, emission, transition matrix, pseudo-measurement and its skip rule); calibration (k₆₈ = 1.5096, k₉₅ = 2.4477, coverage vs
  overconfidence factor).
- Verified: every scene loaded in a browser (no console errors except the favicon 404); numbers computed in the scenes were
  checked (σ_b after 120 ZUPT updates 2.7·10⁻⁴; crossover 0.036 Hz; coverage 25% at f = 2 and 99% at f = 0.5; bank weight collapse;
  HMM α before/after the turn). Not checked: Safari/Firefox, a listening test of the pronunciation, offline use (needs the CDNs).
- Fixed in the popular video while doing this: the compass scene claimed the raw compass is wrong "up to 30°" while the model gave
  83°; the model now gives ~38° and the text says 40°.

### 2026-10-02 — Explanatory video: "How the phone finds itself without GPS" (D-060)
- Built `docs/visualization/` (open `index.html`; or `python3 -m http.server --directory docs/visualization`): a ~16-minute
  linear video in Ukrainian, 10 scenes: GPS can be fooled → a cloud, not a point → counting steps (gyro + speed) → fusing
  two witnesses (Kalman, 1-D and 2-D) → trusting GNSS (the checks, and the slow-drift blind spot) → network fixes and the
  odometry check → heading without GPS (compass ellipse, heading bank) → road map (matcher probabilities, corner fix,
  off-road) → the ladder of improvements on one drive → what we measured on real drives and what we do not know.
  Controls: play/pause, scrub with scene marks, speed, voice-over on/off, full screen, keys.
  Voice-over: Lesya (macOS), pre-rendered by `make-audio.js` into `audio/`; the first version used browser speech synthesis and
  read Ukrainian with an English accent (no Ukrainian voice in the browser), so it was replaced.
- Verified: every scene loaded in a browser (screenshots at several times, no console errors); the narration pace was
  checked by script (≤ 15.5 characters per second of subtitle); the engine's ladder on the synthetic drive (post-jam, p95):
  hold-last-fix 668 m → gyro only 151 → + OBD 102 → + network 50 → + road 34. These come from a simplified teaching model on a
  synthetic town and are **not** results of the real algorithm; the real numbers shown on the last scene are copied from R-011 and R-020.
- Uncertain: the audio was checked only for loading and playing (no listening test of the pronunciation of abbreviations such as
  OBD or GPS, which `make-audio.js` respells); not checked on Safari/Firefox; the text claims must be re-read when the algorithm changes.

### 2026-10-02 — Play graphics (D-063)
- `docs/play/assets/`: `icon-512.png`, `feature-graphic-{en,uk}.png` (1024×500), and 4 screenshots per language (1080×2160), plus the generator
  (`tools/play-assets/make_icon_svg.py`), `feature-graphic.html` and a README with the steps.
- Verified: sizes and formats checked (PNG, exact dimensions, 50 KB to 230 KB each); every image viewed (the first icon crop was too tight and the first
  feature graphic wrapped the title, both fixed); the screenshots show the clean demo status bar. **Not verified**: Play Console's own checks on upload;
  the screenshots are from the emulator, with simulated GNSS.

### 2026-10-02 — Release build: R8, upload-key signing, AAB, bounded wake lock (D-062)
- `release` build type: R8 and resource shrinking, signing from `GPES_UPLOAD_*` (unsigned without them), `proguard-rules.pro` (empty on purpose);
  CI builds `bundleRelease`, signs it when the secrets exist, uploads the AAB and `mapping.txt`; wake lock 10 min, renewed every 5 min;
  fix: a recording begun when spoofing starts has the right mode in its header.
- Verified locally with a throwaway key (scratchpad, not committed): `assembleRelease` and `bundleRelease` succeed, no R8 warnings; the release APK is 2.9 MB
  (debug about 11 MB), the AAB 3.6 MB and `jarsigner -verify` accepts it; on the emulator the release APK ran tracking, spoofing with recording
  (a 1.1 MB `.db`, header `MOCK_OUTPUT`, `["GPS"]`, no crash in the log) and the map. Then the upload key was created and put into the four CI
  secrets, the work was pushed, CI went green and built `gpes-patron-0.1.4-e1f6fd7-240-signed.aab`, whose certificate matches the key's fingerprint (D-062).
  **Not verified**: a Play upload, the wake lock over a long drive, the Pixel 8.

### 2026-10-02 — Documents for Google Play, GitHub Pages, OpenStreetMap attribution (D-061)
- `site/` (privacy policy, terms of use, home; English and Ukrainian on each page), `.github/workflows/pages.yml`, `docs/play/` (store
  listing in both languages, Data safety answers, foreground service declaration and video script, release checklist, Pages note).
- App: Settings → About links to the policy and the terms (per language), and "© OpenStreetMap contributors" on the map and in About.
- Verified: the pages parse (balanced tags) and read correctly in both languages; the policy was checked against the code and the manifest
  (permissions, the single network call, optional recording); build and lint pass; on the emulator the About links and the map attribution show.
  Deployed 2026-10-02 (13 commits pushed to `main`): the Pages workflow succeeded and `https://dreamescaper.github.io/gpes-patron/`,
  `/privacy.html`, `/terms.html` return 200; the Android workflow also succeeded on the same push, and named the APK
  `gpes-patron-main-0.1.2-05321b1-238.apk` (the versioning of D-059 works in CI). **Not verified**: a Play Console review, the video
  (not recorded), the legal wording (not a lawyer's work).
- Open: a release build as an Android App Bundle, the wake-lock limit, the rights to the name and icon (`docs/play/release-checklist.md`).

### 2026-10-02 — Automatic versioning (D-059)
- `version.json` + git in `app/build.gradle.kts`: `versionName` `0.1.<height>+<sha>[.dirty]`, `versionCode` 200 + commit
  count; `:app:printVersion`; CI with full history and APK names from the version, `-PversionCode` removed.
- Verified locally: `printVersion` gave `0.1.0+52a5163.dirty (235)` before committing and `0.1.0+e9037e0 (236)` after;
  the debug APK installed over the emulator's older build (code 112 → 236) and Settings shows `GPES Patron 0.1.0+e9037e0`;
  a shallow clone with `CI=1` fails the build with the explanatory message (without `CI` it silently gives a wrong code,
  201). CI run (2026-10-02, after the push): green, APK `gpes-patron-main-0.1.2-05321b1-238.apk`, height 2 as expected.


### 2026-10-02 — Estimation before spoofing; stale mock cleanup (D-058)
- Tracking (estimate only) starts by itself on the Drive and Map tabs and stops with them; the big button turns spoofing
  on in the running session (`ACTION_SPOOF_ON`, `Session.startSpoof`, recording begins there if enabled); developer
  selector reduced to spoof / record-only; settings are locked only while spoofing or recording; the verdict card no
  longer says "no GPS" or lists our own mock when `gps` is replaced and the probe is off.
- Found and fixed: a force-stopped app leaves the mock `gps` provider installed (frozen position for every app).
  `cleanupStale` removes it at the next start and shows a message (dev-guide P34).
- Verified on the emulator: tracking starts on opening the app (`gps` real), spoofing on in the same session (timer, track
  and estimate kept, `gps [mock]`), stop returns to tracking, Settings tab and the background stop tracking, force-stop while
  spoofing leaves the mock and the relaunch removes it. Build and lint pass. **Not verified**: the Pixel 8 (not connected),
  battery cost of tracking with the screen on, recording started mid-session, other Android versions.

### 2026-10-02 — New icon artwork (D-057)
- Replaced the icon with the user's updated set (see D-057): drawables `ic_launcher_*`, `mipmap-anydpi-v26`
  (`ic_launcher`, `ic_launcher_round`), manifest `roundIcon`. Verified: build and lint pass; on the emulator's
  launcher the new icon sits inside the round mask. Not verified: Pixel 8, themed icon, other masks.

### 2026-10-02 — Recordings list: compact rows, multi-select, no export (D-056)
- Removed the in-app "Export JSONL + GnssLogger" (`DriveStorage.export`, its strings). One compact row per recording;
  long press selects, tap toggles, batch Share and Delete (confirmation with count and total size, Ukrainian plural
  forms), All, Cancel, Back leaves selecting.
- Verified on the emulator (copies of one recording): selection by long press and tap, the header, the delete dialog
  ("Видалити 2 записи?", 4.1 MB), deletion of two files (disk and list updated), the system chooser "Sharing 2 files".
  Build and lint pass. **Not verified**: on the Pixel 8, sharing a 100+ MB file to a real target, TalkBack (the row's
  long press has no custom action label).
- Not done: smaller recording files (IMU about 800 Hz is most of the 4 MB per minute); a size and free-space indicator.

### 2026-10-02 — App icon (D-055)
- Adaptive icon from the user's `gpes_patron_android_icon` set: dog-face pin on blue, foreground and monochrome
  layers scaled to the safe zone (0.82), `icon` and `roundIcon` in the manifest, source SVG in `docs/assets`.
- Verified: build and lint pass; on the emulator's launcher the whole pin sits inside the round mask. **Not
  verified**: the Pixel 8 launcher (the phone was not connected), the themed (monochrome) icon, other masks.

### 2026-10-02 — Recording and start modes made developer options (D-053)
- Main screen: only "turn on spoofing". Recording is optional and off by default; developer mode adds Diagnostics,
  record-only / estimate-only modes, marks and the QUESTIONABLE toggle. Tabs are dynamic (2–4).
- `Recorder` in the service (`FileRecorder` / `NoRecorder`), `Status.recording`, new `record` extra.
- Verified on the emulator (clean install): two tabs, no files created while spoofing with recording off; with
  recording on, a `.db` is created, the "Recordings" tab and the marks appear. Build and lint pass. Pixel 8 not
  checked after this change (the phone was locked); the 15 existing recordings stay installed.
- Not done: a "send to the developer" flow, free-space check, clean-up of old recordings.

### 2026-10-02 — First run of the GNSS probe on the Pixel 8 (D-052)
- Pixel 8 (SDK 37), indoors and stationary, spoof mode with `gps` as the target, ≈ 3 min.
  Chip healthy (7–13 satellites in the fix after a ~20 s cold start). Windows opened, the real GPS returned its first fix
  1.1–3.1 s later and trust accepted it within 2–6 s: three `gps_probe_recovered` in a row. A first 70 s session with
  the 4 s no-fix abort ended in `gps_probe_failed` (no fix yet), hence the 8 s limit; the C/N0-spread threshold was
  relaxed to 1.0 dB over the last 5 statuses (`GnssProbeTest`, case from this session in miniature). A third
  session with an 8 s abort again failed its first window (32–40 s): NMEA and the status kept flowing, the chip had a
  fix, but no `Location` came, so the abort became 12 s. In total 3 of 5 windows recovered.
- The device keeps the old install's recorded drives (installed over it with a higher versionCode).
- Waze with spoof mode on (`gps` target) keeps guiding (reported by the user, 2026-10-02; not observed by us: the
  moment relative to a probe window, whether the phone was moving and for how long are unknown).
- **Not verified**: Waze exactly during a window with no `Location` yet, driving, a real spoofed or jammed signal,
  hand-over (roadmap P3).

### 2026-10-02 — Spoofing the platform GPS (Waze) and the GNSS recovery probe (D-052)
- Spoof mode now targets `gps` by default; Fused is optional. New setting "Check whether GPS is back" (default on).
- `GnssProbeController` (core, pure) + `MockLocationPublisher.suspend/resume`, wired in `DriveService` through a
  1 s handler tick; `Status.probe`; Drive tab: "GPS chip" evidence chip, hero "Tracking by sensors" / "Checking real
  GPS…", last-check line; annotations `gps_probe_*`.
- Verified: `GnssProbeTest` (9 tests: healthy/uniform/few satellites/stale status, no fix, hard rejection, ambiguous
  rejection until the limit, recovery keeps the base interval, ignored foreign assessments); build and lint pass;
  emulator plumbing with a temporary debug hook (removed): window open → real `gps` provider, a real fix delivered to the app
  and Fused, trust evaluated it, close → `[mock]` again, no crash. **Not verified**: the probe's own trigger on a device
  (the emulator has no satellites), a real GNSS chip after `removeTestProvider`, how Waze behaves during a window
  or on a spoofed signal, thresholds. Real-drive cases have to be turned into tests after the first drive.

### 2026-10-02 — App UI redesign and "turn off" notification (D-051)
- Four tabs, driver screen with a single GNSS verdict, evidence chips, plain-language trust reasons, pinned Stop,
  trips list with date titles and delete (confirm dialog; removes WAL/SHM too), Diagnostics (old raw panel),
  Settings (persisted `AppSettings`), light/dark theme, edge-to-edge. English and Ukrainian strings.
- Mock output: dedicated notification channel, "Turn off" action, text updated each second only when it changes,
  re-posted if swiped away.
- Verified: `:app:assembleDebug :app:lintDebug` pass (exit 0); emulator (API 37, uk locale): modes, Spoof-mode start,
  hero/chips/reasons, pinned Stop, notification with the action stops the service (no ServiceRecord left),
  trips and settings layouts, English locale and dark mode (Trips, Drive idle). **Not verified**: real car; swipe-away re-post and lock-screen visibility; dark Drive-running state
  TalkBack; Android 12 and older.
- Known gaps: no map yet, no onboarding wizard, no per-trip duration or mini-track in Trips, small icon is the
  system placeholder, no app icon.

### 2026-10-02 — Speed without OBD (D-050, R-026)
- `AccelSpeedConfig` + bias state in `BaselineDrEstimator`; `MotionUpdate.longitudinalAccel`, `lateralAccel`,
  `stillLoose`; motion-tracker fixes (forward axis from smoothed signals, up gates).
  `AccelSpeedTest` (no harm in the simulator), `HeadingBankTest` pinned to the no-speed case. All tests pass. Road
  tiles kept under `recordings/tiles` (local).

### 2026-10-02 — Speed without OBD: signal study (R-025), up-gate fix
- `MotionTracker`: the up-correction gate uses the yaw rate low-passed over 0.5 s (`upGateYawTauS`), not the
  raw gyro sample, which vibration keeps above 0.05 rad/s. Real drives: neutral (×0.99–1.01; A +osm ×0.94).
- Signal study for D-036 in R-025; no estimator change yet. Tests pass.

### 2026-10-02 — Road map in the app, first run on the emulator
- Emulator (Medium_Phone, position fixed at Kyiv centre, ESTIMATE_ONLY, fuse QUESTIONABLE on): tiles
  downloaded from overpass-api.de (≈ 100–250 KB each), network built (4 tiles, 17 928 segments), status
  line shown; the recording's `config_json.roads` is true. Overpass answered HTTP 504 for several tiles
  (overloaded server); they are retried after 5 min as designed.
- Fixed: the network was built only after the whole download loop, so with a slow server there were no
  roads for minutes although near tiles were on disk. `RoadMapManager` now builds from the tiles on disk
  first and again after each downloaded tile.
- Not verified on the emulator: the matcher itself (emulator GNSS has no course and the IMU is static, so
  the heading stays unknown); replay covers it. Next: a real drive.

### 2026-10-02 — Corner fix with significance test, on (D-049, R-024)
- `RoadConstraintConfig.cornerMinSigmas` 2.0, `cornerFix` true. `CornerFixTest` (2). All tests pass.

### 2026-10-02 — Road confidence dips (P1, D-048, R-023)
- `RoadMatcherConfig.dipMaxSteps`, `dipMinProb`, `dipNeedsTurnDeg` (default off). Tests pass.

### 2026-10-02 — M4 on gentle bends (D-047, R-022)
- `AlongTrackMatch`: ambiguity by local minima; `RoadConstraintConfig.alongMinTurnDeg` 12°.
- Test `a gentle 15 degree bend still gives the shift, with a wider sigma`. All tests pass.

### 2026-10-02 — Re-timed jammed truths, OBD latency and corner-fix experiments (D-046, R-021)
- `tools/truth/align_turns.py` (new); both jammed truths re-timed (local files).
- `BaselineConfig.obdLatencyS`, `RoadConstraintConfig.cornerFix` added, both off; tests pass.

### 2026-10-02 — Matcher vs parallel carriageways (D-045, R-020b)
- `road_right` in `ticks.csv` is now geometric (matched segment, its way or a connected segment of
  similar bearing within 10 m of truth), not by street name, which a side carriageway often shares.
- `minorRoadPenalty` 0.3 (default); `serviceRoadPenalty`, `holdOffRoadFixM` (off) kept for experiments.
- Tests pass; full regression below.

### 2026-10-01 — Road constraint M6: app
- `RoadMapManager`: on each estimate (or network fix before the first estimate), at most every 1 km,
  downloads missing tiles within 6 km from the Overpass API (POST, User-Agent set, retry after 5 min) and
  rebuilds the network from tiles within 12 km on a background thread; the estimator reads it through
  `BaselineDrEstimator(roads = …)` and restarts the matcher when it changes.
- UI: "Road map (OpenStreetMap)" toggle (default on, persisted), the area-not-route privacy note, a status
  line (tiles, segments, downloads, last error) and the road state (street, probability, confident
  distance, or off-road). Strings in English and Ukrainian. INTERNET permission. `config_json.roads`.
- Verified: build and lint pass. **Not run on a device or emulator**: the download path is untested
  against the live Overpass server; replay covers the estimator side.

### 2026-10-01 — Road constraint M0–M5 (D-041…D-044)
- M0 `core/road`: `OverpassImport`, `OsmWay`, `RoadNetwork` (split at shared nodes, successors, grid index,
  projection), `RoadTile` grid + Overpass query, `RoadTileCodec`, `RoadTileFiles`; CLI `replay roads`.
- M1 `RoadMatcher` (HMM with OFF-ROAD) inside `BaselineDrEstimator`, on a road-free twin (D-042);
  `PositionEstimate.road` filled; `ticks.csv` road columns.
- M2/M3 heading and cross-track road updates; M4 `AlongTrackMatch`; honest radius from the twin (D-043, D-044).
- M5 `RoadEdit` (`remove_roads_along_truth`, `shift_roads`) on variants.
- Ladder rungs `phone+network+osm`, `phone+network+obd+osm` (with `--roads`).
- Tests: `RoadNetworkTest` (7), `RoadMatcherTest` (4, incl. determinism with roads), `AlongTrackMatchTest`
  (5). All unit tests pass. Measured: R-020. Not run in the app yet (M6).

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

### R-042 (2026-10-05, 4c5ef9e + D-086 working tree) — Fused echo guard

Before = 4c5ef9e (git worktree build), after = working tree. Variants keep FUSED (the standard ladder drops it), roads
on, with and without recorded OBD; seven recordings, 13 scenarios on the GNSS drives, clean on the jammed ones
(134 runs). Files: local `recordings/analysis/d086/`.

- `ticks.csv` (every estimate) byte-identical in all 134 runs; p50/p95/max/within95 unchanged.
- FUSED verdicts change only on the three MOCK_OUTPUT drives: 104833 clean+OBD TRUSTED 81 / QUESTIONABLE 486 /
  REJECTED 196 → UNAVAILABLE 763; 140822 2152 of 2183 fixes → UNAVAILABLE (31 TRUSTED before the first override); 153540
  970 of 986. The four RECORD_ONLY drives are identical.

### R-041 (2026-10-05, f6e19a2 + D-085 working tree) — unconfirmed coarse voters

Before = `coarseOdoUnconfirmedRejects: true`, after = default. Both with roads, FUSED dropped; rungs with and without
recorded OBD (the check needs vehicle speed). Files: local `recordings/analysis/d085/`.

| Drive / set | Result |
|---|---|
| 104833 (no truth), clean + OBD | the 53.4-s fix: REJECTED → QUESTIONABLE (disputed). Distance from the estimate to the next fixes: 91.8 s 945 → 498 m, 121.7 s 166 → 39 m, 151.9 s 51 → 34 m, later unchanged. Without OBD identical (no odometry) |
| Six recordings with truth × 13 scenarios × with/without OBD (104 pairs) + two jammed drives clean (4 pairs) | all metrics and trust summaries identical; `COARSE_ODOMETRY_DISPUTED` never fires |
| Synthetic seed-1 standard matrix (117 summaries) | identical |

The remaining 498 m at 91.8 s is the unknown heading (position does not move between fixes; the car drove 480 m). No
truth exists for 104833, so the gain is measured against later network fixes, which agree with the OBD distance.

### R-040 (2026-10-05, 110a8ec + D-077…D-082 working tree, uncommitted) — Stop-to-move transitions

Compare D-081 `{trajectoryScoring:true,motionTransitions:false}` → D-082 with `motionTransitions:true`;
D-078 off. Both real variants exclude recorded OBD/FUSED, keep identical NETWORK, road tiles and faults.
Four GNSS-bearing recordings have sparse/recorded position reference; 085946/103343 truth is OSM/OBD
reconstruction, not independent position validation. Six ×13 real pairs and three seeds ×nine rungs
×13 synthetic pairs. All 78 real p95/max pairs non-worsening versus D-081; all 351 synthetic summaries
identical, 339 scored and 12 unscored. D-081's R-039 regressions versus D-080 are therefore not removed.

| Drive | Scenario | p95 before → after, m | max before → after, m |
|---|---|---:|---:|
| 153540, hand | GNSS absent | 127.43 →118.49 | 157.03 →157.03 |
| 153540, hand | drop_10min | 87.23 →87.23 | 157.03 →157.03 |
| 140822, hand | GNSS absent | 407.32 →407.32 | 443.02 →443.02 |
| 103211, holder | GNSS absent | 342.79 →342.79 | 627.96 →627.96 |
| 103211, holder | drop_10min | 138.77 →131.49 | 294.01 →294.01 |
| 190523, holder | GNSS absent | 472.44 →468.85 | 1544.31 →1544.31 |
| 190523, holder | drop_10min | 113.00 →113.00 | 455.23 →453.26 |
| 085946, holder, reconstructed | GNSS absent | 118.64 →116.34 | 304.37 →304.37 |
| 103343, holder, reconstructed | GNSS absent | 164.47 →164.47 | 696.04 →696.04 |

Recorded OBD is reference only, excluded from estimator. Sampled at 1 Hz: reference moving >3 m/s,
stopped ≤0.5, estimated near-zero <0.5. No inferred precise standstill from sparse hand-drive GNSS.

| Holder, GNSS absent | False-stop ticks | Longest false-stop run, s | Missed-stop ticks | Predicted travel during reference stops, m |
|---|---:|---:|---:|---:|
| 103211 | 52 →52 | 36 →36 | 103 →104 | 632.7 →715.5 |
| 190523 | 19 →19 | 15 →15 | 33 →34 | 197.2 →203.8 |
| 085946 | 50 →45 | 13 →13 | 153 →99 | 648.4 →576.5 |
| 103343 | 3 →3 | 1 →1 | 80 →85 | 378.6 →425.0 |

190523 drop_10min: false-stop ticks 16→16, missed 22→23, predicted stop travel 109.3→116.0 m.
An intermediate candidate added 19 false-stop ticks and censored the departure at 125.24 s; a fresh
zero temporarily prevented fallback, then the stopped origin had been forgotten. Final pending-state
correction removes those additional false stops; reference departure has zero sampled delay but
neither final variant had near-zero speed on the final preceding stop tick, so this is not a successful
detector claim. Keep all censored/non-detected entries in `motion-metrics.json`.

103211 absent at replay 303.54 s: error 244.80→147.55 m, selected speed 8.96→8.00 m/s; GNSS
reference ≈0.83 m/s and delayed OBD reference 0. A bounded departure pulse may already indicate real
motion near the end of the stop. The fix relocates the origin of possible departure; it does not measure
8 m/s or fully resolve that stop. D-080 error was 119.86 m at the same tick. 153540 session 136–200 s:
near-zero ticks 35→37/64 and predicted speed integral 213.60→195.59 m without GNSS, not true travel.

Calibration is still pessimistic: 190523 absent within68 0.986→0.963, 103211 0.867→0.861;
103343 within95 0.992→0.980. Individual ticks and stop travel can worsen despite non-worsening p95/max.
All 429 per-fix trust files byte-identical; baseline reproduction matches 78/78 D-081 summaries.
Ten new tests, five negative proofs; 194 JVM tests, Android assembly/lint exit 0. No car/device
verification or cost profiling. `motionTransitions=false`, `trajectoryScoring=false`, `stopMotion=null`
remain defaults. Local evidence/maps in `recordings/analysis/d082/` (ignored).

### R-039 (2026-10-04, 110a8ec + D-077…D-081 working tree, uncommitted) — Moving-path likelihood prototype

Compare D-080 `stopMotion={}` → D-081 `{trajectoryScoring:true}`; D-078 off, recorded OBD/FUSED
excluded, recorded NETWORK and identical road tiles/faults. Four GNSS-bearing drives have sparse/recorded
position truth; 085946/103343 use explicit OSM/OBD reconstructions, not independent position truth.
Simulation: three seeds ×nine paired rungs ×13 scenarios (351 pairs; 339 truth-scored).

| Drive | Scenario | p95 before → after, m | max before → after, m |
|---|---|---:|---:|
| 153540, hand | GNSS absent | 110.83 →127.43 | 157.03 →157.03 |
| 153540, hand | drop_10min | 81.14 →87.23 | 157.03 →157.03 |
| 140822, hand | GNSS absent | 407.32 →407.32 | 443.02 →443.02 |
| 103211, holder | GNSS absent | 342.79 →342.79 | 627.96 →627.96 |
| 103211, holder | drop_10min | 130.72 →138.77 | 294.01 →294.01 |
| 190523, holder | GNSS absent | 469.79 →472.44 | 1544.31 →1544.31 |
| 190523, holder | drop_10min | 113.00 →113.00 | 459.11 →455.23 |
| 085946, holder, reconstructed | GNSS absent | 109.68 →118.64 | 304.37 →304.37 |
| 103343, holder, reconstructed | GNSS absent | 164.47 →164.47 | 696.04 →696.04 |

Separate motion metrics on GNSS-absent runs, using recorded OBD solely as reference. These are 1-Hz
sample counts, not exact event durations. Reference moving >3 m/s, stopped ≤0.5, estimated near-zero <0.5:

| Holder drive | False-stop ticks | Longest false-stop run, s | Missed-stop ticks | Predicted travel during reference stops, m |
|---|---:|---:|---:|---:|
| 103211 | 40 →52 | 29 →36 | 17 →103 | 84 →633 |
| 190523 | 98 →19 | 83 →15 | 16 →33 | 93 →197 |
| 085946 | 39 →50 | 9 →13 | 28 →153 | 143 →648 |
| 103343 | 2 →3 | 2 →1 | 41 →80 | 176 →379 |

The unchanged 103211 p95 hides a standstill regression at replay 303.54 s: OBD reference 0 m/s,
ordinary selected speed 0.59 m/s versus new 8.95; position error 119.86 →244.80 m. The map opens
280–308 s and has a second button for local improvement around 840 s. 153540's later window at
session ≈833 s worsens 74.83 →128.14 m. On its original session 136–200 s, near-zero ticks
37/64 →35/64; predicted speed integral 193.53 →213.60 m, not measured distance. Sparse GNSS truth
cannot establish the whole 64-s standstill or score all departures.

Departure metric: reference >2 after ≥5 consecutive stop ticks, estimate >0.5 within 15 s. Keep censored
nulls and whether the estimate actually stopped beforehand. For example, 190523 absent at 125.24 s
changes 0 →5 s release delay, but the old zero-delay estimate had missed the preceding stop. At 103211
96.54 s the new release is censored beyond 15 s; 103343 at 46.93 s delay 2 →5 s. Do not present
zero delay after a missed stop as a successful detector. OBD is quantized and delayed (~0.8 s); these
are coarse offline diagnostics, not subsecond timing accuracy.

23/78 real p95 regressions >0.01 m; simulation 36 regressions, 10 improvements among 339 scored pairs,
largest +117.61 m (seed 1 Doppler-consistent drift, gyro/phone-only rungs: 781.19 →898.80 m).
All 429 per-fix trust files identical; trusted spoof coordinates can still yield different position error.
D-080 reproduction matches 78/78 saved summaries. Uncertainty remains pessimistic: 190523 absent
within68 0.950 →0.986, 085946 0.961 →0.969. No accepted global improvement or stable mode selector;
retain opt-in/off. 184 JVM tests, assembly/lint pass; no car/device or cost validation.
Evidence: `recordings/analysis/d081/` (ignored), reusable evaluator under `tools/plot/`.

### R-038 (2026-10-04, 110a8ec + D-077/D-078/D-079/D-080 working tree, uncommitted) — Bounded pull-away evidence

Comparison: D-079 `stopMotion={departureHoldS:null}` → D-080 `stopMotion={}`, recorded NETWORK and identical
roads, FUSED/OBD dropped, D-078 off. All six drives ×13 scenarios and three simulated seeds ×nine paired
rungs ×13 scenarios. Four drives use interpolated accepted GNSS reference; the two jammed drives use
OSM/OBD reconstructed truth, not independent map validation. No retrospective output correction.

| Case | D-079 | D-080 |
|---|---:|---:|
| 190523 drop_10min p95 | 120.70 m | 113.00 m |
| 190523 drop_10min error at 487.24 s | 45.89 m | 9.77 m |
| 190523 drop_10min error at 569.24 s | 161.42 m | 45.63 m |
| 153540 absent p95 / max | 110.83 /157.03 m | 110.83 /157.03 m |
| 153540 session 136–200 s stopped ticks / predicted travel | 37/64 /193.53 m | 37/64 /193.53 m |
| 140822 absent p95 | 407.32 m | 407.32 m |
| 103211 drop_10min p95 | 130.72 m | 130.72 m |
| 085946 reconstructed p95 | 109.68 m | 109.68 m |
| 103343 reconstructed p95 | 164.47 m | 164.47 m |

All 78 real and 339 truth-scored simulated p95/max comparisons non-worsening versus D-079; another
12 simulated pairs have no scored output. Trust summaries identical in all 351 simulated pairs.
Legacy reproduction matches all 78 saved D-079 summaries. Per-tick error can still differ in either direction:
e.g. 190523 570.24 s 42.43 →49.22 m; this is not universal pointwise improvement. Radius calibration changes:
153540 absent within68 0.98 →0.96, 103211 absent 0.912 →0.865. No calibrated stop probability claim;
`stopMotion=null` remains default. Evidence/configs/source hashes: `recordings/analysis/d080/` (ignored).
175 JVM tests and Android assembly/lint pass; pre-change miniature fails. No car validation.

### R-037 (2026-10-04, 110a8ec + D-077/D-078/D-079 working tree) — Conditional stop alternative

Compare `stopMotion=null` vs `{}`; D-078 off in both. FUSED/OBD removed, recorded NETWORK and identical
road tiles. Four GNSS drives use the normal replay truth; two jammed drives use explicitly supplied
OSM/OBD reconstructed truth (circular for road positions, not independent validation). 13 scenarios,
78 real pairs. Source hashes/configs/report: local `recordings/analysis/d079/`.

| Drive | Scenario | p95 before → after, m | max before → after, m | within68 before → after |
|---|---|---|---|---|
| 153540 | absent | 110.83 →110.83 | 157.03 →157.03 | 0.48 →0.98 |
| 140822 | absent | 407.32 →407.32 | 443.02 →443.02 | 0.72 →0.87 |
| 103211 | absent | 342.79 →342.79 | 627.96 →627.96 | 0.71 →0.91 |
| 190523 | absent | 469.79 →469.79 | 1544.31 →1544.31 | 0.94 →0.93 |
| 190523 | drop_10min | 113.00 →120.70 | 459.11 →459.11 | 0.88 →0.87 |
| 085946 (reconstructed) | clean / absent | 109.08 →109.68 | 304.15 →304.37 | 0.88 →0.97 |
| 103343 (reconstructed) | clean / absent | 164.47 →164.47 | 696.04 →696.04 | 0.91 →0.96 |

At 136–200 s on 153540 absent/drop_10min: 13 →37 of 64 one-second ticks have |v|<0.5 m/s;
predicted speed integral 370.01 →193.53 m. This is estimated movement, not known true distance:
one GPS point at 176.254 s reports 0.749 m/s, so complete standstill for all 64 s remains uncertain.
Clean uses 13 →36 ticks and 366.61 →198.13 m. This is not the high-rate stationary-detector count in R-034.

Rejected prototypes: pure always-moving branch and permissive selection produced absent p95 ≈234 m;
release threshold 0.6 increased later max to219 m. Release at0.85 restores whole-trip p95/max but yields
fewer held stop ticks. Enlarged display covariance in source validation caused gradual spoof p95
58.47 →490.50 m (false trusted fixes 1 →9). Separate ordinary trust prediction restores **identical trust
summaries in all 78 real pairs**, including spoof scenarios; all four GNSS drives' final clean p95/max
also match baseline. Synthetic standard ladder: 117 pairs ×3 seeds, all trust summaries identical and
maximum p95 increase <0.000001 m. Simulated cruising is quiet, not a real engine-vibration reference.

Default summaries match saved D-077 for78/78 pairs, excluding variant name. 164 core +6 recording tests,
Android build/lint exit0. Seventeen new miniatures cover coarse movement/coarseness, quiet cruising,
phone motion, selected endpoints, uncertainty, snapshots, mock/trusted inputs and separate trust/up hints.
Disabling the candidate makes the stop miniature fail (expected0, actual8 m/s); restored suite passes.
Keep candidate **disabled by default**: holder accuracy slightly regresses, ambiguity radii are too broad,
support weights are uncalibrated, and ordinary strict ZUPT still does not preserve an exclusive moving
mode. No car verification and no retrospective metric correction. Map v5 is the experiment, not app default.

### R-036 (2026-10-04, 110a8ec + D-077/D-078 working tree) — Holder stop flicker without OBD

D-078 disabled/default-null; recorded NETWORK and identical road tiles; FUSED and OBD removed from estimator.
Use recorded `obd:elm327` as an independent speed reference only: contiguous speed <0.5 m/s for ≥8 s, no gaps
>1.5 s, trim 2 s at either edge for IMU window/OBD delay, score initialized estimator motion updates only.
For the GPS drives run both recorded GNSS and GNSS absent; the other two originally have no GNSS fix.

| Holder recording, GNSS absent | Scored stopped seconds | Recognized stopped seconds | Estimated speed >3 m/s, seconds | False assignments to 8 m/s |
|---|---:|---:|---:|---:|
| 20260928-103211 | 185.6 | 131.6 (71%) | 6.3 | 8 |
| 20260929-190523 | 115.7 | 94.4 (82%) | 4.0 | 2 |
| 20260928-085946 | 157.3 | 131.8 (84%) | 18.4 | 1 |
| 20260929-103343 | 130.1 | 99.9 (77%) | 17.7 | 1 |

Recorded-GNSS runs without OBD: 103211/190523 still assign 8 m/s 8/2 times at real stops, but speed >3
lasts only 4.4/0.9 s because GNSS brings it down. Their internal stopped fractions are 42%/18%; that is not
position/speed failure by itself because trusted GNSS already bounds speed and intentionally suppresses loose
ZUPT. Published GNSS_TRACKING mode takes precedence over STATIONARY; counts above use the private stop state.

Concrete holder cases: 103211 session 3.70–30.22 s has 22.56 scored stopped seconds, axis unknown, six false
8 m/s assignments; 190523 107.04–124.30 s has 13.31 scored seconds, strict evidence 10.45 s vs loose 13.31 s,
unknown axis and false assignments at 114.90/121.10 s. After axis learning the problem can remain: 085946
79.84–90.84 s, scored 6.98 s, estimated speed 4.94 m/s throughout despite stopped OBD; accel-norm std median
0.306 (0.287–0.323) exceeds both 0.12 strict and often 0.3 loose thresholds. Loose evidence exists for 2.21 s
but nominal speed ≥3 rejects it even though its σ is 5.81–7.03 m/s and includes zero. 103343 end stop also has
large IMU excursions, so a holder is not asserted to have been perfectly motionless for every stop.

Verified observer/current replay equality for 7342 overlapping tick positions/speeds (103211 clean/absent
1608/1606, 190523 909/891, 085946 1368, 103343 960). No real coordinates added to CI, no estimator changes
for this diagnosis. Existing D-078 stop miniature covers the unknown-axis flicker mechanism; it is still
experimental/default-disabled after R-035. Local observer, analysis, per-stop detail and report under
`recordings/analysis/d078/holders/`. Do not attribute the stop problem solely to user hand movement.

### R-035 (2026-10-04, 110a8ec + D-077/D-078 working tree) — Stop experiment, disabled

Before = D-077 with `accelSpeed.stopHoldS=null` (also the new default); candidate = 1.5 s. Same road tiles,
recorded NETWORK, no recorded OBD/FUSED. Four GPS-window drives × 13 scenarios plus two jammed drives × 13
formal scenarios with explicit reconstructed OSM/OBD truth (two effective cases; truth is circular).

| Drive/scenario | p95 before → candidate, m | within68 | within95 |
|---|---:|---:|---:|
| 153540 clean | 3.26 → 3.27 | 0.88 → 0.86 | 1.00 → 1.00 |
| 153540 absent | 110.83 → 178.27 | 0.48 → 0.34 | 1.00 → 0.86 |
| 153540 drop_10min | 81.14 → 178.27 | 0.88 → 0.76 | 1.00 → 0.88 |
| 140822 absent | 407.32 → 418.15 | 0.723 → 0.713 | 0.979 → 0.979 |
| 140822 drop_10min | 125.99 → 107.85 | 0.947 → 0.979 | 0.979 → 0.979 |
| 103211 absent | 342.79 → 342.54 | 0.710 → 0.721 | 0.858 → 0.885 |
| 103211 consistent spoof ramp | 349.05 → 400.26 | see local CSV | 0.828 → 0.804 |
| 190523 drop_10min | 113.00 → 103.89 | 0.877 → 0.884 | 0.956 → 0.956 |
| 085946 reconstructed truth | 109.08 → 122.91 | 0.884 → 0.810 | 0.966 → 0.966 |
| 103343 reconstructed truth | 164.47 → 165.03 | 0.914 → 0.876 | 0.981 → 0.981 |

153540 session 136–200 s: 1072 updates / 63.985 s; recognized stopped 11.997 → 25.666 s, exits 12 → 7;
integral of estimated speed 370.134 → 280.659 m. Those are diagnostics, not independently measured travelled
distance or proof of full standstill. Observer matches all 964 overlapping clean tick positions/speeds exactly.
The later absent window (session 684–689 s) has near-zero candidate speed while GPS truth indicates driving;
maximum error rises 157.03 → 218.88 m. 153540 truth has only 50 interpolated ticks, but the regression is large.
Candidate trust summaries differ in one pair (140822 gradual drift); default/null summaries match D-077 exactly
in all 78 pairs. Seed-1 standard synthetic matrix: 117 pairs, worst p95 increase +14.27 m (drop_2min gyro-only,
phone-only and phone+obd: 198.35 → 212.63 m). These last rungs have no recorded OBD in the synthetic drive.
Opt-in results do not justify changing defaults. Artifacts: `recordings/analysis/d078/comparison-final.md`,
`all-recordings-deltas.csv`, `baseline-identity.json`, source hashes, observer, matrices and map generator.

### R-034 (2026-10-04, 110a8ec + D-077 working tree) — Stop flicker on 153540

Times below are session/map seconds (replay +6.00999952 s). Observer logs original `MotionUpdate` values and
estimator state through the existing pipeline; reflection only reads state, no sensor/decision changes. Compared
all 199 overlapping output ticks with R-033 clean/after: zero numerical difference in lat/lon/speed.

136–200 s: 1072 motion updates, 63.985 s total, strict/stopped 11.997 s; loose stop evidence 8.177 s, all with
`accelReady = false`. Forward axis first available at 189.287 s. Strict gates are accel-norm std < 0.12 m/s² and
mean gyro norm < 0.03 rad/s over 1 s. Window medians are 0.181 m/s² and 0.048 rad/s, so the phone is seldom
quiet enough; values do not establish whether the *vehicle* is moving. The alternate loose rule (<0.3 m/s²,
<0.02 rad/s) also needs `accelReady`, low predicted speed, old trusted GNSS and no longitudinal acceleration.

The estimator recognized twelve short stop intervals (longest 2.51 s). Every exit had unavailable accelerometer
speed readiness, so its pull-away branch assigned v = 8 m/s, σ_v = 10 m/s. Two examples: 145.894 s accel std
0.127 crosses 0.12 while gyro 0.022 stays below 0.03; 164.934 s gyro 0.039 crosses 0.03 while accel std 0.094
stays below 0.12. Both reset speed to 8. GNSS at 176.254 s gives 0.749 m/s, accepted with predicted speed
near zero; interruptions at 176.514 and 179.140 s again take the unmeasured pull-away branch. Network fixes
at 139.352/151.859 s reduce speed 13.575 → 8.600 / 8.000 → 4.278 m/s, but do not measure speed or explicitly
declare a stop. This diagnoses loss of a stopped state, not proof of a completely stationary 64-s vehicle leg.
Follow-ups and future miniature are in the roadmap. Evidence files: local `motion.csv`, `fixes.csv`, `ticks.csv`
and `TraceStop.java` under `recordings/analysis/d077/stop-136-200/`.

### R-033 (2026-10-04, 110a8ec + working tree) — D-077 road axis with uncertain speed

Before/after both use roads, recorded NETWORK, no FUSED or recorded OBD, default D-075/D-076 configuration;
only `roadConstraint.uncertainSpeedHeading` changes from false to true. A third road-free rung is retained.
Truth is existing GPS-window truth, not OSM-matched truth. The 153540 drive has only 50 interpolated truth ticks.

| Drive | Scenario | p50 before → after (m) | p95 before → after (m) | within68 before → after |
|---|---|---|---|---|
| 153540 (hand) | absent | 58.6 → 58.6 | 110.8 → 110.8 | 0.480 → 0.480 |
| 140822 (hand) | absent | 84.1 → 84.3 | 407.3 → 407.3 | 0.723 → 0.723 |
| 103211 (holder) | absent | 53.2 → 53.3 | 342.7 → 342.8 | 0.710 → 0.710 |
| 190523 (holder) | absent | 22.3 → 19.8 | 469.8 → 469.8 | 0.922 → 0.937 |
| 103211 | drop_10min | 0.6 → 0.6 | 130.7 → 130.7 | 0.850 → 0.850 |
| 190523 | drop_10min | 13.2 → 13.2 | 113.0 → 113.0 | 0.876 → 0.877 |

Clean p95/max are unchanged at reported precision on all four drives (including the existing 140822 408.4 m
recovery regression). The initial 153540 false heading remains. These are neutral results with a small median
gain on one drive, not evidence of a general accuracy improvement.

Bad maps: shift all roads 25 m east, or remove segments within 30 m of available truth over 0–4000 s.
Again compare enabled/disabled on each edited map. p95/max mostly unchanged; 103211 removed-road drop_10min
p95 130.7 → 128.3 m, max 294.0 → 293.7 m. Sparse truth only removes roads near the recorded GPS windows on
the hand drives, so this does not verify an entirely missing route. The miniature separately rejects a distant
parallel road and prevents duplicated geometry from overriding a crossing-road ambiguity.

Synthetic seed 1: all 13 scenarios × two road rungs (without/with synthetic OBD), 26 before/after summary pairs
identical. The road graph is derived from simulator truth; this checks consistency and known-speed compatibility,
not independent road accuracy. Standard non-road matrix: all 117 summaries exactly match 110a8ec/D-076.
The miniature supplies the active-case evidence: 20° residual heading becomes < 8° with speed and speed σ
unchanged and reported r68 retaining the road-free floor. All 143 JVM tests, Android build and lint pass.
Full runs/variant files: local `recordings/analysis/d077/`. None of this candidate code has run in a car.

Extended check (2026-10-04): all six recordings × 13 scenarios, same recorded-network/no-OBD before/after variants.
52 pairs use GNSS-window truth; the two jammed drives explicitly use their existing `.truth.json` reconstruction.
Their 26 formal scenario pairs repeat two effective cases, since no recorded GNSS is available to perturb.
The first full-matrix pass had zero truth ticks on these drives; it is retained under `full-real/`, but only
the explicit-truth reruns under `jammed-real-*` are used for accuracy conclusions.

| Other recording | Scenario | p95 before → after (m) | Note |
|---|---|---|---|
| 140822 | absent | 407.3 → 407.3 | GNSS-window truth |
| 103211 | absent | 342.7 → 342.8 | GNSS-window truth |
| 190523 | absent | 469.8 → 469.8 | Median 22.3 → 19.8 m |
| 085946 | as recorded, already jammed | 112.2 → 109.1 | OSM/OBD reconstructed truth |
| 103343 | as recorded, already jammed | 179.4 → 164.5 | Max 798.9 → 696.0 m; OSM/OBD reconstructed truth |

Regressions on 140822: naive ramp p95 36.95 → 38.20 m; Doppler-consistent ramp 37.65 → 41.94 m
(+4.28 m / 11.4%); teleport 42.85 → 43.86 m. These were not covered by the previous three real-drive
scenarios; the synthetic matrix's neutral result did not establish neutrality on real data.
Across the 78 formal pairs, trust summaries are identical and within68/within95 do not decrease. Largest max-error
increase is 0.004 m; worst RMSE increase 0.111 m; worst heading-p95 increase 0.228° on 103343. The original 153540
detour remains. Do not claim the change is regression-free. Per-pair full-precision deltas and the report are local
`recordings/analysis/d077/all-recordings-deltas.csv` and `extended-comparison.md`.

### R-032 (2026-10-04, 80ab792 + working tree) — D-076 heading noise during hand movement, vs D-075
Same inputs, timestamps, default trust, no FUSED or recorded OBD; before sets `handYawNoise = 0`, after uses 0.5 / floor 0.2.
Truth is the same trusted real-GPS stream from the passthrough truth-building pass, independent of these estimator settings.
`clean` here means the original stream with recorded probe windows; it does not create continuous GPS. Truth covers 50 / 94
ticks on 153540 / 140822. Pre-window error compares the immediately preceding estimate with the first truth tick of each
window (includes about one second of motion); use it alongside the ordinary metrics, not instead of them.

| Drive | Scenario / metric | D-075 | D-075 + D-076 |
|---|---|---|---|
| 20261003-153540, hand | `clean` max / RMSE, m | 166.0 / 23.5 | 3.5 / 1.4 |
| 20261003-153540, hand | Before 7 GPS windows: median / max, m | 107.7 / 163.7 | 56.3 / 153.0 |
| 20261003-153540, hand | `absent` p95 / max, m | 120.4 / 154.2 | 110.8 / 157.0 |
| 20261003-153540, hand | `absent` within68 / within95 | 0.36 / 0.98 | 0.48 / 1.00 |
| 20261003-153540, hand | `drop_10min` p95, m | 120.4 | 81.1 |
| 20261003-140822, hand | `clean` max / RMSE, m | 2.1 / 0.7 | 408.4 / 58.3 |
| 20261003-140822, hand | Before 12 GPS windows: median / max, m | 73.9 / 444.2 | 72.0 / 441.3 |
| 20261003-140822, hand | `absent` p95 / max, m | 379.7 / 434.2 | 407.3 / 443.0 |
| 20261003-140822, hand | `absent` within68 / within95 | 0.71 / 0.98 | 0.72 / 0.98 |
| 20261003-140822, hand | `drop_10min` p95, m | 97.9 | 126.0 |
| 20260928-103211, holder | `drop_10min` p95, m | 130.7 | 130.7 |
| 20260928-103211, holder | `absent` p95, m | 338.4 | 342.7 |
| 20260929-190523, holder | `drop_10min` p95 / within95 | 113.0 / 0.87 | 113.0 / 0.96 |
| 20260929-190523, holder | `absent` p95, m | 469.8 | 469.8 |

The 140822 clean regression is at replay 1782.6–1783.6 s (391 and 408 m); returning GPS then becomes trusted. A short
failure still matters, despite similar pre-window medians. On 153540 the heading initially follows the false turn in both
versions: at session 221 s ≈ 252° / 253°, then 245 s 261° / 281°, 295 s 291° / 314°. At replay 295.1 s (~session 301.1 s)
the new version accepts GPS, giving 0.45 m error vs 166 m with D-075. This is delayed correction, not removal of phone yaw.

Standard simulator: 587-s loop, network every 20 s, seeds 1/2/3, all 13 scenarios × 9 variants; on/off summary metrics
identical for all 117 pairs per seed, including spoof scenarios. No hand-held artifact in that simulation. These results
support a local improvement on 153540, not a universal accuracy improvement.
Local outputs: `recordings/analysis/d076/real/<drive>/`, `sim-seed{1,2,3}/`, and variant JSONs; map as above.

### R-031 (2026-10-04, 80ab792 + working tree) — D-075 speed walk, vs the code before D-073 and vs 80ab792
Replay `phone+network`, truth = trusted real GPS; before = tree of R-030's base, main = 80ab792. p95 / max m (within68 / within95):
- 20261003-153540 (hand): `absent` before 123 / 132 (0.60 / 0.62), main 131 / 202 (0.20 / 0.54), now 120 / 154 (0.36 / 0.98);
  `clean` max 6 → 223 → 166; `drop_10min` 123 → 131 → 120 (0.90 → 0.60 → 0.76).
- 20261003-140822 (hand): `absent` 404 / 620 (0.64 / 0.85) → 332 / 428 (0.47 / 0.73) → 380 / 434 (0.71 / 0.98); `clean` max 2024 →
  316 → 2.1; `drop_10min` max 2024 → 316 → 141.
- 20260928-103211 (holder): `drop_10min` 173 (0.81 / 0.91) → 169 → 131 (0.85 / 0.98); `absent` 339 → 340 → 338.
- 20260929-190523 (holder): `drop_10min` 113 (0.72) → 113 (0.74) → 113 (0.80); `absent` 471 unchanged, within68 0.85 → 0.92.
Simulated 10-min matrix vs 80ab792: 14 of 113 cells changed, spoof scenarios none (see D-075).
Map: `recordings/20261003-153540-map.html` (before / main / now).

### R-030 (2026-10-04, dc7d5e7 + working tree) — unsteady mount (D-073) and trust run outage (D-074), vs the code before
Replay `phone+network`, truth = trusted real GPS. Base built from the same tree without D-073/D-074.
Drive 20261003-140822 (hand-held):
- `clean` (as driven, GNSS in probe windows): max 2024 → 316 m; error just before each window median 107 → 87 m, max 1920 → 431 m;
  estimator speed max 67 → 25 m/s, ticks > 30 m/s 140 → 0; position jumps > 300 m between ticks 8 → 2 (both onto real GPS).
- `gnss_absent_from_start`: p50 88 → 103 m, p95 404 → 332 m, max 620 → 428 m, within68 / within95 0.64 / 0.85 → 0.47 / 0.73.
- `gnss_drop_10min`: p95 79 → 92 m, max 2024 → 316 m.
Holder drives (k = 1), p95 m (within68): 20260928-103211 `drop_10min` 173 (0.81) → 169 (0.81), `absent` 339 (0.69) → 340 (0.68);
20260929-190523 `drop_10min` 113 (0.72) → 113 (0.74), `absent` 471 (0.85) → 471 (0.86). Simulated 10-min matrix: no cell changed.
Rejected variants on the hand drive / holders (p95 `absent`): bias walk 0.1 for all 467 / 336 (holder within68 0.69 → 0.58);
fixed 2° centripetal tilt 379 / 390; `coarseSpeedGain` 0: 585 / 395, 0.5: 389 / 349.

### R-029 (2026-10-04, dc7d5e7 + working tree) — fast up correction at stops (D-072), vs R-028
Drive 20261003-140822, phone in hand, no OBD, replay `phone+network`, truth = real GPS at probe moments (124 s):
- `gnss_absent_from_start` (GNSS never): p50 137 → 88 m, p95 877 → 404 m, max 957 → 620 m, within68 0.29 → 0.64.
- `gnss_drop_10min`: p95 1240 → 79 m, within68 0.62 → 0.95.
- Variants without the accelerometer speed (`enabled=false`): p95 337 m, within68 0.35 (so the accelerometer still costs p95 on this drive).
Other recorded drives, `phone+network` before → after (p95): 20260928-103211 `drop_10min` 169 → 173, `absent` 337 → 339; 20260929-190523
`drop_10min` 165 → 113, `absent` 471 → 471 (20260929-103343 has no truth). Simulated 10-min matrix: 24 cells changed by > 0.05 m, max 0.8 m.
Tilt error at stops (angle between `up` and the accelerometer): before 4–15° (e.g. 14° at 1518 s); after 20 s of stop 0.5–1.7°.

### R-028 (2026-10-04, dc7d5e7 + working tree) — innovation gate rejects only ≥ 5 km (D-071), vs R-027
Real drive 20261003-140822, TRUSTED / QUESTIONABLE / REJECTED (GNSS): phone+network 204 / 29 / 16 → 203 / 46 / 0; phone-only 54 / 35 / 45 → 93 / 41 / 0.
Simulated 10-min drive, before (R-027) → after (rmse m; missed; false rejection): `gnss_drift_gradual` phone-only 390 → 472, 0.971, 0.417 → 0.580;
phone+network 264 → 279, 0.964, 0.052 → 0.094; `gnss_drift_doppler_consistent` phone+network 267 → 283, 0.052 → 0.094;
`gnss_ramp_capture` phone+network 597 → 382 m, missed 0.478 → 0.164; with synthetic network+OBD 609 → 558;
`gnss_noise_overconfident` synthetic network+OBD 6.8 → 20.1 m. Clean unchanged.

### R-027 (2026-10-04, dc7d5e7 + working tree) — trust: mock history fix and 100 m agreement (D-069)
Real drive 20261003-140822 replayed (`clean`, GNSS assessments; 134 real fixes + 2014 of our mock = UNAVAILABLE), TRUSTED / QUESTIONABLE / REJECTED:
- phone+network: before 150 / 98 / 1 (includes 115 network fixes) → mock fix only 202 / 31 / 16 → + 100 m rule 204 / 29 / 16.
- phone-only: 22 / 68 / 44 → 49 / 40 / 45 → 54 / 35 / 45.
- Left QUESTIONABLE/REJECTED GNSS (phone+network): INNOVATION_GATE 32 (estimate > 100 m off), NETWORK_DISAGREEMENT 10, RECOVERING 3.
Simulated 10-min drive matrix (`simulate --network-period 20`), phone-only / phone+network, before → after (missed-detection rate, detection latency):
- gnss_drift_gradual 0.569 → 0.971 / 0.715 → 0.964 (26 → 83 s); gnss_drift_doppler_consistent 0.993 → 1.000 / 0.985 → 0.993;
  gnss_ramp_capture_doppler_consistent 0.601 → 1.000 / 0.706 → 0.898; gnss_ramp_capture 0.587 → 0.205 / 0.457 → 0.478.
- gnss_noise_overconfident: rmse 96.8 → 33.9 / 86.3 → 33.5 m, missed 0 → 0.049. Clean: unchanged. Variants with synthetic or real OBD: unchanged.
- Without the 100 m rule the simulated matrix equals the old one (the simulation has no mock fixes); so the loss of slow-drift
  detection comes from the rule, while on the real drive the rule adds only 2–5 TRUSTED fixes.

### R-026 (2026-10-02, 8209e55 + working tree) — speed without OBD (final)
Full matrix vs 8209e55 (rebuilt in a worktree), same road tiles (`recordings/tiles`, re-downloaded), p95 geo
(within95 before → after), R-007 / B / 2026-09-28 / A:
- phone+network (no OBD; +osm identical, the road stays off without OBD): ×0.752 / ×0.893 / ×0.556 / ×0.929;
  R-007 GNSS absent 72/563 → 50/337 m, B GNSS absent p50 47 → 27 m, 2026-09-28 44/204 → 30/114 m, A 48/253 →
  27/235 m; within95 0.87→0.86, 0.84→0.83, 0.86→0.96, 0.90→0.96.
- phone+network+obd: ×1.000 / ×1.013 / ×1.009 / ×0.997.
- phone+network+obd+osm: ×1.007 / ×1.010 / ×1.067 / ×1.188 (A 24/57 → 27/68 m; R-007 GNSS absent 47/168 → 62/186 m):
  the compass FORWARD_ALIGNED start that the newly learned forward axis enables (D-050).
- No network: phone-only R-007 ×0.33, B ×0.93; σ-500-m synthetic network R-007 ×0.48, B ×1.05, A ×1.09.
Steps (no OBD): forward axis fixed → mode active on all drives; noise 0.3 → 0.8 and bias walk 0.005 → 0.05 (A p95
887 → 250 m); centripetal σ 30 % → 10 %; up gate on horizontal accel with the at-rest exception (removed a false
braking bias); bias known before use (σ0 0.5, gate 0.3) fixed B (×1.17 → ×0.90) but cost R-007 (×0.49 → ×0.81),
recovered by also using it under fresh GNSS (×0.76); anchors only with hAcc ≤ 200 m within 30 s (B synthetic
network ×1.27 → ×1.05). Compass-start rule tried and removed (D-050).
Road tiles: the scratch copy was wiped (P31); 8 tiles came from the emulator, 3 were re-downloaded from Overpass
(r1007_380, r1008_381, r1009_382); r1009_380 failed (HTTP 504) and is north of all drives.

### R-025 (2026-10-02) — speed without OBD: what the phone offers (four drives, OBD as speed truth)
- **Wheel-rotation harmonics in the accelerometer spectrum** (100 Hz, 2.56-s windows): ridges at f = k·v with
  k ≈ 0.373, 0.75, 1.13 Hz per m/s on all drives (1.5–1.8× the background), but too weak per window: blind
  estimate within 1 m/s in 18–32 % of windows; with a ±1.5–3 m/s prior the spectrum does not improve it
  (median 1.1–3.3 m/s vs prior 1–2 m/s). Dropped; also car- and road-dependent (user, 2026-10-02).
- **Centripetal speed** |a_lat|/|ω|, 1-s windows, |ω| > 0.15 rad/s: v_c / v_OBD median 0.96–1.06, p10–p90
  0.59–1.33 (noisy but unbiased; usable in turns with ~30 % σ).
- **Longitudinal accel**: bias at stops +0.08…+0.14 m/s² (one drive −0.02), std between stops 0.07–0.15;
  integrating from the last stop with that bias: |Δv| median 1.5–2.1 m/s after 10 s, 6.5–8.9 after 30 s,
  11–17 after 60 s, 27–39 after 120 s. The accelerometer only bridges seconds.
- **Stop detection** (raw 1-s windows): the current rule (‖a‖ std < 0.12 and mean ‖ω‖ < 0.03) catches
  10–47 % of stops. ‖a‖ std < 0.3 and ‖ω‖ < 0.02: 74–87 % of stops, 0–0.4 % false while moving > 4 m/s,
  16–28 % false at 0.5–4 m/s (creeping around stops). Gyro alone fails: 15–38 % of moving seconds have
  ‖ω‖ < 0.015 on smooth straight roads. Not applied yet: the simulator has no gyro vibration, so the new
  rule sees a simulated moving car as stopped; adding realistic vibration to the simulator exposed a
  compass sensitivity to vibration in simulation (heading off by up to 6° in `ObdTest`).

### R-024 (2026-10-02, 0553627 + working tree) — corner fix significance k
`+osm`, corner fix off / k 0 / 1.5 / 2 / 2.5. 2026-09-28 clean p50/p95 15.6/44 → 15.2/48 (all k), cross-track at
5:48 +24 → +3 m; A 23.2/58 → 23.9/57, removed-road window 48 → 44 m; R-007 all scenarios ×1.000; B p95 geo
×1.021 (k 0) → ×1.000 (k ≥ 1.5), p50 ×0.994 (k 1.5–2). Per-interval errors on 2026-09-28: D-049.

### R-023 (2026-10-02, 0553627 + working tree) — carrying road confidence through dips
`+osm`, all drives (GNSS drives all 13 scenarios; jammed with re-timed truths). Dip carry 0 (current) /
2 / 3 / 5 steps anywhere, and 3 / 5 steps only around a gyro turn ≥ 20° (5 at ≥ 30°):
- 2026-09-28 clean p50/p95: 15.6/44, 15.6/44, 15.0/47, 13.1/45; turn-gated 15.6/44. Cross-track at 5:48:
  +24, +24, +2, 0 m; turn-gated +24 m.
- A: 23.2/58, 22.5/58, 23.7/60, 25.2/60; removed-road window p95 48, 48, 63, 63 m; turn-gated 22.5/58, 48 m.
- p95 geo R-007 / B: 2 steps ×0.999 / ×1.022, 3 ×1.030 / ×1.019, 5 ×1.022 / ×1.019; turn-gated ×1.030 / ×1.006
  (30°: ×1.000 / ×1.011).

### R-022 (2026-10-02, working tree on bce013c) — M4 on gentle bends
`+osm` rungs, `alongMinTurnDeg` 20 → 12° (both with the local-minimum ambiguity test), re-timed jammed
truths: 2026-09-28 clean p50/p95 16.9/51 → 15.6/44 m, along-track median 19 → 16 m; A 23.7/58 → 23.2/58 m.
Full matrix p95 geo: R-007 ×0.999, B ×0.950 (along-track ×0.822; 10-min and 1-h drops p95 18.7 → 16.2 m,
teleport 15.9 → 12.9 m). Removed-road window p95: R-007 ×1.000, B ×0.935, jammed unchanged.

### R-021 (2026-10-02, working tree on bce013c) — re-timed truths; latency and corner experiments
Re-timed truths vs independent evidence: network-fix along-track offset median +3.2 → +0.7 m
(2026-09-28), +17.7 → +3.2 m (A); |truth heading − estimate heading| p95 11.7 → 9.2°, 21.5 → 16.8°.
New jammed baselines (clean, same code): 2026-09-28 phone+network+obd p50/p95 23.9 / 51 m (was 17.3 / 52
against the old truth), +osm 16.8 / 51 m (was 8.0 / 52); A 21.4 / 76 m (15.1 / 75), +osm 22.2 / 58 m
(16.7 / 54). The estimate runs 7–17 m behind along-track (median) without GNSS. Earlier jammed numbers
(R-008…R-020b) are against the old truth. Latency and corner-fix numbers: D-046.

### R-020b (2026-10-02, working tree on bce013c) — geometric right-road metric, minor-road prior
Matcher right when confident (≥ 100 m), old name-based → geometric metric (R-020 code, with OBD):
2026-09-28 0.989 → 0.714, R-007 GNSS absent 0.960 → 0.887, A 1.000 → 0.996, B 0.993 → 0.962. The name
metric counted parallel carriageways named like the main road as right.

With `minorRoadPenalty` 0.3 (geometric metric): 2026-09-28 right when confident 0.71 → 0.88, p50
7.8 → 8.0, p95 51.7 → 52.9 m; R-007 0.89 → 0.89; A, B unchanged. Full matrix (13 scenarios, R-007 and B
≤ 700 s), all rungs vs R-020: p95 geo ×1.000; jammed A ×1.000, 2026-09-28 ×1.024. Removed-road window
(M5) unchanged (42 / 31 / 57 / 19 / 163 m). Rejected variants: see D-045.
Remaining case: 2026-09-28 6:26–7:03 on a primary side carriageway after the 6:22 fix (error ~30–38 m vs
the no-road EKF's ~50 m).

### R-020 (2026-10-01, working tree on de60a52) — road constraint (M1–M5)
Road tiles from the two Overpass extracts already used for the OSM truths (cover all four drives).

**M1 matcher** (on the twin; `road_right` = matched street within 15 m of truth), with OBD:

| Drive (scenario) | right road | confident ≥ 100 m | right when confident | P(off) > 0.5 |
|---|---|---|---|---|
| 2026-09-28 jammed | 0.86 | 0.67 | 0.989 | 0.01 |
| A jammed | 0.98 | 0.76 | 1.000 | 0.00 |
| R-007 GNSS absent | 0.83 | 0.66 | 0.960 | 0.03 |
| B GNSS absent (≤ 700 s) | 0.99 | 0.78 | 0.993 | 0.00 |

Without OBD: right 0.74–0.90, right when confident 0.87–0.99. Wrong-but-confident stretches on R-007
only where the EKF itself was 120–370 m off. Off-road: R-007 underground car park P(off) = 1.0; the
parking search at the end of 2026-09-28 was on a mapped street (right 0.99), correctly not off-road.

**Full matrix** (all 13 scenarios; R-007 and B ≤ 700 s), `+osm` vs the same rung without it:

| Rung | R-007 p95 geo | B p95 geo | mean within95 R-007 / B |
|---|---|---|---|
| phone+network+obd+osm | ×0.812 | ×0.815 | 0.76 → 0.77 / 0.75 → 0.75 |
| phone+network+osm | ×1.000 | ×1.000 | unchanged (no known speed → road off) |

Largest changes with OBD: R-007 GNSS absent p50 56.4 → 47.8, p95 365 → 169 m; R-007 1-h drop p95 375 →
140 m; R-007 teleport p50 52.9 → 1.1 m; B 1-h drop p50 11.4 → 7.7, p95 30.0 → 18.7 m. Worse: R-007
10-min drop p95 103 → 126 m. Jammed drives (circular truth): 2026-09-28 p50 17.3 → 7.8 m, p95 51.7 → 50.8;
A p50 15.1 → 16.7, p95 74.8 → 53.9 m. The R-019 wobble (2026-09-28 +2:40–4:25): max error 43 → 14 m,
heading error 3.7° → 1.2°.

**M4** (along-track shape) vs M3 only, GNSS-truth drives: R-007 GNSS absent along-track p50 48.9 → 46.3 m,
p50 48.0 → 45.4; R-007 1-h drop p50 65.4 → 60.5; B 1-h drop p50 9.0 → 8.2, p95 22.4 → 20.8 m.

**M5 robustness** (roads removed within 30 m of the truth for 300–480 s; window p95, m):

| Drive | no road | entry 50 m / off 2.5σ | 100 m / 2.0σ | **150 m / 2.0σ** |
|---|---|---|---|---|
| 2026-09-28 | 53 | 106 | 43 | 42 |
| A | 28 | 79 | 24 | 31 |
| B GNSS absent | 58 | 57 | 67 | 57 |
| B 1-h drop | 21 | 42 | 42 | 19 |
| R-007 GNSS absent | 163 | 164 | 163 | 163 |

Map shifted 15 m east / 30 m north (jammed truths are OSM, so this shows the bias directly): 2026-09-28
p50 15.0 / 23.8 m, A 18.8 / 32.7 m (vs 11.1 / 13.2 unshifted, entry 50 m run). Non-road rungs are
unchanged by the new code (regression vs de60a52).

### R-019 (2026-10-01, de60a52) — heading wobble between coarse fixes: error correlation and two tuning knobs (no change)
Symptom (2026-09-28 jammed, +2:40–+4:25, straight road at 157–158°): the heading swings 152° → 161° → 156°
and the track drifts up to 40 m across the road between fixes. EKF log per fix: the off-road fix at 2:46
turned the heading only −1.9°; the next two on-road fixes turned it +3.6° and +5.4° and overshot to +3°.
The heading std stays 5–7° (back up by ~1° between fixes); 2:36 was skipped by the D-021 spacing rule.

Google network error correlation vs truth (fixes ≤ 200 m off; OSM truth for the jammed drives, GNSS for
R-007 and B): per-axis rms 26–39 m. Correlation between errors: consecutive moving fixes −0.14…+0.24,
25–60 s −0.35…+0.26, 60–300 s ≈ 0; at stops +0.69…+0.80. So a correlated-error (bias) state would
model little while driving; the independence assumption is about right.

Tuning tried (FUSED dropped, real OBD; R-007 and B ≤ 700 s × all scenarios, both jammed drives):

| Knob | Jammed 09-28 p50/p95 | Jammed A p50/p95 | R-007 p95 geo | B p95 geo | 2:40–4:25 mean \|heading err\| |
|---|---|---|---|---|---|
| default (headingRandomWalk 0.01, networkInflation 1.5) | 17.3 / 52 | 15.1 / 75 | 1 | 1 | 3.7° |
| headingRandomWalk 0.005 / 0.003 | 17.4 / 50, 17.2 / 50 | 15.1 / 77, 14.7 / 77 | ×1.041 / ×1.078 | ×0.944 / ×0.941 | 4.0° / 4.1° |
| networkInflation 2.0 / 2.5 | 19.3 / 50, 25.0 / 52 | 20.9 / 75, 34.2 / 77 | ×1.042 / ×1.127 | ×1.028 / ×1.042 | 3.6° / 3.5° |

Without OBD, headingRandomWalk 0.005 / 0.003: R-007 ×1.083 / ×1.136, B ×0.966 / ×0.965. Defaults kept: with
~30–40 m per-axis independent fix noise every 13–15 s and no road knowledge, a few degrees of heading
error per fix is close to what a filter can do. The wobble is a case for the road constraint (Phase 2).

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
