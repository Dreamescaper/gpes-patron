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

## D-005: 2-D EKF [e, n, ψ, v, b] with yaw from gyro·gravity — Accepted (2026-09-27); state extended by D-029 (speed scale s)
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

## D-023: Localize the UI, not the data — Accepted (2026-09-28)
Decision: English is the default resource locale and Ukrainian (`uk`) is a full translation,
selectable per app on Android 13+. Everything written to recordings stays locale-independent:
annotation labels are stable English codes (the button text is translated), and trust reasons, modes
and record names stay English enum codes. This keeps drives from different phones and languages
comparable in replay and analysis. On-screen reason codes stay as codes (they are technical and map
1:1 to docs/estimation-algorithm.md).

## D-024: Compass self-assessment with a verdict and reason codes — Accepted (2026-09-28)
Context: the question "can we tell from recordings that the magnetometer on this holder is
useless?" The compass only inflated σ for poor coverage; it never checked the field itself.
Decision: `CompassQuality` has metrics (radius vs WMM horizontal field, scatter, centre drift, hard
iron, disturbed/shaky time fractions, saturation, wireless charging, GNSS alignment residual), a
verdict and stable reason codes (thresholds in estimation-algorithm §3b). UNUSABLE silences the
compass; MARGINAL adds 10° to σ. It is shown live in the app and offline via `replay compass-report`
(cross-validated: align on the first half of trusted GNSS, evaluate on the second).
Alternatives: a single continuous quality score (harder to explain and act on); offline-only analysis
(the app would keep using a bad compass).
Consequences: the thresholds are simulation-based guesses. Tune them on real mounts (roadmap).

## D-025: Shake gate depends on where "up" comes from — Accepted (2026-09-28)
Context: the natural idea is "don't calibrate while the phone shakes". Measured in simulation
(wobble bursts at 3 Hz):
- With GAME_ROTATION_VECTOR, 3° wobble (≈ 0.7 rad/s RMS) did **not** hurt (p95 1.9° gated vs 1.9°
  ungated), while a naive 0.15 rad/s gate threw away 35% of readings and silenced the compass
  entirely under continuous wobble.
- 8° wobble (≈ 1.9 rad/s) did hurt: p95 3.7° → 1.9° with the gate.
- Without a rotation vector (accelerometer "up"), 3° wobble hurt the heading (p95 12.1° → 10.7°
  with the gate), and the fit scatter dropped 4.3° → 0.8°.
Decision: threshold 1.2 rad/s with a rotation vector and 0.15 rad/s without, plus the
horizontal-accel gate only without one. The gate covers fit samples, alignment and readings.
Alternatives: always gate at a low threshold (rejected: loses the compass on rough roads with no
benefit when GRV is present); no gate (rejected: strong wobble and accel-only phones suffer).

## D-026: Record power/charging state — Accepted (2026-09-28)
Why: a wireless-charging holder is a time-varying magnetic field (its coil current changes), and
temperature shifts magnetometer offsets. `PowerState` (plug type, charging, battery current, voltage,
level, temperature) is polled every 1 s and recorded on change or every 5 s. The compass flags
`WIRELESS_CHARGING`.

## D-027: OBD via ELM327 over Bluetooth Classic SPP — Accepted (2026-09-28)
Context: the user's adapter is a cheap "Mini Bluetooth ELM327 v1.5/v2.1" clone (Bluetooth Classic).
Decision: `Elm327` protocol client in `:core` (JVM-testable with a scripted fake), and `ObdSource` in
the app. It uses paired devices only (no scanning; `BLUETOOTH_CONNECT`), SPP with fallbacks
(insecure socket, then RFCOMM channel 1 via reflection, which clones often need), auto-reconnect
with backoff, and polls PID 0D at ≤ 10 Hz. The response-count suffix (`010D1`) is probed and dropped
if unsupported. Unknown AT commands are tolerated. Samples are stamped at the request/response
midpoint. Every raw exchange is recorded (`obd_raw`) for debugging real adapters. Read-only: no
Mode 04 or writes.
Alternatives: Wi-Fi ELM327 (rejected: it occupies the phone's Wi-Fi, which hurts network location
and Wi-Fi scans); BLE adapters (deferred: needs a GATT transport, and the user's adapter is Classic);
CAN sniffing for wheel speeds (deferred: model-specific).

## D-028: ZUPT updates are local (Schmidt-style) — Accepted (2026-09-28)
Context: found while testing OBD: after a 5-min outage, the first stop moved the position by
**178 m** (error 2 → 178 m). A single noisy bias sample (R = 0.003²) was propagated through the
P(bias, position) correlation, whose lever grows as v·t²/2. That is formally correct EKF behaviour,
but it is dominated by noise and model mismatch.
Decision: ZUPT updates change only v and b (Joseph form for the suboptimal gain keeps P
consistent). Result: max error in that outage 178 → 8 m. On the 1-hour drive, phone-only p95 went
2685 → 1396 m (R-006 vs R-004).

## D-029: Speedometer scale error as an EKF state — Accepted (2026-09-28)
Context: OBD speed is typically 1–5% high (tyre wear and size, OEM over-reading). Over an hour
without GNSS, 3% is kilometres.
Decision: state s with z_obd = v·(1+s), learned whenever trusted GNSS speed and OBD coexist, frozen
(tiny random walk) otherwise. Simulation (4% scale, integer km/h, 0.15 s delay, 5-min outage): max
error 8 m with learning vs 65 m without, and within95 0.99 vs 0.63.

## D-030: GNSS vs OBD speed as a spoofing check — Accepted (2026-09-28)
Decision: QUESTIONABLE when |v_gnss − v_obd| > 1.5 m/s + 8%. A spoofer can fake a consistent GNSS
track, including Doppler, but not the car's speedometer. Simulated Doppler-consistent 2 m/s drift:
missed detection 99% → 36% (10-min drive) and 99.6% → 80% (1-hour drive, where the drift direction
varies relative to motion). Clean data: 0 false rejections.

## D-031: Coarse fixes vs distance driven (odometry chord, voting) — Accepted (2026-09-28)
Context: on the jammed drive (R-008) the estimator had no heading, so it followed every Google network
fix: a fix 1.1 km off after 170 m of driving, and fixes that stayed at a traffic light for ~25 s after
the car left. OBD + gyro know how far the car moved and whether the path was straight, without knowing
the absolute heading.
Decision: `MotionTracker.odometry(t1, t2)` gives the distance and the straight-line chord from OBD speed
along the gyro bearing. A network fix is REJECTED (`COARSE_ODOMETRY_MISMATCH`) when most of the last 3
trusted network fixes disagree: |d − chord| > K·√(σ₁²+σ₂²) + 5%·distance + 10 m, K = 3.
Alternatives:
- Compare with the last trusted fix only (first version): cascades. A stale fix that passes becomes the
  reference and the correct fix after it is rejected (R-008: max jump 578 → 908 m). Rejected.
- K = 2: rejects 3 fixes on R-007 (all truly 160–290 m off) and the stale fix on R-008, but the
  downstream metrics are mixed (R-007 GNSS absent from start p95 810 → 932 m; 1-h drop 380 → 357 m).
  Kept as a config value, not the default, until more drives exist.
- A directional check (triangle/shape of several fixes vs the dead-reckoned shape): stronger, but it is
  shape fitting, which the user parked; it belongs with the heading work.
- Put it in the estimator (inflate R instead of rejecting): the dirless EKF already accepts these fixes
  as "inside a growing disk"; the flaw is the disk, not R. A ring constraint needs a heading bank or a
  particle filter (roadmap).
Consequences: small, safe gain (R-009). The real fix for R-008 is an absolute heading; this check stays
useful as a sanity gate afterwards. Revisit K with more drives.

## D-032: Heading bank (Gaussian-sum filter over heading) for starts without GNSS — Accepted (2026-09-28)
Context: on the jammed drive (R-008) the EKF never had a heading, so it ignored the gyro and OBD for
position and hopped between network fixes. The gyro bearing is very stable (R-010: −0.12°/min), so
only one unknown offset is missing, and an offline rigid fit of the driven path to network fixes
recovered it (R-010).
Decision: `HeadingBank`, 12 heading hypotheses weighted by coarse-fix likelihood, handing heading,
position and joint covariance to the EKF when σψ ≤ 15° after ≥ 4 fixes and ≥ 300 m (§3c).
Alternatives:
- Rigid fit per fix (the script): jumps of up to 81 m in 2 s when a new fix swings the rotation
  about a centroid ~1 km away; thinning fixes did not help; fixed-gain smoothing of the rotation cut
  jumps but hurt accuracy (p50 30 → 79 m). Rejected: the heading must be a state with its own
  uncertainty.
- Initialise the EKF heading from the bearing between two fixes: one pair is noisy, and a single
  EKF with σψ ≈ 60° is badly non-linear. Rejected.
- Keep the bank running after hand-over and make coarse fixes position-only in the EKF: measured
  worse (R-007 GNSS absent: p50 47 → 108 m, within95 0.79 → 0.21), because the position gets
  confident while heading errors stay uncorrected. Rejected.
- Hand-over position std ×1/×2/×3: within95 0.76/0.78/0.81 with the same errors; ×2 kept. The real
  calibration problem is the coarse-fix error model (roadmap).
Consequences: R-011. Calibration after hand-over is optimistic when network fixes are correlated or
wrong (within95 0.79 on R-007). The bank does not help once a heading is known (drops after GNSS).

## D-033: Honest OBD handling in the replay ladder — Accepted (2026-09-28)
Context: `drop_source` could not remove OBD, so on real drives every rung used the recorded OBD and the
`+synthObd` rungs added a second speed source. Without GNSS, speed v and speedometer scale s enter only
as v·(1+s); two conflicting sources (−2.9% and +3%, 0.8 s vs 0.15 s latency) drove v to 1.9× truth.
This stayed hidden until the heading bank made the EKF move along its speed.
Decision: `drop_vehicle_speed` step (keeps synthetic speed). Every rung drops recorded OBD except the
new `phone+obd` and `phone+network+obd`; `+synthObd` rungs replace the recorded OBD.
Alternatives: a per-variant flag in the estimator (rejected: the replay layer owns the input); keep
the old ladder and document it (rejected: it produced a false 4.6-km regression).
Consequences: real-drive numbers before 2026-09-28 for gyro-only / phone-only / phone+network include
OBD; compare with `phone+obd` / `phone+network+obd` now.

## D-034: Robust coarse updates with a consistent-stream reset — Accepted (2026-09-28)
Context: after the heading bank, a single bad network fix (R-008 at 358 s: 365 m off at hAcc 110 m)
moved the estimate and rotated the heading by 9°, and the DR then zigzagged for 40 s. The old rule
updated with full weight up to NIS 50 and **reset onto the fix** above it, so one far outlier made the
estimate jump (sim: 5 → 378 m). But ignoring far fixes is dangerous when it is *our* estimate that
drifted.
Decision: NIS ≤ 9.21 → normal; above → candidate with R × NIS/9.21. Three candidates in a row
(≥ 20 s) whose spacing matches the odometry chord → reset onto them; restart the heading bank if no
GNSS for 60 s (a big DR drift means a wrong heading; with recent GNSS it was spoofing and the heading
is fine).
Alternatives (measured on R-007, 13 scenarios × 2 rungs, p95 geo-mean vs plain):
- Also require the candidates to show the same offset from us: 1.00 / 0.89, but ramp capture
  106 → 668 m, because under spoofing our estimate is dragged between fixes and the stream never forms.
  Rejected.
- Clear candidates on every GNSS update: the stream never forms under spoofing (p95 up to 757 m).
  Rejected.
- Always restart the heading on a stream reset: ramp capture 106 → 266 m (2 min without heading after
  a spoof). Rejected; only after 60 s without GNSS.
- Two candidates over 10 s: geo-mean 0.84 / 0.85, but the 1-h drop 387 → 536 m. Rejected for now.
- Threshold 5.99 (95%): the 358-s snap 42 → 18 m (9.21: → 37 m), geo-mean 0.86 / 0.96, but the 10-min
  and 1-h drops +28% / +19%. Kept as a config option.
Consequences: R-012. Recovery from a real drift now takes 3 agreeing fixes (~50–60 s) instead of one.
The 09:05 zigzag on R-008 is only slightly smaller; its later snaps are correct corrections of a
heading that the first fix had already rotated. Spoofing scenarios are mixed (ramp capture 106 → 165,
Doppler-consistent ramp 255 → 185 with OBD).

## D-035: Limit the heading correction from coarse fixes — Rejected (2026-09-28)
Context: the user observed on R-008 that from 09:05:32 to 09:05:41 the track followed the road's shape
exactly but was offset to the right: the heading was right, only the position was off. The fix at 358 s
corrected in the right direction, but the EKF explained part of the lateral offset as a heading error
(−8°), and the track then turned off the road (snap of 78 m at 383 s).
Tried: `coarseHeadingGain` = fraction of the Kalman heading/bias correction that a coarse fix may apply
(Joseph form keeps P consistent).
Measured: R-008 snaps at 358 / 383 s: gain 1.0 → 37 / 78 m, 0.5 → 49 / 78, 0.25 → 81 / 93, 0 → 180 /
153 m; snaps > 50 m over the drive 7 / 7 / 8 / 12. R-007 p95 geo-mean vs 1.0: 1.05 / 1.06 / 1.05 (with
OBD), worst ×2.6 (Doppler-consistent ramp); within95 improves (GNSS absent 0.77 → 0.86 / 0.88 / 0.92).
The heading does turn less at 358 s (−5° at 0.5), but coarse fixes are needed to keep refining the
heading, so the DR drifts more between fixes and the snaps grow.
Decision: keep 1.0 (parameter kept for experiments). The parallel-offset case is what the Phase 2 road
constraint solves directly (a track with the road's shape is snapped onto the road).

## D-036: OBD is optional; accelerometer speed with bounded error — Accepted direction (2026-10-01)
Context (product decision by the user): the app must work at least somehow without an OBD adapter.
Today, without OBD the speed is a random walk and the output is close to the coarse fixes themselves:
on the jammed drives the current estimator gives p50 14–16 m / p95 52–77 m with OBD, but p50 44–50 m /
p95 199–263 m without it (R-013b, R-015b). Open-loop accelerometer integration is not an option: 32–44
km/h speed error within 1–5 min and hundreds of metres of distance error (R-014).
Decision: rely more on the accelerometer when OBD is absent, but never open-loop. Accelerometer speed is
a filter state whose error is bounded by everything else:
- ZUPT at stops (speed = 0, the strongest anchor; a city drive stops every 1–3 min);
- centripetal speed in turns, |a_lat| / |ω| (correlation with OBD speed 0.93–0.98 with a proper "down");
- the distance between coarse fixes vs the dead-reckoned chord (D-031 logic, now as a speed constraint);
- GNSS speed when present, OBD when present (then the accelerometer only fills short gaps, e.g. the
  ~0.8 s OBD latency);
- forward/reverse from accelerometer + gyro (validated on a 3-point turn).
With OBD, nothing changes in principle: OBD stays the speed source and the accelerometer is auxiliary.
Alternatives: keep "speed from GNSS/OBD/ZUPT only" (rejected by the user: no-OBD would stay near the
coarse-fix accuracy); open-loop integration (rejected by measurement).
Consequences: the invariant in AGENTS.md is reworded from "no accelerometer double-integration" to "no
open-loop accelerometer integration". Prerequisites: a correct gravity estimate ("down"; Android's
leans in turns, P14), the forward axis, stop detection that works on an idling car. Roadmap P1.

## D-037: Own gravity ("down") estimate from gyro + gated accelerometer — Accepted (2026-10-01)
Context: Android's GRAVITY and rotation vectors lean towards the apparent gravity in sustained turns
(P14), which breaks everything that measures horizontal acceleration: the mount forward axis, the
compass anomaly gate (UNUSABLE without a charger, R-014), reverse detection, and the planned
accelerometer speed without OBD (D-036).
Decision: `MotionTracker` carries up with the gyro and corrects it towards the accelerometer with
τ = 30 s, gated on | ‖a_lp‖ − mean‖a‖ | < 0.3 m/s² and |yaw rate| < 0.05 rad/s.
Alternatives:
- ±60 s mean of the raw accelerometer: good (centripetal corr 0.92–0.95) but needs a minute of future
  data, so not usable live. Rejected for the app; fine for offline analysis.
- Gate on | ‖a‖ − 9.80665 |: on the Pixel 8 only ~50% of samples pass (‖a‖ p10 9.0–9.3, p90 10.5–10.9),
  so the gyro drifts between corrections. Replaced by the phone's own long-term mean.
- τ = 10 / 30 / 60 s: centripetal corr 0.97–0.98 / 0.97 / 0.94–0.97; 30 s chosen.
- Keep Android's rotation vector: corr 0.26–0.31. Rejected.
Consequences (R-016): compass mostly MARGINAL instead of UNUSABLE, more GNSS-aligned readings; estimator
p95 geo-mean ×0.85 (R-007) / ×0.94 (B); a few spoofing runs without network and OBD got worse. Exposed
the questionable-stream lock-out fixed in D-038.

## D-038: Accept a consistent questionable GNSS stream after an outage — Accepted (2026-10-01)
Context: after an outage the returning GNSS was QUESTIONABLE (innovation gate, NIS 13.8–50); the
estimator ignores QUESTIONABLE GNSS, so its prediction cannot converge and the NIS can stay in the band
for minutes (R-007 10-min drop: 500 s without GNSS once D-037 changed the DR track slightly). The
reset-after-consistent-stream rule existed only for REJECTED fixes.
Decision: a mutually consistent stream of fixes QUESTIONABLE only through the innovation gate is
accepted after 10 s, if the stream began after ≥ 30 s with no fix of that source at all.
Alternatives:
- Without the outage condition: a Doppler-consistent spoofer with OBD was missed 51% of the time
  (test threshold; before 36%). Rejected.
- Outage measured from the last *trusted* fix: during a long spoof the last trusted fix ages too, so
  the spoofer qualified after 30 s. Rejected; the gap must have no fixes at all.
- Let the estimator use QUESTIONABLE GNSS with inflated R (`questionableRScale`): also helps spoofers
  during tracking. Not chosen.
- Sharing the stream-tracking fields with the rejected-stream rule: changed when the older rule fired
  even with no outage in the data (R-007 overconfident noise 35 → 103 m). The rule has its own fields.
Tests: `QuestionableResetTest` (returning after a 60-s outage → accepted after ~10 s; the same
disagreement without an outage → stays QUESTIONABLE).


## D-039: Coarse-odometry check in vector form when the heading is known without GNSS — Accepted (2026-10-01)
Context: the D-031 check compares only the *distance* between network fixes with the odometry chord.
Drive A, 632 s: a fix 400 m off (hAcc 136 m) sat as far from the previous fixes as the car had driven,
but north-west instead of east; it was TRUSTED. Same for R-008 939 s (306 m off, hAcc 100 m).
Decision: when the estimator heading std ≤ 15° (`coarseOdoVectorMaxHeadingStdDeg`) and no GNSS/fused fix
was trusted for 180 s (`coarseOdoVectorNoGnssS`), each voter compares the displacement *vector*: the
gyro-frame odometry displacement rotated to the estimated heading at the new fix. A voter agrees if
|z − o| ≤ K_v·√(σ₁²+σ₂²+(chord·σψ)²) + 5%·distance + 10 m, with σψ ≥ 5° and K_v = 2.5. Otherwise the scalar
form stays. Voting and the "agrees with the previous rejected fix" exit are unchanged.
Alternatives:
- K_v = 3 (same as scalar): the 2-D residual is looser at the same K; caught neither 632 s nor 939 s.
- K_v = 2: also rejected good fixes (A 622 s, 116 m off at hAcc 78; A 760 s, 33 m off) and A p95 77 → 97 m.
- No GNSS condition / 60 s: in `gnss_ramp_capture_doppler_consistent` (B) the spoofer steers the EKF
  heading; honest network fixes were rejected and p95 went 657 → 940 m (60 s: 860 m). The heading stays
  spoofed after GNSS is rejected, so the window is 180 s (= the voter age limit).
Consequences (R-017): both bad fixes rejected, no false rejections against the driver-checked truth;
accuracy almost unchanged (the robust weighting D-034 already gave them little weight).

## D-040: Weight coarse-odometry votes by accuracy, with a σ floor — Accepted (2026-10-01)
Context: drive A, 699 s: a fix 1117 m off (hAcc 157 m) passed the D-031 vote 2:1. The only voter that
could tell (hAcc 200 m) said no; two voters with hAcc 700 m said yes, because their tolerance is
> 1.2 km. The accepted fix pulled the estimate 100 m (error 21 → 122 m).
Decision: each vote weighs 1/(σ₁²+σ₂²) with σ = max(hAcc/1.515, 50 m) (`coarseOdoWeighted`,
`coarseOdoWeightMinSigmaM`); rejected when the weight against exceeds the weight for.
Alternatives:
- Weight without a floor: R-007 109.8 s claimed hAcc 38 m but was 180 m off; its single vote then
  rejected the good 121.7-s fix (26 m off), and `ramp_capture` net+obd p95 went 166 → 362 m
  (`ramp_capture_doppler_consistent` 154 → 371 m), though `teleport_country` p50 53 → 1 m and `jump_5km`
  p95 68 → 34 m improved. Rejected: weighting by claimed accuracy hands power to overconfident fixes.
- Floors 30 / 50 / 80 m: identical results on all four drives; 50 m chosen (middle).
- Vague voters abstain above an hAcc threshold: a second threshold with the same effect; not chosen.
Consequences (R-018): A 699 s rejected, A p50 16.1 → 15.1 m, p95 76.7 → 74.8 m; all other runs unchanged.

## D-041: Road data as OSM tiles downloaded around the current location, own compact format — Accepted (2026-10-01)
Context: the road constraint (Phase 2) needs drivable roads with topology wherever the car is; the user
wants the app to fetch them by itself around its location.
Decision: a fixed grid of 0.05° × 0.08° tiles (≈ 5.6 km). A tile holds every drivable OSM way that touches
it, with node ids, from an Overpass query (`RoadTile.overpassQuery`), stored as a gzipped binary file
(`RoadTileCodec`, 1e-7° coordinates). The network is built from all loaded tiles at once
(`RoadNetwork.build`): duplicate ways merge by id and ways split at shared nodes, so tile borders join
by themselves. Car-park aisles, driveways, drive-throughs and emergency access are left out (off-road,
not weak roads); areas and `access=no` too. Kotlin in `:core`, so the app and replay share one converter.
Alternatives:
- One region file: does not follow the car. Rejected after the user's answer.
- GraphHopper or Barefoot import: heavy, routing-oriented, JVM-desktop parts; we need geometry,
  topology and four tags. Rejected.
- Car parks as weak roads: a matcher locked to an aisle would steer the heading while manoeuvring.
  Rejected; they count as off-road.
Measured: Kyiv extracts of the four drives: 9 857 ways → 15 411 segments (1 049 km), load 57 ms + build
55 ms on the JVM, 598 KiB in 7 tiles.

## D-042: EKF stays; road matcher on a road-free twin feeds gated road updates — Accepted (2026-10-01)
Context: plan in road-constraint.md. First version ran the HMM matcher on the road-constrained EKF pose.
Decision: `BaselineDrEstimator` with a road network keeps an internal twin that gets the same inputs but
never uses the road. The matcher runs on the twin's pose, the constrained EKF applies the matched road
(heading, cross-track, along-track). After two consecutive cross-track gate rejections the constrained
EKF takes the twin's state (resync).
Alternatives:
- Matcher on the constrained pose: a road update confirms the road it came from; on R-007 with GNSS
  absent it locked onto wrong roads (p50 56 → 115 m, within95 0.69 → 0.35). Rejected.
- A road-state particle filter replacing the EKF: kept for later (M7); the EKF with soft updates can be
  switched off per case, which the "do not overdo" requirement needs.

## D-043: When road updates apply, and an honest radius — Accepted (2026-10-01)
Decision: road updates only when the road (same street, travel direction within 45°) has probability
≥ 0.9 for ≥ 150 m, P(off-road) ≤ 0.1, pose σ ≤ 50 m, speed ≥ 4.2 m/s with σ_v ≤ 1.5 m/s (OBD or GNSS),
at most every 40 m, never below the road's own variance (P5), χ² gate 9. Heading only on straight road
(< 5° over ±40 m) with the gyro straight (< 3° over the last 4 steps), σ 3°; cross-track σ² = (half
width)²/3 + 4², only ≥ 15 m + 1σ from segment ends. The reported covariance is the road-free twin's
whenever it is larger.
Alternatives (R-020):
- Entry after 50 m, off-road at 2.5σ: with the road removed along the truth (M5), the matcher locked
  onto parallel streets 40–60 m away: window p95 48 → 106 m (2026-09-28), 20 → 79 m (A), worse than no
  road (53, 28 m). 100 m / 2.0σ fixed the jammed drives but B lost (21 → 42 m). 150 m / 2.0σ: no worse
  than without the road anywhere (A 31 vs 28 m, noise).
- No intersection margin: cross-track updates near a node use the next street's normal; margin 30 m +
  2σ cost R-007 p50 43 → 69 m; 15 m + 1σ kept most of the gain.
- Without a known-speed condition: phone+network+osm on R-007 p95 ×1.17 (clean 35 → 164 m). With it,
  the road is effectively off without OBD (×1.000).
- Narrowed radius (constrained covariance): within95 0.18–0.76; along-only honest variance: 0.50–0.98.
  The twin's radius keeps calibration (mean within95 0.76 → 0.77, 0.75 → 0.75).

## D-044: Along-track position from the path shape (turns, bends) — Accepted (2026-10-01)
Decision: `AlongTrackMatch` aligns the gyro heading profile (odometry distance back) with the matched
road's bearing profile (from the hypothesis trail) over ≤ 250 m, shift ±50 m. Accepted only if the road
turns ≥ 20° inside the window, the residual RMS < 6°, and no shift ≥ 15 m away is within 2× the cost.
σ = half the width of the cost valley (≥ 4 m). Before the update, the along-track variance is restored to
the twin's (cross-track updates on differently oriented streets shrink it without along evidence). At
most every 150 m. A lane change (out-and-back, net ≈ 0) never qualifies.
Measured (R-020, GNSS-truth drives): along-track p50 48.9 → 46.3 m (R-007 GNSS absent), B 1-h drop p95
22.4 → 20.8 m. Fires a few times per drive. On the jammed drives the truth's along-track position is tied
to OBD distance (osm_match), so it cannot judge M4.

## D-045: Small prior against parallel residential carriageways; no fix hold — Accepted (2026-10-02)
Context (R-020b): with a geometric right-road metric, the matcher on 2026-09-28 was confidently on a
parallel carriageway ~20 m from the main one for minutes: a residential "Голосіївський проспект" (9:25–
11:53) and, after a bad fix at 6:22 (130 m off, 107 m sideways, plausible for its hAcc 88 m), a primary
local carriageway (6:26–7:03), pulling the estimate ~20–30 m sideways. Parallel carriageways have the
same shape and turns, so nothing the matcher sees separates them when the twin's pose is 20–50 m off.
Decision: emission penalty 0.3 nats per step for residential / living_street / unclassified candidates
(`minorRoadPenalty`); none for service (`serviceRoadPenalty` 0).
Alternatives:
- Penalties 1.0/2.0, 0.3/0.6, 0.1/0.3, 0.05/0.15 (minor/service): any service penalty, even 0.15, made
  R-007 GNSS absent p50 47.8 → 66 m (that route uses service ways). Minor only 0.3: 2026-09-28 right
  when confident 0.71 → 0.88, p50 7.8 → 8.0; R-007, A, B unchanged.
- Cheaper entry from off-road (`pEnter` 0.05 → 0.01): no effect; the jump to the side carriageway went
  through real graph links, not through the off-road state.
- Hold a fix > 40/60/80 m to the side of a confidently matched straight road until the next fix confirms
  (`holdOffRoadFixM`, kept, default off): no effect at 6:22 (the matcher was already unsure there, 0.64,
  because the side carriageway had appeared), and R-007 GNSS absent p95 168 → 188 m. Rejected.
Open: the primary side carriageway case (6:26–7:03) remains.

## D-046: Re-time OSM truths with OBD latency and gyro corners; OBD latency compensation and corner fix stay off — Accepted (2026-10-02)
Context: on 2026-09-28 +5:32–5:42 the gyro turned 2–3 s before the OSM truth. `osm_match.py` places the
car by cumulative OBD distance without the adapter's ~0.8 s latency, and the EKF shares that lag, so the
old truth hid it.
Decision:
- `tools/truth/align_turns.py`: shift the truth distance by 0.8 s, then anchor along-track at isolated
  route corners (≥ 30°, no other corner within 100 m) by matching the gyro heading profile (shift ±8 s,
  RMS < 8°, unique), interpolating between anchors. The route does not change. Both jammed truths are
  re-timed (old ones kept as `*.truth.unaligned.json`, local only). Residual corner shifts after the
  latency: +15 / −20 m (2026-09-28), +20 / +13 / 0 m (A).
- `BaselineConfig.obdLatencyS` (advance the reading by latency × LS slope over 1.5 s) stays 0.
- `RoadConstraintConfig.cornerFix` (2-D road-point update right after a completed turn ≥ 45°) stays off.
Alternatives / measurements (R-021):
- Without the latency shift, corners alone gave +26 / −17 m and S-bends slid onto the neighbour corner
  (−72 m), hence the isolation rule.
- Latency compensation: GNSS drives better (all scenarios, p95 geo, no road / road: R-007 ×0.86 / ×0.92
  at 0.5 s, ×0.93 / ×0.96 at 0.8 s; B ×0.83 / ×0.70 at 0.5 s, ×0.80 / ×0.65 at 0.8 s; within95 up), jammed
  drives worse (2026-09-28 p50 23.9 → 31.2 m at 0.8 s; A 21.4 → 36.9 m) and more behind along-track
  (−10 → −23 m). Without GNSS the EKF does not apply the speedometer scale (EKF distance 2.2 % below the
  scaled OBD distance on 2026-09-28) and the compensated reading is clipped near stops, so the net effect
  depends on GNSS. Not resolved; see roadmap.
- Corner fix: 2026-09-28 p50 16.8 → 13.7, p95 51 → 45 m (cross-track after the 5:37 turn 22 → 3 m), A
  neutral; GNSS drives R-007 ×1.000, B ×1.038 (worse). Its along-track shift disagrees with the re-timed
  truth (−8 m vs about +30 m at 5:43). Kept for experiments.

## D-047: M4 uses gentle bends (≥ 12°) with a local-minimum ambiguity test — Accepted (2026-10-02)
Context: on 2026-09-28 gentle bends (2:27–2:31, 20°; 5:04–5:08, 18°) carried the along-track error
(loose matching: +22…+26 m, σ 16–18 m, RMS 2°, while the estimate ran 21–28 m behind the re-timed truth),
but M4 rejected them every step: the ambiguity test ("no shift ≥ 15 m away within 2× the best cost")
fails on any wide valley, which is what a gentle bend gives.
Decision: ambiguity = another *local minimum* ≥ 15 m away within 2× the best cost; `alongMinTurnDeg` 12°
(was 20°, fixed). The valley width still sets σ, so gentle bends get small weight.
Measured (R-022): 2026-09-28 p50/p95 16.9/51 → 15.6/44 m; A unchanged; B all scenarios p95 ×0.95,
along-track ×0.82 (1-h drop p95 18.7 → 16.2 m, along 7.4 → 4.5 m); R-007 ×0.999; removed-road window no
worse. 15° ≈ 12° ≈ 10°; B `ramp_capture` p50 3.4 → 5.7 m (p95 same).

## D-048: Keep resetting road confidence on dips (P1 tried) — Rejected (2026-10-02)
Context: on 2026-09-28 +5:30–6:16 `confidentM` never reached 150 m after the 5:37 turn, so the ~22 m
cross-track offset stayed for ~40 s. Tried `dipMaxSteps` (keep the confident distance through dips with
probability ≥ 0.5, inherited through the HMM transition) and `dipNeedsTurnDeg` (only around a gyro turn).
Measured (R-023): any-dip carry 3 / 5 steps fixes the case (cross-track at 5:48 +24 → +2 / 0 m) but the
resets that matter were at 5:42 and 5:56 on straight road, next to the parallel lower branch of бульвар
Міхновського — the parallel-road ambiguity the 150 m rule exists for; with the road removed (M5) the same
carry locked onto a parallel street (A window p95 48 → 63 m), and GNSS drives p95 ×1.02–1.03. Turn-gated
carry leaves the case unchanged (+24 m). Both kept, default off (`dipMaxSteps` 0).

## D-049: Corner fix on, with a significance test — Accepted (2026-10-02)
Context: the corner fix (D-046, off) removed the 2026-09-28 +5:40 cross-track offset (+24 → +3 m) but made
drive B worse (p95 ×1.021): there the estimate was already good (5–13 m) and the fix added its own noise
(σ ≈ 7–8 m; B 2:41: 5.5 → 7.7 m over 30 s). Confidence carry-over (D-048) could not fix the case safely.
Decision: apply the corner fix only if the estimate is more than 2 σ (of the fix) from the target road
point (`cornerMinSigmas` 2.0); `cornerFix` on.
Measured (R-024): k = 1.5 / 2 / 2.5 give the same jammed results; B p95 ×1.021 → ×1.000 (k ≥ 1.5), p50 ×0.994;
R-007 ×1.000; A p50/p95 23.2/58 → 23.9/57 m, removed-road window 48 → 44 m; 2026-09-28 mean error 5:40–6:40
33.0 → 21.9 m, 6:40–8:00 26.5 → 19.2, 8:00–11:40 10.5 → 6.4, 11:40–end 14.5 → 17.3 m; clean p50/p95 15.6/44 →
15.2/48 m. Tests: `CornerFixTest` (offset carried into a turn is removed; an accurate estimate is left alone).

## D-050: Speed without OBD from the longitudinal accelerometer, anchored — Accepted (2026-10-02)
Context: D-036; signal study R-025. Path chosen by the user: build it on real drives first, make the simulator
realistic later.
Decision: EKF state 7 is the bias of the longitudinal specific force (σ0 0.5 m/s², random walk 0.05 m/s²/√s).
While no vehicle speed is fresh (2 s), the forward axis is known and a fix with hAcc ≤ 200 m was used within 30 s,
the bias is learned and ZUPT may apply; v is propagated with a_long − b (process noise 0.8 m/s/√s) only once the bias
is known (σ ≤ 0.3) or while trusted GNSS is fresh (2 s), otherwise the random-walk model stays. Anchors: ZUPT with
the real-car stop rule (`stillLoose`) only without trusted GNSS for 5 s, with v < 3 and v − 2σ < 1.5 m/s and |a_long −
b| < 0.3; bias at rest only after 2 s stopped and |a_long| < 0.5 (σ 0.2); centripetal speed a_lat/ω per second for
|ω| > 0.07 rad/s, σ = 10 % + 0.5 m/s (|v_c − v_OBD| median 0.32 m/s). With OBD nothing changes.
Fixes found on the way (all drives): forward axis learned from smoothed accel and yaw rate (single samples carry
0.5–0.9 m/s² of vibration; it was never learned on two of four drives); up correction gated on the smoothed yaw
rate and on a small horizontal specific force (< 0.5 m/s²) except at rest (braking tilted the up and left a false
bias at the next stop).
Compass side effect, accepted: with the forward axis now learned on all drives, FORWARD_ALIGNED compass readings
(σ 40°) appear where they did not before and the first one initializes the heading. With OBD and roads: R-007 GNSS
absent 62/186 m (without that start 97/478), A 27/68 m (vs 22/60), 2026-09-28 15/51 (vs 15/48). Tried and removed:
"weak readings may not initialize while the heading bank runs" (helps A, ruins R-007) and feeding them to the bank
as a prior once a minute (no effect).
Alternatives (R-026): noise 0.3 m/s/√s and bias walk 0.005 (overconfident; A p95 253 → 887 m); centripetal σ 30 %
(too weak); accelerometer speed from the start with σ0 0.2 (unlearned bias: B v 27–31 m/s at a true 17, B ×1.17);
no fix condition or 120 s (drift with an optimistic σ in rungs without fixes or with σ-500-m synthetic fixes: B
×1.27); loose ZUPT without the low-speed, no-GNSS and not-accelerating guards (zeroed a moving car's speed).
Open: simulator vibration (the guards keep the quiet simulator safe, but it cannot show the gain); the road
constraint stays off without OBD (speed σ rarely ≤ 1.5 m/s); compass FORWARD_ALIGNED start quality per drive.

## D-051: Driver-facing UI: four tabs, one verdict, persistent "turn off" notification — Accepted (2026-10-02)
Context: the single scrolling panel mixed setup, the researcher's raw dump and the driver's needs; the Stop
button could scroll away, and while our output replaced the system location the only sign was the standard
recording notification without a way out.
Decision: bottom tabs Drive / Trips / Diagnostics / Settings (plain `NavigationBar`, no Navigation dependency).
Drive: three modes as a segmented control (Record / Estimate / Spoof = `RunMode`; Ukrainian "Підміна", chosen over "Захист", which sounds like an antivirus, and "Навігація"), readiness notices with a fix
action only for what blocks the chosen mode, then a hero verdict about GNSS (reliable / doubtful / not trusted /
absent) with accuracy, speed and time, "what is helping now" chips (GPS, network, OBD, roads, compass; symbol plus
colour), trust reasons in plain language (`reason_*` strings), big annotation buttons, and a **pinned** Stop
button. Diagnostics keeps the old raw panel. Settings holds roads, OBD, mock targets, QUESTIONABLE toggle, all
persisted in `AppSettings`. Mock mode uses its own notification channel (importance default, silent, public on
the lock screen), title "GPES is replacing your position", a chronometer, accuracy and GNSS state in the text,
and a "Turn off" action (`ACTION_STOP`). Android 14+ lets users swipe away foreground-service notifications, so a
delete intent re-posts it (`ACTION_REFRESH_NOTIFICATION`). The screen stays on while a drive runs on the Drive tab.
Alternatives: Navigation Compose (a dependency for four static tabs); an in-app map now (deferred, roadmap);
material-icons-extended (about 10 MB of dependency; glyph characters suffice for now); hiding the notification
action in non-mock modes (kept everywhere: stopping a recording from the shade is also useful).
Consequences: no change in `:core` or in recordings. Trust reasons are still English codes in the data.

## D-052: Mock the platform `gps` and probe for returning GNSS — Accepted (2026-10-02)
Context: D-009 made Fused the default target because it keeps real fixes flowing. The user's navigator (Waze)
refuses to guide without a platform GPS, so `gps` must be replaced (D-009's "optional" target becomes the main one).
While it is replaced, real Location fixes do not reach us, so a returning GNSS cannot be recognised and the
estimator stays on dead reckoning (drifting) for good.
Decision: `gps` is the default target (Fused off); `GnssProbeController` (estimation-algorithm.md §3f) opens a short
window now and then in which the test provider is removed, gated by a healthy `GnssStatus` (satellites used, C/N0
level and spread), closed by trust verdicts or time, with back-off on failure. A user setting turns it off.
Alternatives: (2) own PVT from raw measurements (independent position, no windows, but a large separate project,
and devices differ in what they expose); (3) never give the provider back, record only (no risk, but no online
return to GPS). Fixed short windows (6 s): rejected, because trust needs 10–15 s of a consistent stream after an
outage, so a short window only ever sees QUESTIONABLE fixes that the estimator ignores. Aborting on every
REJECTED fix: rejected, it would abort exactly the case the probe exists for (a drifted estimate disagreeing with
the returned GNSS, INNOVATION_GATE).
Consequences: other apps see the real GPS during a window; if it is spoofed they see the spoofed position for up to
20 s. Unverified on a device: the emulator reports no satellites, so only the plumbing was checked (window open →
`gps provider` without `[mock]`, a real fix delivered to the app and to Fused, closed → `[mock]` again); whether
the real Pixel 8 GNSS hardware resumes at once after `removeTestProvider`, and what Waze shows during a window,
are not known. Revisit with the first real drive; thresholds are in `GnssProbeConfig`.

Measured (Pixel 8, 2026-10-02, indoors, stationary, 3 sessions): after `RESTORED` the first real fix came after
3.1 s (it had POOR_ACCURACY, QUESTIONABLE), 1.3 s and 1.1 s; trust reached TRUSTED 6.1 s, 2.0 s and 2.0 s after the
window opened (window-closed → `gps_probe_recovered`), so the real GPS agreed with our estimate (NIS 0.4–2.6) and
windows lasted 2–6 s. Two other windows (4 s and 8 s no-fix aborts) saw no real `Location` although NMEA and the
satellite status kept flowing (the chip had a fix; the delay of the first `Location` after the provider is given back
varies from about 1 s to more than 8 s indoors), so the abort is 12 s. A C/N0 spread threshold of 1.5 dB flapped
indoors and became 1.0 dB over the last 5 statuses. While no `Location` arrives other apps have no GPS at all: whether
that bothers Waze is the open question. All 65 `gps`
locations received while the provider was replaced were our own mock (`is_mock=1`) and were rejected as
SYNTHETIC_INPUT, which confirms the feedback guard on a device. Still unknown: how Waze reacts to a window, behaviour
while driving and under real jamming or spoofing.

## D-053: Recording and the start modes are developer options, not the product — Accepted (2026-10-02)
Context: the app started as a research tool (record drives, estimate offline). The product is now the position
replacement (D-051, D-052): most users will not record anything, and recording people's drives is a privacy matter.
Decision: the main screen offers only "turn on spoofing" (`RunMode.MOCK_OUTPUT`). Recording is **off by default**
and optional (Settings → For the developer → "Record drives"): it saves the drive to a file on the phone so the
user can send it to the developer; nothing is sent automatically, and the Drive tab says when recording is on.
"Developer mode" (also off) adds the Diagnostics tab, the start modes (record only, estimate only), event marks and
the QUESTIONABLE-GNSS toggle. The Recordings tab appears when recording or developer mode is on, or when drive files
already exist (so they can still be shared or deleted). In the service a `Recorder` is either a file (`FileRecorder`)
or `NoRecorder`: without recording no file or database is created, and the estimator, trust and mock output are
unchanged. Record-only mode always records.
Alternatives: removing recording from the app (loses the data that improves the algorithm); recording always but
hidden (privacy, storage 4 MB/min); a separate research build (two builds to keep in sync).
Consequences: the estimator and `:core` are unchanged; `Status.recording` tells the UI; event marks exist only while
recording. Revisit: an in-app "send to the developer" flow and clear-on-share, a recording size/space indicator,
automatic clean-up of old recordings.

## D-054: Own vector map on a Canvas, no map SDK and no raster tiles — Accepted (2026-10-02)
Context: the app had no map. The point is to show where we think the car is next to what GPS claims, so a spoofed
or jammed GPS is visible.
Decision: a "Map" tab drawn with a Compose `Canvas` from data the app already has: roads from the OSM tiles of the
road matcher (`RoadNetwork.segmentsWithin`, major roads thicker), our estimate with its uncertainty circle and
heading, its recent track (3 m minimum step, 1200 points), and the real GNSS fixes coloured by trust (green, amber,
red) and network fixes as hollow rings (`MapTrack`, 300 fixes, the trust verdict joined by fix time). North-up, pan,
pinch and buttons to zoom, a button to follow the car, scale bar and legend. `MapView` (core, tested) holds the
projection and the scale-bar rounding. The tab is always present; after a session it shows the last one.
Alternatives: osmdroid or MapLibre (a dependency, raster or vector tile downloads, a second source of map data that
disagrees with the matcher's roads, and no offline use without more work); Google Maps (proprietary, needs network
and keys, and our mock location would feed it); only a track plot (no roads, no context).
Consequences: no basemap labels, buildings or water, only roads, so it is a monitoring view and not navigation;
roads appear only with the road map on and downloaded. Heading-up, rotation, route preview and tap-to-inspect are not
done.

## D-055: App icon: a dog-face map pin (Patron), supplied as an adaptive icon — Superseded by D-057 (the artwork; the adaptive structure stands) (2026-10-02)
Context: the app needs an icon; the name comes from "Пес Патрон", and the app is about location, hence a dog and a map
pin. Many variants were drawn and compared (face in the pin, a Jack Russell, a paw, a head-shaped pin, profile, nose,
signal arcs, bone, palettes). A nose alone read as a wine glass (the muzzle line under it looked like a stem and a
base).
Decision: the user's own design (folder `gpes_patron_android_icon`): a white pin on the app's blue `#0B5CAD`, a
Jack Russell face inside it (orange ear patches, eyes with brows, dark nose with nostrils and a muzzle line).
Adaptive icon (`mipmap-anydpi/ic_launcher.xml`, background colour, vector foreground, monochrome layer for Android 13+
themed icons), used for `icon` and `roundIcon`. The source SVG is kept in `docs/assets/gpes_patron_icon.svg`.
The artwork reaches y 14..94 of the 108 dp canvas, beyond the visible 72 dp circle, so the foreground and the
monochrome layer are wrapped in a `<group>` scaled by 0.82 about the centre (pin 21..87, inside the 66 dp safe zone);
the SVG itself is unchanged.
Alternatives: the several variants above (kept in the conversation, not in the repository); a raster icon (no theming,
several densities to keep).
Consequences: the in-app palette could follow the icon (the blue is shared; the yellow and the orange of the
variants are not used). Not checked: other launchers' masks (squircle, teardrop) and the themed monochrome icon on
a device.

## D-056: No export in the app; recordings share the `.db`, listed compactly with multi-select — Accepted (2026-10-02)
Context: the Recordings tab had "Share .db" and "Export JSONL + GnssLogger" on a large card per drive, and one
action at a time. Recordings are now an optional hand-over to the developer (D-053). The export loaded the whole drive
into memory (a drive can be 144 MB), gave the developer nothing the `.db` does not (`replay export` does the same on a
computer), and confused users with a second button.
Decision: the app only shares the `.db` file(s). `DriveStorage.export` and its strings are removed; the CLI
`replay export` (jsonl, csv, gnsslogger) and the format tests stay. The list is one row per recording (local date and
time, size; the recording in progress is marked and cannot be selected). A long press starts selecting, a tap toggles,
the header shows the count with Share, Delete (with a confirmation that gives the count and the total size), All and
Cancel; Back leaves selecting. Share sends all selected files in one chooser (`ACTION_SEND_MULTIPLE`).
Alternatives: keeping the export in developer mode (nobody needs it on the phone); a per-row overflow menu (no batch);
swipe-to-delete (no confirmation for big files, no batch share).
Consequences: JSONL and GnssLogger text come only from the CLI. Recordings are still ~4 MB per minute (the IMU at
about 800 Hz dominates); making the files themselves smaller is a separate change of the recording format.

## D-057: Updated icon artwork — Accepted (2026-10-02)
Context: the user replaced the icon set from D-055 with a new one (`gpes_patron_android_icon`).
Decision: the new set is used as supplied: a white pin with a larger Jack Russell face (orange head patches and small
ears, brows, eyes with highlights, dark nose, muzzle line) on the app's blue `#0B5CAD`; `ic_launcher_background` (a
colour shape), `ic_launcher_foreground` and `ic_launcher_monochrome` (themed icon), `mipmap-anydpi-v26/ic_launcher.xml`
and a separate `ic_launcher_round.xml`, `roundIcon` pointing at it. The set already scales the artwork by 0.82 into the
safe zone, so nothing is changed. The old drawables, `mipmap-anydpi` and the SVG in `docs/assets` were removed.
Consequences: there is no SVG source in the repository now (the set has none); the vector drawables are the source.
Still not checked: the Pixel 8 launcher, the themed icon, other masks.

## D-058: Estimation starts by itself; spoofing is turned on inside the running session; stale mocks are cleaned — Accepted (2026-10-02)
Context: the map and the GNSS verdict only existed after "turn on spoofing", because the estimate lives in a
`DriveService` session started by that button. Estimation before spoofing is useful (the user sees at once how far the
GNSS can be trusted, and the estimator has learned heading, speed scale and compass before the GNSS fails). While
testing it, a force-stop was found to leave the mock `gps` provider installed: it keeps serving the last mock location to
every app (a frozen car).
Decision: (1) While the Drive or Map tab is open and the location permission is granted, the app starts an
estimate-only session by itself (no mock output, no recording, foreground notification "tracking the position") and
stops it when the app goes to the background, or another tab (Settings, Recordings, Diagnostics) opens, unless spoofing
or a developer record-only session is running. (2) The big button then calls `startSpoof` on the running session
(`ACTION_SPOOF_ON`): the publisher and the optional GNSS probe are created in place and recording, when enabled,
begins there, so the estimator keeps everything it has learned. (3) Turning spoofing off (button or notification) stops
the session; tracking restarts by itself if the tab is open. (4) The developer selector offers only spoof and
record-only; "estimate only" is now simply the tracking phase. (5) The publisher notes that spoofing is active (written
synchronously); at the next start the app removes the test providers left behind (`gps`, `network`, fused mock) and tells
the user.
Alternatives: tracking in the background all the time (battery, a permanent notification, and a warm estimator only helps
if the app has been running); an explicit "start tracking" button (one more step, and no map until pressed); stopping
the spoof in place and keeping tracking (a second code path for the same result as stop + restart); cleaning stale
mocks unconditionally at start (it would remove another mock-location app's provider).
Consequences: opening the Drive or Map tab starts sensors and the wake lock until the tab is left; the Settings tab is
unlocked while only tracking runs (the tab stops it). If the app is killed while spoofing and never opened again, the
frozen location stays until it is (nothing can run after a force-stop); the persistent notification disappears with the
process. Hand-over (spoofing only when the GNSS is bad) stays on the roadmap.

## D-059: Version from git, in the Nerdbank.GitVersioning model, implemented in Gradle — Accepted (2026-10-02)
Context: `versionCode` and `versionName` were edited by hand, with CI passing `-PversionCode=<run>`; local builds were all
code 1, so reinstalling over a CI build failed, and tests on devices needed manual `-PversionCode` numbers.
Decision: `version.json` at the root holds `"version": "MAJOR.MINOR"` and `"versionCodeOffset"`. `app/build.gradle.kts`
asks git: `versionName` = `MAJOR.MINOR.<height>+<short sha>[.dirty]`, where height is the number of commits since
`version.json` last changed (as in nbgv; changing MAJOR.MINOR starts a new line at height 0), and `versionCode` =
offset + the number of commits on HEAD, which only grows. A build with uncommitted tracked changes is marked `.dirty`.
The offset is 200 so that the numbers stay above the 100–112 that builds made by hand put on test devices. The name
is shown in Settings, is the User-Agent of the road download, and goes into every recording's `SessionInfo.appVersion`
(so a recording says which commit made it). CI fetches the full history (`fetch-depth: 0`) and names the APK from
`./gradlew -q :app:printVersion`; a shallow clone fails the build when `CI` is set, because its commit count would be
wrong. `-PversionCode` is gone.
Alternatives: GitVersion (GitTools; not tried): SemVer from tags, branch names and commit-message bumps, with
pre-release labels per branch, a .NET tool (also a Docker image and a GitHub Action); it fits when releases are tagged and
branches matter, but it still gives no per-commit `versionCode`, and needs .NET on every machine and runner, so it is
the first thing to swap in if tag-driven SemVer is wanted (call it from `app/build.gradle.kts` for the name and keep the
code as it is). Nerdbank.GitVersioning itself (a .NET tool on every dev machine and runner, only to read two numbers);
axion-release or git-versioning plugins (versions come from tags, so every release needs a tag and the code is not
monotonic per commit; `gradle-git-versioning` would also be one more plugin to keep in step with AGP 9); the CI run
number (differs between CI and local builds, so reinstalls break); a manual bump (what we had).
Consequences: rewriting history (rebase, squash of published commits) changes the counts and can lower the code of a
rebuilt app (installs then fail with a downgrade: raise the offset in `version.json`). Tags `v*` still publish a release but
do not set the version: bump `version` in `version.json` before tagging a release. Verified in CI on 2026-10-02: `0.1.2+05321b1`, code 238.

## D-061: Public documents on GitHub Pages from `site/`, one bilingual page each; Play texts in `docs/play/` — Accepted (2026-10-02)
Context: Google Play needs a privacy policy URL (the app handles location, which is sensitive) and a Data safety form, and the
app has a foreground service of type location to declare. The owner wants the documents in English and Ukrainian, in the repository and
on GitHub Pages.
Decision: `site/` (plain HTML and one CSS file, no build step) holds `privacy.html`, `terms.html` and `index.html`; each page contains both
languages (`lang="en"`, `lang="uk"` sections), chosen by `?lang=` or the browser language, both visible without JavaScript; the Play field gets
one URL (`/privacy.html`). `.github/workflows/pages.yml` deploys `site/` on every push to `main` that changes it (Pages source: "GitHub
Actions"). The policy states what the code does (checked against it on 2026-10-02): everything is processed on the phone; the only request that
leaves it is the road download to an Overpass server (bounding box of a ≈5.6 km tile, IP address, app name and version), which switching the road
map off removes; recording is optional, local and shared only by the user; no accounts, ads, analytics or crash reporting; Google Play services
supply the fused location. The terms say it is experimental and not safety-critical, that spoofing replaces the location for every app, and set
acceptable use. `docs/play/` holds what is pasted into Play Console: the store listing (both languages), the Data safety answers, the foreground
service declaration with a video script, a release checklist and a note on the Pages site. The app links to the policy and the terms (a different URL
per language) in Settings → About, and shows "© OpenStreetMap contributors" on the map and in About (the ODbL asks for it).
Contact is the GitHub issues page and the developer contact on the Play page; the developer's name (Oleksandr Liakhevych, as in the git history)
is named as the controller; no e-mail address is published.
Alternatives: Pages from `docs/` (would also publish the developer documentation and the explanatory video); a `gh-pages` branch (a second
history to keep); a page per language (two copies of every sentence in separate files, easy to let them drift; we kept the pair in one file, in the same place);
Markdown with a Jekyll theme (a build step, and a theme that can change); an external host such as Notion or Google Sites (another account and a URL
we do not control).
Consequences: the policy has to change with the app: a new network call, an SDK, a new permission or a change of what a recording holds means
updating `site/privacy.html` (both languages and the date), `docs/play/data-safety.md` and the permissions table. The policy is a statement
about the code, not legal advice; the owner should read it, and the choice of controller name and contact is theirs. Not verified: Play's review of the
declarations.

## D-062: Release build: R8, signing from the environment, Android App Bundle, bounded wake lock — Accepted (2026-10-02)
Context: Google Play takes an Android App Bundle signed with an upload key, not a debug APK, and penalises long wake locks (Android vitals).
Decision: the `release` build type turns on R8 (`isMinifyEnabled`, `isShrinkResources`) with an empty `proguard-rules.pro` (the libraries
ship their consumer rules; a rule is added only when a build breaks, with the reason). It is signed with an upload key read from
`GPES_UPLOAD_KEYSTORE`, `GPES_UPLOAD_STORE_PASSWORD`, `GPES_UPLOAD_KEY_ALIAS` and `GPES_UPLOAD_KEY_PASSWORD`; without them the release build is
left **unsigned**, never signed with the debug key (a debug-signed first upload could end up as the registered upload key). CI builds
`:app:bundleRelease` on every run (so R8 is always checked), signs it when the secrets `UPLOAD_KEYSTORE_B64`, `UPLOAD_STORE_PASSWORD`,
`UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` exist, and uploads `gpes-patron-<version>-signed|unsigned.aab` and `mapping.txt` as artifacts. The
wake lock is bounded to 10 minutes, not reference counted, and renewed every 5 minutes by the status tick, so a service that dies without
releasing it cannot hold the CPU awake for hours. Fixed on the way: a recording that begins when spoofing is turned on now writes the new mode in
its header (it said ESTIMATE_ONLY).
Alternatives: signing the release with the debug key (rejected, see above); keeping the key in the repository or in `local.properties` (a secret
in the history; env variables work the same locally and in CI); `proguard-android.txt` without optimisation (a larger app and no
`-optimize`, no reason for it); a long wake lock with no renewal (what we had: 12 h).
Consequences: only the owner holds the upload key; losing it means a reset through Play support. R8 can break reflection-based code: the release APK was
exercised end to end on the emulator (tracking, spoofing, recording and its serialization, the map), but a release build must be re-checked after a
new library or a reflection use is added. Upload key created 2026-10-02 (PKCS12, RSA 2048, valid to 2054; certificate SHA-256 `3A:24:4C:AF:15:3C:61:EE:51:4E:0C:EF:89:70:92:E9:7B:1D:A6:8B:32:23:A7:E8:3F:D6:BF:1E:55:4C:43:80`), stored as the four CI secrets, and verified in CI: the signed AAB `gpes-patron-0.1.4-e1f6fd7-240-signed.aab` carries exactly that certificate. Not verified: a Play upload, the signed AAB installed through Play, the wake lock on a long drive.

## D-063: Play graphics rendered from the app's vector icon, committed with their sources — Accepted (2026-10-02)
Context: Play needs a 512×512 icon, a 1024×500 feature graphic and 2–8 phone screenshots, per language for the text ones.
Decision: `tools/play-assets/make_icon_svg.py` converts the launcher's vector drawables to SVG (so the store icon is the launcher artwork, cropped
to a square with a wider margin than the launcher's 72 dp window); Chrome headless renders PNGs from the SVG and from `feature-graphic.html`
(one file, English and Ukrainian by `?lang=`); screenshots come from a debug build on an emulator set to 1080×2160 (Play accepts at most 2:1, and
today's phones are 20:9), with the demo-mode status bar. Everything is in `docs/play/assets/` (about 2 MB of PNG); the generator, the HTML and the
steps to repeat it are committed.
Alternatives: exporting from a design tool (no source in the repository, and the icon would drift from the app); Android Studio's Image Asset
(the launcher crop, with little margin); taking screenshots on the Pixel 8 (better, but it was not connected, and a real drive is needed for a real verdict).
Consequences: the screenshots show the emulator's simulated GNSS (the verdict reads "GPS is not trusted"), so they are a placeholder for real-drive
ones. The icon PNG is RGB, not RGBA; Play accepts that for a full-square icon, but check on upload. If the icon or the UI changes, regenerate.
