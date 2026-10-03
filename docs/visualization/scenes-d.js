'use strict';
/* Scenes 8–10: the road map, the ladder of improvements, the honest summary. Then the player starts. */

/* ============ 8. Road map ============ */
const ROAD_EV = run => { const o = []; run.frames.forEach((f, i) => f.ev.forEach(e => { if (e.k === 'road' || e.k === 'corner') o.push(Object.assign({ ts: i * DT }, e)); })); return o; };
const EV_ROAD = ROAD_EV(RUNS.road), EV_HIDE = ROAD_EV(RUNS.hide);
const STREET = Object.fromEntries(TOWN.streets.map(s => [s.id, s]));
mk({
  title: 'Карта доріг: їздимо дорогами', short: 'Карта', question: 'Машина їздить дорогами, а не полем. Чи можна це використати?', T: 82,
  map: [[0, 12], [3.2, 12], [13, 20], [24, 39], [34, 110], [44, 230], [54, 392]],
  summary: ['Карту OpenStreetMap: «ми на якійсь вулиці». Матчер веде ймовірності для кожної вулиці; впевнений (≥ 90%) — підказує напрямок, «притягує до осі» й виправляє позицію на перехрестях.', 'Прибирає «розмазування» вбік від дороги між мережевими точками: за вимірюваннями на реальних записах — похибка (p95) до ~0,8 від попередньої (R-020), з OBD.', 'Потребує відомої швидкості (OBD). Не допомагає, коли поруч дві вулиці. Коли дороги на карті немає або ми на парковці — сам вимикається.'],
  beats: [
    [3.4, 'Тепер — карта. Машина їздить дорогами, а не полем. Справа — наші гіпотези: яка це вулиця? Для кожної рахуємо ймовірність.'],
    [13, 'Дві схожі вулиці за 30 м. Поки наша хмара мала — розрізняємо. Була б більшою за 30 м — вийшло б 50 на 50, і ми <u>не втручались би</u>: дорозі віримо лише від 90%.'],
    [24, 'Ймовірність вулиці росте, якщо наш шлях схожий на її форму: де повертаємо і на який кут. Коли одна вулиця набирає 90% — ми їй віримо.'],
    [34, 'Що дає дорога? Перше: на прямій вулиці напрямок такий, як у вулиці. Друге: збоку від дороги бути не можна — притягуємо до осі (<b>оранжеві крапки</b>).'],
    [44, 'Третє: після повороту ми точно на перехресті — виправляємо позицію (<b>оранжеве коло</b>). Так хмара стискається знову й знову.'],
    [55.5, 'А що, коли дороги немає на карті? Вулицю Північну «прибрали». Жодна вулиця не пояснює наш шлях — ймовірність «поза дорогою» росте, і обмеження вимикається саме.'],
    [66, 'Гірше, ніж без карти, не стає: карта підказує, а не командує. Дорога з’явилась — і ми знову їй віримо.'],
  ],
  draw(ts, t) {
    const ph2 = t >= 55, run = ph2 ? RUNS.hide : RUNS.road, evs = ph2 ? EV_HIDE : EV_ROAD;
    if (ph2) ts = lerp(56, 112, ramp(t, 55.5, 72));
    mapView(); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, 440); ctx.clip(); drawTown({ names: ['main', 'par', 'north'], hide: ph2 ? ['north'] : [] });
    if (ph2) text('Північної на карті нема', X(120), Y(75), { size: 12.5, weight: 700, col: C.bad, bg: true });
    const i = Math.floor(idxF(ts)), fr = fAt(run, ts), tr = tAt(ts), A = RUNS.net;
    if (fr.road && fr.road.conf) { const st = STREET[fr.road.ids[fr.road.best]]; if (st) strokeW(st.pts, C.road2, Math.max(6, 11 * V.s), { a: .4 }); }
    trace(TR.x, TR.y, i, 4, '#fff', 1.4, { a: .35 });
    trace(A.frames.map(f => f.e), A.frames.map(f => f.n), i, 4, COL.hold, 1.8, { a: .9, dash: [5, 4] });
    trace(run.frames.map(f => f.e), run.frames.map(f => f.n), i, 4, C.est, 2.6);
    cloud(fr.e, fr.n, fr.xx, fr.xy, fr.yy, C.est);
    evs.forEach(e => { const age = ts - e.ts; if (age < 0) return;
      if (e.k === 'road' && age < 10) dotW(e.e, e.n, 2.6, C.road2, { a: 1 - age / 10 });
      if (e.k === 'corner' && age < 7) { circW(e.e, e.n, 8 + age * 6, C.road2, { a: 1 - age / 7, lw: 2.4 }); if (age < 4) text('перехрестя!', X(e.e) + 16, Y(e.n) - 14, { size: 13, weight: 700, col: C.road2, a: 1 - age / 4, bg: true }); } });
    carW(tr.x, tr.y, tr.psi, '#fff', 8);
    ctx.restore();
    // probabilities
    panel(690, 16, 260, 412); text('ЯКА ЦЕ ВУЛИЦЯ?', 706, 38, { size: 12, weight: 700, col: C.est });
    const rd = fr.road;
    if (rd) {
      const rows = rd.p.map((p, k) => [p, k < rd.ids.length ? STREET[rd.ids[k]].name : 'поза дорогою']).sort((a, b) => b[0] - a[0]).slice(0, 5);
      rows.forEach(([p, nm], k) => { const y = 74 + k * 44, off = nm === 'поза дорогою', col = off ? C.bad : C.road2;
        text(nm, 706, y - 9, { size: 13, col: k === 0 ? '#fff' : C.dim, weight: k === 0 ? 650 : 500 });
        ctx.save(); ctx.fillStyle = '#1b2438'; ctx.fillRect(706, y + 2, 170, 10); ctx.fillStyle = col; ctx.fillRect(706, y + 2, 170 * p, 10); ctx.restore(); text(`${Math.round(p * 100)}%`, 884, y + 7, { size: 13, weight: 700, col: '#cfd5e6' });
      });
      text(rd.conf ? '✓ упевнені: дорога допомагає' : 'не впевнені: дорогу не слухаємо', 706, 316, { size: 13.5, weight: 700, col: rd.conf ? C.ok : C.gnss });
    } else text('збираємо дані (потрібно ~150 м)…', 706, 74, { size: 13, col: C.dim });
    legend(706, 352, [[C.est, 'оцінка з картою', 'line'], [COL.hold, 'оцінка без карти', 'line'], [C.road2, 'підказка дороги', 'dot']], { size: 13, lh: 20 });
  },
});

/* ============ 9. The ladder ============ */
const RUNG = [
  { key: 'hold', run: RUNS.hold, col: COL.hold, name: 'тримати останню точку' }, { key: 'none', run: RUNS.none, col: COL.none, name: '+ гіроскоп, швидкість невідома' },
  { key: 'obd', run: RUNS.obd, col: COL.obd, name: '+ швидкість авто (OBD)' }, { key: 'net', run: RUNS.net, col: C.net, name: '+ мережеві координати' }, { key: 'road', run: RUNS.road, col: C.road2, name: '+ карта доріг' },
];
const I12 = Math.round(12 / DT);
RUNG.forEach(r => { const e = r.run.err.slice(I12); r.p50 = percentile(e, .5); r.p95 = percentile(e, .95); });
const PASS = 11, T9 = 3.2;
mk({
  title: 'Усе разом: драбина покращень', short: 'Разом', question: 'Що дає кожен новий свідок на одному й тому ж проїзді?', T: 80,
  summary: [`Той самий проїзд, п’ять наборів свідків: тримати точку → гіроскоп → швидкість OBD → мережа → карта.`, `Похибка (p95, модель): ${RUNG.map(r => Math.round(r.p95)).join(' → ')} м. Кожен крок опускає лінію нижче.`, 'Це синтетична поїздка у вигаданому місті. Реальні числа — у наступному кроці; у живому режимі в машині алгоритм ще не їздив.'],
  beats: [
    [3.4, 'Той самий проїзд, 6,5 хвилин без GPS. <b>Сірий</b> — «тримати останню точку»: через пів хвилини ми вже за сотні метрів.'],
    [14.4, 'Додаємо гіроскоп і оцінку швидкості. Напрямок відомий, швидкість майже ні: <u>червоний</u> шлях схожий на справжній, але гуляє.'],
    [25.4, 'Додаємо швидкість авто з OBD. <i>Блакитний</i> шлях майже збігається з білим, але повільно «пливе».'],
    [36.4, 'Додаємо мережеві точки: тепер він не може відплисти — <span style="color:#b58cf0">фіолетовий</span> тримається в межах.'],
    [47.4, 'І карта доріг: <b style="color:#ff9f43">оранжевий</b> — найточніший, бо знає, де дорога.'],
    [58.5, 'Ось усі п’ять на одному графіку: кожен новий свідок опускає лінію нижче.'],
    [64, 'Кожен крок допомагає по-різному — і разом вони дають те, чого не вміє жоден із них окремо.'],
  ],
  draw(ts, t) {
    const pass = clamp(Math.floor((t - T9) / PASS), 0, 4), t0 = T9 + pass * PASS, chartPhase = t >= T9 + 5 * PASS;
    const tsP = chartPhase ? 392 : 12 + 380 * smooth(ramp(t, t0 + 0.4, t0 + 8.6)), r = RUNG[pass];
    if (!chartPhase) {
      mapView(); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, 440); ctx.clip(); drawTown({ names: false });
      const i = Math.floor(idxF(tsP)); trace(TR.x, TR.y, i, 4, '#fff', 1.4, { a: .4 });
      trace(r.run.frames.map(f => f.e), r.run.frames.map(f => f.n), i, 4, r.col, 2.8);
      const fr = fAt(r.run, tsP), tr = tAt(tsP); cloud(fr.e, fr.n, fr.xx, fr.xy, fr.yy, r.col); carW(tr.x, tr.y, tr.psi, '#fff', 8);
      ctx.restore();
      text(`${r.name}`, 24, 30, { size: 18, weight: 700, col: r.col, bg: true });
      text(`похибка зараз: ${errAt(r.run, tsP).toFixed(0)} м    ·    радіус хмари: ${Math.round(r68(fr))} м`, 24, 424, { size: 13.5, col: '#cfd5e6', bg: true });
    } else {
      const u = ramp(t, T9 + 5 * PASS, T9 + 5 * PASS + 6), tsC = 12 + 380 * smooth(u);
      chart(70, 70, 560, 290, RUNG.map(q => ({ col: q.col, f: s => errAt(q.run, s) })), 12, 392, tsC, 300, { title: 'похибка позиції, м (вище 300 м обрізано)', xlabel: 'час без GPS →' });
      legend(70, 396, RUNG.slice(0, 3).map(q => [q.col, q.name, 'line']), { size: 12, lh: 18 }); legend(330, 396, RUNG.slice(3).map(q => [q.col, q.name, 'line']), { size: 12, lh: 18 });
    }
    panel(690, 16, 260, 412); text('ПОХИБКА ПОЗИЦІЇ (p95), М', 706, 38, { size: 12, weight: 700, col: C.est });
    RUNG.forEach((q, k) => {
      const done = chartPhase || k < pass || (k === pass && t > t0 + 8.8), a = done ? 1 : k === pass ? 0.55 : 0.25, y = 72 + k * 68;
      text(q.name, 706, y - 4, { size: 13, col: '#cfd5e6', a, weight: k === pass && !chartPhase ? 700 : 500 });
      ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = '#1b2438'; ctx.fillRect(706, y + 8, 228, 16); ctx.restore();
      if (done) { const w = clamp(Math.sqrt(q.p95 / 700), 0, 1) * 228 * 0.75; ctx.save(); ctx.fillStyle = q.col; ctx.fillRect(706, y + 8, Math.max(3, w), 16); ctx.restore(); text(`${Math.round(q.p95)} м`, 712 + Math.max(3, w), y + 16, { size: 14, weight: 700, col: '#fff' }); text(`типово ${Math.round(q.p50)} м`, 706, y + 38, { size: 11.5, col: C.dim }); }
    });
    text('довжина смуги ~ √похибки', 706, 416, { size: 11, col: C.dim });
  },
});

/* ============ 10. Honest summary ============ */
const GROUPS = [
  { t: 10.5, title: 'Напрямок без GPS — «банк напрямків» (R-011)', sub: 'GPS немає від самого старту, 2026-09-28, мережа + OBD', rows: [['типова похибка (p50)', 138, 47], ['p95', 799, 380]], unit: 'м' },
  { t: 18.5, title: 'Карта доріг (R-020)', sub: 'реальна поїздка, GPS відсутній, з OBD', rows: [['p95 похибки', 365, 169]], unit: 'м' },
  { t: 28, title: 'Симуляція: 10 хвилин без GPS', sub: 'без швидкості авто → зі швидкістю (OBD)', rows: [['p95 похибки', 1400, 250]], unit: 'м' },
];
const UNKNOWN = ['У машині в живому режимі алгоритм ще не їздив: є лише записи реальних поїздок і симуляція.', 'Компас у реальних авто спотворений сильніше, ніж у моделі.', 'Без датчика швидкості карта майже не допомагає.', 'Повільну підміну GPS, що зберігає всі зв’язки, самими датчиками телефона не відрізнити від правди — потрібні OBD, карта, маршрут.'];
mk({
  title: 'Що ми справді виміряли — і чого не знаємо', short: 'Чесно', question: 'Модель — це гарно. А що кажуть реальні записи?', T: 68,
  beats: [
    [3.4, 'Усе, що ви бачили, — модель. А що ми справді виміряли на записах реальних поїздок? Дивіться.'],
    [10.5, '<b>Банк напрямків:</b> якщо GPS немає від самого старту, типова похибка впала зі 138 до 47 метрів.'],
    [18.5, '<b>Карта доріг</b> (з OBD): у найгіршому випадку (p95) — з 365 до 169 метрів на реальній поїздці.'],
    [28, 'У симуляції 10 хвилин без GPS: близько 1,4 км без швидкості авто — і близько 250 м з нею.'],
    [36, 'А тепер чесно про те, чого ми <u>ще не знаємо</u>.'],
    [47, 'Тому оцінку завжди супроводжує чесна хмара: ми не вдаємо, що знаємо точніше, ніж знаємо.'],
    [57, 'Мета — не бути впевненим. Мета — <b>чесно знати, наскільки ми впевнені</b>.'],
  ],
  draw(ts, t) {
    GROUPS.forEach((g, gi) => {
      const a = smooth(ramp(t, g.t, g.t + 1)); if (a <= 0) return; const y0 = 56 + gi * 126;
      text(g.title, 36, y0, { size: 15, weight: 700, a, col: '#fff' }); text(g.sub, 36, y0 + 20, { size: 12, col: C.dim, a });
      g.rows.forEach(([lab, b, aft], k) => {
        const y = y0 + 46 + k * 34, mx = Math.max(...g.rows.map(r => r[1])), w = 230, u = smooth(ramp(t, g.t + 1 + k * 0.5, g.t + 3 + k * 0.5));
        text(lab, 36, y, { size: 12.5, col: C.dim, a });
        ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = '#6b7690'; ctx.fillRect(176, y - 9, b / mx * w * u, 8); ctx.fillStyle = C.ok; ctx.fillRect(176, y + 1, aft / mx * w * u, 8); ctx.restore();
        text(`${b} → ${aft} ${g.unit}`, 176 + b / mx * w * u + 8, y - 5, { size: 13, weight: 700, a: a * u, col: '#fff' });
      });
    });
    if (t >= 36) text('ЧОГО МИ ЩЕ НЕ ЗНАЄМО', 570, 56, { size: 13, weight: 700, col: C.road2, a: smooth(ramp(t, 36, 37)) });
    UNKNOWN.forEach((s, k) => {
      const a = smooth(ramp(t, 37 + k * 2.4, 38 + k * 2.4)); if (a <= 0) return; const y = 88 + k * 92;
      ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = '#151c2e'; ctx.beginPath(); ctx.roundRect(570, y, 360, 80, 10); ctx.fill(); ctx.fillStyle = C.road2; ctx.fillRect(570, y, 4, 80); ctx.restore();
      const words = s.split(' '); let line = '', ly = y + 22; words.forEach(w => { const test = line + w + ' '; ctx.font = '500 14.5px system-ui'; if (ctx.measureText(test).width > 320) { text(line, 588, ly, { size: 14.5, a }); line = w + ' '; ly += 20; } else line = test; }); text(line, 588, ly, { size: 14.5, a });
    });
    text('джерела: docs/progress.md (R-011, R-020), docs/estimation-algorithm.md §4', 36, 416, { size: 11, col: C.dim, a: smooth(ramp(t, 10.5, 12)) });
    if (t >= 56.5) { const k = smooth(ramp(t, 56.5, 58.5)); ctx.save(); ctx.globalAlpha = 0.93 * k; ctx.fillStyle = '#0b101b'; ctx.fillRect(0, 0, W, H); ctx.restore();
      text('Чесно сказати «я тут, з точністю до 40 м» —', W / 2, 220, { size: 30, weight: 700, align: 'center', a: k }); text('краще, ніж упевнено збрехати.', W / 2, 264, { size: 30, weight: 700, align: 'center', col: C.est, a: k }); }
  },
});

startApp(SCENES);
