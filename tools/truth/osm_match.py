#!/usr/bin/env python3
"""
Reconstruct the driven route on OpenStreetMap roads (offline map matching) and write it as a replay
truth file. Meant for drives without GNSS (jamming), where there is otherwise no truth at all.

Method: HMM map matching (Newson & Krumm 2009), Viterbi over road candidates.
  - Observations: an estimated track (replay ticks.csv, e.g. phone+network+obd) every STEP seconds.
  - Emission: Gaussian in the distance from the observation to the road candidate (sigma SIGMA_OBS).
  - Transition: the road distance between consecutive candidates must match the distance driven by OBD
    (exponential in |road − odo|, scale BETA). This is what keeps the route off parallel streets.
  - One-way streets and roundabouts are respected (driving direction only).
Then the full route is the chain of shortest paths between the chosen candidates, and positions over
time are placed along it by the OBD distance (so stops stay stops). Where the road distance between two
observations disagrees with the OBD distance (> 40 m and > 30%: an ambiguous interchange, a loop), no
truth is written for that interval, so replay leaves it out instead of scoring against a wrong place.
Check the route against what was really driven (e.g. a navigation app screenshot) before trusting it.

Pure Python, no dependencies. Roads: Overpass JSON (ways with highway=* and their nodes), downloaded once.

usage: osm_match.py <drive.db> <ticks.csv> <osm_roads.json> <out.truth.json> [route.geojson] [constraints.json]

constraints.json (corrections from the driver), a list of intervals in seconds since the drive start:
  {"fromS": 355, "toS": 395, "names": ["Голосіївський проспект"], "note": "..."}   only these road names
  {"fromS": 595, "toS": 640, "highways": ["trunk"]}                               only these highway types
  {"fromS": 1070, "toS": 1100, "excludeHighways": ["trunk_link"]}                 never these types
"""
import bisect
import csv
import heapq
import json
import math
import sqlite3
import sys

STEP = 5.0          # s between observations
SIGMA_OBS = 50.0    # m, emission sigma (our estimate is coarse)
RADIUS = 150.0      # m, candidate search radius
MAX_CAND = 40
BETA = 25.0         # m, transition scale for |road distance − OBD distance|
ONEWAY_PENALTY = 6.0  # log-likelihood penalty for a transition that ignores one-way rules (OSM link
                      # directions at complex interchanges, or a candidate on the wrong carriageway)
OBD_SCALE = 1 / 0.971   # R-007: the adapter reads 2.9% low
OBD_LAG_NS = 800_000_000

R_EARTH = 6371000.0


def enu_factory(lat0, lon0):
    kx = math.radians(1) * R_EARTH * math.cos(math.radians(lat0)); ky = math.radians(1) * R_EARTH
    return (lambda la, lo: ((lo - lon0) * kx, (la - lat0) * ky)), (lambda x, y: (lat0 + y / ky, lon0 + x / kx))


def load_graph(path, to_xy):
    data = json.load(open(path))
    nodes = {e['id']: to_xy(e['lat'], e['lon']) for e in data['elements'] if e['type'] == 'node'}
    adj = {}      # node → list of (node, length), respecting one-way rules
    adj_any = {}  # node → list of (node, length), ignoring them (penalized fallback)
    segs = []     # directed-usable segments: (u, v, length, two_way)
    seg_tags = []  # way tags per segment
    for e in data['elements']:
        if e['type'] != 'way':
            continue
        t = e.get('tags', {})
        hw = t.get('highway', '')
        ow = t.get('oneway', 'no')
        oneway = ow in ('yes', 'true', '1') or t.get('junction') in ('roundabout', 'circular') or hw in ('motorway',)
        reverse = ow == '-1'
        nd = [n for n in e['nodes'] if n in nodes]
        for a, b in zip(nd, nd[1:]):
            if reverse:
                a, b = b, a
            (xa, ya), (xb, yb) = nodes[a], nodes[b]
            L = math.hypot(xb - xa, yb - ya)
            if L <= 0:
                continue
            two_way = not (oneway or reverse)
            segs.append((a, b, L, two_way))
            seg_tags.append(t)
            adj.setdefault(a, []).append((b, L))
            if two_way:
                adj.setdefault(b, []).append((a, L))
            adj_any.setdefault(a, []).append((b, L)); adj_any.setdefault(b, []).append((a, L))
    return nodes, adj, adj_any, segs, seg_tags


class SegIndex:
    def __init__(self, nodes, segs, cell=100.0):
        self.cell = cell; self.grid = {}; self.nodes = nodes; self.segs = segs
        for i, (a, b, L, _) in enumerate(segs):
            (xa, ya), (xb, yb) = nodes[a], nodes[b]
            for gx in range(int(min(xa, xb) // cell), int(max(xa, xb) // cell) + 1):
                for gy in range(int(min(ya, yb) // cell), int(max(ya, yb) // cell) + 1):
                    self.grid.setdefault((gx, gy), []).append(i)

    def near(self, x, y, r):
        out = {}
        for gx in range(int((x - r) // self.cell), int((x + r) // self.cell) + 1):
            for gy in range(int((y - r) // self.cell), int((y + r) // self.cell) + 1):
                for i in self.grid.get((gx, gy), ()):
                    if i in out:
                        continue
                    a, b, L, _ = self.segs[i]
                    (xa, ya), (xb, yb) = self.nodes[a], self.nodes[b]
                    f = max(0.0, min(1.0, ((x - xa) * (xb - xa) + (y - ya) * (yb - ya)) / (L * L)))
                    px, py = xa + f * (xb - xa), ya + f * (yb - ya)
                    d = math.hypot(x - px, y - py)
                    if d <= r:
                        out[i] = (d, f, px, py)
        return sorted(((d, i, f, px, py) for i, (d, f, px, py) in out.items()))


def dijkstra(adj, src, limit):
    dist = {src: 0.0}; prev = {}; pq = [(0.0, src)]
    while pq:
        d, u = heapq.heappop(pq)
        if d > dist.get(u, 1e18) or d > limit:
            continue
        for v, L in adj.get(u, ()):
            nd = d + L
            if nd < dist.get(v, 1e18):
                dist[v] = nd; prev[v] = u; heapq.heappush(pq, (nd, v))
    return dist, prev


def path_nodes(prev, src, dst):
    out = [dst]
    while out[-1] != src:
        out.append(prev[out[-1]])
    return out[::-1]


def main():
    db_path, ticks_path, osm_path, out_path = sys.argv[1:5]
    geo_path = sys.argv[5] if len(sys.argv) > 5 else None
    db = sqlite3.connect(db_path)
    T1 = db.execute("select min(t_ns) from (select t_ns from location union all select t_ns from imu)").fetchone()[0]
    ticks = [r for r in csv.DictReader(open(ticks_path))]
    lat0 = sum(float(r['est_lat']) for r in ticks) / len(ticks); lon0 = sum(float(r['est_lon']) for r in ticks) / len(ticks)
    to_xy, to_ll = enu_factory(lat0, lon0)
    nodes, adj, adj_any, segs, seg_tags = load_graph(osm_path, to_xy)
    constraints = json.load(open(sys.argv[6])) if len(sys.argv) > 6 else []

    def allowed(ts, si):
        t = seg_tags[si]
        for c in constraints:
            if not (c['fromS'] <= ts <= c['toS']):
                continue
            if 'names' in c and not any(n in t.get('name', '') for n in c['names']):
                return False
            if 'highways' in c and t.get('highway') not in c['highways']:
                return False
            if t.get('highway') in c.get('excludeHighways', ()):
                return False
        return True
    idx = SegIndex(nodes, segs)
    print(f"graph: {len(nodes)} nodes, {len(segs)} segments", file=sys.stderr)

    obd = db.execute("select t_ns,speed_mps from vehicle_speed order by t_ns").fetchall()
    ot = [o[0] for o in obd]
    # cumulative OBD distance at time t (ns since T1 → metres)
    cum = [0.0]
    for i in range(1, len(obd)):
        dt = (obd[i][0] - obd[i - 1][0]) / 1e9
        cum.append(cum[-1] + (obd[i - 1][1] * dt * OBD_SCALE if dt < 2 else 0.0))

    def odo(t_ns):
        t = t_ns + OBD_LAG_NS
        i = bisect.bisect_left(ot, t)
        if i <= 0: return 0.0
        if i >= len(ot): return cum[-1]
        f = (t - ot[i - 1]) / (ot[i] - ot[i - 1])
        return cum[i - 1] + f * (cum[i] - cum[i - 1])

    # observations every STEP seconds, skipping stretches where the car does not move
    obs = []
    last_odo = -1e9
    for r in ticks:
        ts = float(r['t_s'])
        if obs and ts - obs[-1][0] < STEP:
            continue
        t_ns = T1 + int(ts * 1e9)
        o = odo(t_ns)
        if obs and o - last_odo < 5.0:
            continue
        x, y = to_xy(float(r['est_lat']), float(r['est_lon']))
        obs.append((ts, t_ns, x, y, o)); last_odo = o

    cands = []
    for ts, t_ns, x, y, o in obs:
        c = [q for q in idx.near(x, y, RADIUS * (2 if constraints else 1)) if allowed(ts, q[1])][:MAX_CAND]
        if not c:
            c = idx.near(x, y, RADIUS)[:MAX_CAND]
            print(f"no candidate satisfies the constraints at {ts:.0f}s; ignoring them there", file=sys.stderr)
        cands.append(c)
    print(f"observations: {len(obs)}, empty: {sum(1 for c in cands if not c)}", file=sys.stderr)

    # Viterbi
    def emis(d):
        return -0.5 * (d / SIGMA_OBS) ** 2

    INF = -1e18
    score = [[emis(c[0]) for c in cands[0]]]
    back = [[None] * len(cands[0])]
    paths = [[None] * len(cands[0])]
    for k in range(1, len(obs)):
        prev_c, cur_c = cands[k - 1], cands[k]
        d_odo = obs[k][4] - obs[k - 1][4]
        limit = d_odo * 2.0 + 300
        sc = [INF] * len(cur_c); bk = [None] * len(cur_c); ph = [None] * len(cur_c)
        for i, (dp, si, fi, pxi, pyi) in enumerate(prev_c):
            if score[-1][i] <= INF / 2:
                continue
            a, b, L, two = segs[si]
            # exits from the previous candidate: forward to b, backward to a if two-way
            starts = [(b, (1 - fi) * L)] + ([(a, fi * L)] if two else [])
            dmaps = [(n0, d0, dijkstra(adj, n0, limit)) for n0, d0 in starts]
            dmaps_any = [(n0, d0, dijkstra(adj_any, n0, limit)) for n0, d0 in [(b, (1 - fi) * L), (a, fi * L)]]
            for j, (dc, sj, fj, pxj, pyj) in enumerate(cur_c):
                c2, d2, L2, two2 = segs[sj]

                def shortest(maps, any_dir):
                    best = None
                    if sj == si and (fj >= fi or two or any_dir):
                        best = (abs(fj - fi) * L, None)
                    for n0, d0, (dist, prev) in maps:
                        for entry, d_in in [(c2, fj * L2)] + ([(d2, (1 - fj) * L2)] if (two2 or any_dir) else []):
                            if entry in dist:
                                tot = d0 + dist[entry] + d_in
                                if best is None or tot < best[0]:
                                    best = (tot, (n0, entry, prev))
                    return best
                best = shortest(dmaps, False)
                trans = -abs(best[0] - d_odo) / BETA if best else INF
                alt = shortest(dmaps_any, True)
                if alt is not None:
                    t_alt = -abs(alt[0] - d_odo) / BETA - ONEWAY_PENALTY
                    if t_alt > trans:
                        best, trans = alt, t_alt
                if best is None:
                    continue
                s = score[-1][i] + trans + emis(dc)
                if s > sc[j]:
                    sc[j] = s; bk[j] = i; ph[j] = best[1]
        if all(s <= INF / 2 for s in sc):
            # broken chain: restart from emissions (logged)
            print(f"break at t={obs[k][0]:.0f}s", file=sys.stderr)
            sc = [emis(c[0]) for c in cur_c]; bk = [None] * len(cur_c); ph = [None] * len(cur_c)
        score.append(sc); back.append(bk); paths.append(ph)

    # backtrack
    j = max(range(len(cands[-1])), key=lambda q: score[-1][q])
    chosen = [None] * len(obs)
    for k in range(len(obs) - 1, -1, -1):
        chosen[k] = j
        j = back[k][j] if back[k][j] is not None else (max(range(len(cands[k - 1])), key=lambda q: score[k - 1][q]) if k > 0 else None)

    # route geometry: per observation, the candidate point; between, the node path
    route = []  # list of (x, y, obs_index_or_None)
    for k in range(len(obs)):
        c = cands[k][chosen[k]]
        if k > 0 and paths[k][chosen[k]] is not None:
            n0, entry, prev = paths[k][chosen[k]]
            for n in path_nodes(prev, n0, entry):
                route.append((nodes[n][0], nodes[n][1], None))
        route.append((c[3], c[4], k))
    # cumulative distance along the route; anchor observation k at route distance
    rd = [0.0]
    for p, q in zip(route, route[1:]):
        rd.append(rd[-1] + math.hypot(q[0] - p[0], q[1] - p[1]))
    anchors = [(obs[p[2]][1], rd[i], obs[p[2]][4]) for i, p in enumerate(route) if p[2] is not None]

    def point_at(dist):
        i = max(0, min(bisect.bisect_right(rd, dist) - 1, len(route) - 2))
        seg = rd[i + 1] - rd[i]
        f = 0.0 if seg <= 0 else (dist - rd[i]) / seg
        x = route[i][0] + f * (route[i + 1][0] - route[i][0]); y = route[i][1] + f * (route[i + 1][1] - route[i][1])
        b = math.degrees(math.atan2(route[i + 1][0] - route[i][0], route[i + 1][1] - route[i][1])) % 360
        return x, y, b

    unreliable = [(ta, tb) for (ta, ra, oa), (tb, rb, ob) in zip(anchors, anchors[1:])
                  if abs((rb - ra) - (ob - oa)) > max(40.0, 0.3 * (ob - oa))]
    truth = []
    t_start, t_end = anchors[0][0], anchors[-1][0]
    t = t_start
    ai = 0
    while t <= t_end:
        while ai < len(anchors) - 2 and anchors[ai + 1][0] < t:
            ai += 1
        (ta, ra, oa), (tb, rb, ob) = anchors[ai], anchors[ai + 1]
        o = odo(t)
        f = 0.0 if ob - oa < 1e-6 else max(0.0, min(1.0, (o - oa) / (ob - oa)))
        x, y, b = point_at(ra + f * (rb - ra))
        la, lo = to_ll(x, y)
        i = bisect.bisect_left(ot, t + OBD_LAG_NS)
        v = obd[min(i, len(obd) - 1)][1] * OBD_SCALE
        if not any(ta <= t <= tb for ta, tb in unreliable):
            truth.append({"tNs": t, "lat": la, "lon": lo, "bearingDeg": b, "speedMps": v})
        t += 1_000_000_000
    json.dump(truth, open(out_path, 'w'))
    dev = sorted(cands[k][chosen[k]][0] for k in range(len(obs)))
    print(f"route {rd[-1] / 1000:.2f} km vs OBD {(obs[-1][4] - obs[0][4]) / 1000:.2f} km; observation→road p50 {dev[len(dev) // 2]:.0f} m, "
          f"p95 {dev[int(len(dev) * .95)]:.0f} m; truth samples {len(truth)}", file=sys.stderr)
    print("unreliable (no truth): " + ", ".join(f"{(a - T1) / 1e9:.0f}–{(b - T1) / 1e9:.0f} s" for a, b in unreliable), file=sys.stderr)
    if geo_path:
        coords = [[round(to_ll(x, y)[1], 6), round(to_ll(x, y)[0], 6)] for x, y, _ in route]
        json.dump({"type": "Feature", "properties": {}, "geometry": {"type": "LineString", "coordinates": coords}}, open(geo_path, 'w'))


if __name__ == '__main__':
    main()
