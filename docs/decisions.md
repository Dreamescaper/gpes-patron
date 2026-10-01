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

