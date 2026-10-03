'use strict';
/* Numeric helpers for the formal scenes (FFT, PSD, distributions). Random numbers come from engine.js. */
function fft(re, im) { // in place, radix-2
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) { let bit = n >> 1; for (; j & bit; bit >>= 1) j ^= bit; j ^= bit; if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; } }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) { let cr = 1, ci = 0; for (let j = 0; j < len / 2; j++) {
      const ur = re[i + j], ui = im[i + j], vr = re[i + j + len / 2] * cr - im[i + j + len / 2] * ci, vi = re[i + j + len / 2] * ci + im[i + j + len / 2] * cr;
      re[i + j] = ur + vr; im[i + j] = ui + vi; re[i + j + len / 2] = ur - vr; im[i + j + len / 2] = ui - vi; const t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t; } }
  }
}
/* one-sided power spectral density (units²/Hz), Welch: Hann window, 50% overlap */
function psd(x, dt, seg) {
  const hann = Array.from({ length: seg }, (_, i) => 0.5 - 0.5 * Math.cos(2 * Math.PI * i / seg)), U = hann.reduce((a, w) => a + w * w, 0), acc = new Array(seg / 2).fill(0); let cnt = 0;
  for (let s = 0; s + seg <= x.length; s += seg / 2) {
    const re = x.slice(s, s + seg).map((v, i) => v * hann[i]), im = new Array(seg).fill(0); fft(re, im);
    for (let k = 0; k < seg / 2; k++) acc[k] += (re[k] * re[k] + im[k] * im[k]) * dt / U * (k === 0 ? 1 : 2); cnt++;
  }
  return { f: acc.map((_, k) => k / (seg * dt)), p: acc.map(v => v / cnt) };
}
function lgamma(z) { const c = [76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5]; let y = z, x = z, t = x + 5.5; t -= (x + 0.5) * Math.log(t); let s = 1.000000000190015; for (let j = 0; j < 6; j++) s += c[j] / ++y; return -t + Math.log(2.5066282746310005 * s / x); }
const chi2pdf = (v, k) => v <= 0 ? 0 : Math.exp((k / 2 - 1) * Math.log(v) - v / 2 - (k / 2) * Math.log(2) - lgamma(k / 2));
const chi2cdf2 = v => 1 - Math.exp(-v / 2);
const range = (a, b, n) => Array.from({ length: n + 1 }, (_, i) => a + (b - a) * i / n);
const logspace = (a, b, n) => Array.from({ length: n + 1 }, (_, i) => a * Math.pow(b / a, i / n));
const fxt = (v, n = 1) => (Math.abs(v) < 0.05 * Math.pow(10, -(n - 1)) ? 0 : v).toFixed(n).replace('.', ',');   // plain text
const fx = (v, n = 1) => fxt(v, n).replace(',', '{,}');                                                        // inside TeX
const matTex = (M, n = 1) => String.raw`\begin{bmatrix}` + M.map(r => r.map(v => fx(v, n)).join(' & ')).join(String.raw` \\ `) + String.raw`\end{bmatrix}`;
/* points of a covariance ellipse (k sigmas) around (cx, cy) */
function ellipsePts(cx, cy, C2, k, n = 80) {
  const e = ellipseOf(C2[0][0], C2[0][1], C2[1][1]), xs = [], ys = [];
  for (let i = 0; i <= n; i++) { const a = 2 * Math.PI * i / n, u = e.a * k * Math.cos(a), v = e.b * k * Math.sin(a); xs.push(cx + u * Math.cos(e.ang) - v * Math.sin(e.ang)); ys.push(cy + u * Math.sin(e.ang) + v * Math.cos(e.ang)); }
  return { x: xs, y: ys };
}
/* 2x2 helpers */
const inv2 = ([[a, b], [c, d]]) => { const det = a * d - b * c; return [[d / det, -b / det], [-c / det, a / det]]; };
const add2 = (A, B) => [[A[0][0] + B[0][0], A[0][1] + B[0][1]], [A[1][0] + B[1][0], A[1][1] + B[1][1]]];
const mul2 = (A, B) => [[A[0][0] * B[0][0] + A[0][1] * B[1][0], A[0][0] * B[0][1] + A[0][1] * B[1][1]], [A[1][0] * B[0][0] + A[1][1] * B[1][0], A[1][0] * B[0][1] + A[1][1] * B[1][1]]];
const covAxes = (brg, sa, sc) => { const u = [Math.sin(brg), Math.cos(brg)], v = [Math.cos(brg), -Math.sin(brg)]; return [[sa * sa * u[0] * u[0] + sc * sc * v[0] * v[0], sa * sa * u[0] * u[1] + sc * sc * v[0] * v[1]], [sa * sa * u[0] * u[1] + sc * sc * v[0] * v[1], sa * sa * u[1] * u[1] + sc * sc * v[1] * v[1]]]; };
const ellTrace = (cx, cy, C2, k, color, fill, w = 2.5) => Object.assign(ellipsePts(cx, cy, C2, k), { mode: 'lines', line: { color, width: w }, fill: fill ? 'toself' : 'none', fillcolor: fill || undefined, hoverinfo: 'skip' });

const lerp = (a, b, u) => a + (b - a) * u, clampN = (v, a, b) => Math.max(a, Math.min(b, v)), ramp = (t, a, b) => clampN((t - a) / (b - a), 0, 1), smooth = u => { u = clampN(u, 0, 1); return u * u * (3 - 2 * u); };
