'use strict';
/* Scenes 6–7: the network position, and the heading without GPS (compass ellipse + heading bank). */

/* ============ 6. Network positions ============ */
const NETEV = []; RUNS.net.frames.forEach((f, i) => f.ev.forEach(e => { if (e.k === 'net') NETEV.push(Object.assign({ ts: i * DT }, e)); }));
const STUCK = NETEV.find(e => e.rej);
mk({
  title: 'Грубі координати з мережі', short: 'Мережа', question: 'Чи є щось, що не глушиться разом із GPS?', T: 68,
  map: [[0, 0], [3.2, 0], [12.5, 22], [20.5, 45], [28, 70], [32, 82.8], [43, 98], [51, 300], [59, 392]],
  summary: ['Мережеві координати: Wi-Fi та вежі оператора дають позицію з похибкою десятки метрів, раз на ~15 с.', 'Не дають шляху «відпливти»: похибка тримається в межах, навіть якщо GPS немає годинами. Глушити їх разом із GPS складно.', 'Груба: точнішою за ~40 м не зробить. Може «застрягти» чи збрехати — тому кожну точку звіряємо з одометром (проїхана відстань).'],
  beats: [
    [3.4, 'Нове джерело — мережеві координати: телефон здогадується про місце за Wi-Fi та вежами оператора. Це не GPS, тож заглушка GPS на нього не діє.'],
    [12.5, '<b>Фіолетове коло</b> — «ви десь тут»: радіус близько 45 м. Грубо, зате чесно. Приходить раз на 15 секунд.'],
    [20.5, 'Кожну таку точку змішуємо з нашою хмарою — як у кроці «Злиття». Чим менша наша хмара, тим менше важить мережа.'],
    [31.5, 'А ось підступний випадок: мережа повторила стару точку — між ними майже нуль, а одометр каже: проїхали сотні метрів. Не збігається — <u>відкидаємо</u>.'],
    [43, 'Подивіться на графік: без мережі похибка повільно повзе вгору, а з мережею тримається в межах.'],
    [51, 'Мережа груба — десятки метрів, зате нікуди не «пливе». Вона не робить нас точними — вона не дає нам загубитися.'],
  ],
  draw(ts, t) {
    mapView(); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, 440); ctx.clip(); drawTown({ names: false });
    const i = Math.floor(idxF(ts)), A = RUNS.obd, B = RUNS.net;
    trace(TR.x, TR.y, i, 4, '#fff', 1.4, { a: .3 });
    trace(A.frames.map(f => f.e), A.frames.map(f => f.n), i, 4, COL.hold, 2, { a: .9, dash: [5, 4] });
    trace(B.frames.map(f => f.e), B.frames.map(f => f.n), i, 4, C.est, 2.4);
    const fa = fAt(A, ts), fb = fAt(B, ts), tr = tAt(ts);
    cloud(fa.e, fa.n, fa.xx, fa.xy, fa.yy, COL.hold, { a: .75 }); cloud(fb.e, fb.n, fb.xx, fb.xy, fb.yy, C.est);
    NETEV.forEach(ev => { const age = ts - ev.ts; if (age < 0 || age > (ev.rej ? 30 : 22)) return;
      const a = ev.rej ? clamp(1 - Math.max(0, age - 24) / 6, 0, 1) : Math.max(0.15, 1 - age / 22), col = ev.rej ? C.bad : C.net;
      circW(ev.e, ev.n, ev.hAcc, col, { a, lw: 2, fill: ev.rej ? 'rgba(252,98,85,.10)' : 'rgba(181,140,240,.10)' }); dotW(ev.e, ev.n, 3, col, { a });
      if (ev.rej) text('✗', X(ev.e), Y(ev.n), { size: 26, weight: 800, col: C.bad, align: 'center', a }); });
    carW(tr.x, tr.y, tr.psi, '#fff', 8);
    ctx.restore();
    // the odometry check, shown when the stuck fix arrives
    if (STUCK && ts >= STUCK.ts && ts <= STUCK.ts + 12) {
      const ck = STUCK.checks[STUCK.checks.length - 1] || STUCK.checks[0]; const a = smooth(ramp(ts, STUCK.ts, STUCK.ts + 1));
      if (ck) { panel(16, 292, 330, 138, { a: .96 }); text('ПЕРЕВІРКА ОДОМЕТРОМ', 30, 310, { size: 12, weight: 700, col: C.est, a });
        const sc = 250 / Math.max(ck.chord, ck.d, 1);
        text('мережа: між двома точками', 30, 336, { size: 13, col: C.dim, a }); ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = C.net; ctx.fillRect(30, 346, Math.max(3, ck.d * sc), 12); ctx.restore(); text(`${Math.round(ck.d)} м`, 38 + ck.d * sc, 352, { size: 14, weight: 700, col: C.net, a });
        text('ми проїхали (одометр)', 30, 376, { size: 13, col: C.dim, a }); ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = C.speed; ctx.fillRect(30, 386, ck.chord * sc, 12); ctx.restore(); text(`${Math.round(ck.chord)} м`, 38 + ck.chord * sc, 392, { size: 14, weight: 700, col: C.speed, a });
        text('розбіжність → точку відкидаємо ✗', 30, 417, { size: 14, weight: 700, col: C.bad, a }); } }
    // right panel
    panel(690, 16, 260, 412);
    legend(706, 40, [[C.net, 'мережева точка', 'ring'], [C.est, 'оцінка з мережею', 'line'], [COL.hold, 'оцінка без мережі', 'line']], { size: 13.5, lh: 22 });
    chart(716, 150, 218, 150, [{ col: COL.hold, f: s => errAt(RUNS.obd, s) }, { col: C.est, f: s => errAt(RUNS.net, s) }], 12, 392, Math.max(12, ts), 120, { title: 'похибка, м', xlabel: 'час без GPS →' });
    const acc = NETEV.filter(e => e.ts <= ts && e.acc).length, rej = NETEV.filter(e => e.ts <= ts && e.rej).length;
    text(`прийнято точок: ${acc}`, 706, 350, { size: 14, col: C.ok, weight: 650 }); text(`відкинуто: ${rej}`, 706, 374, { size: 14, col: C.bad, weight: 650 });
    text(`зараз: ${errAt(RUNS.net, ts).toFixed(0)} м з мережею\nпроти ${errAt(RUNS.obd, ts).toFixed(0)} м без неї`, 706, 404, { size: 13, col: C.dim });
  },
});

/* ============ 7. Heading: compass ellipse and heading bank ============ */
const MAG = { O: [4, -2], R: 25, M: [[1, 0.25], [0, 0.62]] };
const magRaw = th => { const s = Math.sin(th), c = Math.cos(th); return [MAG.O[0] + MAG.R * (MAG.M[0][0] * s + MAG.M[0][1] * c), MAG.O[1] + MAG.R * (MAG.M[1][0] * s + MAG.M[1][1] * c)]; };
const magAng = p => Math.atan2(p[0], p[1]);
const MAG_MAXERR = (() => { let m = 0; for (let a = 0; a < 360; a += 2) m = Math.max(m, Math.abs(wrapPi(magAng(magRaw(a * D2R)) - a * D2R))); return m * R2D; })();
// heading bank data
const BANK = (() => {
  const re = [0], rn = [0]; let psi = 0;
  for (let i = 1; i < NT; i++) { psi -= (DRIVE.sens.gyro[i] - 0.0033) * DT; re.push(re[i - 1] + DRIVE.sens.obd[i] * DT * Math.sin(psi)); rn.push(rn[i - 1] + DRIVE.sens.obd[i] * DT * Math.cos(psi)); }
  const start = [TR.x[0] + 14, TR.y[0] - 22], hyp = Array.from({ length: 12 }, (_, k) => (7 + 30 * k) * D2R);
  const pos = (k, i) => { const c = Math.cos(hyp[k]), s = Math.sin(hyp[k]); return [start[0] + re[i] * c + rn[i] * s, start[1] - re[i] * s + rn[i] * c]; };
  const fixes = NETEV.map(e => e).filter(e => !(DRIVE.sens.net[Math.round(e.ts / DT)] || {}).bad && e.ts > 10);
  let lw = new Array(12).fill(0); const steps = [];
  fixes.forEach((fx, j) => {
    const i = Math.round(fx.ts / DT), dist = TR.s[i]; lw = lw.map((l, k) => { const p = pos(k, i), d2 = (p[0] - fx.e) ** 2 + (p[1] - fx.n) ** 2, s2 = 44.7 ** 2 + (0.10 * dist) ** 2 + 30 ** 2; return l - 0.5 * d2 / s2; });
    const mx = Math.max(...lw); let w = lw.map(l => Math.max(1e-4, Math.exp(l - mx))); const sm = w.reduce((a, b) => a + b, 0); w = w.map(x => x / sm);
    let se = 0, sc = 0; w.forEach((x, k) => { se += x * Math.sin(hyp[k]); sc += x * Math.cos(hyp[k]); }); const mix = Math.atan2(se, sc);
    const sd = Math.sqrt(w.reduce((a, x, k) => a + x * wrapPi(hyp[k] - mix) ** 2, 0) + (5 * D2R) ** 2);
    steps.push({ ts: fx.ts, w, mix, sd: sd * R2D, fx, dist });
  });
  return { re, rn, start, hyp, pos, steps };
})();
function bankState(ts) { let w = new Array(12).fill(1 / 12), prev = null, cur = null, k0 = -1;
  BANK.steps.forEach((s, k) => { if (s.ts <= ts) { cur = s; k0 = k; } });
  if (!cur) return { w, mix: null, sd: null, n: 0 };
  const pr = k0 > 0 ? BANK.steps[k0 - 1].w : w, u = smooth(ramp(ts, cur.ts, cur.ts + 2));
  return { w: cur.w.map((x, k) => lerp(pr[k], x, u)), mix: cur.mix, sd: cur.sd, n: k0 + 1, dist: cur.dist }; }

mk({
  title: 'Куди ми дивимось: напрямок без GPS', short: 'Напрямок', question: 'Гіроскоп знає лише «на скільки повернули». А куди ми дивились на початку?', T: 84,
  map: [[0, 0], [39, 0], [49, 22], [56, 38], [61, 53], [67, 68], [75, 100]],
  summary: ['Абсолютний напрямок: компас із «виправленим» спотворенням, а якщо він ненадійний — банк із 12 гіпотез напрямку, які відсіюються мережевими точками.', 'Фільтр дізнається, куди дивиться машина, без GPS. Це потрібно, щоб кроки «швидкість × час» малювали шлях у правильний бік.', 'Компас у реальних авто спотворений сильно: ми його поки перевіряли лише в симуляції. Банк потребує ~4 мережевих точок і кількох сотень метрів руху.'],
  beats: [
    [3.4, 'Мало знати, <i>скільки</i> ми проїхали. Треба ще знати <b>куди</b> дивиться машина. Гіроскоп каже лише «на скільки повернули». Є компас…'],
    [14, 'Але в машині компас бреше: сталь кузова, двигун, проводка. Поки ми кружляємо, показання малюють не коло, а <u>зсунутий еліпс</u>.'],
    [22.5, 'Спотворення постійні — отже, його можна виправити: зсунути еліпс у центр і стиснути до кола. Тоді стрілка вказує правильно.'],
    [31.5, '<u>Червона стрілка</u> — «сирий» компас (помиляється до 40°), <em>зелена</em> — виправлений (кілька градусів).'],
    [39, 'Другий спосіб, коли компасові не віримо, — <b>банк напрямків</b>. Не знаємо напрямок — пробуємо одразу 12 варіантів через кожні 30°.'],
    [49, 'Кожен варіант малює свій шлях за тими самими поворотами й відстанями. Потім приходять грубі мережеві точки…'],
    [58, '…і варіанти, що йдуть не туди, втрачають вагу — блякнуть. Лишається той, чий шлях збігається з точками.'],
    [67, 'Коли напрямок усталився (розкид менший за 15° після 4 точок і 300 м) — передаємо його фільтру. Далі працюємо як зазвичай.'],
  ],
  draw(ts, t) {
    if (t < 39) return this.drawCompass(t);
    this.drawBank(ts, t);
  },
  drawCompass(t) {
    const th = t < 5 ? 0 : t < 19 ? 2 * Math.PI * ramp(t, 5, 19) : t < 31 ? 0 : 2 * Math.PI * ramp(t, 31, 39) * 0.9;
    const u1 = smooth(ramp(t, 24, 27)), u2 = smooth(ramp(t, 27, 30)), cx = 230, cy = 212, k = 4.9;
    const P = a => { const r = magRaw(a), s = Math.sin(a), c = Math.cos(a), sh = [r[0] - MAG.O[0] * u1, r[1] - MAG.O[1] * u1], ci = [MAG.R * s, MAG.R * c]; return [lerp(sh[0], ci[0], u2), lerp(sh[1], ci[1], u2)]; };
    const sc = p => [cx + p[0] * k, cy - p[1] * k];
    // axes
    ctx.save(); ctx.strokeStyle = '#2f3a5e'; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(cx - 190, cy); ctx.lineTo(cx + 190, cy); ctx.moveTo(cx, cy - 170); ctx.lineTo(cx, cy + 170); ctx.stroke(); ctx.restore();
    text('поле, µT (вісь «схід»)', cx + 190, cy + 14, { size: 11, col: C.dim, align: 'right' }); text('«північ»', cx + 6, cy - 166, { size: 11, col: C.dim });
    dotPx(cx, cy, 3, '#fff');
    const lastA = t < 19 ? th : 2 * Math.PI, nPts = t < 5 ? 0 : Math.floor(lastA / (2 * D2R));
    if (t >= 5) for (let a = 0; a <= nPts; a++) { const p = sc(P(a * 2 * D2R)); dotPx(p[0], p[1], 3, COL.compass, { a: .9 }); }
    if (t >= 22.5 && t < 30) { const a = smooth(ramp(t, 22.5, 24)); ctx.save(); ctx.globalAlpha = a * (1 - u2); ctx.strokeStyle = '#fff'; ctx.setLineDash([5, 5]); ctx.beginPath();
      for (let a2 = 0; a2 <= 360; a2 += 3) { const p = sc(P(a2 * D2R)); a2 ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1]); } ctx.stroke(); ctx.restore(); }
    if (t >= 24 && t < 30) { text(u2 < 0.05 ? 'крок 1: зсунути центр' : 'крок 2: зробити колом', cx, 40, { size: 16, weight: 700, col: '#fff', align: 'center', bg: true }); }
    if (t >= 14 && t < 22.5) text('вийшов еліпс, зсунутий від центру', cx, 40, { size: 16, weight: 700, col: COL.compass, align: 'center', bg: true });
    // needles
    const raw = magRaw(th), cor = [MAG.R * Math.sin(th), MAG.R * Math.cos(th)], pr = sc(t >= 24 ? P(th) : raw);
    if (t >= 5) { dotPx(pr[0], pr[1], 7, '#fff', { stroke: '#0b101b', lw: 2 }); }
    const e = wrapPi(magAng(raw) - th) * R2D;
    if (t >= 31) { const ar = magAng(raw), q = sc([MAG.R * Math.sin(ar), MAG.R * Math.cos(ar)]); arrowPx(cx, cy, q[0], q[1], C.bad, 2.5, 9); const q2 = sc(cor); arrowPx(cx, cy, q2[0], q2[1], C.ok, 2.5, 9); }
    // the car: top view
    const bx = 560, by = 168; panel(450, 24, 232, 288); text('ВИД ЗВЕРХУ', 466, 44, { size: 12, weight: 700, col: C.est });
    ctx.save(); ctx.translate(bx, by); dotPx(0, 0, 64, null, { stroke: '#33406a', lw: 1.2 }); text('пн', 0, -76, { size: 11, col: C.dim, align: 'center' });
    ctx.rotate(th); ctx.fillStyle = '#e6e9f2'; ctx.beginPath(); ctx.roundRect(-14, -30, 28, 60, 8); ctx.fill(); ctx.fillStyle = '#0b101b'; ctx.fillRect(-10, -22, 20, 10); ctx.restore();
    arrowPx(bx, by, bx + Math.sin(th) * 62, by - Math.cos(th) * 62, C.ok, 3, 10);
    if (t >= 5) { const al = t >= 24 ? th + (1 - u2) * 0 : magAng(raw); const aa = t >= 30 ? th : (t >= 24 ? magAng(raw) * (1 - u2) + th * u2 : magAng(raw)); arrowPx(bx, by, bx + Math.sin(aa) * 52, by - Math.cos(aa) * 52, t >= 30 ? C.ok : C.bad, 2, 8); }
    text(`справжній напрямок: ${((th * R2D % 360) + 360) % 360 | 0}°`, 466, 252, { size: 13, col: C.ok, weight: 650 });
    if (t >= 5 && t < 24) text(`компас бреше на ${Math.abs(e).toFixed(0)}°`, 466, 276, { size: 15, col: C.bad, weight: 700 });
    else if (t >= 30) text(`виправлений: ±3°  (було до ${MAG_MAXERR.toFixed(0)}°)`, 466, 276, { size: 13.5, col: C.ok, weight: 700 });
    // legend
    if (t >= 31) legend(40, 410, [[C.bad, 'сирий компас', 'line'], [C.ok, 'виправлений', 'line']], { size: 13, lh: 20 });
  },
  drawBank(ts, t) {
    mapView(); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, 440); ctx.clip(); drawTown({ names: false });
    const i = Math.floor(idxF(ts)), bs = bankState(ts);
    trace(TR.x, TR.y, i, 4, '#fff', 1.4, { a: .3 });
    const order = bs.w.map((w, k) => [w, k]).sort((a, b) => a[0] - b[0]);
    order.forEach(([w, k]) => { const pts = []; for (let j = 0; j <= i; j += 4) pts.push(BANK.pos(k, j)); pts.push(BANK.pos(k, i));
      strokeW(pts, `hsl(${k * 30},75%,62%)`, 1.4 + 3.5 * w, { a: 0.14 + 0.86 * Math.sqrt(w) });
      const e = BANK.pos(k, i); dotW(e[0], e[1], 3 + 4 * w, `hsl(${k * 30},75%,62%)`, { a: 0.3 + 0.7 * Math.sqrt(w) });
      if (w > 0.12) text(`${Math.round(BANK.hyp[k] * R2D)}°`, X(e[0]) + 10, Y(e[1]) - 10, { size: 12.5, weight: 700, col: `hsl(${k * 30},75%,72%)`, bg: true }); });
    BANK.steps.forEach(s => { const age = ts - s.ts; if (age < 0 || age > 25) return; circW(s.fx.e, s.fx.n, s.fx.hAcc, C.net, { a: Math.max(.2, 1 - age / 25), lw: 2, fill: 'rgba(181,140,240,.10)' }); });
    const tr = tAt(ts); carW(tr.x, tr.y, tr.psi, '#fff', 8);
    ctx.restore();
    panel(690, 16, 260, 412); text('ВАГИ ГІПОТЕЗ НАПРЯМКУ', 706, 38, { size: 12, weight: 700, col: C.est });
    BANK.hyp.forEach((h, k) => { const y = 62 + k * 21, w = bs.w[k]; text(`${Math.round(h * R2D)}°`, 706, y, { size: 12.5, col: C.dim }); ctx.save(); ctx.fillStyle = `hsl(${k * 30},75%,62%)`; ctx.fillRect(748, y - 7, Math.max(2, w * 170), 14); ctx.restore(); text(`${Math.round(w * 100)}%`, 752 + Math.max(2, w * 170), y, { size: 12, col: '#cfd5e6' }); });
    const done = bs.n >= 4 && (bs.dist || 0) >= 300 && bs.sd <= 15;
    if (bs.mix !== null) { text(`напрямок ≈ ${(((bs.mix * R2D) % 360) + 360) % 360 | 0}° ± ${bs.sd.toFixed(0)}°`, 706, 332, { size: 15, weight: 700, col: '#fff' }); text(`справжній: 90°   ·   точок: ${bs.n}`, 706, 354, { size: 13, col: C.dim }); }
    else text('чекаємо першу мережеву точку…', 706, 332, { size: 14, col: C.dim });
    if (done) text('✓ напрямок усталився:\nпередаємо фільтру', 706, 396, { size: 15, weight: 700, col: C.ok });
  },
});
