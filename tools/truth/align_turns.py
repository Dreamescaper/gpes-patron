#!/usr/bin/env python3
"""
Re-time an OSM-matched truth (osm_match.py) along its own route using gyro turns.

osm_match.py gets the route right but places the car along it by cumulative OBD distance, with no
correction for the adapter's ~0.8 s latency and with along-track anchors from noisy network fixes, so the
truth can run 10–40 m behind or ahead (seen at 2026-09-28 +5:32: the gyro turns 2–3 s before the truth).

Method: first shift the distance by the OBD latency (OBD_LATENCY_S). Then, for every clear corner of the route (bearing change ≥ MIN_TURN_DEG within CORNER_SPAN_M), find
the time shift δ (±MAX_SHIFT_S) for which the gyro heading-change profile around the corner best matches
the route bearing profile placed at s(t + δ). Corners with another corner within 100 m are skipped
(S-bends, U-turns): the shape could slide onto the neighbour. δ > 0: the car was really further along. Each accepted
corner gives an along-track correction Δs = s(t + δ) − s(t); between corners Δs is interpolated linearly
in time (held before the first and after the last), positions are re-placed on the same route polyline.
The route (which roads) does not change.

usage: align_turns.py <drive.db> <in.truth.json> <out.truth.json>
"""
import bisect
import json
import math
import sqlite3
import sys

MIN_TURN_DEG = 30.0
CORNER_SPAN_M = 60.0
WINDOW_M = 70.0       # route window either side of the corner centre
MAX_SHIFT_S = 8.0
STEP_S = 0.1
MAX_RMS_DEG = 8.0
MIN_GAP_M = 80.0      # corners closer than this are merged (take the larger)
OBD_LATENCY_S = 0.8  # osm_match places the car by cumulative OBD distance, which lags by the adapter latency
ISOLATION_M = 100.0   # a corner with another one this close is ambiguous (an S-bend, a U-turn search): skipped


def hav(a, b, c, d):
    k = math.cos(math.radians(a)) * 111320.0
    return math.hypot((d - b) * k, (c - a) * 111320.0)


def bearing(a, b, c, d):
    k = math.cos(math.radians(a))
    return math.degrees(math.atan2((d - b) * k, c - a)) % 360


def wrap(x):
    return (x + 180.0) % 360.0 - 180.0


def main(db_path, in_path, out_path):
    truth = json.load(open(in_path))
    db = sqlite3.connect(db_path)

    # --- route polyline with cumulative distance (from the truth samples themselves; they lie on the route)
    ts = [p["tNs"] for p in truth]
    pts, cum = [], []
    s_of_sample = []
    for p in truth:
        if pts and hav(pts[-1][0], pts[-1][1], p["lat"], p["lon"]) < 0.5:
            s_of_sample.append(cum[-1]); continue
        cum.append(0.0 if not pts else cum[-1] + hav(pts[-1][0], pts[-1][1], p["lat"], p["lon"]))
        pts.append((p["lat"], p["lon"]))
        s_of_sample.append(cum[-1])
    total = cum[-1]
    # Undo the OBD latency first: the distance read at t + L is where the car really was at t.
    raw_s = list(s_of_sample)
    def raw_s_at(t):
        i = bisect.bisect_left(ts, t)
        if i <= 0: return raw_s[0]
        if i >= len(ts): return raw_s[-1]
        f = (t - ts[i - 1]) / (ts[i] - ts[i - 1]) if ts[i] > ts[i - 1] else 0.0
        return raw_s[i - 1] + f * (raw_s[i] - raw_s[i - 1])
    s_of_sample = [raw_s_at(t + OBD_LATENCY_S * 1e9) for t in ts]

    def at_s(s):
        s = min(max(s, 0.0), total)
        i = max(1, bisect.bisect_left(cum, s))
        i = min(i, len(cum) - 1)
        f = (s - cum[i - 1]) / (cum[i] - cum[i - 1]) if cum[i] > cum[i - 1] else 0.0
        a, b = pts[i - 1], pts[i]
        return a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])

    def route_bearing(s):
        a = at_s(s - 6.0); b = at_s(s + 6.0)
        return bearing(a[0], a[1], b[0], b[1])

    def s_at_t(t):  # truth distance at time t (ns), linear between samples; None in gaps > 3 s
        i = bisect.bisect_left(ts, t)
        if i <= 0 or i >= len(ts) or ts[i] - ts[i - 1] > 3e9:
            return None
        f = (t - ts[i - 1]) / (ts[i] - ts[i - 1])
        return s_of_sample[i - 1] + f * (s_of_sample[i] - s_of_sample[i - 1])

    def t_at_s(s):  # first time the truth reaches s
        i = bisect.bisect_left(s_of_sample, s)
        return ts[min(i, len(ts) - 1)]

    # --- gyro heading change (clockwise positive), 10 Hz
    grav = db.execute("select t_ns,x,y,z from imu where kind='GRAVITY' order by t_ns").fetchall()
    gt = [g[0] for g in grav]
    gyro = db.execute("select t_ns,x,y,z from imu where kind='GYRO' and t_ns between ? and ? order by t_ns",
                      (ts[0] - 20e9, ts[-1] + 20e9)).fetchall()
    ht, hv = [], []
    H, last = 0.0, None
    for t, x, y, z in gyro:
        if last is not None:
            g = grav[min(bisect.bisect_left(gt, t), len(grav) - 1)]
            n = math.sqrt(g[1] ** 2 + g[2] ** 2 + g[3] ** 2)
            H -= math.degrees((x * g[1] + y * g[2] + z * g[3]) / n) * (t - last) / 1e9
            if not ht or t - ht[-1] >= 1e8:
                ht.append(t); hv.append(H)
        last = t

    def H_at(t):
        i = min(max(bisect.bisect_left(ht, t), 0), len(ht) - 1)
        return hv[i]

    # --- corners of the route
    corners = []
    s = CORNER_SPAN_M / 2
    while s < total - CORNER_SPAN_M / 2:
        turn = abs(wrap(route_bearing(s + CORNER_SPAN_M / 2) - route_bearing(s - CORNER_SPAN_M / 2)))
        if turn >= MIN_TURN_DEG:
            if corners and s - corners[-1][0] < MIN_GAP_M:
                if turn > corners[-1][1]:
                    corners[-1] = (s, turn)
            else:
                corners.append((s, turn))
        s += 2.0

    anchors = []
    for ci, (sc, turn) in enumerate(corners):
        if any(abs(o[0] - sc) < ISOLATION_M for j, o in enumerate(corners) if j != ci):
            print(f"corner s={sc:6.0f} m turn {turn:5.1f}°  skip (another corner nearby)")
            continue
        t0, t1 = t_at_s(sc - WINDOW_M), t_at_s(sc + WINDOW_M)
        if t1 - t0 < 4e9 or t1 - t0 > 60e9:
            continue  # too fast to resolve, or a stop inside the window
        times = [t0 + k * 2e8 for k in range(int((t1 - t0) / 2e8) + 1)]
        best = None
        costs = []
        d = -MAX_SHIFT_S
        while d <= MAX_SHIFT_S + 1e-9:
            diffs = []
            for t in times:
                st = s_at_t(t + d * 1e9)
                if st is None:
                    continue
                diffs.append(wrap(H_at(t) - route_bearing(st)))
            if len(diffs) >= 0.8 * len(times):
                c = math.degrees(math.atan2(sum(math.sin(math.radians(x)) for x in diffs), sum(math.cos(math.radians(x)) for x in diffs)))
                cost = sum(wrap(x - c) ** 2 for x in diffs) / len(diffs)
                costs.append((d, cost))
                if best is None or cost < best[1]:
                    best = (d, cost)
            d = round(d + STEP_S, 3)
        if best is None:
            continue
        rms = math.sqrt(best[1])
        rival = min((c for dd, c in costs if abs(dd - best[0]) >= 2.0), default=None)
        edge = abs(best[0]) >= MAX_SHIFT_S - STEP_S
        tc = t_at_s(sc)
        s_now, s_true = s_at_t(tc), s_at_t(tc + best[0] * 1e9)
        ok = rms <= MAX_RMS_DEG and not edge and (rival is None or rival > 2 * best[1]) and s_now is not None and s_true is not None
        print(f"corner s={sc:6.0f} m turn {turn:5.1f}° t={(tc - ts[0]) / 1e9:7.1f} s  δ={best[0]:+5.1f} s  rms {rms:4.1f}°"
              f"  Δs={'%+.0f' % (s_true - s_now) if s_now is not None and s_true is not None else '-'} m  {'OK' if ok else 'skip'}")
        if ok:
            anchors.append((tc, s_true - s_now))

    if not anchors:
        print("no corners accepted; truth unchanged")
        json.dump(truth, open(out_path, "w"))
        return

    at = [a[0] for a in anchors]

    def delta(t):
        i = bisect.bisect_left(at, t)
        if i == 0:
            return anchors[0][1]
        if i >= len(anchors):
            return anchors[-1][1]
        (ta, da), (tb, db_) = anchors[i - 1], anchors[i]
        return da + (db_ - da) * (t - ta) / (tb - ta)

    out = []
    prev_s = None
    for p, s0 in zip(truth, s_of_sample):
        s1 = s0 + delta(p["tNs"])
        if prev_s is not None and s1 < prev_s:
            s1 = prev_s  # never drive backwards
        lat, lon = at_s(s1)
        out.append(dict(tNs=p["tNs"], lat=lat, lon=lon, bearingDeg=route_bearing(s1), speedMps=p["speedMps"]))
        prev_s = s1
    json.dump(out, open(out_path, "w"))
    shifts = [a[1] for a in anchors]
    print(f"{len(anchors)}/{len(corners)} corners used; Δs median {sorted(shifts)[len(shifts) // 2]:+.0f} m, range {min(shifts):+.0f}…{max(shifts):+.0f} m")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
