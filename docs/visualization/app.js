'use strict';
/* Framework: canvas, camera, drawing helpers, chapter player. Chapters live in chapters.js. */
const cv = document.getElementById('cv'), ctx = cv.getContext('2d');
const W = 960, H = 540;
const C = {
  bg: '#0b101b', road: '#27314d', roadLine: '#3a4670', river: '#13304d', text: '#e6e9f2', dim: '#8e98b4',
  truth: '#ffffff', est: '#58c4dd', gnss: '#ffd75e', gyro: '#6fa8ff', speed: '#83c167', net: '#b58cf0',
  road2: '#ff9f43', bad: '#fc6255', ok: '#83c167', panel: 'rgba(14,19,32,0.88)',
};

/* ---------- camera ---------- */
const V = { cx: 0, cy: 0, s: 1, ox: W / 2, oy: H / 2 };
const setView = (cx, cy, s, ox = W / 2, oy = H / 2) => Object.assign(V, { cx, cy, s, ox, oy });
const X = x => V.ox + (x - V.cx) * V.s, Y = y => V.oy - (y - V.cy) * V.s;
const mapView = () => setView(0, 0, 0.64, 340, 228);   // the whole town on the left, a panel on the right
const withClip = fn => { ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, H); ctx.clip(); fn(); ctx.restore(); };
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const lerp = (a, b, u) => a + (b - a) * u;
const smooth = u => { u = clamp(u, 0, 1); return u * u * (3 - 2 * u); };
const ramp = (t, t0, t1) => clamp((t - t0) / (t1 - t0), 0, 1);

/* ---------- drawing helpers ---------- */
function pathW(pts) { ctx.beginPath(); pts.forEach((p, i) => i ? ctx.lineTo(X(p[0]), Y(p[1])) : ctx.moveTo(X(p[0]), Y(p[1]))); }
function strokeW(pts, col, w = 2, o = {}) {
  ctx.save(); ctx.globalAlpha = o.a ?? 1; ctx.strokeStyle = col; ctx.lineWidth = w; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  if (o.dash) ctx.setLineDash(o.dash); pathW(pts); ctx.stroke(); ctx.restore();
}
function dotPx(px, py, r, fill, o = {}) {
  ctx.save(); ctx.globalAlpha = o.a ?? 1; ctx.beginPath(); ctx.arc(px, py, r, 0, 2 * Math.PI);
  if (fill) { ctx.fillStyle = fill; ctx.fill(); }
  if (o.stroke) { ctx.strokeStyle = o.stroke; ctx.lineWidth = o.lw ?? 1.5; if (o.dash) ctx.setLineDash(o.dash); ctx.stroke(); }
  ctx.restore();
}
const dotW = (x, y, r, fill, o) => dotPx(X(x), Y(y), r, fill, o);
function circW(x, y, rM, col, o = {}) { // a circle with a radius in metres
  ctx.save(); ctx.globalAlpha = o.a ?? 1; ctx.beginPath(); ctx.arc(X(x), Y(y), Math.max(o.min ?? 2, rM * V.s), 0, 2 * Math.PI);
  if (o.fill) { ctx.fillStyle = o.fill; ctx.fill(); }
  ctx.strokeStyle = col; ctx.lineWidth = o.lw ?? 1.6; if (o.dash) ctx.setLineDash(o.dash); ctx.stroke(); ctx.restore();
}
function text(str, px, py, o = {}) {
  ctx.save(); ctx.globalAlpha = o.a ?? 1; ctx.font = `${o.weight ?? 500} ${o.size ?? 14}px -apple-system, "SF Pro Text", "Segoe UI", system-ui, sans-serif`;
  ctx.textAlign = o.align ?? 'left'; ctx.textBaseline = o.base ?? 'middle';
  const lines = String(str).split('\n'), lh = (o.size ?? 14) * 1.3;
  if (o.bg) {
    const w = Math.max(...lines.map(l => ctx.measureText(l).width)) + 12, h = lines.length * lh + 8;
    const x0 = o.align === 'center' ? px - w / 2 : o.align === 'right' ? px - w : px - 6;
    ctx.fillStyle = o.bg === true ? C.panel : o.bg; ctx.beginPath(); ctx.roundRect(x0, py - lh / 2 - 4, w, h, 6); ctx.fill();
  }
  ctx.fillStyle = o.col ?? C.text;
  lines.forEach((l, i) => ctx.fillText(l, px, py + i * lh));
  ctx.restore();
}
function panel(x, y, w, h, o = {}) {
  ctx.save(); ctx.globalAlpha = o.a ?? 1; ctx.fillStyle = o.fill ?? C.panel; ctx.strokeStyle = o.stroke ?? '#2a3555'; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.roundRect(x, y, w, h, 10); ctx.fill(); ctx.stroke(); ctx.restore();
}
function arrowPx(x1, y1, x2, y2, col, w = 2.5, head = 9) {
  const a = Math.atan2(y2 - y1, x2 - x1);
  ctx.save(); ctx.strokeStyle = col; ctx.fillStyle = col; ctx.lineWidth = w; ctx.lineCap = 'round';
  ctx.beginPath(); ctx.moveTo(x1, y1); ctx.lineTo(x2, y2); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(x2, y2); ctx.lineTo(x2 - head * Math.cos(a - 0.45), y2 - head * Math.sin(a - 0.45)); ctx.lineTo(x2 - head * Math.cos(a + 0.45), y2 - head * Math.sin(a + 0.45)); ctx.closePath(); ctx.fill(); ctx.restore();
}
/* an uncertainty cloud: the 68% and 95% contours of a 2-D Gaussian */
function cloud(e, n, xx, xy, yy, col, o = {}) {
  const el = ellipseOf(xx, xy, yy);
  ctx.save();
  [[K95, 0.10, 1], [K68, 0.20, 1.6]].forEach(([k, al, lw]) => {
    ctx.beginPath(); ctx.ellipse(X(e), Y(n), Math.max(o.min ?? 4, el.a * k * V.s), Math.max(o.min ?? 4, el.b * k * V.s), -el.ang, 0, 2 * Math.PI);
    ctx.globalAlpha = (o.a ?? 1) * al; ctx.fillStyle = col; ctx.fill();
    ctx.globalAlpha = (o.a ?? 1) * (k === K68 ? 0.95 : 0.6); ctx.strokeStyle = col; ctx.lineWidth = lw; if (k === K95) ctx.setLineDash([5, 4]); ctx.stroke(); ctx.setLineDash([]);
  });
  ctx.restore();
  return el;
}
/* the car: an arrow-like marker; psi is a bearing (clockwise from north) */
function carW(x, y, psi, col, size = 9, o = {}) {
  const px = X(x), py = Y(y), dx = Math.sin(psi), dy = -Math.cos(psi);
  ctx.save(); ctx.globalAlpha = o.a ?? 1; ctx.translate(px, py); ctx.rotate(Math.atan2(dx, -dy));
  ctx.beginPath(); ctx.moveTo(0, -size * 1.4); ctx.lineTo(size * 0.85, size); ctx.lineTo(0, size * 0.55); ctx.lineTo(-size * 0.85, size); ctx.closePath();
  ctx.fillStyle = col; ctx.fill(); ctx.strokeStyle = '#0b101b'; ctx.lineWidth = 1.5; ctx.stroke(); ctx.restore();
}
function drawTown(o = {}) {
  const b = TOWN.bounds, a = o.a ?? 1;
  ctx.save(); ctx.globalAlpha = a;
  // river
  ctx.beginPath(); ctx.moveTo(X(b.x0), Y(TOWN.riverY));
  for (let x = b.x0; x <= b.x1; x += 30) ctx.lineTo(X(x), Y(TOWN.riverY + 7 * Math.sin(x / 60)));
  ctx.lineTo(X(b.x1), Y(b.y0 - 40)); ctx.lineTo(X(b.x0), Y(b.y0 - 40)); ctx.closePath(); ctx.fillStyle = C.river; ctx.fill();
  if (o.names !== false) text('річка', X(-380), Y(-252), { col: '#4f7fb0', size: 13, weight: 600 });
  TOWN.streets.forEach(st => {
    const hidden = (o.hide || []).includes(st.id);
    strokeW(st.pts, C.road, Math.max(4, 9 * V.s), { dash: hidden ? [3, 6] : null });
    if (!hidden) strokeW(st.pts, C.roadLine, 1, { a: .7 });
    if (o.names && o.names.includes(st.id)) {
      const mid = st.pts[0][1] === st.pts[1][1] ? [st.pts[0][0] + 60, st.pts[0][1] + 11] : [st.pts[0][0] + 12, st.pts[0][1] + 60];
      text(st.name, X(mid[0]), Y(mid[1]), { size: 11.5, col: '#6b7899' });
    }
  });
  ctx.restore();
}
function trace(xs, ys, upto, step, col, w, o = {}) {
  const pts = []; for (let i = 0; i <= upto; i += step) pts.push([xs[i], ys[i]]); pts.push([xs[upto], ys[upto]]);
  strokeW(pts, col, w, o);
}


/* ---------- the "video" player: one linear timeline over all scenes ---------- */
const TITLE_S = 3.2, SUMMARY_S = 9;
const P = { scenes: [], starts: [], total: 0, g: 0, playing: false, started: false, rate: 1, last: 0, lastBeat: -1, lastScene: -1, voice: true };
const $ = id => document.getElementById(id);
const UI = { sub: $('sub'), play: $('play'), scrub: $('scrub'), time: $('time'), rate: $('rate'), voice: $('voice'), chap: $('chap'), ticks: $('ticks'), start: $('startcard') };

function sceneAt(g) { let i = P.scenes.length - 1; while (i > 0 && g < P.starts[i]) i--; return i; }
function simTime(sc, t) {
  const m = sc.map; if (!m) return t;
  if (t <= m[0][0]) return m[0][1];
  for (let k = 1; k < m.length; k++) if (t <= m[k][0]) return lerp(m[k - 1][1], m[k][1], (t - m[k - 1][0]) / (m[k][0] - m[k - 1][0]));
  return m[m.length - 1][1];
}
const fmtT = s => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

/* formulas on the stage: lines appear one after another from time t0 */
function wrap(str, x, y, maxW, o = {}) {
  ctx.save(); ctx.font = `${o.weight ?? 500} ${o.size ?? 16}px -apple-system, "SF Pro Text", "Segoe UI", system-ui, sans-serif`;
  const words = String(str).split(' '), lines = []; let line = '';
  words.forEach(w => { const t = line ? line + ' ' + w : w; if (ctx.measureText(t).width > maxW && line) { lines.push(line); line = w; } else line = t; }); lines.push(line); ctx.restore();
  lines.forEach((l, k) => text(l, x, y + k * (o.lh ?? (o.size ?? 16) * 1.35), o)); return lines.length;
}
function formula(lines, x, y, t, t0, o = {}) {
  lines.forEach((ln, k) => {
    const a = smooth(ramp(t, t0 + k * (o.gap ?? 1.2), t0 + k * (o.gap ?? 1.2) + 0.6)); if (a <= 0) return;
    const items = Array.isArray(ln) ? ln : [[ln, C.text]];
    ctx.save(); ctx.globalAlpha = a; ctx.font = `${o.weight ?? 600} ${o.size ?? 22}px "Times New Roman", Georgia, serif`; ctx.textBaseline = 'middle';
    let w = 0; items.forEach(([s]) => w += ctx.measureText(s).width);
    let cx = o.align === 'center' ? x - w / 2 : x;
    items.forEach(([s, col]) => { ctx.fillStyle = col; ctx.fillText(s, cx, y + k * (o.lh ?? 34) + (1 - a) * 6); cx += ctx.measureText(s).width; });
    ctx.restore();
  });
}
function drawTitleCard(sc, idx, t) {
  const a = t < TITLE_S - 0.8 ? 1 : 1 - ramp(t, TITLE_S - 0.8, TITLE_S); if (a <= 0) return;
  ctx.save(); ctx.globalAlpha = 0.93 * a; ctx.fillStyle = '#0b101b'; ctx.fillRect(0, 0, W, H); ctx.restore();
  const k = smooth(ramp(t, 0, 0.7));
  text(`КРОК ${idx + 1} З ${P.scenes.length}`, W / 2, H / 2 - 58 + (1 - k) * 12, { align: 'center', size: 16, weight: 700, col: C.est, a: a * k });
  text(sc.title, W / 2, H / 2 - 12 + (1 - k) * 12, { align: 'center', size: 40, weight: 700, a: a * k });
  if (sc.question) text(sc.question, W / 2, H / 2 + 42 + (1 - k) * 12, { align: 'center', size: 19, col: '#b8c0d8', a: a * k });
}
function drawSummary(sc, t) {
  if (!sc.summary) return; const t0 = sc.T - SUMMARY_S, k = smooth(ramp(t, t0, t0 + 0.8)); if (k <= 0) return;
  ctx.save(); ctx.globalAlpha = 0.94 * k; ctx.fillStyle = '#0b101b'; ctx.fillRect(0, 0, W, H); ctx.restore();
  text('ПІДСУМОК КРОКУ', 70, 70, { size: 14, weight: 700, col: C.est, a: k }); text(sc.title, 70, 102, { size: 30, weight: 700, a: k });
  [['Що додаємо', sc.summary[0], C.est], ['Чим допомагає', sc.summary[1], C.ok], ['Чого не вміє / ризик', sc.summary[2], C.road2]].forEach(([h, body, col], r) => {
    const a = smooth(ramp(t, t0 + 0.8 + r * 1.3, t0 + 1.6 + r * 1.3)); if (a <= 0) return;
    const y = 160 + r * 112;
    ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = col; ctx.fillRect(70, y - 6, 4, 84); ctx.restore();
    text(h.toUpperCase(), 90, y + 6, { size: 13, weight: 700, col, a }); wrap(body, 90, y + 34, 810, { size: 20, a, col: '#e6e9f2', lh: 26 });
  });
}
function setSub(txt) { UI.sub.innerHTML = txt || ''; UI.sub.classList.toggle('on', !!txt); }
const AUD = new Audio();
function speak(id) {
  AUD.pause(); if (!P.voice || !id || !P.playing) return;
  AUD.src = `${window.AUDIO_DIR || 'audio'}/${id}.m4a`; AUD.playbackRate = P.rate; AUD.play().catch(() => {});
}
function frame(now) {
  const dt = Math.min(0.1, (now - P.last) / 1000); P.last = now;
  if (P.playing) { P.g += dt * P.rate; if (P.g >= P.total) { P.g = P.total; P.playing = false; UI.play.textContent = '↻'; } }
  const i = sceneAt(P.g), sc = P.scenes[i], t = Math.min(sc.T, (P.g - P.starts[i]) / sc.k);
  if (sc.ready) {
    ctx.setTransform(cv.width / W, 0, 0, cv.width / W, 0, 0); ctx.fillStyle = C.bg; ctx.fillRect(0, 0, W, H);
    try { sc.draw(simTime(sc, t), t); } catch (e) { console.error(e); text('Помилка: ' + e.message, 20, 30, { col: C.bad }); }
    drawSummary(sc, t); drawTitleCard(sc, i, t);
    let b = -1; (sc.beats || []).forEach((bt, k) => { if (t >= bt[0] - 1e-9 && t >= TITLE_S - 0.5) b = k; });
    if (t >= sc.T - SUMMARY_S + 0.5 && sc.summary) b = -2;
    if (i !== P.lastScene || b !== P.lastBeat) {
      P.lastScene = i; P.lastBeat = b;
      const bt = b >= 0 ? sc.beats[b][1] : ''; setSub(bt); if (P.started) speak(b >= 0 ? `s${i + 1}b${b}` : null);
      [...UI.chap.children].forEach((x, k) => x.classList.toggle('on', k === i));
    }
  }
  if (!UI.scrub.matches(':active')) UI.scrub.value = Math.round(P.g / P.total * 10000);
  UI.time.textContent = `${fmtT(P.g)} / ${fmtT(P.total)}`;
  requestAnimationFrame(frame);
}
function seek(g) { P.g = clamp(g, 0, P.total - 0.01); P.lastBeat = -9; AUD.pause(); }
function setPlaying(on) {
  P.playing = on; UI.play.textContent = on ? '⏸' : '▶';
  if (!on) AUD.pause(); else P.lastBeat = -9;
}
function fit() { const dpr = Math.min(2, window.devicePixelRatio || 1), w = cv.clientWidth || W; cv.width = Math.round(w * dpr); cv.height = Math.round(w * H / W * dpr); }
function startApp(scenes) {
  P.scenes = scenes; let acc = 0; scenes.forEach((s, i) => { s.k = (typeof STRETCH !== 'undefined' && STRETCH[i + 1]) || 1; P.starts.push(acc); acc += s.T * s.k; }); P.total = acc;
  scenes.forEach((s, k) => {
    const b = document.createElement('button'); b.innerHTML = `<span>${k + 1}</span>${s.short || s.title}`; b.onclick = () => { P.started = true; UI.start.style.display = 'none'; seek(P.starts[k]); setPlaying(true); }; UI.chap.appendChild(b);
    const tk = document.createElement('i'); tk.style.left = (P.starts[k] / P.total * 100) + '%'; tk.title = s.title; UI.ticks.appendChild(tk);
  });
  UI.play.onclick = () => { if (P.g >= P.total - 0.05) seek(0); P.started = true; UI.start.style.display = 'none'; setPlaying(!P.playing); };
  UI.start.onclick = () => { P.started = true; UI.start.style.display = 'none'; setPlaying(true); };
  UI.scrub.oninput = () => { seek(UI.scrub.value / 10000 * P.total); };
  UI.rate.onchange = () => { P.rate = +UI.rate.value; AUD.playbackRate = P.rate; };
  UI.voice.onclick = () => { P.voice = !P.voice; UI.voice.classList.toggle('on', P.voice); UI.voice.textContent = P.voice ? '🔊 Озвучка' : '🔈 Озвучка'; P.lastBeat = -9; if (!P.voice) AUD.pause(); };
  UI.voice.classList.toggle('on', P.voice); UI.voice.textContent = '🔊 Озвучка';
  $('full').onclick = () => { const el = $('stage'); if (document.fullscreenElement) document.exitFullscreen(); else el.requestFullscreen && el.requestFullscreen(); };
  addEventListener('keydown', e => {
    if (e.key === ' ') { e.preventDefault(); UI.play.click(); }
    else if (e.key === 'ArrowRight') seek(P.g + 10); else if (e.key === 'ArrowLeft') seek(P.g - 10);
    else if (e.key === 'ArrowDown') { const i = sceneAt(P.g); seek(P.starts[Math.min(i + 1, scenes.length - 1)]); }
    else if (e.key === 'ArrowUp') { const i = sceneAt(P.g); seek(P.g - P.starts[i] > 4 ? P.starts[i] : P.starts[Math.max(0, i - 1)]); }
  });
  addEventListener('resize', fit); fit();
  const q = new URLSearchParams(location.search);          // test hooks: ?g=<seconds>  ?s=<scene number>&t=<seconds in scene>
  if (q.has('g')) { P.g = +q.get('g'); P.started = true; UI.start.style.display = 'none'; }
  if (q.has('s')) { const k = (+q.get('s') || 1) - 1; P.g = P.starts[k] + (+q.get('t') || 0); P.started = true; UI.start.style.display = 'none'; }
  if (document.getElementById('loading')) $('loading').style.display = 'none';
  requestAnimationFrame(t => { P.last = t; frame(t); });
}
