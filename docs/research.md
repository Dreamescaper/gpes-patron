# Research: existing projects and what we take from them

Status: Phase 1, September 2026. Licenses were checked against the upstream repositories. Items are marked
**reuse-format**, **reuse-concept**, **defer** (a candidate for Phase 2+) or **reject**.

The key question for every entry: *does it help an Android phone localize a car for hours when GNSS cannot be trusted?*
Most existing tools assume GNSS is the truth, or at least that it comes back soon. We borrow mechanisms from them, but not those assumptions.

## 1. Android GNSS / IMU recorders

| Project | License | Decision | Notes |
|---|---|---|---|
| [google/gps-measurement-tools](https://github.com/google/gps-measurement-tools) (GnssLogger + MATLAB/Python analysis) | Apache-2.0 | **reuse-format** | We write a GnssLogger-compatible text export (`Raw`, `Fix`, `UncalAccel`, `UncalGyro`, `UncalMag`, `OrientationDeg`, `Status`, `Agc`). That makes Google's analysis tools and the Smartphone Decimeter Challenge ecosystem usable on our drives. The internal store stays SQLite (see `recording-format.md`) because GnssLogger text is lossy for our needs: it has no trust or estimate records and only one fused-orientation style. |
| [barbeau/gpstest](https://github.com/barbeau/gpstest) | Apache-2.0 | **reuse-concept** | The reference for handling GnssStatus / GnssMeasurement / NMEA callbacks, per-constellation carrier frequency, and device quirks. It is not worth pulling in as a dependency, since it is an app and not a library. |
| GetSensorData (IPIN competition logger), phyphox | various / GPL-3 | **reuse-concept** | Sensor FIFO batching (`maxReportLatencyUs`) and preserving `SensorEvent.timestamp` in the `elapsedRealtimeNanos` base. No code copied. |

## 2. Android mock-location providers

| Project | License | Decision | Notes |
|---|---|---|---|
| [mcastillof/FakeTraveler](https://github.com/mcastillof/FakeTraveler) and forks | GPL-3.0 | **reuse-concept** | This confirms the only supported mechanisms. (a) `LocationManager.addTestProvider` / `setTestProviderEnabled` / `setTestProviderLocation`. (b) Play Services `FusedLocationProviderClient.setMockMode(true)` + `setMockLocation()`. Both require the app to be chosen as *Select mock location app* in Developer options (appop `android:mock_location`). The implementation is about 150 lines, so we write our own and avoid GPL. |

What we decided about mock output:
- Google Maps consumes the **fused** provider, so fused mock mode is the default output. It leaves the platform `gps` provider real, which means real GNSS keeps flowing into the trust evaluator. That matters for detecting when GNSS recovers.
- Overriding the platform `gps`/`network` providers with test providers is optional (some apps read `LocationManager` directly). While that override is active, the real GNSS *Location* input is lost, so the pipeline marks that source `UNAVAILABLE (overridden)`. Raw `GnssStatus` / `GnssMeasurement` callbacks still come from the HAL and are kept; this needs to be verified on each device.
- Synthetic output always carries `isMock` and an extras tag `gpes.synthetic=1`. The input side rejects both. This is the feedback-loop guard.

## 3. Flight-controller estimators

| Project | License | Decision | Concepts extracted |
|---|---|---|---|
| [PX4 EKF2 (PX4-ECL)](https://github.com/PX4/PX4-Autopilot/tree/main/src/modules/ekf2) | BSD-3 | **reuse-concept** | **Delayed fusion horizon.** Observations and IMU go into ring buffers, and fusion happens at the observation's timestamp; our `HistoryRingBuffer` + `rollbackAndReplay` follows this. **Innovation consistency test.** `test_ratio = innov² / (gate² · S)`, where the observation is rejected if the ratio is > 1; our Mahalanobis gate in `InnovationGateCheck` is this. **GPS pre-use quality checks with hysteresis.** Checks on nSats, hAcc, drift and vertical speed must pass for N seconds before GPS is used; our `recoveryConsecutive` does this. **Reset on glitch.** After a long rejection, a trusted source resets state instead of fusing it. **EKF-GSF yaw estimator.** A bank of EKFs with different yaw hypotheses, weighted by GNSS velocity innovations, which gives yaw with no magnetometer. That is very relevant to a car where the magnetometer is useless; it is a follow-up. |
| [ArduPilot EKF3](https://github.com/ArduPilot/ardupilot/tree/master/libraries/AP_NavEKF3) | GPL-3.0 | **reuse-concept** | **Multiple cores ("lanes")** with an error score and lane switching. This is the idea behind our future `Hypothesis` list and `RoadStateEstimator`. It also contributes GPS glitch handling (an innovation variance inflation window before reset) and source-set switching. No code copied (GPL). |

**Rejected: porting either EKF wholesale.** They carry 24 states (3-D attitude quaternion, velocity, position, gyro and accel bias, earth and body magnetic field, wind). They assume a rigidly mounted, calibrated IMU and a vehicle that can move freely in 3-D. A car with a loose phone gets more from constraints (NHC, road topology) than from a larger state vector.

## 4. Automotive dead reckoning / non-holonomic INS

| Source | License | Decision | Notes |
|---|---|---|---|
| [i2Nav-WHU/KF-GINS](https://github.com/i2Nav-WHU/KF-GINS), [OB_GINS](https://github.com/i2Nav-WHU/OB_GINS), Wheel-GINS | GPL-3.0 | **reuse-concept** | Clean reference implementations of loosely coupled GNSS/INS with **odometer + NHC** updates: body-frame lateral and vertical velocity ≈ 0 as a pseudo-measurement, and IMU-to-vehicle mounting angle estimation. Our Phase 1 `BaselineDrEstimator` is the 2-D special case. Position is driven by speed along heading, and only yaw rate (gyro projected on gravity) is used. That is NHC with zero lateral velocity by construction. |
| Dissanayake et al. 2001, *The aiding of a low-cost strapdown INS using vehicle model constraints*; Shin 2001 (Calgary thesis) | papers | **reuse-concept** | NHC formulation and its observability benefits. |
| RoNIN, TLIO (learned inertial odometry) | research | **reject for now** | Trained for pedestrians or head-mounted devices. They could matter later for learned speed from vibration, but not in Phase 1. |

What follows for the phone-in-a-car problem:
1. Heading changes are well observed by the gyro once it is projected on gravity; that works for any mounting. **Absolute heading** is not observed without GNSS or a map. The magnetometer is unreliable inside a steel body, so it is recorded but weighted very low.
2. **Speed** is the weak point without OBD. Integrating the accelerometer diverges within tens of seconds, so we don't do it. Only stationary detection (ZUPT) is reliable. This is why `VehicleSpeedMeasurement` is a first-class input, and why replay offers a `SyntheticVehicleSpeed` transform: it quantifies the value of OBD before any hardware is involved.

## 5. Map matching (Phase 2 candidates)

| Project | License | Decision | Notes |
|---|---|---|---|
| [GraphHopper map-matching](https://github.com/graphhopper/graphhopper/tree/master/map-matching) (built into GraphHopper core) | Apache-2.0 | **defer** | HMM (Newson & Krumm 2009) over GNSS traces, in Java/JVM. Its OSM import and routing graph could back our `RoadGraph`. |
| [bmwcarit/barefoot](https://github.com/bmwcarit/barefoot) | Apache-2.0 | **defer** | JVM, with **online** (incremental) HMM matching and k-state filtering, which is close to what we need. It is not actively maintained and relies on a PostGIS map server; we would take the ideas and maybe the spatial index. |
| Valhalla Meili | MIT | **defer** | C++, HMM, a mature transition cost model. Useful to consult for emission and transition probabilities. |
| OSRM `match` | BSD-2 | **reject** | Server-oriented and batch. |
| FMM (Fast Map Matching) | Apache-2.0 | **defer** | Its precomputed UBODT idea is useful for fast transition probabilities. |

Why none can be used directly: every one of them models **GNSS points with ~10–50 m noise** as the observation. Our observations are gyro turn events, speed or odometry, and coarse fixes of ±300–1500 m. The Phase 2 estimator is therefore a **particle filter or HMM on road state** (`segmentId, distanceAlong, direction`). Particles propagate with speed (or OBD odometry) along the graph, branch at intersections, get weighted by turn-angle agreement with the gyro, and get weighted by coarse fixes. That is closer to map-aided PDR literature and to "odometry-based map matching" than to classical GNSS map matching. The graph loading, spatial index and transition heuristics can come from GraphHopper or Barefoot.

## 5b. Cell / Wi-Fi positioning data (Phase 1 records raw observations; resolver planned)

| Source | License | Decision | Notes |
|---|---|---|---|
| OpenCelliD | CC BY-SA 4.0 (data) | **defer → P1 resolver** | Crowd-sourced cell tower positions by MCC/MNC/LAC/CID. Downloadable per country, so it works offline. Positions are often a centroid of observations rather than the true mast. |
| BeaconDB | check terms | **evaluate** | Community successor to Mozilla Location Service (shut down in 2024). Cells and Wi-Fi. |
| Google `network` provider | proprietary, online | **used as-is** | Good accuracy when online, but a black box, and needs connectivity. Kept as one comparison rung. |

LTE timing advance quantizes the distance to the serving cell in ≈ 78 m steps (16·Ts·c/2). In
practice it is available mainly for the serving cell while connected. Its availability needs to
be measured on real devices.

## 6. Planned-route prior (Phase 3)

There is no directly reusable open-source component. Conceptually, it is sequence alignment (DTW or an HMM) between the planned manoeuvre sequence and the observed gyro turn events. It is injected as a prior on particle weights, never as a hard constraint.

## Summary of what Phase 1 actually reuses

- **Formats:** GnssLogger text export.
- **Concepts:** from PX4 EKF2, innovation gating, the delayed horizon / history buffer, GPS checks with hysteresis and reset-on-glitch. From ArduPilot EKF3, multi-lane thinking for future hypotheses. From the automotive literature, NHC and 2-D DR.
- **Libraries:** Android platform APIs, Play Services location (fused input and fused mock), SQLDelight (one SQLite schema on Android and the JVM), kotlinx.serialization.
- **Written ourselves (small):** mock publisher, trust checks, 6-state EKF, compass iron fit, ELM327 client, replay and metrics. None of these exist in reusable, license-compatible form for our assumptions.
