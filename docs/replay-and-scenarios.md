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
- `ticks.csv`: one row per 1 Hz estimate (estimate, truth, error, r68, mode, degraded window).
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
| `synthetic_network` | sigmaM, periodS | Coarse fixes from truth + noise |
| `synthetic_vehicle_speed` | sigmaMps, periodS, scaleError, quantizeKmh, latencyS | OBD-like speed from truth (the ladder uses ELM327-like: integer km/h, 0.15 s delay, +3%) |

Standard set (`scenarios/`): clean, gnss_drop_30s / 2min / 10min / 1h, gnss_absent_from_start,
gnss_jump_5km, gnss_drift_gradual, gnss_drift_doppler_consistent, gnss_ramp_capture,
gnss_ramp_capture_doppler_consistent, gnss_teleport_country, gnss_noise_overconfident.

## Plots

```bash
pip install -r tools/plot/requirements.txt
python3 tools/plot/plot_replay.py <out>/<drive>/<scenario>__<variant>   # writes plot.png
```

The plot has three panels: track (estimate vs truth), error vs reported r68/r95 with the degraded windows shaded, and the GNSS trust timeline with the injected offset.

## Variants (ablation ladder)

`Variant(name, estimator, extraSteps, baseline: BaselineConfig, trust: TrustConfig)`. Default
ladder (`Variant.standard()`):

| name | estimator | compass | extra steps |
|---|---|---|---|
| hold-last-fix | passthrough | – | – |
| gyro-only | baseline | off | drop NETWORK, drop FUSED |
| phone-only | baseline | on | drop NETWORK, drop FUSED |
| phone+network | baseline | on | drop FUSED (uses recorded network) |
| phone+synthNetwork | baseline | on | drop NETWORK/FUSED, synthetic network σ 500 m / 20 s |
| gyro+synthNetwork+synthObd | baseline | off | + synthetic speed σ 0.3 m/s, 1% scale error |
| phone+synthNetwork+synthObd | baseline | on | + synthetic speed σ 0.3 m/s, 1% scale error |

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
