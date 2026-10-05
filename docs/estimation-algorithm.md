# Location estimation algorithm (Phase 1, as implemented)

Status: 2026-09-28. Code: `core/src/main/kotlin/gpes/core/{motion,trust,estimator,pipeline}`.
Keep this document in sync with the code (see AGENTS.md).

Phase 1 is a **baseline**, not the final estimator. Its jobs are to: (1) never trust GNSS blindly,
(2) bridge short outages, (3) report *honest* uncertainty, and (4) give later estimators
(road-constrained, OBD, route) a reference to beat in the replay matrix.

## 0. Conventions

- Time is `tNs` (elapsedRealtimeNanos). The pipeline advances only on measurement time.
- Local frame: ENU tangent plane (`LocalFrame`) anchored at the first fix, and re-anchored when
  the position is more than 20 km from the origin.
- Heading ψ is a **bearing**: radians clockwise from true north. Yaw rate from the IMU is
  **counter-clockwise positive** about "up", so dψ/dt = −ω_up.
- Android accuracy `hAcc` is a 68% radius. For an isotropic 2-D Gaussian σ = hAcc / 1.5096, and the
  95% radius is 2.4477 σ (`Cov2`).

## 1. Motion tracker (`MotionTracker`)

Input: `ACCEL`, `GYRO` (or `GYRO_UNCAL` if no calibrated gyro), and rotation-vector samples.
Output: a `MotionUpdate` at 20 Hz.

1. **Up direction in the phone frame (D-037).** Our own gravity estimate: the gyro carries the up
   vector (û ← û − (ω × û)·dt), and the accelerometer pulls it back with τ = 30 s, only while the car is
   quiet: | ‖a_lp‖ − m | < 0.3 m/s², where m is the 120-s mean of ‖a‖ on this phone (its scale error
   puts ‖a‖ at rest off 9.81 by more than that), |ω·û| low-passed over 0.5 s < 0.05 rad/s, and the
   horizontal part of the smoothed specific force < 0.5 m/s² unless the car is at rest (braking would
   tilt it; D-050). At a stop (D-072) up is pulled to the accelerometer with τ 3 s instead of 30 s: stop rule ≥ 3 s (gaps ≤ 1.5 s bridged), estimator speed < 1.5 m/s, steady force. Android's GRAVITY / rotation
   vectors lean towards the apparent gravity in turns (P14); on real drives the correlation of lateral
   accel with v·ω is 0.26–0.31 with Android's up and 0.97–0.98 with ours. Re-seeded from the
   accelerometer on a re-mount. (`upTauS = null` restores the old rotation-vector / low-pass up.)
2. **Yaw rate** ω_up = gyro · û. This does not depend on how the phone is mounted, so no
   phone-to-car calibration is needed for heading *changes*. It is averaged over each 50 ms update.
3. **Stationary detection** over a 1 s window: std(|accel|) < 0.12 m/s² and mean |gyro| < 0.03 rad/s.
   It reports `stationaryForS`.
4. **Cumulative bearing history** (10 min) answers `bearingChange(t1, t2)` for trust checks,
   independently of GNSS. The same history integrates vehicle speed (OBD, if fresh ≤ 1.5 s) along
   the cumulative bearing, so `odometry(t1, t2)` returns the distance driven and the **chord** (the
   straight-line displacement). The chord does not depend on the absolute heading. It is null when
   vehicle speed was missing for any part of the interval. OBD has no sign, so reversing counts as
   forward motion (short manoeuvres only).
5. **Vehicle forward axis in the phone frame (mount yaw)**, learned from turns, using the smoothed
   specific force (`accelLp`, τ ≈ 0.1 s) and the yaw rate low-passed over 0.5 s (single samples carry
   0.5–0.9 m/s² of vibration, D-050). In a turn the
   centripetal acceleration points to the turn centre, so `sign(ω_up)·a_horizontal` is the vehicle's
   *left*, and forward = left × up. This needs no speed and has no sign ambiguity. It is used when
   |ω_up| > 0.08 rad/s and |a_h| > 0.4 m/s², and reported after ≥ 1000 gyro samples (about two 90°
   turns) with concentration ≥ 0.6. In simulation it is within 8° for a strongly tilted mount.
6. **Re-mount detection:** a net rotation about horizontal axes > 20° within 3 s (one event per
   window). This increments `mountEpoch`, resets the forward axis and gravity, and resets the compass.

Known weaknesses: sustained longitudinal acceleration still tilts the accelerometer-based up
(mitigated by GAME_ROTATION_VECTOR, which real phones provide). Stationary thresholds are not yet
tuned on real car vibration.

## 2. GNSS trust evaluator (`DefaultTrustEvaluator`)

Each location fix gets a `TrustAssessment {state, confidence, reasons, NIS, impliedSpeed}`.
States: `TRUSTED`, `QUESTIONABLE`, `REJECTED`, `UNAVAILABLE`. The combined state is the **worst**
severity over all checks. Confidence is a product of per-check factors (shown to the user, not used
by the estimator).

### Checks for GNSS and FUSED fixes

| Check | Rule (defaults in `TrustConfig`) | Severity |
|---|---|---|
| Synthetic input | `isMock` or `gpes.synthetic` extra | REJECTED |
| Overridden provider | provider replaced by our own test provider | UNAVAILABLE |
| Stale | latency `receivedNs − tNs` > 2 s / > 10 s | Q / R |
| Accuracy | hAcc missing → Q; > 30 m → Q; > 150 m → R | Q / R |
| Implied velocity | (distance to last TRUSTED fix − 2·(hAcc₁+hAcc₂)) / dt > 70 m/s | REJECTED |
| Implied acceleration | Δspeed/dt > 8 m/s² (dt ≤ 5 s) | Q |
| Innovation gate | NIS vs estimator prediction, 2 dof: > 13.8 → Q, > 50 → R only if the fix is ≥ 5 km from the prediction (D-071), else Q | Q / R |
| Course vs gyro | GNSS course change vs gyro bearing change, both > 5 m/s, dt ≤ 5 s: diff > 25° + 10°/s·dt | Q |
| Velocity–position consistency | displacement over ~10 s vs integral of reported (Doppler) velocity: diff > 15 m + 2·(hAcc₁+hAcc₂) | Q |
| GNSS speed vs OBD speed | fresh OBD (≤ 1.5 s): \|v_gnss − v_obd\| > 1.5 m/s + 8%·v_obd (`SPEED_OBD_MISMATCH`) | Q |
| Moving while stationary | IMU stationary ≥ 3 s but GNSS speed > 3 m/s | Q |
| Network disagreement | fresh network fix (≤ 120 s): d > 2·(accNet+accGnss) + 30 m/s·age → Q; beyond that by 20 km → R (`GEOGRAPHICALLY_IMPOSSIBLE`) | Q / R |
| Agreement with the estimate (D-069) | hAcc ≤ 30 m and fix ≤ 100 m from the predicted position (`agreeWithEstimateM`), no fresh OBD: drops innovation gate, implied acceleration, moving-while-stationary hits; skips the recovery count (`AGREES_WITH_ESTIMATE`). Never overrides impossible velocity/place, network, OBD, velocity–position or course | – |
| Raw GNSS | sats used < 4 → Q. Used-sat C/N0 std < 1 dB with ≥ 6 sats → `CN0_UNIFORM`, which only lowers confidence (low weight in Phase 1) | Q / info |

Network fixes: synthetic → R, missing accuracy or > 5 km → R, latency > 30 s → Q, otherwise TRUSTED,
unless the **coarse-odometry check** rejects them (D-031, `COARSE_ODOMETRY_MISMATCH`, R):

- For each of the last 3 TRUSTED network fixes (≤ 180 s old, with odometry available), the fix
  *agrees* if |d − chord| ≤ K·√(σ₁²+σ₂²) + 5%·distance + 10 m, where d is the distance between the two
  fixes, σ = hAcc / 1.515, and K = 3 (`coarseOdoK`, null disables).
- REJECTED when the voters against outweigh those for, unless the fix agrees with the previous
  (rejected) fix. Each vote weighs 1/(σ₁²+σ₂²) with σ floored at 50 m (D-040,
  `coarseOdoWeightMinSigmaM`): a voter with hAcc 700 m agrees with almost anything, so it counts little,
  but no single fix claiming a small hAcc can outvote the rest. Voting keeps one bad but accepted reference from rejecting the good fixes after it.
- It catches fixes that jump much further than the car drove, and fixes that stay put while it
  drives. Measured effect is small (R-009).
- **Vector form (D-039).** When the predicted heading std ≤ 15° (`coarseOdoVectorMaxHeadingStdDeg`, null
  disables) and no GNSS/fused fix was TRUSTED in the last 180 s (`coarseOdoVectorNoGnssS`), a voter
  compares vectors instead: o = the odometry displacement in the gyro frame (`Odometry.dE/dN`) rotated by
  ψ_pred − relative bearing at the new fix; it agrees if |z − o| ≤ K_v·√(σ₁²+σ₂²+(chord·σψ)²) + 5%·distance
  + 10 m, σψ = max(heading std, 5°), K_v = 2.5 (`coarseOdoVectorK`). This catches a fix at the right
  distance in the wrong direction. With recent GNSS the heading may be a spoofer's, so only the scalar
  form is used (R-017).

### Hysteresis and reset (inspired by PX4 GPS checks and reset-on-glitch)

- **Our own output is not evidence (D-069).** Fixes of an overridden provider (the mock `gps`) or with the synthetic flag are
  assessed (UNAVAILABLE / REJECTED) but do not update `prev`, `recent`, hysteresis or stream state of their source. Otherwise the
  first real fixes of a GNSS probe window are compared with our mock track.

- After any REJECTED fix, the next **5** clean fixes are QUESTIONABLE (`RECOVERING`) before TRUSTED
  returns.
- **Reset after a consistent stream.** Rejected fixes that are mutually consistent (plausible
  speed between consecutive fixes) are accepted as TRUSTED (`RESET_AFTER_CONSISTENT_STREAM`)
  after **120 s**, or **15 s** if a fresh network fix agrees. The estimator then resets its
  position (NIS > 25). This exists because dead reckoning can drift during a long outage, and real
  GNSS must eventually win.
  - **Questionable stream after an outage (D-038).** Fixes that are QUESTIONABLE *only* because of the
    innovation gate (NIS 13.8–50), mutually consistent, in a stream that began after ≥ 30 s with no fix
    of that source at all, are accepted after **10 s**. The estimator ignores QUESTIONABLE GNSS, so on
    its own it could never converge to a returning GNSS (R-007: locked out for 500 s). A spoofer taking
    over during normal tracking does not qualify (no outage before its stream). The outage status belongs to the whole run
    of fixes until one is TRUSTED, so a stream restarted by another reason (a wrong network fix) still qualifies (D-074).
  - It is *never* allowed when the reasons include `IMPOSSIBLE_VELOCITY` (for example
    Kyiv→Lima), `GEOGRAPHICALLY_IMPOSSIBLE`, network disagreement, or synthetic input.
  - **Accepted risk:** a patient spoofer whose track stays self-consistent and physically
    reachable, with no network to contradict it, wins after 120 s. The replay scenarios measure
    this, and the roadmap lists countermeasures.

### Known blind spots (measured in replay)

- Slow drift or ramp capture (≤ 2 m/s offset growth) is detected late or not at all while GNSS
  velocity looks consistent. See `gnss_drift_gradual` and `gnss_ramp_capture` in the results log.
- A spoofer that keeps Doppler velocity consistent defeats the velocity–position check. Measured
  with `*_doppler_consistent` scenarios (R-002): 96–99% of manipulated fixes are accepted without
  OBD, and 75% with synthetic OBD. **With phone sensors alone, slow consistent spoofing is
  essentially undetectable.** Independent speed (OBD) and road topology are the planned
  countermeasures.
- The first fix after startup is trusted if no other evidence exists (there is nothing to compare
  it with).

## 3. Baseline estimator (`BaselineDrEstimator`)

A 2-D EKF in local ENU with state **x = [e, n, ψ, v, b, s]** (east, north, bearing, speed along
bearing, gyro bias about up, vehicle-speed/OBD scale error).

### Propagation (on every MotionUpdate, and before every update)

```
ψ ← ψ − (ω_up − b)·dt
e ← e + v·sin ψ·dt          (only while heading is known)
n ← n + v·cos ψ·dt
v ← v                       (random walk σ = 0.7 m/s/√s; 1.5 after 5 s with no GNSS fix of any trust, D-075)
b ← b                       (random walk σ = 2e−4 rad/s/√s)
s ← s                       (random walk σ = 2e−5 /√s; speedometer scale)
```

- The position follows ψ: **non-holonomic constraint** (no lateral or vertical velocity) by
  construction.
- Q: position 0.3 m/√s; heading 0.01 rad/√s plus a 2% gyro scale error on each turn increment.
  **Phone movement (D-076):** add `handYawNoise² · max(0, tiltRateRms − handTiltRateFloor)² · dt` to heading variance,
  with defaults 0.5 and 0.2 rad/s. The latest motion update supplies the 0.5-s non-yaw rate RMS, saved in snapshots.
  Unlike D-073's 60-s mount classification, this reacts immediately to a burst of phone movement. It leaves the gyro
  heading increment intact but admits that it may be the phone's turn, allowing subsequent position fixes to correct
  heading through the EKF cross-covariance. Below the floor propagation is unchanged; `handYawNoise = 0` disables it.
  A rotation purely about up has no tilt signal and remains a blind spot. This is an uncertainty model, not a detector
  of vehicle turns; recovery can take several coarse fixes, and R-032 records a GNSS recovery regression on 140822.
- **Heading unknown** (for example a network-only start): position is not propagated
  directionally. Its covariance grows isotropically by the worst-case distance D = Σ(|v| + 2σ_v)·dt
  (per-axis variance D²/2), and D resets on every position update.
- **Pulling away** (stationary → moving) sets v = 8 m/s with σ_v = 10 m/s. Without a speed source,
  the speed after a stop is genuinely unknown; before this fix, ZUPT kept v ≈ 0 with tiny variance,
  which made the filter overconfident (see D-012). With OBD, the next vehicle-speed update
  (≤ 0.2 s later) overrides it.

### Updates

| Evidence | When used | Model |
|---|---|---|
| GNSS position | TRUSTED (QUESTIONABLE only if `questionableRScale` is set, R × scale) | H = [I₂ 0], R = σ²I, σ = hAcc/1.51; NIS > 25 on a TRUSTED fix → reset position |
| GNSS speed | with the position update | R = max(sAcc, 0.2)² |
| GNSS course | speed ≥ 5 m/s | wrapped innovation, R = max(bAcc, 1°)²; the first course, or a jump > 60°, re-initializes ψ |
| Network fix | TRUSTED/QUESTIONABLE, if our σ > 0.5·σ_net **or** ≥ 15 s and ≥ 150 m of odometry since the last fused one (D-021) | σ_net = 1.5·hAcc/1.51. **Robust (D-034):** NIS ≤ 9.21 → normal update; above → *candidate*, updated with R × NIS/9.21 (so it barely moves position and heading). 3 candidates in a row over ≥ 20 s whose spacing matches the odometry chord (\|d − chord\| ≤ 3·√(σ₁²+σ₂²) + 5%·distance + 10 m) → reset onto the latest; if no GNSS was used for 60 s, also restart the heading search (bank). A normal update clears the candidates. (`coarseRobustNis = null` restores the old NIS > 50 → reset.) |
| Compass heading | 1 Hz, gated (see §3b), only if σ_ψ > σ_compass (correlated errors must not average down) | wrapped innovation, R = σ_compass²; NIS > 9 → skip; initializes ψ when heading is unknown |
| Vehicle speed (OBD/synthetic) | always | z = v·(1+s), H = [0,0,0,1+s,0,v], R = max(std, 0.05)² (ELM327: 0.3 m/s). s starts at 0 ± 3% with a 2e-5/√s random walk, is learned while GNSS speed is trusted, and is kept during outages (D-029) |
| ZUPT | IMU stationary | **local** updates (only v and b move, Joseph form): v = 0 (R = 0.05²), b = ω_up (R = 0.003²). A full update let one noisy bias sample move the position by the bias-to-heading-to-position lever after a long outage (D-028) |

Updates use the Joseph form, and P is symmetrized after each step.

### Startup

- First trusted GNSS → position, speed and (if moving) heading. Mode GNSS_TRACKING.
- Network only → mode COARSE_ONLY, position = network fix, covariance = network accuracy × 1.5.
  The coarse fix is **never** turned into a confident point.
- Heading without GNSS comes from the compass once it has a reading (UNCORRECTED σ ≈ 35°, then
  FORWARD_ALIGNED after enough turns), or from the heading bank (§3c) once coarse fixes and the
  driven path agree on one heading. Either starts real dead reckoning between coarse fixes.
- Nothing → no estimate (UNINITIALIZED); mock output publishes nothing.

### Output (every 1 s tick)

`PositionEstimate` includes lat/lon, `Cov2`, accuracy (r68), heading ± σ (null if unknown),
speed ± σ, mode (`GNSS_TRACKING` if a GNSS update happened in the last 3 s, then `STATIONARY`,
then `DEAD_RECKONING` if heading is known and σ_v < 3 m/s, otherwise `COARSE_ONLY`), and a single
`Hypothesis`.

### Reference estimator

`GnssPassthroughEstimator` ("hold-last-fix") holds the last TRUSTED GNSS fix, with r68 growing at
20 m/s. Every estimator must beat it.

## 3b. Compass (`Compass`, used by the baseline)

In a car the magnetometer is badly distorted: the steel body, engine and wiring, and sometimes a
magnetic phone holder. With a **fixed mount**, though, all of these distortions are constant in the
phone frame. As the car turns, the horizontal field seen in a phone-fixed horizontal frame traces
an **ellipse**: soft iron gives the shape, hard iron the offset.

1. **Iron fit, no GNSS needed.**
   - Horizontal field points (a 0.2 s EMA of MAG_UNCAL, or MAG if absent) are binned by the
     **bias-corrected gyro heading** (36 bins). Coverage is therefore measured without trusting the
     distorted compass, and gyro drift cannot fake it.
   - A circle fit (Kåsa) is used with ≥ 4 octants covered, and an algebraic ellipse fit with ≥ 6
     octants in both gyro and field angle (axis ratio ≤ 2:1).
   - The corrected angle θc then rotates 1:1 with the vehicle.
2. **Alignment ψ = θc + c.**
   - `GNSS_ALIGNED`: c = circular mean of (trusted course − θc), taken while driving straight
     (≥ 7 m/s, |ω| ≤ 0.05). σ = max(5°, 1.5·rms). Add +5° for circle-only, +10° with ≤ 4 octants,
     and +5° when the heading is > 45° from every alignment sample.
   - `FORWARD_ALIGNED`, no GNSS: the azimuth of the learned forward axis against the corrected
     field, plus WMM declination (`GeomagneticReference`). σ = 15° (+5° circle-only; +25° with 4
     octants, +10° with 5).
   - `UNCORRECTED`, no iron fit: Android-calibrated MAG + forward axis + declination, gated by
     |B| ≈ WMM field ±30%. σ = 35°. Add +10° to any forward-aligned mode when the declination is
     unknown.
3. **Anomaly gates** (trams, bridges, trucks):
   - The field component along *up* is constant for a fixed mount whatever the heading. A jump
     > 3 µT from its running median marks the next 3 s as dirty. No readings, fit samples or
     alignment samples are taken while dirty.
   - The corrected radius must be within ±10% of the fitted radius.
   - The compass heading change over 2 s must agree with the bias-corrected gyro within 10°.
4. **Shake gate.** "Shaky" means the RMS non-yaw angular rate over 0.5 s is > 1.2 rad/s (with a
   rotation vector) or > 0.15 rad/s (accelerometer-only up), or, without a rotation vector, the
   horizontal accel is > 1 m/s². While shaky, the compass takes no fit samples, no alignment
   samples and gives no readings (D-025).
5. **Self-assessment** (`Compass.quality()`), recomputed every update. Verdict
   UNKNOWN / USABLE / MARGINAL / UNUSABLE, with reason codes:

   | Code | UNUSABLE if | MARGINAL if |
   |---|---|---|
   | SATURATED | an axis repeats an identical value > 200 µT 20× (clipping) | – |
   | WEAK_FIELD / STRONG_FIELD | fitted radius / WMM horizontal field < 0.3 / > 3 | < 0.6 / > 1.7 |
   | NOISY_FIT | radial scatter > 20° | > 8° |
   | UNSTABLE_DISTORTION | fitted centre moved > 0.5·radius within 5 min | > 0.2 |
   | LARGE_HARD_IRON | – | horizontal offset > 200 µT (holder magnet: calibratable, but fragile) |
   | OFTEN_DISTURBED | anomaly-gated > 60% of moving time (after 60 s moving) | > 30% |
   | SHAKY_MOUNT | shaky > 70% of moving time | > 30% |
   | WIRELESS_CHARGING | – | a wireless charger was active (`PowerState`) |
   | GNSS_DISAGREES | alignment residual RMS > 25° | > 10° |

   UNUSABLE → no readings at all. MARGINAL → σ + 10°. The estimator publishes `compassStatus`
   (quality + last reading) for the UI.
6. **Correlated errors.** The residual compass error is the same for minutes, so the EKF only takes
   a compass update when σ_ψ > σ_compass (D-020). The compass *bounds* heading drift; it does not
   pretend to beat its own accuracy by repetition.

Simulation, measured:
- Full heading coverage with anomalies: p95 7.9°; without anomalies: 4.1°.
- Short drive with partial coverage: GNSS-aligned p95 3–6° over 6 seeds, with 100% of errors
  within 2σ. The mean σ is about 16°, so the sigma model is deliberately conservative: real cars will
  be worse than the simulation.
- Uncalibrated p50 < 20°.
- Real cars and holders may be much worse, so this needs validation (see progress).

## 3c. Heading bank (`HeadingBank`, D-032)

Absolute heading without GNSS or compass, from coarse fixes + gyro + speed. A Gaussian-sum filter
after PX4's EKF-GSF yaw estimator, with coarse positions instead of GNSS velocity.

- Runs only while the EKF heading is unknown. It starts at the first coarse fix with **12**
  hypotheses spread over 360° (σψ = half the spacing, 15°), each a 3-state EKF [e, n, ψ] in its own
  local frame.
- Propagation (every EKF propagation step): ψ ← ψ − (ω_up − b)·dt with the EKF gyro bias; position
  along ψ with the EKF speed. Process noise: heading random walk 0.01 rad/√s plus 2% gyro scale on
  turns; position 0.5 m/√s; along-track **correlated** error ∫(σ_v + 3%·|v|)dt since the last fix,
  entered as its squared growth (a wrong speed stays wrong, so the error grows ∝ t, not √t).
- Each accepted coarse fix (R = (hAcc/1.515·1.5)²) updates every hypothesis (Joseph form) and adds
  −½·NIS − ½·ln det S to its log-weight. Weights are floored at 10⁻⁴ of the best, so a hypothesis can
  recover.
- Mixture heading = weighted circular mean; σ² = Σw(Pψψ + Δψ²). **Hand-over** when σ ≤ 15°, ≥ 4 fixes
  and ≥ 300 m driven: the EKF takes the mixture heading, position and their joint 3×3 covariance
  (position std ×2, because coarse errors are correlated and the bank treats them as independent),
  decorrelated from v, b, s. Then the bank resets; the EKF runs as usual (coarse fixes update all
  states). GNSS course or a compass reading also reset the bank.
- The heading is observable without speed (fixes line up along the direction of travel), so the
  bank may hand over without OBD; the speed then stays unknown (tests: no harm, p95 better).

Measured: R-011.

## 3e. Speed without OBD (D-050)

State 7 is the bias of the longitudinal specific force. Without fresh vehicle speed (2 s), with the forward axis
known and a fix of hAcc ≤ 200 m in the last 30 s, the estimator learns that bias (at rest after 2 s stopped, and
from GNSS speed) and may apply ZUPT with the real-car stop rule (`MotionUpdate.stillLoose`: ‖a‖ std < 0.3 m/s², mean
‖ω‖ < 0.02 rad/s) when no trusted GNSS for 5 s, v < 3 m/s, v − 2σ < 1.5 m/s and the car is not accelerating
(|a_long − b| < 0.3). v follows ∫(a_long − b) (noise 0.8 m/s/√s) once σ_b ≤ 0.3 m/s² or while trusted GNSS is fresh;
otherwise the random walk. Each second with |ω| > 0.07 rad/s, the centripetal speed a_lat/ω is a speed measurement
(σ = 10 % + 0.5 m/s, χ² gate 9). Negative v is clipped (no reverse detection). Nothing changes with OBD.

**Unsteady mount (D-073).** `tiltRateRms` low-passed over 60 s while moving gives k = clamp(r / 0.07 rad/s, 1, 4) (hand-held ≈ 1.9,
holders 1). The bias random walk is ×k and the centripetal σ gets g·θ/|ω| with θ = 2°·(k − 1): in the hand the up vector and the
forward axis move, so the apparent bias wanders ±1–2 m/s² in minutes and a_lat carries gravity. On a holder nothing changes.

### Stop/motion mode experiments (D-078…D-082, not in main)

Stop continuation without a forward axis, a conditional stopped trajectory beside navigation, an exclusive
moving trajectory with NETWORK leg comparison, and stop-to-move reinitialization were built and measured
offline (R-035…R-040). Against the default estimator the full stack worsens p95 on 153540 and 085946 and
misses more stops than D-080, so the code lives on branch `experiment/stop-motion` and main keeps the
stop rules above unchanged (D-083). The design and measurements are in decisions.md and progress.md.

## 3d. Road constraint (Phase 2, D-041…D-044; plan in road-constraint.md)

Active only when the estimator gets a road network (`BaselineDrEstimator(cfg, roads = { network })`;
replay rungs `+osm`).

- **Road data** (`core/road`): OSM drivable ways in tiles (`RoadTile`, `RoadTileCodec`), joined into a
  `RoadNetwork` (segments between intersections, successors respecting one-way and layer, a 200 m grid
  index, `project(lat, lon, r)` → distance along, signed cross-track, bearing).
- **Road-free twin** (D-042): an internal second estimator with the same inputs and no road updates.
- **Matcher** (`RoadMatcher`, on the twin, every 10 m of odometry once the heading is known): HMM over
  candidates = projections of the twin's pose on segments within 3σ (30–200 m), per allowed direction.
  Emission: Gaussian cross-track distance with variance = pose variance along the road normal + road
  variance ((half width)²/3 + 4², width from `lanes` or class), and heading vs travel bearing (σ² = EKF
  heading variance + 10²°). Transition: Laplace in |route distance − driven distance| (β = 8 m + 0.15·step),
  Gaussian in gyro turn − road bearing change (σ = 15° + 0.15·|turn|), routes over the graph within
  2·step + 60 m. OFF-ROAD state: spatial likelihood of a candidate 2σ away, uniform heading, path score
  1 nat below a perfect road step; leave 2 %/step (10 % below 15 km/h), enter 5 %/step. Road probability is
  summed over the same street and direction; `confidentM` is the distance driven with it ≥ 0.9.
- **Updates in the constrained EKF** (D-043), with the conditions listed there: heading = travel bearing
  (σ 3°) on straight road with a straight gyro; cross-track (1-D along the normal); along-track from the
  path shape (D-044; gentle bends ≥ 12° since D-047, ambiguity judged by other local minima); corner fix
  right after a completed turn ≥ 45° (2-D update to the matched road point shifted by the shape match),
  applied only when the estimate is > 2 σ from that point (D-046, D-049). Each is skipped when the prior variance in that direction is already below the
  measurement variance (repeated road evidence is one piece, P5), and gated at χ² 9. Two cross-track
  rejections in a row → take the twin's state.
- **Output**: position from the constrained EKF; covariance = the twin's when larger (honest radius);
  `PositionEstimate.road` = the matcher's best state with probability, P(off-road), confident distance,
  street name.
- **Uncertain-speed heading (D-077)**: with roads enabled and `uncertainSpeedHeading = true`, a separate geometry
  cue works when the twin's speed σ > 1.5 m/s, nominal |v| ≥ 4.2 m/s, and it is not stationary. No fresh OBD
  (≤ 2 s) or trusted GNSS (≤ 3 s); an accepted position anchor must be ≤ 45 s old. Over the last 3 s the
  gyro net turn must be ≤ 3°, total absolute turn ≤ 9°, mean tilt-rate RMS ≤ 0.2 rad/s; gaps > 1.5 s
  restart this window. This uses elapsed time, never the uncertain odometry distance or HMM confidence.
  Query the twin's 3σ position ellipse including road width and OSM geometry, with maximum pose σ 150 m.
  For each road projection choose the axis direction nearest the twin's heading; spatial and heading NIS
  must each be ≤ 9. Score = exp(−(spatial NIS + heading NIS)/2), with heading variance = twin variance + 10°².
  Group bearings within 10°, take the maximum score per axis (duplicates do not add evidence), and require
  the best axis score / (sum of axis scores + off-road score 0.05) ≥ 0.9. This is heuristic support, not a
  calibrated posterior. Every locally plausible piece of the winning axis must be straight within 5°
  over clipped ±40 m; check every polyline edge so a short S bend is not missed.
  Attempt at most every 10 s, including failed geometry queries. Apply a χ²-gated local Joseph update
  to heading with σ 6° only if prior heading variance exceeds R. Other state means and marginal variances
  (position, speed and biases) stay unchanged at the update; subsequent propagation uses the corrected
  course. Reported position uncertainty retains the twin floor. A road axis cannot choose a parallel
  carriageway, resolve a 180° heading error, infer speed or identify the point along the street.
  The odometry-based cross-track, along-track and corner updates still require speed σ ≤ 1.5 m/s.
  R-033: this mode works in the miniature, but has little measured effect on the four real drives.

## 3f. GNSS recovery probe while the platform `gps` is replaced (D-052)

Waze and most navigators read the platform `gps` provider, so the mock output must replace it (D-009 kept
Fused only so that real fixes kept flowing). While the test provider is installed, real Location fixes do not
reach us, but `GnssStatus` does, so `GnssProbeController` (`core/.../trust/GnssProbe.kt`, pure, driven by
`tNs`) decides when to give the provider back for a window:

- **Healthy chip**: ≥ 5 satellites used in the fix, mean C/N0 ≥ 20 dB-Hz, C/N0 spread ≥ 1.0 dB (the same as trust's
  `CN0_UNIFORM`; a spoofer's signals are uniform), taken as the largest spread over the last 5 statuses because
  indoors the spread of 5–7 satellites dips to 0.6–1.4 dB for seconds, `GnssStatus` not older than 3 s, continuously
  for 10 s.
- **Open** when healthy and at least `intervalS` (60 s) since the last window. The app removes the `gps` test
  provider (`ProviderEvent RESTORED`), so real fixes go through trust and the estimator as always.
- **Close**: a TRUSTED GNSS assessment (recovered; interval back to 60 s); a REJECTED one with a reason other than
  `INNOVATION_GATE`/`RECOVERING` (impossible speed, bad course, OBD mismatch...: closed at once); no real fix within
  12 s (Pixel 8: the first fix came 1.1–3.1 s after the provider was given back in three windows, not within 4 s
  and 8 s in two; until it arrives other apps have no GPS at all); or the window limit of 20 s. A failed window doubles the interval (up to 300 s). The app then re-installs the
  test provider (`OVERRIDDEN`).
- **Why windows can be long**: a returning GNSS that disagrees with a drifted estimate is accepted by trust only
  after a consistent stream (10 s when QUESTIONABLE after an outage, D-038; 15 s REJECTED with an agreeing network
  fix; 120 s otherwise). A disagreeing fix is ambiguous (our drift or a spoofer), so such a window stays open
  until trust decides or the limit is reached.
- **Exposure**: during a window other apps see the real GPS. If the signal is spoofed, they see the spoofed position
  for 1 s (hard rejection) up to 20 s (ambiguous). The healthy-chip filter and the back-off reduce this, not remove
  it. The user can turn the probe off (setting "Check whether GPS is back").
- **Passthrough (D-070).** After 2 RECOVERED windows in a row the provider is not given back: phase PASSTHROUGH, real fixes
  keep flowing, other apps see the real GPS. Ends (mock back) after 5 s without a TRUSTED real fix, 5 s of an unhealthy chip, or
  a hard REJECTED reason; probing then resumes at 60 s. A failed window resets the count.
- Recorded as `Annotation` labels `gps_passthrough_on`/`gps_passthrough_off`, `gps_probe_open`, `gps_probe_recovered`, `gps_probe_failed`, plus the
  `ProviderEvent`s. Replay ignores them for estimation (the provider events already drive trust).

## 4. What the baseline cannot do (by design)

- Absolute heading without GNSS relies on the compass (§3b), untested on real cars yet, or on the
  heading bank (§3c), which needs coarse fixes and a few hundred metres of driving.
- Distance without GNSS or OBD is poorly constrained, because speed is a random walk. That is why
  `synthObd` improves the 10-min outage p95 from about 1.4 km to about 250 m in simulation.
- No map in the baseline itself: errors grow without bound during hours-long outages. The road
  constraint (§3d) bounds the cross-track part when OBD is present; along-track error still grows
  between turns, and a road missing from OSM next to a mapped parallel one can still mislead it.
- Coarse fixes are fused at most every 15 s / 150 m and inflated ×1.5. Real network errors may be
  more correlated than that (same towers for kilometres); check within95 on real drives.
