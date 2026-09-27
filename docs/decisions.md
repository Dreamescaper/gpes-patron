# Decision log

Append-only. One entry per real choice between alternatives, **including rejected options**.
Supersede entries rather than editing their meaning.

Template:

```
## D-NNN: <title> — <Accepted|Rejected|Superseded by D-MMM>  (YYYY-MM-DD)
Context: why a decision was needed.
Decision: what we chose.
Alternatives: what else was considered, and why not.
Consequences: trade-offs, follow-ups, how to revisit.
```

---

## D-001: Kotlin with a shared pure-JVM core — Accepted (2026-09-27)
Context: the estimator must run live on Android and offline over recorded drives.
Decision: all models and logic live in `:core` (Kotlin/JVM, no Android). The app and `replay-cli`
both use it.
Alternatives: Kotlin app + a Python re-implementation for replay. Rejected: the two
implementations drift apart, and results would not reflect on-device behaviour.
Consequences: Python is only for plotting. Core must stay Android-free and wall-clock-free.

## D-002: Do not design around "last good fix + IMU integration" — Accepted (2026-09-27)
Context: GNSS may be absent at startup and for hours.
Decision: the target architecture is coarse absolute positioning + vehicle motion + road
constraints. Phase 1 is a baseline that measures how far the phone alone gets.
Alternatives: a pure INS/DR design. Rejected: phone IMU drift is unbounded over hours.
Consequences: road-state estimation (Phase 2) is the main line; the baseline is a yardstick.

## D-003: No accelerometer double integration — Accepted (2026-09-27)
Decision: speed comes from GNSS Doppler, vehicle speed (OBD/synthetic) or ZUPT. The accelerometer
is used only for gravity direction and stationary detection.
Alternatives: an integrated-accel velocity state. Rejected: a loose phone mount and unknown
orientation make it diverge within seconds to minutes.

## D-004: Port a flight EKF (PX4 EKF2 / ArduPilot EKF3)? — Rejected (2026-09-27)
Decision: take concepts only (innovation gating, delayed horizon/history, GPS checks with
hysteresis, reset on glitch, multi-lane idea, EKF-GSF yaw as a follow-up).
Why rejected: a 24-state 3-D model assumes a rigid calibrated IMU and free 3-D motion, and EKF3
is GPL-3. A car gains more from constraints (NHC, roads) than from states. See research.md.

## D-005: 2-D EKF [e, n, ψ, v, b] with yaw from gyro·gravity — Accepted (2026-09-27)
Decision: project the gyro onto the gravity/up direction for yaw rate, which avoids needing phone
mounting calibration. Velocity is aligned with heading (NHC).
Alternatives: full 3-D attitude with mounting-misalignment estimation (KF-GINS style). Deferred:
it may be needed for lateral-acceleration or turn-radius checks.

## D-006: Explicit trust states with the worst-severity combination — Accepted (2026-09-27)
Decision: TRUSTED/QUESTIONABLE/REJECTED/UNAVAILABLE per fix, with a set of reasons, plus a soft
confidence. The baseline fuses only TRUSTED fixes (QUESTIONABLE is opt-in via `questionableRScale`).
Alternatives: a single continuous score that weights R. Rejected for Phase 1 because it is harder to
audit; can be revisited once trust is calibrated on real drives.

## D-007: Recording in SQLite via SQLDelight, JSONL/CSV/GnssLogger as exports — Accepted (2026-09-27)
Decision: one `.db` per drive, with one schema shared by AndroidSqliteDriver and JdbcSqliteDriver.
Alternatives: (a) Room (Android-only, so no JVM reader); (b) an append-only protobuf log (fast,
but needs custom tooling to inspect); (c) GnssLogger text as the primary format (lossy: no trust or
estimates). Rejected in favour of SQLite, which is queryable with the `sqlite3` CLI.
Consequences: about 1M rows/hour. The reader loads everything into memory (fine on desktop; the
phone export uses largeHeap).

## D-008: Canonical record order = (tNs, record kind) — Accepted (2026-09-28)
Context: equal timestamps across tables came back in a different order from SQLite than from
live delivery, which broke round-trip equality and could change replay results.
Decision: `RecordOrder.comparator` everywhere (reader, simulator, replay).

## D-009: Fused mock mode is the default output target — Accepted (2026-09-27)
Decision: publish through `FusedLocationProviderClient.setMockMode/setMockLocation`. Platform
`gps`/`network` test providers are optional.
Why: Google Maps uses fused, and fused mock leaves the platform `gps` real, so real GNSS still
reaches trust and recovery. Overriding `gps` blinds us to GNSS recovery (raw GnssStatus and
measurements continue; this is to be verified per device).
Verified on the emulator (API 37): fused last location = our estimate, flagged mock.

## D-010: Passive location provider not subscribed — Accepted (2026-09-27)
Why: it re-delivers other providers' fixes under their original provider name, so GNSS would be
double-counted. The fused, gps and network subscriptions cover the useful data.

## D-011: Reset-after-consistent-stream, never onto physically impossible positions — Accepted (2026-09-28)
Context: in the first matrix run, the Lima teleport was accepted after 120 s of consistent spoofed
fixes (a 12 000 km error). Separately, real GNSS returning after DR drift was rejected for the
whole 120 s window.
Decision: `IMPOSSIBLE_VELOCITY` is not a resettable reason. A fresh agreeing network fix
shortens the reset to 15 s.
Alternatives: no reset at all (DR drift would lock out real GNSS forever); an immediate reset on
consistency (trivially spoofable).
Consequences: a consistent, reachable spoof with no network still wins after 120 s. This is
tracked as a known limitation.

## D-012: Speed becomes unknown when pulling away from a stop — Accepted (2026-09-28)
Context: after ZUPT, v ≈ 0 with σ ≈ 0.05. During an outage the car drove on while the filter
stayed put and confident; returning GNSS was then gated out (false rejection 38%, recovery 122 s).
Decision: on stationary→moving, set v = 8 m/s with σ = 10 m/s, and raise the speed random walk to
0.7 m/s/√s.
Result (simulated): drop_2min phone-only max error 1649 m → 395 m, recovery 181 s → 2 s, false
rejection 0.38 → 0.

## D-013: Velocity–position consistency trust check — Accepted (2026-09-28)
Context: a gradual drift is tracked by the EKF, because each step passes the innovation gate.
Decision: compare ~10 s displacement against the integral of the reported Doppler velocity.
Consequences: it catches drift that moves position without velocity. It is defeated by a spoofer
that keeps Doppler consistent, and simulator transforms don't model that, so it is optimistic.

## D-014: Emulator GNSS treated as untrusted; "fuse QUESTIONABLE" is a UI toggle — Accepted (2026-09-28)
Context: the emulator's GNSS moves while its IMU is perfectly static and it reports 0 satellites
used, so trust (correctly) flags it and the estimator never initializes.
Decision: keep the checks strict. Expose `BaselineConfig.questionableRScale = 4` as a UI toggle for
plumbing tests and quirky devices. The value is recorded in the session config.
Alternatives: weaken checks on emulators (rejected: hides real behaviour).

## D-015: AGP 9 built-in Kotlin, compileSdk 37 / targetSdk 36 — Accepted (2026-09-27)
Why: current Compose BOM (2026.09) requires compileSdk 37. targetSdk stays 36 as agreed.

## D-016: Model competent spoofers in replay (Doppler-consistent) — Accepted (2026-09-28)
Context: offset/drift transforms left the reported velocity untouched, so the velocity–position
check caught them easily. That overstated spoof detection.
Decision: add `consistentVelocity` to `offset`/`drift`, plus two standard scenarios. Both variants
are kept, because naive spoofers (and some jammers) exist too.
Consequences: R-002 shows slow consistent spoofing passes the Phase 1 trust evaluator. This
motivates prioritising OBD speed and road-state estimation (roadmap).

## D-017: Record raw cellular and Wi-Fi observations — Accepted (2026-09-28)
Context: coarse location currently comes only from Google's `network` provider. That is a black box,
it usually needs mobile internet (which may be restricted in the target areas), and it gives no
timing advance.
Decision: record serving and neighbour cells (identity, ARFCN/PCI, signal, timing advance, modem
timestamp) about every 2 s via `requestCellInfoUpdate` (exact cached repeats skipped), and Wi-Fi
APs (BSSID, RSSI, frequency, per-AP seen time) from requested scans (~4 per 2 min) plus system
scan broadcasts. Recording only for now; they are not yet used by trust or the estimator.
Alternatives: (a) use only the `network` provider (rejected: not reproducible or offline);
(b) TelephonyCallback cell listeners (possible later; polling is simpler and gives a uniform
cadence); (c) record SSIDs (rejected: personal data such as home network names, and BSSID is what
positioning needs).
Consequences: this enables an offline cell/Wi-Fi resolver (OpenCelliD/BeaconDB + timing-advance
rings), compared against Google network in replay. The tables are additive, and readers tolerate
old bundles.

## D-018: SensorInfo.type serialized as `sensorType` — Accepted (2026-09-28)
Context: JSONL export of real drives crashed, because `SensorInfo.type` collides with the `"type"`
class discriminator. Simulated drives have no SensorInfo, so tests missed it.
Decision: `@SerialName("sensorType")`. The JSONL round-trip test now includes every record kind.

## D-019: Compass via ellipse (hard/soft iron) fit + alignment — Accepted (2026-09-28)
Context: the plan listed the magnetometer as a "weak prior", but the code never used it. **The
earlier statement was not implemented; this is a correction.** Without GNSS, absolute heading was
otherwise unknowable: a network-only start stays heading-less, and gyro drift is unbounded over hours.
Decision: an iron-distortion fit of the horizontal field (circle → ellipse), with coverage measured
by bias-corrected gyro heading, then alignment ψ = θc + c: from trusted GNSS course, or, without
GNSS, from the learned forward axis + WMM declination. Heavy gating (vertical component, radius,
gyro consistency). See estimation-algorithm §3b.
Alternatives:
- *Ship-style deviation card* (ψ = θ + harmonics(θ) fitted on raw θ): **rejected after
  measurement**. With realistic hard iron (≈ 15 µT vs a horizontal field of 20 µT in Kyiv) the
  deviation is far from sinusoidal: p95 was 70°.
- *Android's calibrated heading (ROTATION_VECTOR yaw) as-is*: rejected as the main path. Android's
  calibration targets a handheld phone, not car iron. It is kept as the low-confidence
  `UNCORRECTED` fallback.
- *Ignore the compass entirely* (status quo): rejected, see R-004 (heading p95 over 55 min: 105°
  gyro-only vs 7.6°).

## D-020: Compass updates must not average down correlated errors — Accepted (2026-09-28)
Context: 1 Hz compass updates with a persistent 40° error shrank heading σ to 7° (confidently wrong).
Decision: update the heading only when σ_ψ > σ_compass (`usefulFraction = 1.0`). σ grows with
coverage and fit quality (see §3b).

## D-021: Coarse fixes fused when spaced in time and distance — Accepted, supersedes the σ-only rule (2026-09-28)
Context: the "fuse only if our σ > 0.5·σ_net" rule meant that, once DR was good, later network
fixes were never used, so the error of the *first* network fix persisted (~700 m) even with accurate
heading and speed.
Decision: also fuse when ≥ 15 s and ≥ 150 m of odometry have passed since the last fused coarse
fix (keeping the ×1.5 inflation). Stationary repeats are still not fused.
Result (sim, R-003 vs R-001): 10-min outage with synthObd p95 252 → 117 m; start without GNSS stays
calibrated (within95 0.94–1.0).

## D-022: Simulator realism: GAME_ROTATION_VECTOR, magnetometer, gyro scale error — Accepted (2026-09-28)
Decision:
- The sim emits GRV with a slowly wandering 0.5° tilt error (real phones provide GRV).
- It emits MAG/MAG_UNCAL from the WMM-like field with car hard/soft iron, phone hard iron, noise
  and random 20 µT anomalies, plus a `GeomagneticReference`.
- It has optional gyro scale error and bias random walk (default off to keep old results
  comparable; R-004 uses 1% and 2e-4).
Why: with an ideal constant-bias gyro, the compass looked useless for outages (R-003). The
realistic gyro shows its real value (R-004).
