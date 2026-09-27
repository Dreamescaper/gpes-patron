# Architecture

Status: Phase 1 (2026-09-28). See [estimation-algorithm.md](estimation-algorithm.md) for the math
and [decisions.md](decisions.md) for why things are this way.

## Design principle

The project is **not** "simulate GPS from the accelerometer". It is *infer the vehicle location
from all available evidence when GNSS cannot be trusted*. Phase 1 builds the plumbing and a
deliberately simple baseline, and makes every future constraint measurable:

```
GNSS ──────────────┐
Cell/Wi-Fi ────────┤
IMU ───────────────┤
OBD speed ─────────┤  (future: synthetic in replay today)
OSM topology ──────┼──► probabilistic vehicle location ──► lat/lon ──► Android mock location ──► Maps/Waze
planned route ─────┘  (future)
```

## Modules

| Module | Kind | Depends on | Responsibility |
|---|---|---|---|
| `:core` | Kotlin/JVM, no Android | kotlinx.serialization | Models, geo, motion tracker, trust evaluator, estimators, pipeline, simulator, replay and metrics, Phase 2 interfaces |
| `:recording` | Kotlin/JVM | `:core`, SQLDelight runtime | SQLite drive-bundle schema, writer and reader, exporters |
| `:replay-cli` | JVM application | `:recording`, sqlite-jdbc, clikt | `replay simulate/export/run/matrix` |
| `:app` | Android | `:recording`, Play Services location, Compose | Acquisition, foreground service, mock publisher, UI |

`:core` is the heart of the system. Android code only converts platform objects into core models,
and core outputs back into platform calls.

## Data flow

```
 Android (app)                                   core (shared with replay)
 ─────────────                                   ─────────────────────────
 AndroidLocationSource (gps, network, fused) ─┐
 GnssRawSource (status, raw meas, AGC, NMEA) ─┼─► MeasurementSink ──► DriveWriter (records everything, incl. rejected/mock)
 SensorSource (IMU, mag, rotation vectors)  ──┤         │
 [future] VehicleSpeedSource (OBD)          ──┘         ▼
                                                MeasurementPipeline
                                                  1. reorder buffer (500 ms; late fixes sorted in)
                                                  2. history ring buffer + periodic snapshots (rollback)
                                                  3. MotionTracker → MotionUpdate (yaw rate, stationary)
                                                  4. LocationTrustEvaluator → TrustAssessment
                                                  5. PositionEstimator (baseline EKF / passthrough / [future] road-state)
                                                  6. 1 Hz ticks → PositionEstimate
                                                        │
                        ┌───────────────────────────────┼─────────────────────┐
                        ▼                               ▼                     ▼
                  DriveWriter                     LiveStatus (UI)     MockLocationPublisher
                  (trust, estimates)                                  (fused / gps / network test providers)
```

Offline, `ReplayRunner` feeds recorded measurements, transformed by a `Scenario`, into
**the same** `MeasurementPipeline`.

## Key interfaces (core)

- `Measurement` (sealed): `LocationMeasurement`, `ImuSample`, `OrientationSample`,
  `GnssStatusSnapshot`, `GnssMeasurementBatch`, `NmeaSentence`, `VehicleSpeedMeasurement`,
  `ProviderEvent`, `Annotation`. See [data-model.md](data-model.md).
- `MeasurementSource` / `MeasurementSink`: producers and consumers. Android sources, replay, and future OBD.
- `LocationTrustEvaluator`: `observe(m)`, `assess(fix, ctx) → TrustAssessment`, `sourceState()`,
  snapshot and restore.
- `PositionEstimator`: `onMeasurement(m, trust)`, `onMotion(u)`, `estimate(t)` (a pure
  prediction), snapshot and restore.
- `future/`: `RoadGraph`, `RoadSegment`, `RoutePrior`, `RoadStateEstimator`, `VehicleSpeedSource`.

## Operating modes (app)

| Mode | Acquisition + recording | Trust + estimator | Mock output |
|---|---|---|---|
| RECORD_ONLY | ✓ | – (computable later by replay) | – |
| ESTIMATE_ONLY | ✓ | ✓ (recorded) | – |
| MOCK_OUTPUT | ✓ | ✓ | ✓ (fused by default; platform gps/network optional) |

## Threading (app)

- One `HandlerThread` ("gpes-io") receives **all** Android callbacks and is the only thread that
  touches the pipeline, so there is no locking in core.
- `DriveWriter` has a synchronized buffer, flushed every 500 ms by a scheduler thread in a single
  SQLite transaction (WAL mode).
- The UI observes a process-wide `StateFlow` (`LiveStatus`), updated at 1 Hz.

## Feedback-loop guards (input ≠ output)

1. Published locations carry `isMock` (set by Android, and explicitly on API 31+) and the extra
   `gpes.synthetic=1`.
2. `DefaultTrustEvaluator` rejects any input with either flag (`SYNTHETIC_INPUT`). Estimators also
   ignore `isSynthetic` fixes.
3. `MockLocationPublisher` is only an estimate sink. It never emits measurements, except
   `ProviderEvent(OVERRIDDEN/RESTORED)`, which tells trust that a platform provider is ours.
4. Fused mock mode (the default) leaves the platform `gps` provider real, so real GNSS keeps
   flowing to trust and recovery. Overriding `gps` is optional, and it marks GNSS Location input
   UNAVAILABLE.
5. Verified on the emulator (2026-09-28): the fused stream returned our mock and was
   `REJECTED/SYNTHETIC_INPUT`. `PipelineTest` covers it as well.

## Rollback / delayed decisions

`MeasurementPipeline` keeps about 10 min of processed measurements plus a snapshot of the trust,
motion and estimator state every second. `rollbackAndReplay(fromNs, transform)` restores the
nearest snapshot and re-processes, optionally dropping or altering measurements. This is for the
future case where spoofing is recognised to have started earlier than first detected. It is
implemented and tested but not yet triggered automatically (see roadmap).

## Where Phase 2 plugs in

- `RoadStateEstimator : PositionEstimator` produces several `Hypothesis` entries with a `RoadState`.
  It is registered as a new `Variant.estimator` name, so the replay matrix compares it to the
  baseline immediately.
- A `RoadGraph` implementation is loaded from OSM, possibly using GraphHopper or Barefoot
  components (see [research.md](research.md)).
- A `VehicleSpeedSource` (Bluetooth OBD) emits `VehicleSpeedMeasurement`, which the baseline
  already consumes.
