'use strict';
/* Scenes 1–3: the problem, the cloud, dead reckoning. Shared data + helpers first. */
const DRIVE = makeDrive(7), NT = DRIVE.N, TR = DRIVE.truth, SCENES = [];
const RUNS = {
  hold: runDrive(DRIVE, { speed: 'none', hold: true }),
  none: runDrive(DRIVE, { speed: 'none' }),
  obd: runDrive(DRIVE, { speed: 'obd' }),
  net: runDrive(DRIVE, { speed: 'obd', net: true }),
  road: runDrive(DRIVE, { speed: 'obd', net: true, road: true }),
  hide: runDrive(DRIVE, { speed: 'obd', net: true, road: true, hide: ['north'] }),
};
const COL = { none: '#ff7a7a', obd: C.est, net: C.net, road: C.road2, hold: '#9aa3b8', compass: '#4fd1c5' };
const idxF = ts => clamp(ts / DT, 0, NT - 1);
function fAt(run, ts) {
  const f = idxF(ts), i = Math.floor(f), j = Math.min(NT - 1, i + 1), u = f - i, a = run.frames[i], b = run.frames[j], o = { i };
  for (const k of ['e', 'n', 'xx', 'xy', 'yy', 'psi', 'v', 'spsi']) o[k] = lerp(a[k], b[k], u);
  o.road = a.road; return o;
}
function tAt(ts) {
  const f = idxF(ts), i = Math.floor(f), j = Math.min(NT - 1, i + 1), u = f - i;
  return { x: lerp(TR.x[i], TR.x[j], u), y: lerp(TR.y[i], TR.y[j], u), psi: TR.psi[i], v: lerp(TR.v[i], TR.v[j], u), i };
}
const r68 = f => K68 * Math.sqrt((f.xx + f.yy) / 2);
function invMap(map, ts) {
  for (let k = 1; k < map.length; k++) if (map[k][1] > map[k - 1][1] && ts >= map[k - 1][1] && ts <= map[k][1]) return lerp(map[k - 1][0], map[k][0], (ts - map[k - 1][1]) / (map[k][1] - map[k - 1][1]));
  return map[map.length - 1][0];
}
function mk(def) {
  if (def.beatsSim) def.beats = def.beatsSim.map(([ts, tx]) => [Math.max(TITLE_S + 0.2, invMap(def.map, ts)), tx]);
  def.ready = true; SCENES.push(def); return def;
}
function legend(x, y, items, o = {}) {
  items.forEach(([col, lab, kind], k) => {
    const yy = y + k * (o.lh ?? 24);
    if (kind === 'line') { ctx.save(); ctx.strokeStyle = col; ctx.lineWidth = 3; ctx.beginPath(); ctx.moveTo(x, yy); ctx.lineTo(x + 20, yy); ctx.stroke(); ctx.restore(); }
    else if (kind === 'ring') dotPx(x + 10, yy, 7, null, { stroke: col, lw: 2 });
    else dotPx(x + 10, yy, 6, col);
    text(lab, x + 30, yy, { size: o.size ?? 14, col: '#cfd5e6' });
  });
}
/* a small line chart: series = [{col, f(ts) -> value}], the x axis is sim time [ts0, ts1], drawn up to tsNow */
function chart(x, y, w, h, series, ts0, ts1, tsNow, yMax, o = {}) {
  panel(x - 8, y - 26, w + 16, h + 52, { a: .6 });
  text(o.title ?? '', x, y - 11, { size: 13, col: C.dim });
  ctx.save(); ctx.strokeStyle = '#33406a'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x, y + h); ctx.lineTo(x + w, y + h); ctx.stroke();
  for (let g = 1; g <= 3; g++) { const gy = y + h - h * g / 3; ctx.globalAlpha = .35; ctx.beginPath(); ctx.moveTo(x, gy); ctx.lineTo(x + w, gy); ctx.stroke(); ctx.globalAlpha = 1; text(String(Math.round(yMax * g / 3)), x - 4, gy, { size: 11, col: C.dim, align: 'right' }); }
  ctx.restore();
  series.forEach(s => {
    const pts = []; for (let ts = ts0; ts <= tsNow; ts += (ts1 - ts0) / 160) pts.push([x + (ts - ts0) / (ts1 - ts0) * w, y + h - clamp(s.f(ts) / yMax, 0, 1) * h]);
    if (pts.length < 2) return; ctx.save(); ctx.strokeStyle = s.col; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.beginPath(); pts.forEach((p, k) => k ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); ctx.stroke(); ctx.restore();
  });
  text(o.xlabel ?? 'час →', x + w, y + h + 14, { size: 11, col: C.dim, align: 'right' });
}
const jit = (k, a) => a * Math.sin(k * 91.7 + 3.1);

/* ============ 1. The problem ============ */
mk({
  title: 'GPS можна обдурити', short: 'Проблема', question: 'Що буде, якщо сигналу вже не можна вірити?', T: 46,
  beats: [
    [3.4, 'Ви їдете містом. Телефон показує, де ви, за сигналом GPS — це <b>жовті крапки</b>.'],
    [8.6, 'Поки що все добре: крапки лягають точно на дорогу.'],
    [12.2, 'Але ось увімкнули підмінювач сигналу. <b>Жовта крапка</b> починає повільно повзти вбік — без стрибків, щоб ніхто не здивувався.'],
    [20, 'Телефон нічого не помічає: для нього це звичайний GPS. Навігатор вже вважає, що ви не на дорозі…'],
    [27, '…а ось і річка. Найнебезпечніша підміна — не груба, а <u>повільна</u>.'],
    [31.8, 'Висновок: GPS — це не істина, а лише <b>одна з підказок</b>. То що ще в нас є, крім нього?'],
    [38.5, 'Гіроскоп, швидкість авто, мережа, компас, карта доріг. Кожен — слабкий свідок, але разом вони чогось варті.'],
  ],
  draw(ts, t) {
    const c = Math.max(0, t - TITLE_S), d = Math.min(c * 1.6, 44), i = Math.floor(d / DT);
    mapView(); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, H); ctx.clip(); drawTown({ names: ['main', 'north'] });
    trace(TR.x, TR.y, i, 4, '#ffffff', 1.5, { a: .3 });
    const off = k => Math.max(0, k - 10) * 4;
    for (let k = Math.max(0, Math.floor(d) - 30); k <= Math.floor(d); k++) {
      const j = Math.min(NT - 1, Math.round(k / DT)), gy = TR.y[j] - off(k) + jit(k, 2), gx = TR.x[j] + jit(k + 5, 2);
      dotW(gx, gy, 3.5, C.gnss, { a: 0.25 + 0.75 * (1 - (Math.floor(d) - k) / 31) });
    }
    const tr = tAt(d), g = { x: tr.x + jit(d, 2), y: tr.y - off(d) + jit(d + 1, 2) }, dist = Math.hypot(g.x - tr.x, g.y - tr.y);
    if (dist > 8) { strokeW([[tr.x, tr.y], [g.x, g.y]], C.gnss, 1.2, { dash: [4, 4], a: .7 }); }
    carW(tr.x, tr.y, tr.psi, '#fff', 8); dotW(g.x, g.y, 8, C.gnss, { stroke: '#0b101b', lw: 2 });
    text('GPS', X(g.x) + 14, Y(g.y), { size: 14, weight: 700, col: C.gnss, bg: true });
    if (g.y < TOWN.riverY) text('«ви в річці»', X(g.x), Y(g.y) + 26, { size: 15, weight: 700, col: C.bad, align: 'center', bg: true });
    ctx.restore();
    // panel
    panel(690, 16, 260, 412);
    legend(706, 44, [['#fff', 'справжня машина', 'dot'], [C.gnss, 'що показує GPS', 'dot']]);
    text('розбіжність', 706, 96, { size: 13, col: C.dim }); text(`${Math.round(dist)} м`, 706, 128, { size: 38, weight: 700, col: dist > 25 ? C.bad : C.ok });
    const pills = [[C.gyro, 'Гіроскоп', 'повороти'], [C.speed, 'Швидкість авто', 'OBD-адаптер'], [C.net, 'Мережа', 'Wi-Fi та вежі'], [COL.compass, 'Компас', 'напрямок'], [C.road2, 'Карта доріг', 'де можна їхати']];
    if (c > 33) text('ЩО ЩЕ МИ ЗНАЄМО', 706, 190, { size: 12, weight: 700, col: C.est, a: smooth(ramp(c, 33, 34)) });
    pills.forEach(([col, a, b], k) => {
      const u = smooth(ramp(c, 34 + k * 1.3, 35 + k * 1.3)); if (u <= 0) return;
      const y = 206 + k * 44, x = 706 + (1 - u) * 80;
      ctx.save(); ctx.globalAlpha = u; ctx.fillStyle = '#1b2438'; ctx.beginPath(); ctx.roundRect(x, y, 228, 38, 8); ctx.fill(); ctx.fillStyle = col; ctx.fillRect(x, y, 5, 38); ctx.restore();
      text(a, x + 18, y + 13, { size: 14.5, weight: 650, a: u }); text(b, x + 18, y + 29, { size: 12, col: C.dim, a: u });
    });
  },
});

/* ============ 2. A cloud, not a point ============ */
const SAMP = (() => { const r = mulberry32(11), a = []; for (let i = 0; i < 300; i++) a.push([gaussian(r), gaussian(r)]); return a; })();
const PART = (() => { const r = mulberry32(5), a = []; for (let i = 0; i < 170; i++) a.push([gaussian(r), gaussian(r)]); return a; })();
mk({
  title: 'Не точка, а хмара', short: 'Хмара', question: 'Яка відповідь чесніша: «я ось тут» чи «я десь тут»?', T: 71,
  summary: ['Замість однієї точки зберігаємо хмару: центр і розмір (радіус для 68% та для 95%).', 'Чесно показує, наскільки можна вірити оцінці. Нові дані можуть хмару стискати.', 'Якщо хмара занижена — алгоритм самовпевнений і помиляється частіше, ніж обіцяє. Це гірше, ніж велика хмара.'],
  beats: [
    [3.4, 'Звичайний навігатор малює <b>крапку</b>: «ви тут». Але справжня відповідь — не така певна.'],
    [10, 'Чесніше сказати: «я, мабуть, десь тут». Уявіть багато можливих місць, де ми можемо бути. Це і є <i>хмара</i>: густіше — ймовірніше.'],
    [19, 'Хмару описують двома колами: <i>внутрішнє</i> — «з імовірністю 68% ми всередині», <i>зовнішнє</i> — 95%.'],
    [26.5, 'Перевіримо, чи хмара чесна. Багато разів «кидаємо» справжню позицію машини — це білі крапки.'],
    [33.5, 'Зелені — потрапили у внутрішнє коло, жовті — у зовнішнє, червоні — повз. У чесної хмари зелених близько <b>68 із 100</b>.'],
    [42, 'А якщо хмару стиснути занадто? Це <u>самовпевненість</u>: ми обіцяємо точність, якої немає, і помиляємось.'],
    [49.5, 'Надто велика хмара — теж погано: безпечно, але нічого не підказує.'],
    [54.5, 'Тому головна вимога до алгоритму — чесність: частка влучань має бути близько 68% і 95%. Ми це вимірюємо.'],
  ],
  draw(ts, t) {
    const c = t - TITLE_S;
    const f = t < 42 ? 1 : t < 49.5 ? 0.35 : t < 54.5 ? 2.6 : 1, tf = t < 42 ? 0 : t < 49.5 ? 1 : t < 54.5 ? 2 : 0;
    this.f = lerp(this.f ?? 1, f, 0.12);
    const sg = 18 * this.f; setView(0, 0, 4.0, 340, 262);
    // a plain road and building blocks
    ctx.save(); ctx.fillStyle = '#12182a'; [[-170, 22, 120, 90], [-30, 22, 90, 90], [90, 22, 90, 90], [-170, -112, 120, 90], [-30, -112, 90, 90], [90, -112, 90, 90]].forEach(([x, y, w, h]) => { ctx.beginPath(); ctx.roundRect(X(x), Y(y + h) , w * V.s, h * V.s, 6); ctx.fill(); }); ctx.restore();
    strokeW([[-190, 0], [190, 0]], C.road, 9 * V.s); strokeW([[-190, 0], [190, 0]], C.roadLine, 1, { a: .6 });
    // phase 1: a single point
    const pa = 1 - smooth(ramp(t, 10, 12));
    if (pa > 0) { dotW(0, 0, 9, '#fff', { a: pa, stroke: '#0b101b', lw: 2 }); text('«я тут»', X(0) + 18, Y(0) - 22, { size: 18, weight: 650, a: pa, bg: true }); }
    // phase 2: particles
    const n = Math.floor(clamp((t - 10) * 12, 0, PART.length));
    if (t >= 10) for (let k = 0; k < n; k++) dotW(PART[k][0] * sg, PART[k][1] * sg, 2.6, C.est, { a: 0.65 });
    // phase 3: contours
    const ra = smooth(ramp(t, 19, 21.5));
    if (ra > 0) {
      circW(0, 0, K68 * sg, C.est, { a: ra, fill: 'rgba(88,196,221,0.10)', lw: 2.2 }); circW(0, 0, K95 * sg, C.est, { a: ra * .8, dash: [6, 5], lw: 1.6 });
      text('68%', X(K68 * sg * 0.7071) + 8, Y(K68 * sg * 0.7071) - 4, { size: 15, weight: 700, col: C.est, a: ra }); text('95%', X(K95 * sg * 0.7071) + 8, Y(K95 * sg * 0.7071) - 4, { size: 15, weight: 700, col: C.est, a: ra });
    }
    // phase 4: the honesty test
    if (t >= 26.5) {
      const m = Math.floor(clamp((t - 26.5) * 16, 0, SAMP.length)); let in68 = 0, in95 = 0;
      for (let k = 0; k < m; k++) {
        const dx = SAMP[k][0] * 18, dy = SAMP[k][1] * 18, r = Math.hypot(dx, dy), a = r <= K68 * sg ? 1 : r <= K95 * sg ? 2 : 3;
        if (a === 1) in68++; if (a <= 2) in95++;
        const last = m - k < 4;
        dotW(dx, dy, last ? 5.5 : 3.2, last ? '#fff' : a === 1 ? C.ok : a === 2 ? C.gnss : C.bad, { a: last ? 1 : 0.8 });
      }
      panel(690, 16, 260, 210); text('У ЧЕСНОЇ ХМАРИ', 706, 38, { size: 12, weight: 700, col: C.est });
      const p68 = m ? in68 / m : 0, p95 = m ? in95 / m : 0;
      text(`внутрішнє коло: ${Math.round(p68 * 100)}%`, 706, 72, { size: 18, weight: 650, col: C.ok }); text('має бути ≈ 68%', 706, 94, { size: 13, col: C.dim });
      text(`зовнішнє коло: ${Math.round(p95 * 100)}%`, 706, 128, { size: 18, weight: 650, col: C.gnss }); text('має бути ≈ 95%', 706, 150, { size: 13, col: C.dim });
      const label = tf === 0 ? 'хмара чесна' : tf === 1 ? 'САМОВПЕВНЕНА: хмара замала' : 'надто велика: безпечно, але марно';
      text(label, 706, 196, { size: 15, weight: 700, col: tf === 0 ? C.ok : tf === 1 ? C.bad : C.gnss });
    }
    formula([[['радіус 68% ≈ 1,5 · σ', C.est]], [['радіус 95% ≈ 2,4 · σ', C.est]], [['σ — «розкид» хмари, у метрах', C.dim]]], 706, 262, t, 20, { size: 17, gap: 0.9, lh: 26 });
  },
});

/* ============ 3. Counting steps: gyro + speed ============ */
mk({
  title: 'Рахуємо кроки: гіроскоп і швидкість', short: 'Кроки', question: 'Чи можна їхати наосліп, знаючи лише повороти й швидкість?', T: 85,
  map: [[0, 0], [3.2, 0], [10, 11], [14, 12.5], [21.5, 18], [29, 25], [37, 26.5], [48, 33], [55, 60], [61, 100], [68, 250], [76, 392]],
  summary: ['Гіроскоп (повороти) і швидкість; кожні 0,25 с зсуваємо позицію: крок = швидкість × час у напрямку руху.', 'Працює без жодного зовнішнього сигналу — його неможливо «заглушити» чи «підмінити». Світлофор дає нульову швидкість для «калібрування» гіроскопа.', 'Похибки лише копичаться: хмара росте без кінця. Без датчика швидкості — набагато швидше (колесо не каже, 30 чи 60 км/год).'],
  beats: [
    [3.4, 'Спершу GPS є, і ми знаємо три речі: де ми, куди їдемо і з якою швидкістю (<b>жовті крапки</b>).'],
    [10, 'І раптом GPS зникає — його заглушили. Що тепер?'],
    [14, 'Гіроскоп відчуває кожен поворот. Додаємо кут до напрямку — і знаємо, куди дивиться машина.'],
    [21.5, 'Далі просто: <b>крок = швидкість × час</b>, у напрямку руху. Повторюємо знову й знову — малюється шлях.'],
    [29, 'Кожен крок трохи неточний, помилки копичаться — <i>хмара росте</i>. Червона: швидкість невідома.'],
    [37, 'Світлофор! Стоїмо — отже швидкість рівно нуль, і гіроскоп мусить показувати нуль. Усе інше — його власний «зсув»: запам’ятовуємо й віднімаємо.'],
    [48, '<i>Блакитна</i> хмара — швидкість з адаптера OBD в авто. Вона росте помітно повільніше.'],
    [55, 'Але й з OBD помилки не зникають: колесо трохи інше, гіроскоп «пливе»…'],
    [61, 'Висновок: рахувати шлях наосліп можна, та без нових даних похибка росте безмежно.'],
    [68, 'Потрібні свідки, які «прив’язують» нас до реальності. Дивіться, які саме.'],
  ],
  draw(ts, t) {
    mapView(); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, H); ctx.clip(); drawTown({ names: false });
    const jam = DRIVE.sens.gnss.findIndex((g, i) => i > 0 && !g && TR.t[i] > GNSS_UNTIL) * DT;
    const i = Math.floor(idxF(ts)), N_ = RUNS.none, O = RUNS.obd;
    trace(TR.x, TR.y, i, 4, '#fff', 1.5, { a: .35 });
    trace(N_.frames.map(f => f.e), N_.frames.map(f => f.n), i, 4, COL.none, 2, { a: .85 });
    trace(O.frames.map(f => f.e), O.frames.map(f => f.n), i, 4, COL.obd, 2.4);
    for (let k = 0; k < NT && TR.t[k] <= Math.min(ts, GNSS_UNTIL); k++) if (DRIVE.sens.gnss[k]) dotW(DRIVE.sens.gnss[k].e, DRIVE.sens.gnss[k].n, 3.5, C.gnss);
    const fn = fAt(N_, ts), fo = fAt(O, ts), tr = tAt(ts);
    if (ts > 12) { cloud(fn.e, fn.n, fn.xx, fn.xy, fn.yy, COL.none, { a: .9 }); }
    cloud(fo.e, fo.n, fo.xx, fo.xy, fo.yy, COL.obd, { a: 1 });
    carW(tr.x, tr.y, tr.psi, '#fff', 8);
    if (TR.v[i] < 0.05 && ts > 20) text('СТОП: швидкість = 0\nгіроскоп мусить показувати 0', X(tr.x) + 22, Y(tr.y) - 30, { size: 13, weight: 650, col: C.ok, bg: true });
    ctx.restore();
    // side panel: one step
    panel(690, 16, 260, 412);
    text('ОДИН КРОК (кожні 0,25 с)', 706, 38, { size: 12, weight: 700, col: C.est });
    const cx = 820, cy = 90, R = 36; dotPx(cx, cy, R, null, { stroke: '#33406a', lw: 1.5 }); text('пн', cx, cy - R - 8, { size: 11, col: C.dim, align: 'center' });
    arrowPx(cx, cy, cx + Math.sin(fo.psi) * R * 0.92, cy - Math.cos(fo.psi) * R * 0.92, C.gyro, 3.5, 11);
    text(`напрямок ψ = ${((fo.psi * R2D % 360) + 360) % 360 | 0}° (гіроскоп)`, 706, 144, { size: 13.5, col: C.gyro, weight: 650 });
    text(`швидкість v = ${(Math.max(0, fo.v)).toFixed(1)} м/с (OBD)`, 706, 168, { size: 13.5, col: C.speed, weight: 650 });
    ctx.save(); ctx.fillStyle = C.speed; ctx.fillRect(706, 178, clamp(fo.v / 20, 0, 1) * 228, 5); ctx.restore();
    formula([[['нова позиція = стара + ', C.text], ['v', C.speed], [' · Δt · ', C.text], ['напрямок', C.gyro]]], 706, 202, t, 0, { size: 14, weight: 500 });
    chart(716, 256, 218, 90, [{ col: COL.none, f: s => errAt(RUNS.none, s) }, { col: COL.obd, f: s => errAt(RUNS.obd, s) }], 12, 392, Math.max(12, ts), 160, { title: 'похибка, м', xlabel: 'час без GPS →' });
    legend(706, 384, [[COL.none, 'швидкість невідома', 'line'], [COL.obd, 'швидкість з OBD', 'line']], { size: 13, lh: 18 });
  },
});
const errAt = (run, ts) => run.err[Math.round(idxF(ts))];
