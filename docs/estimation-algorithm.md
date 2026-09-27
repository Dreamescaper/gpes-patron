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

1. **Up direction in the phone frame.** Use GAME_ROTATION_VECTOR / ROTATION_VECTOR when fresh
   (≤ 0.5 s): world-up = the third row of the rotation matrix. Otherwise use a low-passed
   accelerometer (τ = 1 s; specific force at rest points up).
2. **Yaw rate** ω_up = gyro · û. This does not depend on how the phone is mounted, so no
   phone-to-car calibration is needed for heading *changes*. It is averaged over each 50 ms update.
3. **Stationary detection** over a 1 s window: std(|accel|) < 0.12 m/s² and mean |gyro| < 0.03 rad/s.
   It reports `stationaryForS`.
4. **Cumulative bearing history** (10 min) answers `bearingChange(t1, t2)` for trust checks,
   independently of GNSS.

Known weaknesses: sustained centripetal acceleration tilts the low-pass gravity estimate (it is
mitigated when a rotation vector is available). Stationary thresholds are not yet tuned on real
car vibration.

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
| Innovation gate | NIS vs estimator prediction, 2 dof: > 13.8 → Q, > 50 → R | Q / R |
| Course vs gyro | GNSS course change vs gyro bearing change, both > 5 m/s, dt ≤ 5 s: diff > 25° + 10°/s·dt | Q |
| Velocity–position consistency | displacement over ~10 s vs integral of reported (Doppler) velocity: diff > 15 m + 2·(hAcc₁+hAcc₂) | Q |
| Moving while stationary | IMU stationary ≥ 3 s but GNSS speed > 3 m/s | Q |
| Network disagreement | fresh network fix (≤ 120 s): d > 2·(accNet+accGnss) + 30 m/s·age → Q; beyond that by 20 km → R (`GEOGRAPHICALLY_IMPOSSIBLE`) | Q / R |
| Raw GNSS | sats used < 4 → Q. Used-sat C/N0 std < 1 dB with ≥ 6 sats → `CN0_UNIFORM`, which only lowers confidence (low weight in Phase 1) | Q / info |

Network fixes: synthetic → R, missing accuracy or > 5 km → R, latency > 30 s → Q, otherwise TRUSTED.

### Hysteresis and reset (inspired by PX4 GPS checks and reset-on-glitch)

- After any REJECTED fix, the next **5** clean fixes are QUESTIONABLE (`RECOVERING`) before TRUSTED
  returns.
- **Reset after a consistent stream.** Rejected fixes that are mutually consistent (plausible
  speed between consecutive fixes) are accepted as TRUSTED (`RESET_AFTER_CONSISTENT_STREAM`)
  after **120 s**, or **15 s** if a fresh network fix agrees. The estimator then resets its
  position (NIS > 25). This exists because dead reckoning can drift during a long outage, and real
  GNSS must eventually win.
  - It is *never* allowed when the reasons include `IMPOSSIBLE_VELOCITY` (for example
    Kyiv→Lima), `GEOGRAPHICALLY_IMPOSSIBLE`, network disagreement, or synthetic input.
  - **Accepted risk:** a patient spoofer whose track stays self-consistent and physically
    reachable, with no network to contradict it, wins after 120 s. The replay scenarios measure
    this, and the roadmap lists countermeasures.

### Known blind spots (measured in replay)

- Slow drift or ramp capture (≤ 2 m/s offset growth) is detected late or not at all while GNSS
  velocity looks consistent. See `gnss_drift_gradual` and `gnss_ramp_capture` in the results log.
- A spoofer that keeps Doppler velocity consistent defeats the velocity–position check. The
  simulator's offset transforms do *not* adjust velocity, so replay results are optimistic here.
- The first fix after startup is trusted if no other evidence exists (there is nothing to compare
  it with).

## 3. Baseline estimator (`BaselineDrEstimator`)

A 2-D EKF in local ENU with state **x = [e, n, ψ, v, b]** (east, north, bearing, speed along
bearing, gyro bias about up).

### Propagation (on every MotionUpdate, and before every update)

```
ψ ← ψ − (ω_up − b)·dt
e ← e + v·sin ψ·dt          (only while heading is known)
n ← n + v·cos ψ·dt
v ← v                       (random walk σ = 0.7 m/s/√s)
b ← b                       (random walk σ = 2e−4 rad/s/√s)
```

- The position follows ψ: **non-holonomic constraint** (no lateral or vertical velocity) by
  construction.
- Q: position 0.3 m/√s; heading 0.01 rad/√s plus a 2% gyro scale error on each turn increment.
- **Heading unknown** (for example a network-only start): position is not propagated
  directionally. Its covariance grows isotropically by the worst-case distance D = Σ(|v| + 2σ_v)·dt
  (per-axis variance D²/2), and D resets on every position update.
- **Pulling away** (stationary → moving) sets v = 8 m/s with σ_v = 10 m/s. Without a speed source,
  the speed after a stop is genuinely unknown; before this fix, ZUPT kept v ≈ 0 with tiny variance,
  which made the filter overconfident (see D-012).

### Updates

| Evidence | When used | Model |
|---|---|---|
| GNSS position | TRUSTED (QUESTIONABLE only if `questionableRScale` is set, R × scale) | H = [I₂ 0], R = σ²I, σ = hAcc/1.51; NIS > 25 on a TRUSTED fix → reset position |
| GNSS speed | with the position update | R = max(sAcc, 0.2)² |
| GNSS course | speed ≥ 5 m/s | wrapped innovation, R = max(bAcc, 1°)²; the first course, or a jump > 60°, re-initializes ψ |
| Network fix | TRUSTED/QUESTIONABLE, and only if our σ > 0.5·σ_net | σ_net = 1.5·hAcc/1.51; NIS > 50 → reset to the network fix |
| Vehicle speed (OBD/synthetic) | always | R = max(std, 0.05)² |
| ZUPT | IMU stationary | v = 0 (R = 0.05²), b = ω_up (R = 0.003²) |

Updates use the Joseph form, and P is symmetrized after each step.

### Startup

- First trusted GNSS → position, speed and (if moving) heading. Mode GNSS_TRACKING.
- Network only → mode COARSE_ONLY, position = network fix, covariance = network accuracy × 1.5.
  The coarse fix is **never** turned into a confident point.
- Nothing → no estimate (UNINITIALIZED); mock output publishes nothing.

### Output (every 1 s tick)

`PositionEstimate` includes lat/lon, `Cov2`, accuracy (r68), heading ± σ (null if unknown),
speed ± σ, mode (`GNSS_TRACKING` if a GNSS update happened in the last 3 s, then `STATIONARY`,
then `DEAD_RECKONING` if heading is known and σ_v < 3 m/s, otherwise `COARSE_ONLY`), and a single
`Hypothesis`.

### Reference estimator

`GnssPassthroughEstimator` ("hold-last-fix") holds the last TRUSTED GNSS fix, with r68 growing at
20 m/s. Every estimator must beat it.

## 4. What the baseline cannot do (by design)

- It cannot determine absolute heading without GNSS. The magnetometer is recorded but unused,
  because the in-car magnetic field is disturbed. See the roadmap for EKF-GSF yaw.
- Distance without GNSS or OBD is poorly constrained, because speed is a random walk. That is why
  `synthObd` improves the 10-min outage p95 from about 1.4 km to about 250 m in simulation.
- No map: errors grow without bound during hours-long outages. Phase 2 road-state estimation is
  the intended fix.
- A random-walk model fused with repeated coarse fixes under directed motion becomes somewhat
  overconfident (`gnss_absent_from_start` with synthetic OBD: within95 ≈ 0.81). See progress
  limitations.
