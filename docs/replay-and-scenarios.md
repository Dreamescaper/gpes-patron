# Replay, scenarios and metrics

Code: `core/src/main/kotlin/gpes/core/replay/`, CLI `replay-cli/`.

## CLI

```bash
./gradlew :replay-cli:installDist
R=replay-cli/build/install/replay/bin/replay

$R simulate --out sim/drive.db [--network-period 20] [--seed 1] [--config simconfig.json]
      # SimConfig JSON can set legs, magnetometer distortions (car/phone hard & soft iron, anomalies,
      # magClipUt saturation, wirelessChargingUt), holder wobble (mountWobbleDeg/Hz/OnS/OffS),
      # gyroScaleError, gyroBiasWalk, GRV on/off, …
      # also writes sim/drive.truth.json
$R export  --drive x.db --format jsonl|csv|gnsslogger --out <file|dir>
$R run     --drive x.db|x.jsonl [--scenario file|dir]... [--variant name]... [--variants v.json] [--truth t.json] --out dir
$R matrix  --drive a.db --drive b.db --scenario scenarios [--variants v.json] [--truth t.json] --out dir
$R compass-report --drive x.db [--truth t.json] --out dir
      # verdict + reasons, fit metrics, held-out heading error per mode, compass_timeline.csv
```

Outputs per run (`<out>/<drive>/<scenario>__<variant>/`):
- `summary.json`: all metrics.
- `ticks.csv`: one row per 1 Hz estimate (estimate, truth, error, r68, mode, degraded window, and
  estimated/true speed and heading).
- `trust.csv`: every GNSS assessment with its injected offset label.
- `error_vs_time.csv`: p50/p95 error by time since degradation began (10 s bins).

Aggregate: `comparison.csv`, `comparison.md`, `summaries.json`.

## How a replay works (`ReplayRunner`)

1. **Truth.** Clean GNSS fixes that the trust evaluator accepts with hAcc ≤ 10 m, linearly
   interpolated (gaps ≤ 3 s), or `--truth` for simulated drives. Ticks without truth are excluded
   from error metrics.
2. **Scenario + variant steps.** These are applied to each measurement (drop, offset, …).
   Synthetic measurements (network, vehicle speed) are generated from truth. Every GNSS fix is
   labelled with its injected offset.
3. **Pipeline.** The same `MeasurementPipeline` as the app, with reorder window 0, the chosen
   estimator and the chosen trust config.

## Scenario format

```json
{ "name": "gnss_jump_5km", "description": "...", "seed": 42,
  "steps": [ { "type": "offset", "startS": 120, "durationS": 120, "dEastM": 5000, "dNorthM": 0 } ] }
```

Times are seconds from the first measurement; omit `durationS` for "until the end".

| `type` | Parameters | Effect |
|---|---|---|
| `drop_source` | source, dropRawGnss (true) | Removes location fixes from a source. For GNSS it also removes status, raw measurements and NMEA (jamming). |
| `offset` | dEastM, dNorthM, rampS, sources, consistentVelocity | A jump, or a ramped capture. With `consistentVelocity`, reported speed and bearing are rewritten to match (a competent spoofer); otherwise velocity is left untouched. |
| `drift` | rateMps, bearingDeg, sources, consistentVelocity | An offset growing linearly (velocity optionally consistent) |
| `teleport` | lat, lon, frozen, sources | Translates the track to another place (consistent motion), or freezes it there |
| `inflate_noise` | source, sigmaM, reportHonestly | Extra noise, by default with unchanged (overconfident) accuracy |
| `drop_sensor` | kind (null = all IMU), orientation | Removes IMU or orientation samples |
| `drop_vehicle_speed` | – | Removes recorded vehicle speed (OBD); synthetic speed is kept |
| `synthetic_network` | sigmaM, periodS | Coarse fixes from truth + noise |
| `synthetic_vehicle_speed` | sigmaMps, periodS, scaleError, quantizeKmh, latencyS | OBD-like speed from truth (the ladder uses ELM327-like: integer km/h, 0.15 s delay, +3%) |

Standard set (`scenarios/`): clean, gnss_drop_30s / 2min / 10min / 1h, gnss_absent_from_start,
gnss_jump_5km, gnss_drift_gradual, gnss_drift_doppler_consistent, gnss_ramp_capture,
gnss_ramp_capture_doppler_consistent, gnss_teleport_country, gnss_noise_overconfident.

## Truth for drives without GNSS (`tools/truth/osm_match.py`)

Offline map matching of an estimated track onto OpenStreetMap roads (HMM + Viterbi, Newson & Krumm):
candidates within 150 m, emission σ 50 m, transition |road distance − OBD distance| (β 25 m), one-way
rules with a penalized fallback. Positions over time are placed along the route by OBD distance;
intervals where road and OBD distance disagree (> 40 m and > 30%: ambiguous interchanges, loops) get
no truth. Output: a `--truth` JSON for `replay run` and a GeoJSON route.

```bash
# roads for the drive's bounding box (one download; only the box is sent)
curl -A "gpes-patron/0.1" --data-urlencode 'data=[out:json];way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|service|living_street|.*_link)$"](S,W,N,E);(._;>;);out body qt;' https://overpass-api.de/api/interpreter > roads.json
python3 tools/truth/osm_match.py drive.db out/clean__phone+network+obd/ticks.csv roads.json drive.truth.json route.geojson
$R run --drive drive.db --scenario scenarios/clean.json --truth drive.truth.json --out out2
```

Driver corrections: a constraints JSON (6th argument) restricts candidates by time interval —
`names` (road names), `highways` (types) or `excludeHighways`. See the script's docstring. For R-008 the
driver's three corrections turned a 12.45-km route with loops into 11.29 km (OBD 11.16 km).

Caveats: the route follows the track it was matched from, so scoring *that* estimator against it is
partly circular; confirm the route against what was really driven (navigation screenshot) first.

## Plots

```bash
pip install -r tools/plot/requirements.txt
python3 tools/plot/plot_replay.py <out>/<drive>/<scenario>__<variant>   # writes plot.png
```

The plot has three panels: track (estimate vs truth), error vs reported r68/r95 with the degraded windows shaded, and the GNSS trust timeline with the injected offset.

## Variants (ablation ladder)

`Variant(name, estimator, extraSteps, baseline: BaselineConfig, trust: TrustConfig)`. Default
ladder (`Variant.standard()`):

Recorded OBD is dropped (`drop_vehicle_speed`) in every rung except the `+obd` ones; the `+synthObd`
rungs replace it instead of adding a second speed source (D-033). Before 2026-09-28 the recorded OBD
leaked into every rung, so R-007…R-010 "gyro-only/phone-only/phone+network" on real drives include OBD.

| name | estimator | compass | extra steps |
|---|---|---|---|
| hold-last-fix | passthrough | – | – |
| gyro-only | baseline | off | drop NETWORK, FUSED, OBD |
| phone-only | baseline | on | drop NETWORK, FUSED, OBD |
| phone+network | baseline | on | drop FUSED, OBD (uses recorded network) |
| phone+synthNetwork | baseline | on | drop NETWORK/FUSED/OBD, synthetic network σ 500 m / 20 s |
| gyro+synthNetwork+synthObd | baseline | off | + synthetic speed (ELM327-like: integer km/h, 0.15 s, +3%) |
| phone+synthNetwork+synthObd | baseline | on | + synthetic speed (as above) |
| phone+obd | baseline | on | drop NETWORK, FUSED (uses recorded OBD) |
| phone+network+obd | baseline | on | drop FUSED (recorded network + OBD) |

Custom variants go in a JSON list passed with `--variants`. Future rungs: `+osm`, `+route`.

## Metrics (`Metrics`)

- Position error: RMSE, p50, p95, max, over all ticks and over degraded windows.
- Heading error p50/p95 (truth speed > 3 m/s).
- **Calibration:** `within68` / `within95`, the fraction of ticks where error ≤ the reported radius.
  Ideal is about 0.68 / 0.95; much higher means too pessimistic, lower means overconfident.
- Per degraded window: max error, error at the end, time to exceed 50/100/250/500/1000 m, and
  recovery time after the window (error < 20 m, within 5 min).
- Trust: false-rejection rate (clean, truth-quality fixes not TRUSTED), missed-detection rate
  (fixes offset ≥ 50 m that were TRUSTED), and detection latency per manipulated window.
- Mode fractions.
