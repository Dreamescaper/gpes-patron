#!/usr/bin/env python3
"""Compare causal motion outputs; recorded OBD is a reference, never an estimator input.

Uses only the Python standard library. Example:
  python3 tools/plot/compare_motion.py --drive drive.db --before old/ticks.csv \
    --after new/ticks.csv --out comparison.json
Both replay variants must explicitly exclude OBD. Otherwise the OBD comparison is not independent.
"""
import argparse
import bisect
import csv
import json
import math
import sqlite3
from pathlib import Path

MEASUREMENT_TABLES = (
    "location", "imu", "orientation", "gnss_status", "gnss_clock", "nmea",
    "cell_scan", "wifi_scan", "geomag", "power", "vehicle_speed", "provider_event", "annotation",
)


def reference(drive):
    """Use the same first-measurement time origin as replay, excluding recorded output."""
    with sqlite3.connect(f"file:{Path(drive).resolve()}?mode=ro", uri=True) as connection:
        available = {r[0] for r in connection.execute("select name from sqlite_master where type='table'")}
        starts = [connection.execute(f"select min(t_ns) from {table}").fetchone()[0]
                  for table in MEASUREMENT_TABLES if table in available]
        origin = min(t for t in starts if t is not None)
        samples = list(connection.execute("select t_ns,speed_mps from vehicle_speed order by t_ns")) \
            if "vehicle_speed" in available else []
    times = [(t - origin) / 1e9 for t, _ in samples]
    speeds = [v for _, v in samples]

    def at(t):
        i = bisect.bisect_left(times, t)
        if i < len(times) and times[i] == t:
            return speeds[i]
        if i == 0 or i == len(times) or times[i] - times[i - 1] > 2:
            return None
        fraction = (t - times[i - 1]) / (times[i] - times[i - 1])
        return speeds[i - 1] + fraction * (speeds[i] - speeds[i - 1])

    return at, bool(samples)


def score(rows, reference_at, obd):
    records = []
    for row in rows:
        t = float(row["t_s"])
        truth = reference_at(t) if obd else \
            (float(row["truth_speed_mps"]) if row["truth_speed_mps"] else None)
        speed = float(row["est_speed_mps"]) if row["est_speed_mps"] else None
        if truth is not None and speed is not None and all(map(math.isfinite, (t, truth, speed))):
            records.append((t, truth, abs(speed)))
    false_stops = [r for r in records if r[1] > 3 and r[2] < .5]
    stops = [r for r in records if r[1] <= .5]
    longest = run = 0
    last = -math.inf
    for t, _, _ in false_stops:
        run = run + 1 if t - last < 1.1 else 1
        longest = max(longest, run)
        last = t

    intervals, current = [], []
    for r in records:
        if r[1] > .5 or (current and r[0] - current[-1][0] > 1.1):
            if len(current) >= 5:
                intervals.append(current)
            current = []
        if r[1] <= .5:
            current.append(r)
    if len(current) >= 5:
        intervals.append(current)
    departures = []
    for interval in intervals:
        end = interval[-1][0]
        departure = next((r for r in records if end < r[0] <= end + 30 and r[1] > 2), None)
        if departure is None:
            continue
        exit_tick = next((r for r in records if departure[0] <= r[0] <= departure[0] + 15 and r[2] > .5), None)
        departures.append({
            "reference_departure_s": departure[0],
            "delay_s": None if exit_tick is None else exit_tick[0] - departure[0],
            # Zero delay is not a successful departure if the estimate missed the preceding stop.
            "estimated_stopped_before_departure": interval[-1][2] < .5,
        })
    return {
        "reference": "recorded OBD (must be excluded from estimator)" if obd else "clean GNSS tick truth",
        "reference_ticks": len(records),
        "moving_reference_ticks": sum(r[1] > 3 for r in records),
        "false_stop_ticks": len(false_stops), "longest_false_stop_s": longest,
        "stop_reference_ticks": len(stops), "missed_stop_ticks": sum(r[2] >= .5 for r in stops),
        "predicted_travel_during_reference_stops_m": sum(r[2] for r in stops),
        "sustained_stop_intervals": len(intervals), "departures": departures,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--drive", required=True, type=Path)
    parser.add_argument("--before", required=True, type=Path)
    parser.add_argument("--after", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    at, obd = reference(args.drive)
    result = {
        "sampling": "1 Hz replay ticks; gaps are unscored, not interpolated",
        "thresholds": "reference moving >3 m/s; stop <=0.5; estimated near-zero <0.5",
        "departure": "reference >2 after >=5 stop ticks; 15-s horizon, null means censored",
    }
    for name, path in [("before", args.before), ("after", args.after)]:
        with path.open() as file:
            rows = list(csv.DictReader(file))
        if any(b <= a or abs(b - a - 1) > 1e-6 for a, b in zip(
                [float(r["t_s"]) for r in rows], [float(r["t_s"]) for r in rows][1:])):
            parser.error("ticks must be ordered at 1 Hz to interpret counts/integrals as seconds/metres")
        result[name] = score(rows, at, obd)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
