'use strict';
/*
 * Teaching engine for the visualization. NOT a port of :core — a small, readable re-implementation of the same
 * ideas (docs/estimation-algorithm.md): a 5-state EKF [east, north, bearing, speed, gyro bias], a robust network
 * update, a coarse-odometry check and a road matcher with an off-road state. It runs on a synthetic drive in a
 * synthetic town, so no real coordinates or traces are used. Deterministic (seeded).
 */
const D2R = Math.PI / 180, R2D = 180 / Math.PI;
const wrapPi = a => { a = (a + Math.PI) % (2 * Math.PI); if (a < 0) a += 2 * Math.PI; return a - Math.PI; };
function mulberry32(a) {
  return function () {
    a |= 0; a = a + 0x6D2B79F5 | 0;
    let t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}
function gaussian(rnd) { const u = 1 - rnd(), v = rnd(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }

/* ---------- tiny matrices ---------- */
const mat = {
  zeros: (n, m) => Array.from({ length: n }, () => new Array(m).fill(0)),
  eye(n) { const A = mat.zeros(n, n); for (let i = 0; i < n; i++) A[i][i] = 1; return A; },
  mul(A, B) {
    const n = A.length, m = B[0].length, k = B.length, C = mat.zeros(n, m);
    for (let i = 0; i < n; i++) for (let l = 0; l < k; l++) { const a = A[i][l]; if (a === 0) continue; for (let j = 0; j < m; j++) C[i][j] += a * B[l][j]; }
    return C;
  },
  T: A => A[0].map((_, j) => A.map(r => r[j])),
  add: (A, B) => A.map((r, i) => r.map((v, j) => v + B[i][j])),
  inv(A) {
    if (A.length === 1) return [[1 / A[0][0]]];
    const [[a, b], [c, d]] = A, det = a * d - b * c;
    return [[d / det, -b / det], [-c / det, a / det]];
  },
};

/* 2x2 covariance -> 1-sigma ellipse (semi axes in metres, angle from east, counter-clockwise) */
function ellipseOf(xx, xy, yy) {
  const tr = xx + yy, det = xx * yy - xy * xy, d = Math.sqrt(Math.max(0, tr * tr / 4 - det));
  return { a: Math.sqrt(Math.max(1e-9, tr / 2 + d)), b: Math.sqrt(Math.max(1e-9, tr / 2 - d)), ang: 0.5 * Math.atan2(2 * xy, xx - yy) };
}
const K68 = 1.5096, K95 = 2.4477; // 2-D Gaussian: radius of the 68% / 95% contour in sigmas

/* ---------- the synthetic town (metres, x east, y north) ---------- */
const TOWN = {
  streets: [
    { id: 'main', name: 'вул. Центральна', pts: [[-450, -120], [450, -120]] },
    { id: 'par', name: 'вул. Паралельна', pts: [[-450, -152], [450, -152]] },
    { id: 'sobor', name: 'вул. Соборна', pts: [[-100, -280], [-100, 280]] },
    { id: 'lug', name: 'вул. Лугова', pts: [[-300, -280], [-300, 280]] },
    { id: 'rynok', name: 'вул. Ринкова', pts: [[100, -280], [100, 280]] },
    { id: 'sun', name: 'вул. Сонячна', pts: [[330, -280], [330, 280]] },
    { id: 'north', name: 'вул. Північна', pts: [[-450, 60], [-100, 60], [150, 60], [260, 95], [450, 95]] },
    { id: 'high', name: 'вул. Висока', pts: [[-450, 175], [450, 175]] },
    { id: 'emb', name: 'Набережна', pts: [[-450, -205], [450, -205]] },
  ],
  riverY: -222, // below this is the river
  bounds: { x0: -450, x1: 450, y0: -280, y1: 280 },
};
const LAP = [[-100, 60], [150, 60], [260, 95], [330, 95], [330, 175], [100, 175], [100, -120], [-100, -120]];
const ROUTE_WPS = [[-440, -120], [-100, -120], ...LAP, ...LAP, ...LAP.slice(0, 6), [100, -70]];
const LEG_SPEEDS = [9, 18, 14, 9, 9, 16, 11, 14]; // legs after the first: city, avenue, bend, …

function projectOnPolyline(pts, e, n) {
  let best = null;
  for (let i = 0; i < pts.length - 1; i++) {
    const [x1, y1] = pts[i], [x2, y2] = pts[i + 1], dx = x2 - x1, dy = y2 - y1, L2 = dx * dx + dy * dy;
    let u = ((e - x1) * dx + (n - y1) * dy) / L2; u = Math.max(0, Math.min(1, u));
    const qx = x1 + u * dx, qy = y1 + u * dy, d = Math.hypot(e - qx, n - qy);
    if (!best || d < best.d) best = { d, qx, qy, brg: Math.atan2(dx, dy), seg: i };
  }
  let vd = 1e9;
  for (let i = 1; i < pts.length - 1; i++) vd = Math.min(vd, Math.hypot(best.qx - pts[i][0], best.qy - pts[i][1]));
  best.cornerDist = vd; // distance to the nearest interior vertex (a bend or a corner)
  return best;
}
function intersectStreets(A, B, nearE, nearN) {
  let best = null;
  for (let i = 0; i < A.pts.length - 1; i++) for (let j = 0; j < B.pts.length - 1; j++) {
    const [x1, y1] = A.pts[i], [x2, y2] = A.pts[i + 1], [x3, y3] = B.pts[j], [x4, y4] = B.pts[j + 1];
    const den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4); if (Math.abs(den) < 1e-9) continue;
    const t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den, u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / den;
    if (t < 0 || t > 1 || u < 0 || u > 1) continue;
    const p = [x1 + t * (x2 - x1), y1 + t * (y2 - y1)], d = Math.hypot(p[0] - nearE, p[1] - nearN);
    if (!best || d < best.d) best = { x: p[0], y: p[1], d };
  }
  return best;
}

/* ---------- the true route: waypoints with rounded corners, resampled every metre ---------- */
function buildRoute(wps, rad) {
  const pts = [];
  pts.push(wps[0]);
  for (let i = 1; i < wps.length - 1; i++) {
    const P0 = wps[i - 1], P1 = wps[i], P2 = wps[i + 1];
    const l0 = Math.hypot(P1[0] - P0[0], P1[1] - P0[1]), l2 = Math.hypot(P2[0] - P1[0], P2[1] - P1[1]);
    const d = Math.min(rad, l0 / 2, l2 / 2);
    const A = [P1[0] + (P0[0] - P1[0]) / l0 * d, P1[1] + (P0[1] - P1[1]) / l0 * d];
    const B = [P1[0] + (P2[0] - P1[0]) / l2 * d, P1[1] + (P2[1] - P1[1]) / l2 * d];
    for (let k = 0; k <= 14; k++) {
      const u = k / 14, a = (1 - u) * (1 - u), b = 2 * u * (1 - u), c = u * u;
      pts.push([a * A[0] + b * P1[0] + c * B[0], a * A[1] + b * P1[1] + c * B[1]]);
    }
  }
  pts.push(wps[wps.length - 1]);
  const cum = [0];
  for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
  const len = cum[cum.length - 1], res = [];
  let j = 0;
  for (let s = 0; s <= len; s += 1) {
    while (j < pts.length - 2 && cum[j + 1] < s) j++;
    const u = (s - cum[j]) / Math.max(1e-9, cum[j + 1] - cum[j]);
    res.push([pts[j][0] + u * (pts[j + 1][0] - pts[j][0]), pts[j][1] + u * (pts[j + 1][1] - pts[j][1])]);
  }
  const at = s => {
    s = Math.max(0, Math.min(len - 1.001, s));
    const i = Math.floor(s), u = s - i, p = res[i], q = res[i + 1];
    return { x: p[0] + u * (q[0] - p[0]), y: p[1] + u * (q[1] - p[1]), psi: Math.atan2(q[0] - p[0], q[1] - p[1]) };
  };
  // corner positions along the path (for speed limits)
  let acc = 0; const corners = [], legEnds = [];
  for (let i = 1; i < wps.length; i++) {
    acc += Math.hypot(wps[i][0] - wps[i - 1][0], wps[i][1] - wps[i - 1][1]); legEnds.push(acc);
    if (i < wps.length - 1) {
      const a1 = Math.atan2(wps[i][0] - wps[i - 1][0], wps[i][1] - wps[i - 1][1]), a2 = Math.atan2(wps[i + 1][0] - wps[i][0], wps[i + 1][1] - wps[i][1]);
      if (Math.abs(wrapPi(a2 - a1)) > 45 * D2R) corners.push(acc);
    }
  }
  return { len, at, corners, legEnds, pts: res };
}

/* ---------- a whole synthetic drive with all sensors ---------- */
const DT = 0.25, GNSS_UNTIL = 12, NET_FIRST = 7.5, NET_PERIOD = 15;
const SENSOR = { gyroBias: 0.004, gyroScale: 1.02, gyroSigma: 0.004, obdScale: 1.02, obdSigma: 0.3, gnssSigma: 3, netSigma: 30, netHAcc: 45 };

function makeDrive(seed = 7) {
  const rnd = mulberry32(seed), g = () => gaussian(rnd);
  const route = buildRoute(ROUTE_WPS, 18);
  const truth = { t: [], x: [], y: [], psi: [], v: [], s: [] };
  let s = 0, v = 10, t = 0, served = false, hold = 0;
  while (s < route.len - 3) {
    const nearCorner = route.corners.some(c => s > c - 32 && s < c + 18);
    let leg = route.legEnds.findIndex(e => s < e); if (leg < 0) leg = route.legEnds.length - 1;
    const zone = leg === 0 ? 14 : LEG_SPEEDS[(leg - 1) % 8];   // city, residential, avenue, …
    let target = nearCorner ? 7 : zone;
    if (!served && s >= 255) { target = 0; if (v < 0.05) { hold += DT; if (hold >= 6) served = true; } }
    v += Math.max(-2.0 * DT, Math.min(1.6 * DT, target - v));
    if (v < 0.001) v = 0;
    s += v * DT; t += DT;
    const p = route.at(s);
    truth.t.push(t); truth.x.push(p.x); truth.y.push(p.y); truth.psi.push(p.psi); truth.v.push(v); truth.s.push(s);
  }
  const N = truth.t.length;
  const sens = { gyro: [], obd: [], still: [], gnss: [], net: [] };
  let prevFixes = [];
  for (let i = 0; i < N; i++) {
    const psiDot = i === 0 ? 0 : wrapPi(truth.psi[i] - truth.psi[i - 1]) / DT;
    sens.gyro.push(-psiDot * SENSOR.gyroScale + SENSOR.gyroBias + SENSOR.gyroSigma * g());
    sens.obd.push(truth.v[i] * SENSOR.obdScale + SENSOR.obdSigma * g());
    sens.still.push(truth.v[i] < 0.05);
    const tt = truth.t[i];
    sens.gnss.push(tt <= GNSS_UNTIL && i % 4 === 0 ? {
      e: truth.x[i] + SENSOR.gnssSigma * g(), n: truth.y[i] + SENSOR.gnssSigma * g(),
      speed: truth.v[i] + 0.2 * g(), course: truth.psi[i] + 2 * D2R * g(), hAcc: 4.5,
    } : null);
    const k = Math.round((tt - NET_FIRST) / NET_PERIOD);
    if (tt >= NET_FIRST - 1e-9 && Math.abs(tt - (NET_FIRST + k * NET_PERIOD)) < DT / 2) {
      let fix = { e: truth.x[i] + SENSOR.netSigma * g(), n: truth.y[i] + SENSOR.netSigma * g(), hAcc: SENSOR.netHAcc, bad: false };
      if (k === 5 && prevFixes.length >= 4) { // a "stuck" network fix: repeats an old position while the car has driven on
        fix = { e: prevFixes[3].e + 4 * g(), n: prevFixes[3].n + 4 * g(), hAcc: SENSOR.netHAcc, bad: true };
      }
      prevFixes.push(fix); sens.net.push(fix);
    } else sens.net.push(null);
  }
  return { N, dt: DT, truth, sens, route };
}

/* ---------- EKF ---------- */
class Ekf {
  constructor(x, P) { this.x = x.slice(); this.P = P.map(r => r.slice()); }
  propagate(dt, gyro, q) {
    const [e, n, psi, v, b] = this.x, w = -(gyro - b);
    const F = mat.eye(5);
    F[0][2] = v * Math.cos(psi) * dt; F[0][3] = Math.sin(psi) * dt;
    F[1][2] = -v * Math.sin(psi) * dt; F[1][3] = Math.cos(psi) * dt;
    F[2][4] = dt;
    this.x = [e + v * Math.sin(psi) * dt, n + v * Math.cos(psi) * dt, psi + w * dt, v, b];
    const P = mat.mul(mat.mul(F, this.P), mat.T(F));
    P[0][0] += q.pos * q.pos * dt; P[1][1] += q.pos * q.pos * dt;
    P[2][2] += q.hd * q.hd * dt + Math.pow(0.02 * w * dt, 2);
    P[3][3] += q.v * q.v * dt; P[4][4] += q.b * q.b * dt;
    this.P = P;
  }
  innov(z, h, H, R, wrapIdx) {
    const y = z.map((zi, i) => zi - h[i]); (wrapIdx || []).forEach(i => y[i] = wrapPi(y[i]));
    const PHT = mat.mul(this.P, mat.T(H)), S = mat.add(mat.mul(H, PHT), R), Si = mat.inv(S);
    let nis = 0; for (let i = 0; i < y.length; i++) for (let j = 0; j < y.length; j++) nis += y[i] * Si[i][j] * y[j];
    return { y, PHT, S, Si, nis };
  }
  update(z, h, H, R, wrapIdx) {
    const { y, PHT, Si, nis } = this.innov(z, h, H, R, wrapIdx), K = mat.mul(PHT, Si);
    for (let i = 0; i < 5; i++) { let d = 0; for (let j = 0; j < y.length; j++) d += K[i][j] * y[j]; this.x[i] += d; }
    const IKH = mat.eye(5), KH = mat.mul(K, H);
    for (let i = 0; i < 5; i++) for (let j = 0; j < 5; j++) IKH[i][j] -= KH[i][j];
    const A = mat.mul(mat.mul(IKH, this.P), mat.T(IKH)), B = mat.mul(mat.mul(K, R), mat.T(K));
    for (let i = 0; i < 5; i++) for (let j = 0; j < 5; j++) this.P[i][j] = A[i][j] + B[i][j];
    for (let i = 0; i < 5; i++) for (let j = i + 1; j < 5; j++) { const m = (this.P[i][j] + this.P[j][i]) / 2; this.P[i][j] = this.P[j][i] = m; }
    return nis;
  }
  /* "local" update (ZUPT): only one state moves; others keep their values */
  local(idx, z, R) {
    const P = this.P, K = P[idx][idx] / (P[idx][idx] + R);
    this.x[idx] += K * (z - this.x[idx]);
    for (let j = 0; j < 5; j++) if (j !== idx) { P[idx][j] *= (1 - K); P[j][idx] = P[idx][j]; }
    P[idx][idx] *= (1 - K);
  }
  pos(i) { return this.P[i][i]; }
}

const Q = { pos: 0.3, hd: 0.01, v: 0.7, b: 2e-4 };

/*
 * opts: { speed: 'obd' | 'none', net: bool, road: bool, hold: bool, hide: [street ids missing from the map] }
 * Returns frames[i] = {e,n,psi,v,xx,xy,yy,spsi, ev:[…], road?:{p:[…], best, conf}}
 */
function runDrive(drive, opts) {
  const { N, dt, truth, sens } = drive;
  const g0 = sens.gnss[0];
  const mk = () => new Ekf([g0.e, g0.n, g0.course, g0.speed, 0],
    [[9, 0, 0, 0, 0], [0, 9, 0, 0, 0], [0, 0, Math.pow(5 * D2R, 2), 0, 0], [0, 0, 0, 0.09, 0], [0, 0, 0, 0, 0.005 * 0.005]]);
  const main = mk(), twin = opts.road ? mk() : null;
  const filters = twin ? [main, twin] : [main];
  const frames = [], th = [0];            // th: cumulative gyro bearing (for the odometry chord)
  let prevStill = false, lastNetT = -99, odoSinceNet = 0, netHist = [];
  const odoV = [];                         // OBD speed log
  const chord = (i1, i2) => {
    let ce = 0, cn = 0, dist = 0;
    for (let i = i1 + 1; i <= i2; i++) { ce += odoV[i] * dt * Math.sin(th[i] - th[i1]); cn += odoV[i] * dt * Math.cos(th[i] - th[i1]); dist += odoV[i] * dt; }
    return { chord: Math.hypot(ce, cn), dist };
  };
  // road matcher state
  const streets = TOWN.streets.filter(st => !(opts.hide || []).includes(st.id)), K = streets.length;
  let p = null, odoSinceRoad = 0, confDist = 0, lastBest = -1, lastBestT = -99, lastBestPsi = 0, psiHist = [], rd = null;
  if (opts.road) { p = new Array(K + 1).fill(1 / (K + 1)); }
  // hold-last-fix reference
  let holdFix = null;

  for (let i = 0; i < N; i++) {
    const ev = [], t = truth.t[i];
    odoV.push(sens.obd[i]);
    if (i > 0) th.push(th[i - 1] - (sens.gyro[i] - main.x[4]) * dt);
    if (i > 0) filters.forEach(F => F.propagate(dt, sens.gyro[i], Q));
    const still = sens.still[i];
    if (i > 0) filters.forEach(F => {
      if (still) { F.local(3, 0, 0.05 * 0.05); F.local(4, sens.gyro[i], 0.005 * 0.005); }
      else if (prevStill && opts.speed === 'none') { F.x[3] = 8; F.P[3][3] = 100; for (let j = 0; j < 5; j++) if (j !== 3) { F.P[3][j] = 0; F.P[j][3] = 0; } }
    });
    prevStill = still;
    // GNSS (only the first seconds: then it is jammed)
    const gn = sens.gnss[i];
    if (gn) {
      holdFix = { e: gn.e, n: gn.n, t };
      if (i > 0) filters.forEach(F => {
        const s2 = Math.pow(gn.hAcc / 1.51, 2);
        F.update([gn.e, gn.n], [F.x[0], F.x[1]], [[1, 0, 0, 0, 0], [0, 1, 0, 0, 0]], [[s2, 0], [0, s2]]);
        F.update([gn.speed], [F.x[3]], [[0, 0, 0, 1, 0]], [[0.09]]);
        if (gn.speed >= 5) F.update([gn.course], [F.x[2]], [[0, 0, 1, 0, 0]], [[Math.pow(3 * D2R, 2)]], [0]);
      });
      ev.push({ k: 'gnss', e: gn.e, n: gn.n });
    }
    if (opts.speed === 'obd' && t > 0) filters.forEach(F => F.update([sens.obd[i]], [F.x[3]], [[0, 0, 0, 1, 0]], [[SENSOR.obdSigma * SENSOR.obdSigma]]));
    odoSinceNet += Math.max(0, main.x[3]) * dt;
    // network fix
    const nf = sens.net[i];
    if (nf && opts.net) {
      const sigNet = 1.5 * nf.hAcc / 1.51, sigOur = Math.sqrt((main.P[0][0] + main.P[1][1]) / 2);
      let reject = false, reason = '', checks = [];
      if (opts.speed === 'obd') { // coarse-odometry check: compare the distance between fixes with the driven chord
        let ag = 0, dis = 0;
        netHist.filter(h => t - h.t <= 180 && h.trusted).slice(-3).forEach(h => {
          const d = Math.hypot(nf.e - h.e, nf.n - h.n), c = chord(h.i, i);
          const s1 = h.hAcc / 1.51, s2 = nf.hAcc / 1.51;
          const ok = Math.abs(d - c.chord) <= 3 * Math.sqrt(s1 * s1 + s2 * s2) + 0.05 * d + 10;
          checks.push({ from: h, d, chord: c.chord, ok });
          if (ok) ag++; else dis++;
        });
        if (dis > ag) { reject = true; reason = 'odo'; }
      }
      netHist.push({ i, t, e: nf.e, n: nf.n, hAcc: nf.hAcc, trusted: !reject });
      let used = false;
      if (!reject && (sigOur > 0.5 * sigNet || (t - lastNetT >= 15 && odoSinceNet >= 150))) {
        filters.forEach(F => {
          const H = [[1, 0, 0, 0, 0], [0, 1, 0, 0, 0]], s2 = sigNet * sigNet, R0 = [[s2, 0], [0, s2]];
          const r = F.innov([nf.e, nf.n], [F.x[0], F.x[1]], H, R0).nis;
          const k = r <= 9.21 ? 1 : r / 9.21;      // robust: an odd fix is heard, but quietly
          F.update([nf.e, nf.n], [F.x[0], F.x[1]], H, [[s2 * k, 0], [0, s2 * k]]);
          if (F === main) ev.push({ k: 'net', e: nf.e, n: nf.n, hAcc: nf.hAcc, acc: true, soft: r > 9.21, nis: r, checks });
        });
        used = true; lastNetT = t; odoSinceNet = 0;
      }
      if (!used) ev.push({ k: 'net', e: nf.e, n: nf.n, hAcc: nf.hAcc, acc: false, rej: reject, reason, checks });
    }
    // road matcher (runs on the road-free twin) and pseudo-measurements for the main filter
    if (opts.road && opts.speed === 'obd') {
      odoSinceRoad += Math.max(0, twin.x[3]) * dt;
      psiHist.push(main.x[2]);
      if (odoSinceRoad >= 10) {
        odoSinceRoad = 0;
        const te = twin.x[0], tn = twin.x[1], tpsi = twin.x[2];
        const varPos = (twin.P[0][0] + twin.P[1][1]) / 2, sPsi = Math.sqrt(twin.P[2][2]);
        const varRoad = 3.5 * 3.5 / 3 + 16, proj = streets.map(st => projectOnPolyline(st.pts, te, tn));
        const eps = 0.04, pn = new Array(K + 1);
        let sum = 0;
        for (let k = 0; k < K; k++) {
          const dth = Math.min(Math.abs(wrapPi(tpsi - proj[k].brg)), Math.abs(wrapPi(tpsi - proj[k].brg - Math.PI)));
          const ll = -0.5 * proj[k].d * proj[k].d / (varPos + varRoad) - 0.5 * dth * dth / (sPsi * sPsi + Math.pow(10 * D2R, 2));
          pn[k] = ((1 - eps) * p[k] + eps / (K + 1)) * Math.exp(ll); sum += pn[k];
        }
        pn[K] = ((1 - eps) * p[K] + eps / (K + 1)) * Math.exp(-2.0); sum += pn[K];
        for (let k = 0; k <= K; k++) p[k] = pn[k] / sum;
        let best = 0; for (let k = 1; k <= K; k++) if (p[k] > p[best]) best = k;
        const conf = p[best] >= 0.9 && best < K;
        confDist = conf ? confDist + 10 : 0;
        // corner fix: the confident street changed after a turn that finished between the two confident states
        if (conf) {
          if (lastBest >= 0 && best !== lastBest && t - lastBestT <= 25) {
            const back = lastBestPsi, turn = Math.abs(wrapPi(main.x[2] - back));
            const J = intersectStreets(streets[lastBest], streets[best], main.x[0], main.x[1]);
            if (J && turn >= 60 * D2R) {
              const sg = Math.sqrt((main.P[0][0] + main.P[1][1]) / 2);
              if (J.d > 2 * sg && J.d < 50) {
                main.update([J.x, J.y], [main.x[0], main.x[1]], [[1, 0, 0, 0, 0], [0, 1, 0, 0, 0]], [[36, 0], [0, 36]]);
                ev.push({ k: 'corner', e: J.x, n: J.y });
              }
            }
          }
          lastBest = best; lastBestT = t; lastBestPsi = main.x[2];
        }
        rd = { p: p.slice(), best, conf, confDist, ids: streets.map(st => st.id) };
        if (conf && confDist >= 150) {
          const pr = proj[best], q = projectOnPolyline(streets[best].pts, main.x[0], main.x[1]);
          let nx, ny;
          if (q.d > 0.5) { nx = (main.x[0] - q.qx) / q.d; ny = (main.x[1] - q.qy) / q.d; } else { nx = Math.cos(q.brg); ny = -Math.sin(q.brg); }
          const zn = nx * q.qx + ny * q.qy, H = [[nx, ny, 0, 0, 0]];
          const varN = nx * nx * main.P[0][0] + 2 * nx * ny * main.P[0][1] + ny * ny * main.P[1][1];
          if (varN > varRoad) {
            const inn = main.innov([zn], [nx * main.x[0] + ny * main.x[1]], H, [[varRoad]]);
            if (inn.nis < 9) { main.update([zn], [nx * main.x[0] + ny * main.x[1]], H, [[varRoad]]); ev.push({ k: 'road', e: q.qx, n: q.qy }); }
          }
          // heading from a straight road, with a straight gyro
          const wAvg = Math.abs(psiHist[psiHist.length - 1] - psiHist[Math.max(0, psiHist.length - 13)]) / 3;
          if (q.cornerDist > 25 && wAvg < 0.02 && main.P[2][2] > Math.pow(3 * D2R, 2)) {
            const b1 = q.brg, b2 = q.brg + Math.PI, bb = Math.abs(wrapPi(main.x[2] - b1)) < Math.abs(wrapPi(main.x[2] - b2)) ? b1 : b2;
            if (Math.abs(wrapPi(main.x[2] - bb)) < 20 * D2R) main.update([bb], [main.x[2]], [[0, 0, 1, 0, 0]], [[Math.pow(3 * D2R, 2)]], [0]);
          }
        }
      }
    }
    // record
    let P = main.P, xx = P[0][0], xy = P[0][1], yy = P[1][1], e = main.x[0], n = main.x[1];
    if (twin && twin.P[0][0] + twin.P[1][1] > P[0][0] + P[1][1]) { xx = twin.P[0][0]; xy = twin.P[0][1]; yy = twin.P[1][1]; } // honest radius
    if (opts.hold && holdFix) { // reference: "hold the last trusted fix"; r68 grows at 20 m/s
      const r = 4.5 + 20 * (t - holdFix.t), s2 = Math.pow(r / K68, 2); e = holdFix.e; n = holdFix.n; xx = s2; xy = 0; yy = s2;
    }
    const fr = { e, n, psi: main.x[2], v: main.x[3], xx, xy, yy, spsi: Math.sqrt(P[2][2]), sv: Math.sqrt(P[3][3]), ev };
    if (opts.road && rd) fr.road = rd;
    frames.push(fr);
  }
  const err = frames.map((f, i) => Math.hypot(f.e - truth.x[i], f.n - truth.y[i]));
  return { frames, err };
}

const percentile = (arr, q) => { const s = arr.slice().sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(q * s.length))]; };

if (typeof module !== 'undefined') module.exports = { makeDrive, runDrive, TOWN, ROUTE_WPS, percentile, ellipseOf, SENSOR, DT, GNSS_UNTIL };
