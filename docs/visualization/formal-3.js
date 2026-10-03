'use strict';
/* Formal video, scenes 7–9: observability and ZUPT, robust coarse updates and the odometry chord, compass harmonics and least squares. */

/* ============ 7. Observability, ZUPT, local update ============ */
const HROWS = [
  ['GNSS: позиція', { e: '1', n: '1' }], ['GNSS: швидкість', { v: '1' }], ['GNSS: курс (v ≥ 5 м/с)', { psi: '1' }], ['OBD: z = (1+s)v', { v: '1+s', s: 'v' }],
  ['ZUPT: v = 0', { v: '1' }], ['ZUPT: ω = b', { b: '1' }], ['мережа: позиція', { e: '1', n: '1' }], ['компас: азимут', { psi: '1' }], ['дорога: поперечне', { e: 'nₑ', n: 'nₙ' }], ['дорога: курс', { psi: '1' }], ['центрошвидкісна v = aₗ/ω', { v: '1' }],
];
const HCOLS = ['e', 'n', 'psi', 'v', 'b', 's', 'ba'], HCL = ['e', 'n', 'ψ', 'v', 'b', 's', 'bₐ'];
const ZU = (() => {
  const dt = 0.05, n = 120, R = 0.003 ** 2, P0 = 0.01 ** 2, bt = 0.004, r = mulberry32(31); let P = P0, b = 0, ts = [0], bs = [0], sg = [Math.sqrt(P0)];
  for (let k = 1; k <= n; k++) { const z = bt + 0.003 * gaussian(r), K = P / (P + R); b += K * (z - b); P *= (1 - K); ts.push(k * dt); bs.push(b); sg.push(Math.sqrt(P)); }
  return { ts, bs, sg, bt, final: sg[n] };
})();
fscene({
  title: 'Спостережуваність, ZUPT і локальне оновлення', short: 'ZUPT', question: 'Яке вимірювання про який стан говорить — і чому один стан іноді оновлюють «сам»?', T: 80,
  beats: [
    [3.4, 'Яке вимірювання про який стан говорить? Це записано в рядках матриці H: ненульові елементи показують, які компоненти стану входять у вимірювання.'],
    [13, 'GNSS-позиція та мережа спостерігають східну й північну координати. Швидкість і курс GNSS — швидкість і азимут. Швидкість з OBD — добуток один плюс s на v, тому її рядок зачіпає й v, і масштаб s, а похідна за s дорівнює v.'],
    [26, 'Світлофор дає два псевдовимірювання. Перше: швидкість дорівнює нулю. Друге: під час стоянки гіроскоп вимірює чистий зсув, тож показання гіроскопа — це безпосередньо вимірювання b.'],
    [38, 'Але повне оновлення з таким вимірюванням небезпечне: кореляції між b, азимутом і позицією після довгого дрейфу здатні зсунути позицію на десятки метрів через важіль. Тож використано локальне оновлення за Шмідтом: рухається лише один стан.'],
    [50, 'Коваріацію при цьому оновлюють за Джозефом для цього неоптимального підсилення, тож вона лишається узгодженою.'],
    [57, 'Скільки це дає? При n незалежних вимірюваннях дисперсія оцінки зсуву — обернена сума точностей. Шість секунд стоянки на двадцяти герцах — сто двадцять оновлень, і сигма падає до менш ніж трьох десятитисячних радіана на секунду.'],
    [68, 'Наслідок для курсу: помилка кута росте як сигма b на t. Без навчання зсуву за п’ять хвилин це сотні градусів, з навчанням — менше п’яти. На практиці шум корельований, тож виграш скромніший, але напрям той самий.'],
  ],
  build() {
    const z = HROWS.map(r => HCOLS.map(c => (r[1][c] ? 1 : 0))), txt = HROWS.map(r => HCOLS.map(c => r[1][c] || ''));
    const heat = { data: [{ z, x: HCL, y: HROWS.map(r => r[0]), type: 'heatmap', colorscale: [[0, 'rgba(21,28,46,0.9)'], [1, '#3b6f8c']], showscale: false, xgap: 3, ygap: 3, hoverinfo: 'skip' }],
      layout: { title: 'матриця H: хто що спостерігає', margin: { l: 150, r: 8, t: 28, b: 32 }, xaxis: { side: 'bottom', tickfont: { size: 14 } }, yaxis: { autorange: 'reversed', tickfont: { size: 11.5 } },
        annotations: HROWS.flatMap((r, i) => HCOLS.map((c, j) => r[1][c] ? { x: HCL[j], y: r[0], text: r[1][c], showarrow: false, font: { size: 13, color: '#fff' } } : null).filter(Boolean)) } };
    const dynB = t => { const n = Math.max(1, Math.min(120, Math.floor((t - 57) / 8 * 120))), x = ZU.ts.slice(0, n + 1), up = ZU.bs.slice(0, n + 1).map((v, i) => (v + 2 * ZU.sg[i]) * 1000), lo = ZU.bs.slice(0, n + 1).map((v, i) => (v - 2 * ZU.sg[i]) * 1000);
      return { data: [{ x, y: up, mode: 'lines', line: { width: 0 }, hoverinfo: 'skip' }, { x, y: lo, mode: 'lines', fill: 'tonexty', fillcolor: 'rgba(88,196,221,.22)', line: { width: 0 }, hoverinfo: 'skip' },
        { x, y: ZU.bs.slice(0, n + 1).map(v => v * 1000), mode: 'lines', line: { color: FC.blue, width: 3 } }, { x: [0, 6], y: [ZU.bt * 1000, ZU.bt * 1000], mode: 'lines', line: { color: '#fff', dash: 'dot', width: 1.2 } }],
        layout: { title: 'оцінка зсуву b̂ ± 2σ під час стоянки, мрад/с', xaxis: { range: [0, 6], title: { text: 't, с' } }, yaxis: { range: [-25, 25] } } }; };
    const tt = range(1, 300, 120), hd = { data: [{ x: tt, y: tt.map(t => 0.01 * t * 180 / Math.PI), mode: 'lines', line: { color: FC.red, width: 3 } }, { x: tt, y: tt.map(t => ZU.final * t * 180 / Math.PI), mode: 'lines', line: { color: FC.green, width: 3 } }],
      layout: { title: 'похибка курсу δψ = σ_b·t, °', xaxis: { title: { text: 't, с' }, range: [0, 300] }, yaxis: { type: 'log', range: [-1, 3.3] }, annotations: [labelAnn(150, Math.log10(0.01 * 150 * 57.3) + 0.25, 'без ZUPT: σ_b = 0,01', FC.red, { size: 11 }), labelAnn(150, Math.log10(ZU.final * 150 * 57.3) - 0.3, `після ZUPT: σ_b = ${fxt(ZU.final * 1e4, 1)}·10⁻⁴`, FC.green, { size: 11 })] } };
    return [
      PL(36, 24, 480, 300, Object.assign({ t0: 3.6, t1: 56 }, heat)),
      TX(String.raw`\omega_{\mathrm{meas}}\big|_{\dot\psi=0}=b+n_g\ \Rightarrow\ z_b=\omega_{\mathrm{meas}},\ \ H=e_b^{\top}`, 540, 40, { size: 17, t0: 27, t1: 56, color: 'blue' }),
      TX(String.raw`k=\frac{P_{ii}}{P_{ii}+r},\qquad x_i\leftarrow x_i+k\,(z-x_i)`, 540, 112, { size: 19, t0: 39, t1: 56 }),
      TX(String.raw`A=I-k\,e_ie_i^{\top},\quad P\leftarrow A\,P\,A^{\top}+k^2r\,e_ie_i^{\top}`, 540, 170, { size: 18, t0: 44, t1: 56 }),
      HT('Рядки: GNSS, OBD, ZUPT (v = 0 з σ = 0,05 м/с; ω = b), мережа, компас, дорога (D-041…D-044), v = a<sub>lat</sub>/ω у поворотах (D-050).', 540, 230, 390, { t0: 14, t1: 56, size: 13 }),
      PL(36, 24, 440, 200, { dyn: dynB, t0: 57 }), PL(500, 24, 440, 200, Object.assign({ t0: 66 }, hd)),
      TX(String.raw`P_n=\Big(\frac{1}{P_0}+\frac{n}{R}\Big)^{-1}\ \Rightarrow\ \sigma_b\approx\frac{\sigma_z}{\sqrt{n}}=\frac{0{,}003}{\sqrt{120}}\approx 2{,}7\cdot10^{-4}\ \frac{\text{рад}}{\mathrm{c}}`, 36, 256, { size: 20, t0: 59, box: true }),
      TX(String.raw`\delta\psi(t)=\sigma_b\,t`, 500, 256, { size: 22, t0: 68, color: 'orange' }),
    ];
  },
});

/* ============ 8. Robust coarse updates and the odometry chord ============ */
const ROB = (() => { const P = 1, R = 1, S = P + R, nu0 = 9.21; const yy = range(0, 12, 240); const dx = y => { const nu = y * y / S; const Re = R * Math.max(1, nu / nu0), K = P / (P + Re); return K * y; }; return { yy, plain: yy.map(y => P / (P + R) * y), robust: yy.map(dx), nu0, ycrit: Math.sqrt(nu0 * S) }; })();
fscene({
  title: 'Грубі координати: робастне R і хорда одометра', short: 'Мережа', question: 'Як не дати хибній грубій точці зіпсувати оцінку?', T: 86,
  beats: [
    [3.4, 'Мережеві координати грубі й можуть бути хибними. Перший захист — робастне оновлення. Якщо NIS перевищує дев’ять і двадцять одну соту, дисперсію вимірювання множать на NIS, поділене на дев’ять і двадцять одну соту.'],
    [16, 'Поки розбіжність мала, поправка така, як у звичайного фільтра. А коли велика — підсилення спадає обернено пропорційно до NIS, і поправка до оцінки не росте, а зменшується. Така функція впливу називається спадною.'],
    [30, 'Другий захист — одометр. Відстань між двома мережевими точками порівнюють із хордою: прямою, що сполучає початок і кінець пройденого шляху. Хорду отримують інтегруванням швидкості за накопиченим курсом гіроскопа.'],
    [43, 'Важливо, що хорда не залежить від абсолютного напряму: початковий азимут лише повертає всю фігуру, а довжина хорди не змінюється. Тому перевірка працює без компаса й без GPS.'],
    [54, 'Точку приймають, якщо різниця відстані й хорди не перевищує три сигми суми дисперсій плюс п’ять відсотків відстані плюс десять метрів. Для справжніх точок це майже дев’яносто дев’ять і сім десятих відсотка.'],
    [66, 'Геометрично: допустимі положення нової точки — кільце навколо попередньої з радіусом, що дорівнює хорді. «Застрягла» точка лежить у центрі кільця, а не в ньому, тож відкидається.'],
    [77, 'Голоси кількох попередніх точок зважують за оберненою дисперсією з підлогою п’ятдесят метрів, щоб одна точка із заявленою малою похибкою не переголосувала решту.'],
  ],
  build() {
    const infl = { data: [{ x: ROB.yy, y: ROB.plain, mode: 'lines', line: { color: '#6b7690', width: 2, dash: 'dash' } }, { x: ROB.yy, y: ROB.robust, mode: 'lines', line: { color: FC.purple, width: 3.5 } },
      { x: [ROB.ycrit, ROB.ycrit], y: [0, 6], mode: 'lines', line: { color: '#fff', width: 1.2, dash: 'dot' } }],
      layout: { title: 'поправка Δx залежно від інновації y (P = R = 1)', xaxis: { title: { text: 'y / σ' }, range: [0, 12] }, yaxis: { range: [0, 4.5], title: { text: 'Δx / σ' } },
        annotations: [labelAnn(ROB.ycrit, 4.2, 'ν = 9,21', '#fff', { size: 11, extra: { xanchor: 'left', xshift: 5 } }), labelAnn(9, 4.1, 'звичайний', '#aab3cc', { size: 11 }), labelAnn(9.2, 1.5, 'робастний: спадає ∝ 1/y', FC.purple, { size: 12 })] } };
    // geometry in the gyro frame: a dog-leg path, the chord, and the acceptance annulus
    const path = [[0, 0], [90, 20], [170, 70], [190, 150], [185, 210]], c = Math.hypot(185, 210), s1 = 30, tol = 3 * Math.sqrt(2) * s1 + 0.05 * c + 10, circ = (cx, cy, r) => range(0, 2 * Math.PI, 90).map(a => [cx + r * Math.cos(a), cy + r * Math.sin(a)]);
    const outer = circ(0, 0, c + tol), inner = circ(0, 0, Math.max(0, c - tol)), ann = outer.concat(inner.slice().reverse());
    const geo = { data: [
      { x: ann.map(p => p[0]), y: ann.map(p => p[1]), mode: 'lines', fill: 'toself', fillcolor: 'rgba(131,193,103,.16)', line: { color: FC.green, width: 1 }, hoverinfo: 'skip' },
      { x: path.map(p => p[0]), y: path.map(p => p[1]), mode: 'lines+markers', line: { color: FC.blue, width: 3 }, marker: { size: 5, color: FC.blue } },
      { x: [0, 185], y: [0, 210], mode: 'lines', line: { color: FC.yellow, width: 3, dash: 'dash' } },
      { x: [0], y: [0], mode: 'markers', marker: { size: 12, color: FC.purple } }, { x: [185 + 12], y: [210 - 16], mode: 'markers', marker: { size: 12, color: FC.green, symbol: 'circle' } }, { x: [6], y: [-5], mode: 'markers', marker: { size: 12, color: FC.red, symbol: 'x' } }],
      layout: { title: 'кільце допустимих положень: |d − c| ≤ допуск', xaxis: { range: [-330, 330], title: { text: 'м' } }, yaxis: { range: [-300, 330], scaleanchor: 'x' },
        annotations: [labelAnn(80, 140, `хорда c = ${Math.round(c)} м`, FC.yellow, { size: 12 }), labelAnn(-5, 30, 'точка 1', FC.purple, { size: 11, extra: { xanchor: 'right' } }), labelAnn(240, 175, 'нова: ✓', FC.green, { size: 12 }), labelAnn(60, -25, '«застрягла»: d ≈ 0 ✗', FC.red, { size: 12 }), labelAnn(-190, 190, `допуск ±${Math.round(tol)} м`, FC.green, { size: 11 })] } };
    return [
      TX(String.raw`R_{\mathrm{eff}}=R\cdot\max\!\Big(1,\ \frac{\nu}{9{,}21}\Big)`, 36, 28, { size: 24, t0: 5, color: 'purple' }),
      TX(String.raw`K_{\mathrm{eff}}=\frac{P}{P+R_{\mathrm{eff}}}\ \xrightarrow{\ \nu\gg 9{,}21\ }\ \frac{P}{R}\cdot\frac{9{,}21}{\nu}`, 36, 96, { size: 21, t0: 17 }),
      TX(String.raw`c=\Big\lVert\sum_i v_i\,\Delta t\,\big(\sin\theta_i,\ \cos\theta_i\big)\Big\rVert`, 36, 176, { size: 19, t0: 32 }),
      TX(String.raw`\theta_i=-\sum_{j\le i}(\omega_j-b)\,\Delta t`, 36, 238, { size: 19, t0: 36 }),
      TX(String.raw`\theta_i\to\theta_i+\theta_0\ \Rightarrow\ c\ \text{не змінюється}`, 36, 280, { size: 19, t0: 44, color: 'blue' }),
      TX(String.raw`\big|\,d-c\,\big|\le 3\sqrt{\sigma_1^2+\sigma_2^2}+0{,}05\,d+10\ \text{м}`, 36, 330, { size: 19, t0: 55, box: true }),
      TX(String.raw`P(\text{пройде})\approx P(|\mathrm{N}(0,1)|\le 3)=99{,}7\%`, 36, 386, { size: 17, t0: 60, color: 'green' }),
      PL(500, 24, 440, 200, Object.assign({ t0: 17 }, infl)), PL(500, 232, 440, 190, Object.assign({ t0: 31 }, geo)),
    ];
  },
});

/* ============ 9. Compass: harmonic analysis and least squares ============ */
const MG = (() => {
  const O = [4, -2], Rr = 25, M = [[1, 0.25], [0, 0.62]], r = mulberry32(44), th = range(0, 2 * Math.PI, 72).slice(0, 72);
  const raw = th.map(a => [O[0] + Rr * (M[0][0] * Math.sin(a) + M[0][1] * Math.cos(a)) + 0.4 * gaussian(r), O[1] + Rr * (M[1][0] * Math.sin(a) + M[1][1] * Math.cos(a)) + 0.4 * gaussian(r)]);
  const dft = (arr, m) => { let re = 0, im = 0; arr.forEach((v, k) => { re += v * Math.cos(m * th[k]); im -= v * Math.sin(m * th[k]); }); return Math.hypot(re, im) / arr.length * (m === 0 ? 1 : 2); };
  const bx = raw.map(p => p[0]), harm = [0, 1, 2, 3, 4, 5].map(m => dft(bx, m));
  // fits
  const solve = (A, b) => { const n = b.length, M2 = A.map((r_, i) => r_.concat([b[i]])); for (let i = 0; i < n; i++) { let p = i; for (let k = i + 1; k < n; k++) if (Math.abs(M2[k][i]) > Math.abs(M2[p][i])) p = k; [M2[i], M2[p]] = [M2[p], M2[i]]; for (let k = i + 1; k < n; k++) { const f = M2[k][i] / M2[i][i]; for (let j = i; j <= n; j++) M2[k][j] -= f * M2[i][j]; } } const x = new Array(n); for (let i = n - 1; i >= 0; i--) { x[i] = (M2[i][n] - M2[i].slice(i + 1, n).reduce((s, v, j) => s + v * x[i + 1 + j], 0)) / M2[i][i]; } return x; };
  const mx = raw.reduce((s, p) => s + p[0], 0) / raw.length, my = raw.reduce((s, p) => s + p[1], 0) / raw.length;
  const rows = raw.map(p => { const x = p[0] - mx, y = p[1] - my; return [x * x, x * y, y * y, x, y]; });
  const ata = Array.from({ length: 5 }, (_, i) => Array.from({ length: 5 }, (_, j) => rows.reduce((s, r_) => s + r_[i] * r_[j], 0))), atb = Array.from({ length: 5 }, (_, i) => rows.reduce((s, r_) => s + r_[i], 0));
  const [A, B, Cc, D, E] = solve(ata, atb), det = 4 * A * Cc - B * B, cx = (B * E - 2 * Cc * D) / det, cy = (B * D - 2 * A * E) / det, k = 1 + A * cx * cx + B * cx * cy + Cc * cy * cy;
  const m11 = A / k, m12 = B / 2 / k, m22 = Cc / k, tr = m11 + m22, ds = Math.sqrt(((m11 - m22) / 2) ** 2 + m12 * m12), l1 = tr / 2 + ds, l2 = tr / 2 - ds, ang = 0.5 * Math.atan2(2 * m12, m11 - m22), cs = Math.cos(ang), sn = Math.sin(ang), s1 = Math.sqrt(l1), s2 = Math.sqrt(l2), sc = 1 / Math.sqrt(s1 * s2);
  const W = [[(cs * cs * s1 + sn * sn * s2) * sc, cs * sn * (s1 - s2) * sc], [cs * sn * (s1 - s2) * sc, (sn * sn * s1 + cs * cs * s2) * sc]], ctr = [cx + mx, cy + my];
  const cor = raw.map(p => [W[0][0] * (p[0] - ctr[0]) + W[0][1] * (p[1] - ctr[1]), W[1][0] * (p[0] - ctr[0]) + W[1][1] * (p[1] - ctr[1])]);
  // heading error of the raw compass over a fine grid, and its harmonics
  const g = range(0, 2 * Math.PI, 360).slice(0, 360), wrap = a => Math.atan2(Math.sin(a), Math.cos(a)), err = g.map(a => { const p = [O[0] + Rr * (M[0][0] * Math.sin(a) + M[0][1] * Math.cos(a)), O[1] + Rr * (M[1][0] * Math.sin(a) + M[1][1] * Math.cos(a))]; return wrap(Math.atan2(p[0], p[1]) - a); });
  const eh = m => { let a = 0, b = 0; err.forEach((v, i) => { a += v * Math.cos(m * g[i]); b += v * Math.sin(m * g[i]); }); return [2 * a / g.length, 2 * b / g.length]; }, e1 = eh(1), e2 = eh(2);
  return { O, Rr, M, th, raw, harm, cor, ctr, W, g, err, e1, e2, maxErr: Math.max(...err.map(Math.abs)) * 180 / Math.PI };
})();
fscene({
  title: 'Компас: гармонічний аналіз і метод найменших квадратів', short: 'Компас', question: 'Чому спотворене поле — це еліпс, і як за кілька градусів повернутись до кола?', T: 88,
  beats: [
    [3.4, 'Компас у машині спотворений, але спотворення сталі в системі телефона. Тому поле як функція азимута має дуже просту структуру: це ряд Фур’є лише з нульовою та першою гармоніками.'],
    [14, 'Нульова гармоніка — стале зміщення o: так зване тверде залізо, магніти й намагнічені деталі. Перша — еліпс; його форму задає матриця M, це м’яке залізо.'],
    [24, 'Це перевірна гіпотеза. Беремо відліки поля за азимутом і обчислюємо дискретне перетворення Фур’є: нульова та перша гармоніки значні, друга й вищі — на рівні шуму. Велика друга гармоніка означала б, що модель не підходить, наприклад поруч трамвай.'],
    [40, 'Що спотворення робить з курсом? Сирий азимут — арктангенс відношення компонент. Похибка містить першу гармоніку від зсуву, порядку відношення o до R, і другу від еліптичності, порядку арксинуса одиниця мінус q, поділити на одиниця плюс q.'],
    [54, 'Виправлення — метод найменших квадратів. Коло за Касою лінійне за параметрами: розв’язуємо нормальні рівняння три на три. Еліпс — конічна форма з п’ятьма параметрами, теж лінійна задача.'],
    [66, 'Корінь квадратний із матриці еліпса, через власні числа, перетворює еліпс на коло: точки переносимо в центр і стискаємо. Азимут після цього — кут від центру.'],
    [77, 'Залишається стала різниця між кутом поля й курсом автомобіля. Її знаходять за курсом GNSS на прямолінійних ділянках або за поздовжньою віссю та магнітним схиленням.'],
  ],
  build() {
    const dynFit = t => { const u = smooth(ramp(t, 66, 78)), pts = MG.raw.map((p, i) => [lerp(p[0], MG.cor[i][0] + 0, u) , lerp(p[1], MG.cor[i][1], u)]);
      const ctr = [lerp(MG.ctr[0], 0, u), lerp(MG.ctr[1], 0, u)];
      const raw = MG.th.map(a => [MG.O[0] + MG.Rr * (MG.M[0][0] * Math.sin(a) + MG.M[0][1] * Math.cos(a)), MG.O[1] + MG.Rr * (MG.M[1][0] * Math.sin(a) + MG.M[1][1] * Math.cos(a))]);
      return { data: [{ x: pts.map(p => p[0]), y: pts.map(p => p[1]), mode: 'markers', marker: { size: 6, color: u > 0.5 ? FC.green : FC.teal } },
        { x: raw.map((p, i) => lerp(p[0], MG.Rr * Math.sin(MG.th[i]), u)).concat([raw[0][0] * (1 - u) + 0 * u]), y: raw.map((p, i) => lerp(p[1], MG.Rr * Math.cos(MG.th[i]), u)).concat([raw[0][1] * (1 - u) + MG.Rr * u]), mode: 'lines', line: { color: '#fff', width: 1.2, dash: 'dot' } },
        { x: [ctr[0]], y: [ctr[1]], mode: 'markers', marker: { size: 11, color: FC.yellow, symbol: 'cross' } }],
        layout: { title: u < 0.02 ? 'відліки поля в системі телефона, µT' : 'після виправлення W·(p − c)', xaxis: { range: [-45, 45], zeroline: true }, yaxis: { range: [-40, 40], scaleanchor: 'x', zeroline: true } } }; };
    const harm = { data: [{ x: [0, 1, 2, 3, 4, 5], y: MG.harm, type: 'bar', marker: { color: MG.harm.map((_, m) => m < 2 ? FC.teal : '#55639a') } }],
      layout: { title: 'гармоніки B_x(θ): |X_m|, µT', xaxis: { title: { text: 'm' }, dtick: 1 }, yaxis: { range: [0, 30] }, annotations: [labelAnn(0, MG.harm[0] + 2.5, `${fxt(MG.harm[0], 1)}`, FC.teal, { size: 11 }), labelAnn(1, MG.harm[1] + 2.5, `${fxt(MG.harm[1], 1)}`, FC.teal, { size: 11 }), labelAnn(3, 4, 'шум', '#aab3cc', { size: 11 })] } };
    const dg = MG.g.map(a => a * 180 / Math.PI), er = { data: [{ x: dg, y: MG.err.map(e => e * 180 / Math.PI), mode: 'lines', line: { color: FC.red, width: 3 } },
      { x: dg, y: dg.map((_, i) => (MG.e1[0] * Math.cos(MG.g[i]) + MG.e1[1] * Math.sin(MG.g[i])) * 180 / Math.PI), mode: 'lines', line: { color: FC.orange, width: 1.5, dash: 'dot' } },
      { x: dg, y: dg.map((_, i) => (MG.e2[0] * Math.cos(2 * MG.g[i]) + MG.e2[1] * Math.sin(2 * MG.g[i])) * 180 / Math.PI), mode: 'lines', line: { color: FC.yellow, width: 1.5, dash: 'dot' } }],
      layout: { title: `похибка сирого курсу, °: до ±${Math.round(MG.maxErr)}°`, xaxis: { title: { text: 'θ, °' }, range: [0, 360], dtick: 90 }, yaxis: { range: [-45, 45] }, annotations: [labelAnn(300, 36, '1-а гармоніка: тверде залізо', FC.orange, { size: 11 }), labelAnn(120, -40, '2-а гармоніка: м’яке залізо', FC.yellow, { size: 11 })] } };
    return [
      TX(String.raw`\mathbf B(\theta)=\mathbf o+R\,M\begin{pmatrix}\sin\theta\\ \cos\theta\end{pmatrix}`, 36, 24, { size: 21, t0: 4.5, t1: 55 }),
      TX(String.raw`B_x(\theta)=\underbrace{o_x}_{m=0}+\underbrace{a_x\sin\theta+b_x\cos\theta}_{m=1}`, 36, 86, { size: 19, t0: 15, t1: 55 }),
      TX(String.raw`X_m=\frac{2}{N}\sum_{k}B_x(\theta_k)\,e^{-jm\theta_k}`, 36, 150, { size: 19, t0: 25, t1: 55, color: 'teal' }),
      TX(String.raw`\alpha=\mathrm{atan2}(B_x,B_y),\qquad e(\theta)=\alpha-\theta`, 36, 214, { size: 18, t0: 41 }),
      TX(String.raw`e\approx\frac{|\mathbf o|}{R}\sin(\theta-\varphi)+\frac{1-q}{1+q}\sin 2\theta`, 36, 256, { size: 18, t0: 46, color: 'orange' }),
      TX(String.raw`x^2+y^2=2ax+2by+c\ \Rightarrow\ A^{\top}A\,\mathbf p=A^{\top}\mathbf d`, 36, 24, { size: 17, t0: 55.5, color: 'blue' }),
      TX(String.raw`r^2=c+a^2+b^2`, 36, 60, { size: 17, t0: 58, color: 'blue' }),
      TX(String.raw`Ax^2+Bxy+Cy^2+Dx+Ey=1`, 36, 100, { size: 17, t0: 61, color: 'green' }),
      TX(String.raw`W=\Big(\tfrac{M_e}{k}\Big)^{1/2},\qquad \mathbf p_c=W(\mathbf p-\mathbf c)`, 36, 140, { size: 18, t0: 66.5, color: 'green' }),
      TX(`e_{\\max}\\approx${Math.round(MG.maxErr)}^{\\circ}\\ \\to\\ \\pm3^{\\circ}`, 36, 330, { size: 21, t0: 70, box: true }),
      PL(480, 24, 220, 200, Object.assign({ t0: 26 }, harm)), PL(710, 24, 230, 200, { dyn: dynFit, t0: 55 }), PL(480, 232, 460, 190, Object.assign({ t0: 41 }, er)),
    ];
  },
});
