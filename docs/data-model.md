# Data model

Code: `core/src/main/kotlin/gpes/core/model/`. All classes are `@Serializable` and Android-free.

## Timebase

- `tNs` is **nanoseconds of `SystemClock.elapsedRealtimeNanos()`**: monotonic and including deep
  sleep. `Location.getElapsedRealtimeNanos()` and `SensorEvent.timestamp` use this base on API 29+
  devices. `SensorSource` checks this once per session, records an `Annotation("timebase", …)`,
  and corrects only if the offset is > 1 s.
- `SessionInfo` stores one anchor `(anchorElapsedNs, anchorWallMs)`, so
  `wallMs(t) = anchorWallMs + (t − anchorElapsedNs)/1e6`.
- `LocationMeasurement.receivedNs` is when the app received the fix. The difference from `tNs` is
  the delivery latency, which the trust check uses for staleness.
- Canonical order: `RecordOrder.comparator` = (tNs, kind rank). It is stable within one kind.

## Records (`DriveRecord`)

| Type (`"type"` in JSONL) | Class | Key fields / units |
|---|---|---|
| `session` | `SessionInfo` | sessionId, anchor, device, SDK, app version, mode, configJson |
| `sensor_info` | `SensorInfo` | Android sensor metadata (vendor, resolution, range, delays, FIFO). Sensor type is serialized as `sensorType`, because `type` is the JSONL discriminator |
| `location` | `LocationMeasurement` | source (GNSS/FUSED/NETWORK/PASSIVE/OTHER), provider, lat/lon (deg), altM, hAccM (68%), vAccM, speedMps, speedAccMps, bearingDeg, bearingAccDeg, wallTimeMs, isMock, satsUsed, extras |
| `provider` | `ProviderEvent` | provider, ENABLED/DISABLED/OVERRIDDEN/RESTORED |
| `imu` | `ImuSample` | kind (ACCEL/GYRO/MAG/*_UNCAL/GRAVITY/LINEAR_ACCEL), x/y/z in the phone frame (m/s², rad/s, µT), bias bx/by/bz for uncalibrated kinds, accuracy |
| `orientation` | `OrientationSample` | kind (ROTATION_VECTOR/GAME_ROTATION_VECTOR/GEOMAG_ROTATION_VECTOR), unit quaternion qw,qx,qy,qz (phone→world ENU), headingAccRad |
| `gnss_status` | `GnssStatusSnapshot` | sats[]: svid, constellation, cn0DbHz, elevDeg, azDeg, usedInFix, ephemeris/almanac, carrierHz, basebandCn0 |
| `gnss_meas` | `GnssMeasurementBatch` | clock (GnssClock fields), meas[] (raw GnssMeasurement fields), agc[] (API 34+) |
| `nmea` | `NmeaSentence` | text |
| `cell_scan` | `CellScan` | cells[]: rat (GSM/WCDMA/TDSCDMA/LTE/NR/CDMA), registered, mcc, mnc, area (LAC/TAC), cid (CID/ECI/NCI), pci (BSIC/PSC/PCI), arfcn, bandwidthKhz, rssi/rsrp/rsrq/sinr, timingAdvance (raw units), asuLevel, measuredNs (modem measurement time), connectionStatus |
| `wifi_scan` | `WifiScan` | aps[]: bssid, rssiDbm, freqMhz, channelWidth, seenNs (ScanResult.timestamp), standard. **No SSID** (privacy) |
| `geomag` | `GeomagneticReference` | lat, lon, declinationDeg (east +), inclinationDeg, fieldUt, source (WMM via Android `GeomagneticField`) |
| `power` | `PowerState` | plug (NONE/AC/USB/WIRELESS/DOCK/OTHER), charging, currentUa, voltageMv, levelPct, temperatureC |
| `vehicle_speed` | `VehicleSpeedMeasurement` | speedMps, stdMps, source ("obd:elm327", "synthetic", …). ELM327: integer km/h, stamped at the request/response midpoint |
| `obd_raw` | `ObdExchange` (DriveRecord, not a pipeline input) | request, response, latencyMs: every adapter exchange, for debugging |
| `annotation` | `Annotation` | label, note (user marks, timebase check; `gps_probe_open` / `gps_probe_recovered` / `gps_probe_failed` from the GNSS recovery probe, D-052) |
| `trust` | `TrustAssessment` | source, provider, state, confidence 0..1, reasons[], innovationNis, impliedSpeedMps |
| `estimate` | `PositionEstimate` | estimator, lat/lon, cov (Cov2 m² ENU), headingRad (bearing), headingStdRad, speedMps, speedStdMps, mode, confidence, hypotheses[], road |

`LocationMeasurement.isSynthetic` = `isMock || extras["gpes.synthetic"] == "1"`.

## Derived (not recorded)

- `MotionUpdate` (20 Hz): yawRateUp (rad/s, counter-clockwise positive), stationary,
  stationaryForS, gyroNormMean, accelStd, up (phone frame), forward (vehicle forward axis in the
  phone frame, once learned), mountEpoch, tiltRateRms (non-yaw angular rate RMS over 0.5 s),
  horizontalAccel, upFromOrientation.
- `CompassQuality`: verdict, reasons, fit, octants, radiusRatio, scatterDeg, centerDriftRatio,
  hardIronUt, dirtyFraction, shakyFraction, tiltRateRmsMean, alignRmsDeg, saturated,
  wirelessCharging, mountEpoch.
- `CompassReading`: bearingRad, sigmaRad, mode (GNSS_ALIGNED / FORWARD_ALIGNED / UNCORRECTED).

## Road model (Phase 2)

- `RoadState(segmentId, distanceAlongM, directionForward, probability, pOffRoad, confidentM, roadName)`
  in `PositionEstimate.road`: the matcher's best state. `segmentId` indexes the `RoadNetwork` the
  estimator used (ids change when tiles are added); `probability` covers the same street and travel
  direction; added fields have defaults, so older JSON still decodes.
- `RoadSegment` (`future/Interfaces.kt`) gained `lanes`, `layer`, `name`, `osmWayId`, `startNode`,
  `endNode`; `oneway` means drivable only from the first to the last point.
- `OsmWay(id, nodeIds, lats, lons, roadClass, oneway, lanes, layer, name)`: a reverse one-way way is
  stored reversed with `oneway = true`.
- **Road tile file** (`<tile.key>.roads`, `RoadTileCodec` v1): gzip of big-endian `int magic "GPRD"`,
  `int version`, `int wayCount`, then per way `long id, UTF class, bool oneway, byte lanes, byte layer,
  UTF name, int n, n × (long nodeId, int lat·1e7, int lon·1e7)`. Tile grid: `RoadTile(ix = ⌊lon/0.08⌋,
  iy = ⌊lat/0.05⌋)`, key `r<iy>_<ix>`; a tile holds every way that touches it.

## Phase 2 placeholders

- `Hypothesis(weight, lat, lon, cov, road)`. `PositionEstimate.hypotheses` already carries a list, so multi-hypothesis estimators need
  no model change.
- `future/Interfaces.kt`: `RoadGraph`, `RoadSegment`, `RoutePrior`, `PlannedManeuver`,
  `RoadStateEstimator`, `VehicleSpeedSource`.
