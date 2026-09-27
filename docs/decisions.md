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
