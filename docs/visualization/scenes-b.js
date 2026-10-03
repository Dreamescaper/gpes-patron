'use strict';
/* Scenes 4–5: how evidence is fused (Kalman idea), and how GNSS fixes are checked before they are allowed in. */

/* ---------- tiny 2x2 helpers ---------- */
const m2 = {
  inv: ([[a, b], [c, d]]) => { const det = a * d - b * c; return [[d / det, -b / det], [-c / det, a / det]]; },
  add: (A, B) => [[A[0][0] + B[0][0], A[0][1] + B[0][1]], [A[1][0] + B[1][0], A[1][1] + B[1][1]]],
  mv: (A, v) => [A[0][0] * v[0] + A[0][1] * v[1], A[1][0] * v[0] + A[1][1] * v[1]],
};
/* covariance with sigma sa along a bearing and sc across it */
function covAxes(brg, sa, sc) {
  const u = [Math.sin(brg), Math.cos(brg)], v = [Math.cos(brg), -Math.sin(brg)];
  return [[sa * sa * u[0] * u[0] + sc * sc * v[0] * v[0], sa * sa * u[0] * u[1] + sc * sc * v[0] * v[1]], [sa * sa * u[0] * u[1] + sc * sc * v[0] * v[1], sa * sa * u[1] * u[1] + sc * sc * v[1] * v[1]]];
}
function fuse(list) { // product of Gaussians: precisions add, means are weighted by precision
  let L = [[0, 0], [0, 0]], b = [0, 0];
  list.forEach(({ m, C: Cv }) => { const Li = m2.inv(Cv); L = m2.add(L, Li); const t = m2.mv(Li, m); b = [b[0] + t[0], b[1] + t[1]]; });
  const Pm = m2.inv(L); return { m: m2.mv(Pm, b), C: Pm };
}
const cl2 = (o, col, a = 1) => cloud(o.m[0], o.m[1], o.C[0][0], o.C[0][1], o.C[1][1], col, { a });

/* ============ 4. Fusion ============ */
mk({
  title: 'Як змішати двох свідків', short: 'Злиття', question: 'Одна підказка каже одне, друга — інше. Кому вірити?', T: 94,
  summary: ['Кожне нове свідчення — ще одна «гірка» (еліпс) з центром і розкидом. Їх перемножуємо.', 'Результат завжди точніший за кожного окремо. Точнішому свідкові — більша вага (1/σ²). Так працює фільтр Калмана.', 'Працює, коли помилки свідків незалежні, і ми чесно знаємо їхній розкид. Брехливому свідку фільтр теж повірить — тому спершу потрібна перевірка довіри (наступний крок).'],
  beats: [
    [3.4, 'Як злити дві підказки? Беремо лише позицію вздовж дороги. <i>Синя гірка</i> — наша оцінка після кроків: біля нуля, розкид ±20 м.'],
    [13.5, '<b>Жовта гірка</b> — нове свідчення, наприклад мережа: «ви біля +30 м, розкид ±10 м». Вона вужча — отже, точніша.'],
    [21.5, 'Перемножуємо гірки: там, де обидві високі, — найправдоподібніше. Виходить <em>зелена гірка</em>: вона між двома, ближче до точнішої, і <b>вужча за обидві</b>.'],
    [31.5, 'Нова оцінка — зважене середнє. Вага свідка — це 1/σ²: чим він точніший, тим більше йому віримо.'],
    [38.5, 'Подивіться: свідок стає неточним — жовта розповзається, і зелена повертається до синьої. Поганому свідкові віримо мало.'],
    [48, 'На карті «гірка» — це еліпс. <i>Синій</i> витягнутий вздовж руху: ми погано знаємо, скільки проїхали, але добре — де збоку.'],
    [56, '<b>Жовте коло</b> — мережа: вона не знає, де дорога, і однаково груба в усі боки.'],
    [62, 'Перетин — <em>зелений еліпс</em>: менший за обох. Кожне нове свідчення лише стискає хмару.'],
    [69, 'А якщо додати карту доріг: «ми на дорозі, збоку не далі 4 м»? <u>Оранжева смуга</u> зрізає хмару впоперек дороги.'],
    [77, 'Це і є серце алгоритму — <b>фільтр Калмана</b>: передбачити за кроками, а потім злити з кожним новим свідченням за вагою довіри.'],
  ],
  draw(ts, t) {
    const g = (x, mu, sg) => Math.exp(-0.5 * ((x - mu) / sg) ** 2);
    if (t < 47.5) { // ---- part A: one dimension ----
      const mu1 = -10, s1 = 20, mu2 = 30, s2 = 10 + 50 * smooth(ramp(t, 39, 45)) - 0 * 1;
      const sg2 = t < 39 ? 10 : s2, w1 = 1 / (s1 * s1), w2 = 1 / (sg2 * sg2), sp = Math.sqrt(1 / (w1 + w2)), mup = (w1 * mu1 + w2 * mu2) / (w1 + w2);
      const x0 = 340, px = 6.2, yb = 318, H0 = 1500, xm = m => x0 + px * m;
      const curve = (mu, sg, col, a, fill) => {
        ctx.save(); ctx.globalAlpha = a; ctx.beginPath();
        for (let m = -52; m <= 52; m += 0.5) { const y = yb - (H0 / sg) * g(m, mu, sg); m === -52 ? ctx.moveTo(xm(m), y) : ctx.lineTo(xm(m), y); }
        ctx.strokeStyle = col; ctx.lineWidth = 3; ctx.stroke();
        if (fill) { ctx.lineTo(xm(52), yb); ctx.lineTo(xm(-52), yb); ctx.closePath(); ctx.globalAlpha = a * .18; ctx.fillStyle = col; ctx.fill(); } ctx.restore();
      };
      ctx.save(); ctx.strokeStyle = '#3a4670'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.moveTo(40, yb); ctx.lineTo(640, yb); ctx.stroke();
      for (let m = -40; m <= 40; m += 20) { ctx.beginPath(); ctx.moveTo(xm(m), yb); ctx.lineTo(xm(m), yb + 6); ctx.stroke(); text(`${m > 0 ? '+' : ''}${m}`, xm(m), yb + 18, { size: 12, col: C.dim, align: 'center' }); } ctx.restore();
      text('позиція вздовж дороги, м', 640, yb + 38, { size: 12.5, col: C.dim, align: 'right' });
      const a1 = smooth(ramp(t, 4, 6)), a2 = smooth(ramp(t, 14, 16)), a3 = smooth(ramp(t, 22, 25));
      if (a1) { curve(mu1, s1, C.est, a1, true); text('наша оцінка (з кроків)', xm(mu1) - 90, yb - 92, { col: C.est, size: 14, weight: 650, a: a1 }); }
      if (a2) { curve(mu2, sg2, C.gnss, a2, true); text('свідок (мережа)', xm(mu2) + 30, yb - 195 * (10 / sg2) ** 0.6, { col: C.gnss, size: 14, weight: 650, a: a2 }); }
      if (a3) {
        curve(mup, sp, C.ok, a3, true); ctx.save(); ctx.globalAlpha = a3; ctx.setLineDash([4, 4]); ctx.strokeStyle = C.ok; ctx.beginPath(); ctx.moveTo(xm(mup), yb); ctx.lineTo(xm(mup), 120); ctx.stroke(); ctx.restore();
        text('результат', xm(mup), 108, { col: C.ok, size: 15, weight: 700, align: 'center', a: a3 });
      }
      if (t > 31.5) { const aw = smooth(ramp(t, 31.5, 33));
        text(`вага нашої оцінки: ${Math.round(w1 / (w1 + w2) * 100)}%`, 60, 62, { col: C.est, size: 17, weight: 650, a: aw }); text(`вага свідка: ${Math.round(w2 / (w1 + w2) * 100)}%`, 60, 90, { col: C.gnss, size: 17, weight: 650, a: aw }); }
      formula([[['вага = 1 / σ²', C.text]], [['нова оцінка = (вага₁·оцінка₁ + вага₂·оцінка₂) / (вага₁ + вага₂)', C.text]], [['1/σ²нове = 1/σ₁² + 1/σ₂²   →   розкид менший за обидва', C.ok]]], 60, 150 - 0, t, 25.5, { size: 16, gap: 2.6, lh: 26 });
      text(`σ: ${s1} м   ·   ${sg2.toFixed(0)} м   →   ${sp.toFixed(1)} м`, 640, 70, { size: 15, weight: 600, align: 'right', col: C.dim, a: a3 });
    } else { // ---- part B: two dimensions ----
      setView(0, 0, 5.2, 340, 222); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, 440); ctx.clip();
      const brg = 50 * D2R, prior = { m: [-8, -4], C: covAxes(brg, 24, 6.5) }, net = { m: [13, 5], C: [[15 * 15, 0], [0, 15 * 15]] };
      strokeW([[-300 * Math.sin(brg), -300 * Math.cos(brg)], [300 * Math.sin(brg), 300 * Math.cos(brg)]], C.road, 9 * V.s); strokeW([[-300 * Math.sin(brg), -300 * Math.cos(brg)], [300 * Math.sin(brg), 300 * Math.cos(brg)]], C.roadLine, 1);
      const ap = smooth(ramp(t, 48.5, 50.5)), an = smooth(ramp(t, 56.5, 58.5)), af = smooth(ramp(t, 62.5, 64.5)), ar = smooth(ramp(t, 69.5, 71.5)), af2 = smooth(ramp(t, 74, 76));
      const roadM = [7, 6], roadC = covAxes(brg, 300, 3);
      if (ar) { const L = 300, nx = Math.cos(brg), ny = -Math.sin(brg), cx0 = roadM[0], cy0 = roadM[1], ux = Math.sin(brg), uy = Math.cos(brg), w = K68 * 3;
        ctx.save(); ctx.globalAlpha = ar * .28; ctx.fillStyle = C.road2; ctx.beginPath(); [[-1, 1], [1, 1], [1, -1], [-1, -1]].forEach(([sa, sb], k) => { const x = cx0 + ux * L * sa + nx * w * sb, y = cy0 + uy * L * sa + ny * w * sb; k ? ctx.lineTo(X(x), Y(y)) : ctx.moveTo(X(x), Y(y)); }); ctx.fill(); ctx.restore();
        text('карта: «ми на дорозі»', X(roadM[0] + 18), Y(roadM[1] + 42), { col: C.road2, size: 13, weight: 650, a: ar, bg: true }); }
      if (ap) { cl2(prior, C.est, ap); text('наша оцінка', X(-44), Y(36), { col: C.est, size: 14, weight: 650, a: ap, bg: true }); }
      if (an) { cl2(net, C.gnss, an); text('мережа', X(34), Y(-20), { col: C.gnss, size: 14, weight: 650, a: an, bg: true }); }
      const f1 = fuse([prior, net]), f2 = fuse([prior, net, { m: roadM, C: roadC }]);
      if (af && !af2) { cl2(f1, C.ok, af); text('після злиття', X(f1.m[0]) + 16, Y(f1.m[1]) + 40, { col: C.ok, size: 14, weight: 700, a: af, bg: true }); }
      else if (af && af2) { cl2(f1, C.ok, af * (1 - af2) * .6 + .0); cl2(f2, C.ok, af2); text('з картою — ще менше', X(f2.m[0]) + 16, Y(f2.m[1]) + 40, { col: C.ok, size: 14, weight: 700, a: af2, bg: true }); }
      ctx.restore();
      panel(690, 16, 260, 200); text('РОЗМІР ХМАРИ (радіус 68%)', 706, 38, { size: 12, weight: 700, col: C.est });
      const rr = o => (K68 * Math.sqrt((o.C[0][0] + o.C[1][1]) / 2)).toFixed(0);
      [[ap, 'наша оцінка', rr(prior), C.est], [an, 'мережа', rr(net), C.gnss], [af, 'злиття двох', rr(f1), C.ok], [ar, 'злиття з картою', rr(f2), C.ok]].forEach(([a, l, v, col], k) => {
        text(l, 706, 70 + k * 34, { size: 14, col: C.dim, a }); text(`${v} м`, 934, 70 + k * 34, { size: 20, weight: 700, col, align: 'right', a }); });
    }
  },
});

/* ============ 5. Trust ============ */
const TRUST_CHECKS = ['Неможлива швидкість', 'Заявлена точність', 'Поза «воротами» хмари', 'Курс GPS ≠ гіроскоп', 'Швидкість GPS ≠ OBD', 'Мережа не згодна'];
mk({
  title: 'Кому вірити: перевірка GPS', short: 'Довіра', question: 'Як відрізнити справжній GPS від підробленого?', T: 90,
  summary: ['Перед тим як точка GPS потрапить у хмару, вона проходить ~14 перевірок: швидкість, точність, «ворота» навколо передбаченого місця, збіг курсу й швидкості з іншими датчиками.', 'Відсікає грубі підміни та збої; чотири «повторних» невдалих секунди — і ми знову довіряємо лише після кількох чистих точок.', 'Повільний дрейф, який зберігає всі взаємозв’язки, проходить усі перевірки (за нашими вимірюваннями — це найбільша сліпа зона). Його ловлять лише незалежні джерела.'],
  beats: [
    [3.4, 'Не кожному свідкові можна вірити. Кожну точку GPS ми спершу перевіряємо — і лише потім пускаємо в хмару. Ось кілька перевірок.'],
    [13, 'Раптом GPS каже, що ми за 480 метрів звідси — за одну секунду. Це <u>480 м/с</u> — швидше за літак. Точку відкидаємо.'],
    [23.5, 'Другий випадок: сам приймач зізнається, що точність — 200 метрів. Користі з такої точки мало, ми їй не віримо.'],
    [33.5, 'Третій: точка рівна й «впевнена», але далеко за межами нашої хмари. Інші датчики кажуть, де ми маємо бути, — і це не те місце.'],
    [44, 'Четвертий: GPS каже «їдемо на схід», а гіроскоп знає, що ми повернули. Курс не збігається — підозріло.'],
    [54, 'П’ятий: GPS заявляє швидкість 22 м/с, а спідометр авто (OBD) — 14. Хтось бреше.'],
    [62, 'А тепер найпідступніше: підмінювач зсуває точку на кілька метрів за секунду. Кожна нова точка поруч із попередньою — усі перевірки <em>пройдено</em>.'],
    [72.5, 'Оцінка тягнеться за підмінкою. Тому GPS — лише один зі свідків; потрібні незалежні: швидкість авто, мережа, карта. Далі — про них.'],
  ],
  draw(ts, t) {
    const car = tt => DRIVE.route.at(30 + 12 * (tt - 3.2)), me = car(t);
    const drift = t < 64 ? 0 : Math.min(4 * (t - 64), 4 * 14);
    const rx = Math.cos(me.psi), ry = -Math.sin(me.psi);          // to the right of the heading
    const driftV = [rx * drift, ry * drift];
    setView(me.x, me.y, 2.0, 340, 232); ctx.save(); ctx.beginPath(); ctx.rect(0, 0, 686, 440); ctx.clip();
    drawTown({ names: false });
    const estC = { x: me.x + 0.92 * driftV[0], y: me.y + 0.92 * driftV[1] };
    // regular fixes, one per second
    for (let k = 0; k < 4; k++) { const tk = Math.floor(t) - k, pk = car(tk); if (tk < 3.5) continue;
      const dr = tk < 64 ? 0 : Math.min(4 * (tk - 64), 56), px = pk.x + Math.cos(pk.psi) * dr + jit(tk, 2), py = pk.y - Math.sin(pk.psi) * dr + jit(tk + 3, 2);
      const inc = [15, 25, 35, 45, 55].some(ti => Math.abs(tk - ti) < 1.5);
      if (!inc) dotW(px, py, 3.5, C.gnss, { a: 0.9 - k * 0.22 }); }
    // the predicted cloud and its trust "gate"
    cloud(estC.x, estC.y, 9, 0, 9, C.est, { a: .95 }); circW(estC.x, estC.y, 12, C.est, { dash: [3, 4], lw: 1, a: .8 });
    carW(me.x, me.y, me.psi, '#fff', 8);
    // incidents
    const inc = [
      { t: 15, check: 0, off: [460, 80], hAcc: 4, msg: '480 м за 1 с = 480 м/с\n(межа 70 м/с)' },
      { t: 25, check: 1, off: [30, 24], hAcc: 200, msg: 'точність 200 м\n(межа 150 м)' },
      { t: 35, check: 2, off: [-8, -46], hAcc: 4, msg: 'за 46 м від центру хмари,\nа «ворота» — 12 м' },
      { t: 45, check: 3, off: [10, 12], hAcc: 4, msg: 'курс GPS 70° проти\nгіроскопа 0°' },
      { t: 55, check: 4, off: [14, -10], hAcc: 4, msg: 'GPS: 22 м/с\nOBD: 14 м/с' },
    ];
    let active = -1;
    inc.forEach((ic, k) => {
      const u = t - ic.t; if (u < 0 || u > 8) return; if (u < 7) active = ic.check;
      const p0 = car(ic.t), fx = p0.x + ic.off[0], fy = p0.y + ic.off[1], a = u < 7 ? 1 : 1 - (u - 7);
      const px = X(fx), py = Y(fy), off = px < 8 || px > 678 || py < 8 || py > 432;
      if (off) { const ex = clamp(px, 30, 650), ey = clamp(py, 30, 410); arrowPx(ex - 26, ey, ex + 6, ey, C.bad, 3.5, 11); text(`ще ${Math.round(Math.hypot(ic.off[0], ic.off[1]))} м`, ex - 70, ey, { size: 13, weight: 650, col: C.bad, a, bg: true }); }
      else { dotPx(px, py, 6, C.gnss, { a, stroke: '#0b101b', lw: 2 }); circW(fx, fy, ic.hAcc, C.gnss, { a: a * .8, dash: [4, 3], lw: 1.4 });
        const mark = u > 1.2; if (mark) { text('✗', px, py - 18, { size: 24, weight: 800, col: C.bad, align: 'center', a }); }
        if (ic.check === 3) { arrowPx(px, py, px + Math.sin(70 * D2R) * 50, py - Math.cos(70 * D2R) * 50, C.gnss, 2.5, 9); arrowPx(X(estC.x), Y(estC.y), X(estC.x) + Math.sin(me.psi) * 50, Y(estC.y) - Math.cos(me.psi) * 50, C.gyro, 2.5, 9); } }
      if (u > 1.2) text(ic.msg, clamp(px + 20, 20, 520), clamp(py + 34, 40, 380), { size: 14, weight: 650, col: '#ffb4ae', a, bg: true });
      if (ic.check === 4 && u > 1.2) { ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = C.gnss; ctx.fillRect(40, 330, 22 * 8, 10); ctx.fillStyle = C.speed; ctx.fillRect(40, 350, 14 * 8, 10); ctx.restore(); text('GPS', 224, 335, { size: 12, col: C.gnss, a }); text('OBD', 224, 355, { size: 12, col: C.speed, a }); }
    });
    // drift phase
    const dA = smooth(ramp(t, 64, 66));
    if (drift > 0) {
      for (let k = 0; k < Math.min(14, Math.floor(t - 64) + 1); k++) { const tk = 64 + k, pk = car(tk), dd = 4 * k; dotW(pk.x + Math.cos(pk.psi) * dd, pk.y - Math.sin(pk.psi) * dd, 3.2, C.gnss, { a: .8 }); }
      const gx = me.x + driftV[0], gy = me.y + driftV[1];
      dotW(gx, gy, 6.5, C.gnss, { stroke: '#0b101b', lw: 2 }); strokeW([[me.x, me.y], [gx, gy]], C.bad, 1.5, { dash: [4, 3], a: dA });
      text(`зсув ${Math.round(drift)} м`, X(me.x) + 14, Y(me.y) + 38, { size: 15, weight: 700, col: C.bad, a: dA, bg: true });
      text('оцінка тягнеться\nза підмінкою', X(estC.x) - 120, Y(estC.y) - 40, { size: 13, weight: 650, col: C.est, a: dA, bg: true });
    }
    ctx.restore();
    // checklist
    panel(690, 16, 260, 412); text('ПЕРЕВІРКИ КОЖНОЇ ТОЧКИ', 706, 38, { size: 12, weight: 700, col: C.est });
    TRUST_CHECKS.forEach((nm, k) => {
      const y = 72 + k * 48, isA = k === active, passAll = drift > 0 && k < 5;
      const col = isA ? C.bad : passAll ? C.ok : '#4d5a82';
      ctx.save(); ctx.fillStyle = isA ? 'rgba(252,98,85,.16)' : 'rgba(255,255,255,0.04)'; ctx.beginPath(); ctx.roundRect(704, y - 18, 232, 38, 8); ctx.fill(); ctx.restore();
      dotPx(722, y, 9, null, { stroke: col, lw: 2.2 }); text(isA ? '✗' : passAll ? '✓' : '', 722, y + 1, { size: 13, weight: 800, col, align: 'center' });
      text(nm, 742, y, { size: 14, weight: isA ? 700 : 500, col: isA ? '#ffb4ae' : passAll ? '#cfe9c4' : '#aab3cc' });
    });
    if (drift > 0) text('усі пройдено ✓', 706, 380, { size: 16, weight: 700, col: C.ok, a: dA });
  },
});
