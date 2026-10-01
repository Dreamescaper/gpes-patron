# Recording format

## Drive bundle (`<yyyyMMdd-HHmmss>.db`)

One SQLite file per session, in WAL mode on Android. Schema:
`recording/src/main/sqldelight/gpes/recording/db/Drive.sq`. Version: `schema_version` table
(currently **1**; `DRIVE_SCHEMA_VERSION`).

On the phone: `/storage/emulated/0/Android/data/gpes.patron/files/drives/`. Pull with `adb pull`,
or use "Share .db" in the app.

Road tiles the app downloaded (road map on, D-041) are next to it in `files/roads/*.roads` (format in
`data-model.md`). They are not copied into the drive file; `PositionEstimate.road` in the `estimate`
table refers to segment ids of the network built from the tiles loaded at that moment. To replay with
the same roads: `adb pull …/files/roads tiles/` and `replay run … --roads tiles/`.

| Table | One row per | Notes |
|---|---|---|
| `session` | session | anchor elapsed/wall time, device, SDK, app version, mode, `config_json` (mode, mock targets, estimator config, `roads` on/off) |
| `sensor_info` | sensor present | Android sensor metadata |
| `location` | fix (all providers, **including mock and rejected**) | `extras_json` holds the Location extras |
| `provider_event` | provider enable/disable/override | |
| `imu` | sample | kind as text; bias columns for uncalibrated kinds |
| `orientation` | rotation-vector sample | quaternion |
| `gnss_status` + `gnss_sat` | status snapshot / satellite | joined on `t_ns` |
| `gnss_clock` + `gnss_meas` + `gnss_agc` | measurement event / satellite / band | joined on `t_ns` |
| `nmea` | sentence | |
| `cell_scan` + `cell` | cell scan (about every 2 s, exact repeats skipped) / cell | serving and neighbour cells; `measured_ns` = modem measurement time (ms resolution) |
| `wifi_scan` + `wifi_ap` | emission of newly seen APs / AP | about 4 app-requested scans per 2 min plus scans from the system/other apps; no SSID |
| `geomag` | WMM reference | at the first real fix and every 50 km (added 2026-09-28) |
| `power` | charging state | on change or every 5 s (added 2026-09-28) |
| `vehicle_speed` | speed sample | OBD PID 0D at ≤ 10 Hz when an adapter is selected |
| `obd_raw` | adapter exchange | raw ELM327 request/response/latency (added 2026-09-28) |
| `annotation` | user mark, timebase check | |
| `trust` | assessment of each location fix | only in ESTIMATE/MOCK modes (replay recomputes) |
| `estimate` | 1 Hz tick | only in ESTIMATE/MOCK modes |

All `t_ns` values are stored exactly as received. Readers order by `(t_ns, rowid)` and then apply
the canonical cross-table order (`RecordOrder`).

Typical volume: about 1M rows and 60–100 MB per hour (IMU at 100 Hz dominates).

Handy queries:

```sql
select source, provider, count(*), sum(is_mock) from location group by 1,2;
select state, reasons, count(*) from trust group by 1,2 order by 3 desc;
select kind, count(*) from imu group by kind;
```

## Exports

- **JSONL**: one record per line, `{"type": "...", ...}` in canonical order. Also a valid replay
  input. Written with `replay export --format jsonl` or the app's Export button.
- **CSV**: one file per table-like record kind (`location.csv`, `imu.csv`, `trust.csv`,
  `estimate.csv`, …). Written with `replay export --format csv --out dir/`.
- **GnssLogger text**: a best-effort compatible subset (`Raw`, `Fix`, `Status`, `UncalAccel`,
  `UncalGyro`, `UncalMag`, `OrientationDeg`, `Agc`) for Google's gps-measurement-tools.
  Written with `replay export --format gnsslogger`.

## Compatibility rules

History: the `cell*`/`wifi*` tables were added on 2026-09-28 (additive, `CREATE TABLE IF NOT
EXISTS`). Earlier bundles lack them, and `DriveReader` returns empty lists for them.

- Adding nullable columns or new tables: bump nothing, but readers must tolerate their absence.
- Renaming or removing columns, or changing semantics: bump `DRIVE_SCHEMA_VERSION`, and keep a
  reader path for old versions (recorded drives are research data and must stay replayable).
