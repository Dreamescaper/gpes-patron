'use strict';
/*
 * The formal video player. A scene is a list of items placed on a 960×540 stage and shown at given times:
 *   TX(tex, x, y, {size, t0, t1, color, align:'center'|'right', box})   — a KaTeX formula
 *   PL(x, y, w, h, {data, layout, dyn(t) -> {data, layout}, t0, t1})   — a Plotly chart (dyn: redrawn ~10 times a second)
 *   HT(html, x, y, w, {t0, t1})                                         — a text block
 * plus beats [[t, subtitle, spoken?]] (the voice-over) and T (duration, seconds).
 */
const FSC = [];
const FC = { text: '#e6e9f2', dim: '#8e98b4', blue: '#58c4dd', yellow: '#ffd75e', green: '#83c167', red: '#fc6255', purple: '#b58cf0', orange: '#ff9f43', gyro: '#6fa8ff', teal: '#4fd1c5', grid: '#2a3555' };
const TITLE_S = 3.2;
const TX = (tex, x, y, o = {}) => Object.assign({ type: 'tex', tex, x, y, size: 22, t0: 0 }, o);
const PL = (x, y, w, h, o = {}) => Object.assign({ type: 'plot', x, y, w, h, t0: 0 }, o);
const HT = (html, x, y, w, o = {}) => Object.assign({ type: 'html', html, x, y, w, t0: 0 }, o);
const tc = (c, s) => String.raw`\textcolor{${FC[c] || c}}{${s}}`;   // coloured piece of a formula
function fscene(def) { FSC.push(def); return def; }

function plotLayout(w, h, l = {}) {
  const ax = a => Object.assign({ gridcolor: FC.grid, zerolinecolor: '#3d4a75', linecolor: '#55639a', tickfont: { size: 11, color: '#aab3cc' }, title: { font: { size: 12, color: '#aab3cc' } } }, a || {});
  const o = Object.assign({ width: w, height: h, paper_bgcolor: 'rgba(0,0,0,0)', plot_bgcolor: 'rgba(0,0,0,0)', font: { color: '#cfd5e6', size: 12 }, margin: { l: 54, r: 14, t: 30, b: 42 }, showlegend: false }, l);
  o.xaxis = ax(l.xaxis); o.yaxis = ax(l.yaxis); if (l.yaxis2) o.yaxis2 = ax(l.yaxis2);
  if (l.title && typeof l.title === 'string') o.title = { text: l.title, font: { size: 13, color: '#cfd5e6' }, x: 0.5, y: 0.97 };
  return o;
}
const $ = id => document.getElementById(id);
const FP = { scenes: [], starts: [], total: 0, g: 0, playing: false, started: false, rate: 1, voice: true, cur: -1, lastBeat: -9, last: 0, lastQ: -1, lastPlot: 0 };
const AUD = new Audio();
const clampF = (v, a, b) => Math.max(a, Math.min(b, v));
const fmtT = s => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;

/* time warp: segment j of a scene (between beat j and beat j+1) is played STRETCH[scene][j] times slower, so every phrase fits */
function buildWarp(sc, ks) {
  const bt = (sc.beats || []).map(b => b[0]), tb = [0].concat(bt, [sc.T]), kk = [1].concat(ks && ks.length ? ks : bt.map(() => 1)); let g = 0; sc.seg = [];
  for (let m = 0; m + 1 < tb.length; m++) { sc.seg.push({ t0: tb[m], g0: g, k: kk[m] || 1 }); g += (tb[m + 1] - tb[m]) * (kk[m] || 1); }
  sc.L = g;
}
const sceneT = (sc, gl) => { let s = sc.seg[0]; for (const x of sc.seg) if (gl >= x.g0) s = x; return Math.min(sc.T, s.t0 + (gl - s.g0) / s.k); };
const sceneG = (sc, t) => { let s = sc.seg[0]; for (const x of sc.seg) if (t >= x.t0) s = x; return s.g0 + (t - s.t0) * s.k; };
function fitView() { const w = $('wrap').clientWidth; $('view').style.transform = `scale(${w / 960})`; }
function sceneAt(g) { let i = FP.scenes.length - 1; while (i > 0 && g < FP.starts[i]) i--; return i; }

function enter(i) {
  const sc = FP.scenes[i]; FP.cur = i; FP.lastBeat = -9;
  const L = $('layer'); L.innerHTML = '';
  $('titlecard').innerHTML = `<div><div class="k">КРОК ${i + 1} З ${FP.scenes.length}</div><div class="h">${sc.title}</div><div class="q">${sc.question || ''}</div></div>`;
  sc.els = (sc.items || sc.build()).map(it => {
    const el = document.createElement('div'); el.className = 'it ' + it.type + (it.align ? ' ' + it.align : '') + (it.box ? ' box' : '');
    el.style.left = it.x + 'px'; el.style.top = it.y + 'px';
    if (it.type === 'tex') { el.style.fontSize = it.size + 'px'; if (it.color) el.style.color = FC[it.color] || it.color; katex.render(it.tex, el, { throwOnError: false, trust: true, strict: false, displayMode: false }); }
    else if (it.type === 'html') { el.style.width = it.w + 'px'; if (it.size) el.style.fontSize = it.size + 'px'; if (it.color) el.style.color = FC[it.color] || it.color; el.innerHTML = it.html; }
    else if (it.type === 'plot') {
      el.style.width = it.w + 'px'; el.style.height = it.h + 'px';
      const d = it.dyn ? it.dyn(it.t0) : { data: it.data, layout: it.layout };
      Plotly.newPlot(el, d.data, plotLayout(it.w, it.h, d.layout || it.layout), { staticPlot: true, displayModeBar: false, responsive: false });
    }
    L.appendChild(el); return { it, el };
  });
}
function setSub(txt) { $('sub').innerHTML = txt || ''; $('sub').classList.toggle('on', !!txt); }
function speak(id) { AUD.pause(); if (!FP.voice || !id || !FP.playing) return; AUD.src = `${window.AUDIO_DIR}/${id}.m4a`; AUD.playbackRate = FP.rate; AUD.play().catch(() => {}); }

function frame(now) {
  const dt = Math.min(0.1, (now - FP.last) / 1000); FP.last = now;
  if (FP.playing) { FP.g += dt * FP.rate; if (FP.g >= FP.total) { FP.g = FP.total; FP.playing = false; $('play').textContent = '↻'; } }
  const i = sceneAt(FP.g), sc = FP.scenes[i], t = sceneT(sc, FP.g - FP.starts[i]);
  if (FP.cur !== i) { enter(i); [...$('chap').children].forEach((x, k) => x.classList.toggle('on', k === i)); }
  $('titlecard').classList.toggle('on', t < TITLE_S - 0.3);
  // charts with dyn(t) are redrawn at most ~6 times a second of real time (Plotly is slow); the rest of the stage runs every frame
  const q = Math.floor(t * 8), allow = now - FP.lastPlot > 160 || !FP.playing; let did = false;
  sc.els.forEach(({ it, el }) => {
    const vis = t >= it.t0 && t < (it.t1 ?? 1e9); el.classList.toggle('on', vis);
    if (it.type === 'plot' && it.dyn && vis && el._q !== q && allow && !did) { el._q = q; did = true; const d = it.dyn(t); Plotly.react(el, d.data, plotLayout(it.w, it.h, d.layout || it.layout), { staticPlot: true, displayModeBar: false }); }
  });
  if (did) FP.lastPlot = now;
  let b = -1; (sc.beats || []).forEach((bt, k) => { if (t >= bt[0] - 1e-9 && t >= TITLE_S - 0.5) b = k; });
  if (b !== FP.lastBeat) { FP.lastBeat = b; setSub(b >= 0 ? sc.beats[b][1] : ''); if (FP.started) speak(b >= 0 ? `s${i + 1}b${b}` : null); }
  if (!$('scrub').matches(':active')) $('scrub').value = Math.round(FP.g / FP.total * 10000);
  $('time').textContent = `${fmtT(FP.g)} / ${fmtT(FP.total)}`;
  requestAnimationFrame(frame);
}
function seek(g) { FP.g = clampF(g, 0, FP.total - 0.01); FP.lastBeat = -9; AUD.pause(); }
function setPlaying(on) { FP.playing = on; $('play').textContent = on ? '⏸' : '▶'; if (!on) AUD.pause(); else FP.lastBeat = -9; }
function startFormal(scenes) {
  FP.scenes = scenes; let acc = 0; scenes.forEach((s, i) => { buildWarp(s, typeof STRETCH !== 'undefined' ? STRETCH[i + 1] : null); FP.starts.push(acc); acc += s.L; }); FP.total = acc;
  scenes.forEach((s, k) => {
    const b = document.createElement('button'); b.innerHTML = `<span>${k + 1}</span>${s.short || s.title}`; b.onclick = () => { FP.started = true; $('startcard').style.display = 'none'; seek(FP.starts[k]); setPlaying(true); }; $('chap').appendChild(b);
    const tk = document.createElement('i'); tk.style.left = (FP.starts[k] / FP.total * 100) + '%'; tk.title = s.title; $('ticks').appendChild(tk);
  });
  $('play').onclick = () => { if (FP.g >= FP.total - 0.05) seek(0); FP.started = true; $('startcard').style.display = 'none'; setPlaying(!FP.playing); };
  $('startcard').onclick = () => { FP.started = true; $('startcard').style.display = 'none'; setPlaying(true); };
  $('scrub').oninput = () => seek($('scrub').value / 10000 * FP.total);
  $('rate').onchange = () => { FP.rate = +$('rate').value; AUD.playbackRate = FP.rate; };
  $('voice').onclick = () => { FP.voice = !FP.voice; $('voice').classList.toggle('on', FP.voice); FP.lastBeat = -9; if (!FP.voice) AUD.pause(); };
  $('full').onclick = () => { if (document.fullscreenElement) document.exitFullscreen(); else $('stage').requestFullscreen && $('stage').requestFullscreen(); };
  addEventListener('keydown', e => {
    if (e.key === ' ') { e.preventDefault(); $('play').click(); } else if (e.key === 'ArrowRight') seek(FP.g + 10); else if (e.key === 'ArrowLeft') seek(FP.g - 10);
    else if (e.key === 'ArrowDown') seek(FP.starts[Math.min(sceneAt(FP.g) + 1, scenes.length - 1)]); else if (e.key === 'ArrowUp') { const i = sceneAt(FP.g); seek(FP.g - FP.starts[i] > 4 ? FP.starts[i] : FP.starts[Math.max(0, i - 1)]); }
  });
  addEventListener('resize', fitView); fitView();
  const q = new URLSearchParams(location.search);       // test hooks: ?s=<scene>&t=<seconds in scene>
  if (q.has('s')) { document.body.classList.add('still'); const k = (+q.get('s') || 1) - 1; FP.g = FP.starts[k] + sceneG(scenes[k], +q.get('t') || 0); FP.started = true; $('startcard').style.display = 'none'; }
  requestAnimationFrame(t => { FP.last = t; frame(t); });
}
