#!/usr/bin/env python3
"""
Experiment (2026-09-28, R-008/R-010): absolute heading without GNSS and compass.

The gyro bearing is very stable (R-007: drift −0.12°/min vs GNSS course), so the only unknown is one
heading offset. This script builds a relative path from the gyro (yaw about "up" = ±60 s mean of raw
ACCEL, *not* Android GRAVITY; no bias learning) and OBD speed (scale 1/0.971, 0.8 s lag, from R-007),
then fits it rigidly (rotation + translation, weighted, Huber) to the *past* network fixes in a window
(causal = usable in real time). On a drive with GNSS it prints the error against GNSS.

Defects fixed vs the first version (R-008): bias was learned whenever OBD read 0 km/h (OBD reports 0
at a crawl and in reverse, so turning was learned as bias), and "up" came from Android GRAVITY, which
leans in turns (dev-guide P14).

usage: shape_fit.py <drive.db> <window_s[,window_s...]> [out.json]
"""
# Causal heading-offset fit with long windows: gyro bearing (own long-window "up", no bias learning)
# + OBD speed → relative path; rigid fit (rotation + translation) to past network fixes only.
import sqlite3, sys, math, bisect, json
DB = sys.argv[1]; WINS = [float(w) for w in sys.argv[2].split(',')]; OUT = sys.argv[3] if len(sys.argv) > 3 else None
db = sqlite3.connect(DB)
T1 = db.execute("select min(t_ns) from (select t_ns from location union all select t_ns from imu)").fetchone()[0]
acc = db.execute("select t_ns,x,y,z from imu where kind='ACCEL' order by t_ns").fetchall()
gyr = db.execute("select t_ns,x,y,z from imu where kind='GYRO' order by t_ns").fetchall()
obd = db.execute("select t_ns,speed_mps from vehicle_speed order by t_ns").fetchall(); ot = [o[0] for o in obd]
B = {}
for t, x, y, z in acc:
    k = (t - T1) // 1_000_000_000; s = B.setdefault(k, [0, 0, 0]); s[0] += x; s[1] += y; s[2] += z
up = {}
for k in B:
    sx = sy = sz = 0
    for j in range(k - 60, k + 61):
        if j in B: sx += B[j][0]; sy += B[j][1]; sz += B[j][2]
    n = math.sqrt(sx*sx + sy*sy + sz*sz); up[k] = (sx/n, sy/n, sz/n)
def v_at(t):
    t += int(0.8e9); i = bisect.bisect_left(ot, t)
    if i == 0 or i >= len(ot) or ot[i] - ot[i-1] > 2e9: return 0.0
    a, b = obd[i-1], obd[i]; return (a[1] + (b[1]-a[1]) * (t-a[0]) / (b[0]-a[0])) / 0.971
psi = 0.0; e = n = 0.0; last = None; path = []; c = 0
for t, x, y, z in gyr:
    k = (t - T1) // 1_000_000_000
    if last is not None and k in up:
        dt = (t - last) / 1e9; u = up[k]
        psi -= (x*u[0] + y*u[1] + z*u[2]) * dt
        v = v_at(t); e += v*dt*math.sin(psi); n += v*dt*math.cos(psi)
    last = t; c += 1
    if c % 20 == 0: path.append((t, e, n))
pt = [p[0] for p in path]
def p_at(t): return complex(*path[min(bisect.bisect_left(pt, t), len(path)-1)][1:])
lat0, lon0 = db.execute("select avg(lat),avg(lon) from location where provider='network'").fetchone()
kx = math.radians(1)*6371000*math.cos(math.radians(lat0)); ky = math.radians(1)*6371000
enu = lambda la, lo: complex((lo-lon0)*kx, (la-lat0)*ky)
ll = lambda z: (lat0 + z.imag/ky, lon0 + z.real/kx)
net = [(t, enu(la, lo), p_at(t), h) for t, la, lo, h in db.execute("select t_ns,lat,lon,h_acc_m from location where provider='network' order by t_ns")]
def fit(S):
    w = [1/(s[3]**2) for s in S]
    for _ in range(5):
        W = sum(w); zm = sum(a*s[1] for a, s in zip(w, S))/W; pm = sum(a*s[2] for a, s in zip(w, S))/W
        h = sum(a*(s[1]-zm)*(s[2]-pm).conjugate() for a, s in zip(w, S))
        if abs(h) == 0: return None
        r = h/abs(h); cc = zm - r*pm
        res = [abs(s[1]-(r*s[2]+cc)) for s in S]
        w = [(1/(s[3]**2))*min(1, 2*s[3]/max(q, 1e-6)) for s, q in zip(S, res)]
    return r, cc
gps = db.execute("select t_ns,lat,lon from location where provider='gps' and h_acc_m<=10 order by t_ns").fetchall()
gps = [g for g in gps if g[0] < T1 + 1370e9]
out = {}
for W in WINS:
    track = []
    E = []
    ticks = range(0, int((path[-1][0]-T1)/1e9), 2)
    for s in ticks:
        t = T1 + int(s*1e9)
        S = [q for q in net if 0 <= t - q[0] <= W*1e9]
        if len(S) < 6: continue
        # the fixes must span some distance, otherwise the rotation is meaningless
        if max(abs(q[2] - S[-1][2]) for q in S) < 150: continue
        f = fit(S)
        if not f: continue
        z = f[0]*p_at(t) + f[1]; track.append((s,) + ll(z))
    if gps:
        tt = [x[0] for x in track]
        for g in gps[::5]:
            sg = (g[0]-T1)/1e9; i = bisect.bisect_left(tt, sg)
            if i < len(tt) and abs(tt[i]-sg) <= 1.5:
                E.append(6371000*math.hypot(math.radians(track[i][1]-g[1]), math.radians(track[i][2]-g[2])*math.cos(math.radians(g[1]))))
        E.sort(); m = len(E)
        print(f"causal window {W:5.0f} s: n={m} p50={E[m//2]:.0f} p95={E[int(m*.95)]:.0f} max={E[-1]:.0f} m")
    out[W] = track
if OUT:
    json.dump({str(k): [[s, round(a, 6), round(b, 6)] for s, a, b in v] for k, v in out.items()}, open(OUT, 'w'))
