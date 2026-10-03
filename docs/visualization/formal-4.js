'use strict';
/* Formal video, scenes 10–12: the heading bank (Gaussian sum), the road HMM, calibration of the claimed uncertainty. */

/* ============ 10. Heading bank ============ */
const BK = (() => {
  const drive = makeDrive(7), TRr = drive.truth, re = [0], rn = [0]; let psi = 0;
  for (let i = 1; i < drive.N; i++) { psi -= (drive.sens.gyro[i] - 0.0033) * DT; re.push(re[i - 1] + drive.sens.obd[i] * DT * Math.sin(psi)); rn.push(rn[i - 1] + drive.sens.obd[i] * DT * Math.cos(psi)); }
  const start = [TRr.x[0] + 14, TRr.y[0] - 22], hyp = Array.from({ length: 12 }, (_, j) => (7 + 30 * j) * Math.PI / 180);
  const pos = (j, i) => { const c = Math.cos(hyp[j]), s = Math.sin(hyp[j]); return [start[0] + re[i] * c + rn[i] * s, start[1] - re[i] * s + rn[i] * c]; };
  const fixes = []; drive.sens.net.forEach((f, i) => { if (f && !f.bad && i * DT > 10) fixes.push({ ts: i * DT, i, e: f.e, n: f.n, hAcc: f.hAcc }); });
  let ell = new Array(12).fill(0); const steps = [{ ts: 0, w: new Array(12).fill(1 / 12), ell: ell.slice(), mix: null, sd: null, dist: 0, nis: new Array(12).fill(0) }];
  fixes.forEach(fx_ => {
    const dist = TRr.s[fx_.i], s2 = (1.5 * fx_.hAcc / 1.51) ** 2 + (0.08 * dist) ** 2, nis = hyp.map((_, j) => { const p = pos(j, fx_.i); return ((p[0] - fx_.e) ** 2 + (p[1] - fx_.n) ** 2) / s2; });
    ell = ell.map((l, j) => l - 0.5 * nis[j] - Math.log(s2));      // −½ ν − ½ ln det S, with S = s²I₂
    const mx = Math.max(...ell); let w = ell.map(l => Math.max(1e-4, Math.exp(l - mx))); const sm = w.reduce((a, b) => a + b, 0); w = w.map(x => x / sm);
    let se = 0, sc = 0; w.forEach((x, j) => { se += x * Math.sin(hyp[j]); sc += x * Math.cos(hyp[j]); }); const mix = Math.atan2(se, sc), wr = a => Math.atan2(Math.sin(a), Math.cos(a));
    const sd = Math.sqrt(w.reduce((a, x, j) => a + x * wr(hyp[j] - mix) ** 2, 0) + (5 * Math.PI / 180) ** 2) * 180 / Math.PI;
    steps.push({ ts: fx_.ts, w, ell: ell.slice(), mix, sd, dist, nis });
  });
  return { drive, re, rn, start, hyp, pos, fixes, steps, TR: TRr };
})();
const BK_T = [23, 44, 50, 56, 62, 68];     // scene time at which fix 0..4 is processed (index 0 = start)
fscene({
  title: 'Банк напрямків: суміш гаусіанів', short: 'Банк', question: 'Як оцінити абсолютний азимут, коли немає ні GPS, ні компаса?', T: 86,
  beats: [
    [3.4, 'Коли абсолютний азимут невідомий, його щільність подають сумішшю гаусіан: дванадцять гіпотез, рівномірно по колу, кожна зі своєю вагою.'],
    [13.5, 'Кожна гіпотеза — маленький фільтр Калмана над координатами й азимутом: він інтегрує ті самі повороти гіроскопа та швидкість, але від свого початкового кута.'],
    [24, 'Коли приходить груба мережева точка, кожна гіпотеза обчислює свою інновацію та її коваріацію S. Вага оновлюється на мінус півквадратичної форми мінус пів логарифма детермінанта S — це логарифм гаусової правдоподібності без сталої.'],
    [37, 'Нормування — через логарифм суми експонент: віднімаємо максимум, щоб не було переповнення. Вагу знизу обмежено однією десятитисячною від найкращої, щоб гіпотеза могла відновитись.'],
    [46, 'Подивіться, як ваги змінюються з приходом точок. Хибні напрямки швидко гаснуть: їхній шлях проходить за сотні метрів від точки, а сигма — кілька десятків метрів.'],
    [58, 'Азимут суміші — це кругове середнє: не середнє кутів, а арктангенс зваженої суми синусів і косинусів. Дисперсія враховує розкид гіпотез навколо середнього.'],
    [70, 'Коли сигма менша за п’ятнадцять градусів, враховано щонайменше чотири точки і проїхано триста метрів, азимут передають основному фільтру разом з позицією та спільною коваріацією.'],
  ],
  build() {
    const stepAt = t => { let k = 0; BK_T.forEach((tt, i) => { if (t >= tt) k = i; }); const nxt = BK_T[k + 1], u = nxt === undefined ? 1 : smooth(ramp(t, BK_T[k], BK_T[k] + 2.5)); const A = BK.steps[k], B = BK.steps[Math.min(BK.steps.length - 1, k)], P = k > 0 ? BK.steps[k - 1] : null; return { k, A, w: P ? lerpArr(P.w, A.w, u) : A.w }; };
    const lerpArr = (a, b, u) => a.map((v, i) => v + (b[i] - v) * u);
    const tsAt = t => { for (let i = 1; i < BK_T.length; i++) if (t <= BK_T[i]) return lerp(BK.steps[i - 1].ts, BK.steps[i].ts, ramp(t, BK_T[i - 1], BK_T[i])); return BK.steps[BK.steps.length - 1].ts; };
    const hue = j => `hsl(${j * 30},75%,62%)`, deg = BK.hyp.map(h => h * 180 / Math.PI);
    const polar = t => { const s = stepAt(t); return { data: [{ type: 'barpolar', theta: deg, r: s.w, width: 24, marker: { color: BK.hyp.map((_, j) => hue(j)), line: { color: '#0b101b', width: 1 } }, hoverinfo: 'skip' },
        { type: 'scatterpolar', mode: 'lines', theta: [90, 90], r: [0, 1], line: { color: '#fff', width: 1.5, dash: 'dot' } }].concat(s.A.mix !== null ? [{ type: 'scatterpolar', mode: 'lines', theta: [s.A.mix * 180 / Math.PI, s.A.mix * 180 / Math.PI], r: [0, 1], line: { color: FC.yellow, width: 4 } }] : []),
        layout: { title: `ваги гіпотез після ${s.k} точок(-ки); білий — істинний азимут, жовтий — суміш`, margin: { l: 24, r: 24, t: 40, b: 14 }, polar: { bgcolor: 'rgba(0,0,0,0)', angularaxis: { rotation: 90, direction: 'clockwise', tickfont: { size: 10, color: '#aab3cc' }, gridcolor: FC.grid }, radialaxis: { range: [0, 1], showticklabels: false, gridcolor: FC.grid } } } }; };
    const lw = { data: BK.hyp.map((_, j) => ({ x: [0, 1, 2, 3, 4, 5], y: BK.steps.slice(0, 6).map(s => s.ell[j] - Math.max(...s.ell)), mode: 'lines+markers', line: { color: hue(j), width: 2 }, marker: { size: 5 } })),
      layout: { title: 'ln w_j − max, після кожної точки', xaxis: { title: { text: 'номер точки' }, dtick: 1 }, yaxis: { range: [-120, 2], title: { text: 'ln w' } } } };
    const mapDyn = t => { const ts = tsAt(t), i = Math.floor(clampN(ts / DT, 0, BK.drive.N - 1)), s = stepAt(t);
      const tr = [], truth = []; for (let k = 0; k <= i; k += 6) truth.push([BK.TR.x[k], BK.TR.y[k]]);
      const data = [{ x: truth.map(p => p[0]), y: truth.map(p => p[1]), mode: 'lines', line: { color: '#fff', width: 1.5 }, opacity: 0.5 }];
      BK.hyp.forEach((_, j) => { const pts = []; for (let k = 0; k <= i; k += 6) pts.push(BK.pos(j, k)); data.push({ x: pts.map(p => p[0]), y: pts.map(p => p[1]), mode: 'lines', line: { color: hue(j), width: 1 + 4 * s.w[j] }, opacity: 0.15 + 0.85 * Math.sqrt(s.w[j]) }); });
      BK.fixes.forEach(f => { if (f.ts <= ts) data.push({ x: [f.e], y: [f.n], mode: 'markers', marker: { size: 12, color: 'rgba(181,140,240,.35)', line: { color: FC.purple, width: 2 } } }); });
      return { data, layout: { title: 'шляхи гіпотез і мережеві точки', margin: { l: 36, r: 8, t: 26, b: 28 }, xaxis: { range: [-470, 340], title: { text: '' } }, yaxis: { range: [-170, 120], scaleanchor: 'x' } } }; };
    return [
      TX(String.raw`p(\psi_0\mid z_{1:k})=\sum_{j=1}^{12}w_j\,\mathrm{N}(\psi_j,\sigma_j^2),\quad \psi_j=\psi_{(0)}+\tfrac{2\pi j}{12}`, 36, 24, { size: 17, t0: 4.5 }),
      TX(String.raw`\mathbf y_j=z-\hat{\mathbf p}_j,\qquad S_j=H P_j H^{\top}+R`, 36, 82, { size: 18, t0: 14.5 }),
      TX(String.raw`\ln w_j\ \mathrel{+}=\ -\tfrac12\,\mathbf y_j^{\top}S_j^{-1}\mathbf y_j-\tfrac12\ln\det S_j`, 36, 132, { size: 19, t0: 25, color: 'blue', box: true }),
      TX(String.raw`w_j=\frac{e^{\ell_j-\ell_{\max}}}{\sum_i e^{\ell_i-\ell_{\max}}},\qquad w_j\ge 10^{-4}`, 36, 208, { size: 19, t0: 38 }),
      TX(String.raw`\bar\psi=\mathrm{atan2}\Big(\sum_j w_j\sin\psi_j,\ \sum_j w_j\cos\psi_j\Big)`, 36, 280, { size: 18, t0: 59, color: 'yellow' }),
      TX(String.raw`\sigma_\psi^2=\sum_j w_j\big(P_{\psi\psi,j}+\mathrm{wrap}(\psi_j-\bar\psi)^2\big)`, 36, 336, { size: 18, t0: 62, color: 'yellow' }),
      TX(String.raw`\sigma_\psi\le 15^{\circ},\ \ k\ge 4\ \text{точок},\ \ \ge 300\ \text{м}\ \Rightarrow\ \text{передати в EKF}`, 36, 388, { size: 16, t0: 71, color: 'green', box: true }),
      PL(470, 24, 230, 215, { dyn: polar, t0: 44 }), PL(710, 24, 230, 215, Object.assign({ t0: 47 }, lw)), PL(470, 246, 470, 175, { dyn: mapDyn, t0: 23 }),
    ];
  },
});

/* ============ 11. Road matcher: HMM ============ */
const HM = (() => {
  const A = [[0, 0], [150, 0], [150, 200]], B = [[0, -30], [300, -30]], N = 30, step = 10, sp = 20 * 0 + 1, sPos = 20, sr2 = 3.5 * 3.5 / 3 + 16, sPsi = 8 * Math.PI / 180, s0 = 10 * Math.PI / 180;
  const tr = [], est = [], hd = [], hdE = [];
  for (let k = 0; k <= N; k++) { const d = k * step; if (d <= 150) { tr.push([d, 0]); hd.push(Math.PI / 2); } else { tr.push([150, d - 150]); hd.push(0); } est.push([tr[k][0] - 8, tr[k][1] - 15 + 3 * Math.sin(k / 3)]); hdE.push(hd[k]); }
  const dist = (P, q) => projectOnPolyline(P, q[0], q[1]);
  const Tm = [[0.975, 0.005, 0.02], [0.005, 0.975, 0.02], [0.025, 0.025, 0.95]];        // A, B, off-road
  let a = [1 / 3, 1 / 3, 1 / 3]; const hist = [a.slice()], ll = [];
  const wrap = x => Math.atan2(Math.sin(x), Math.cos(x));
  for (let k = 1; k <= N; k++) {
    const pa = dist(A, est[k]), pb = dist(B, est[k]), turn = k > 15 ? Math.PI / 2 * 0 : 0;
    const dth = (brg) => Math.min(Math.abs(wrap(hdE[k] - brg)), Math.abs(wrap(hdE[k] - brg - Math.PI)));
    const lA = -0.5 * pa.d ** 2 / (sPos ** 2 + sr2) - 0.5 * dth(pa.brg) ** 2 / (sPsi ** 2 + s0 ** 2), lB = -0.5 * pb.d ** 2 / (sPos ** 2 + sr2) - 0.5 * dth(pb.brg) ** 2 / (sPsi ** 2 + s0 ** 2), lO = -2.0;
    const pred = [0, 1, 2].map(j => Tm[0][j] * a[0] + Tm[1][j] * a[1] + Tm[2][j] * a[2]), un = [pred[0] * Math.exp(lA), pred[1] * Math.exp(lB), pred[2] * Math.exp(lO)], s = un[0] + un[1] + un[2];
    a = un.map(x => x / s); hist.push(a.slice()); ll.push([lA, lB, lO]);
  }
  return { A, B, N, tr, est, hist, Tm, ll };
})();
fscene({
  title: 'Матчер доріг: прихована марковська модель', short: 'Дорога', question: 'Як за повільним шляхом з великою похибкою зрозуміти, на якій ми вулиці?', T: 90,
  beats: [
    [3.4, 'Карту вводять через приховану марковську модель. Стани — вулиці, на яких ми можемо бути, плюс стан «поза дорогою». Спостереження — поза оцінки кожні десять метрів проїзду.'],
    [14, 'Алгоритм прямого ходу: нова ймовірність стану — правдоподібність спостереження, помножена на суму за попередніми станами: перехід на попередню ймовірність. Потім нормування.'],
    [26, 'Правдоподібність спостереження — гаусова за відстанню до вулиці, з дисперсією, що складає невизначеність нашої позиції та ширину дороги, і гаусова за різницею азимутів.'],
    [38, 'Перехід — матриця: вулиця лишається вулицею з імовірністю майже один, іноді ми виходимо з дороги, іноді повертаємось. Оцінка проходить по графу, тож довжина шляху й кут повороту теж входять у перехід.'],
    [50, 'Подивіться на дві паралельні вулиці за тридцять метрів. Наша похибка зсуває оцінку на півдорозі між ними, тож ймовірності близькі. Вулиця A повертає, B — ні, і азимут оцінки повертає: B одразу втрачає ймовірність. Під час самого повороту ймовірність ненадовго перетікає в стан «поза дорогою», а потім вулиця A повертає собі майже все.'],
    [66, 'Лише коли найкраща вулиця має щонайменше дев’яносто відсотків, вона створює псевдовимірювання: проєкцію позиції на нормаль до вулиці з дисперсією дороги.'],
    [77, 'Умова пропуску: якщо наша поперечна дисперсія вже менша за дисперсію дороги, не оновлюємо. Інакше ту саму інформацію зарахували б багато разів, і хмара стала б нечесно малою.'],
  ],
  build() {
    const kAt = t => Math.max(0, Math.min(HM.N, Math.floor((t - 44) / 20 * HM.N)));
    const dyn = t => { const k = t < 44 ? 0 : kAt(t), pt = HM.tr.slice(0, k + 1), pe = HM.est.slice(0, k + 1);
      return { data: [{ x: HM.A.map(p => p[0]), y: HM.A.map(p => p[1]), mode: 'lines', line: { color: FC.blue, width: 8 }, opacity: 0.35 }, { x: HM.B.map(p => p[0]), y: HM.B.map(p => p[1]), mode: 'lines', line: { color: FC.orange, width: 8 }, opacity: 0.35 },
        { x: pt.map(p => p[0]), y: pt.map(p => p[1]), mode: 'lines', line: { color: '#fff', width: 2 } }, { x: pe.map(p => p[0]), y: pe.map(p => p[1]), mode: 'markers', marker: { size: 6, color: FC.yellow } }],
        layout: { title: 'вулиця A (синя), B (оранжева); істина (біла), оцінка (жовті)', xaxis: { range: [-20, 320] }, yaxis: { range: [-60, 220], scaleanchor: 'x' }, annotations: [labelAnn(220, -12, 'B: вулиця прямо', FC.orange, { size: 11 }), labelAnn(185, 150, 'A: повертає', FC.blue, { size: 11, extra: { xanchor: 'left' } })] } }; };
    const dyn2 = t => { const k = t < 44 ? 0 : kAt(t), xs = range(0, k, k).map(Math.round); const ser = j => HM.hist.slice(0, k + 1).map(h => h[j]);
      return { data: [{ x: xs, y: ser(0), mode: 'lines', line: { color: FC.blue, width: 3 } }, { x: xs, y: ser(1), mode: 'lines', line: { color: FC.orange, width: 3 } }, { x: xs, y: ser(2), mode: 'lines', line: { color: FC.red, width: 2, dash: 'dot' } },
        { x: [0, HM.N], y: [0.9, 0.9], mode: 'lines', line: { color: '#fff', width: 1, dash: 'dot' } }], layout: { title: 'α_t(j): A, B, поза дорогою', xaxis: { range: [0, HM.N], title: { text: 'крок (10 м)' } }, yaxis: { range: [0, 1.02] }, annotations: [labelAnn(2, 0.93, 'поріг 90%', '#fff', { size: 10, extra: { xanchor: 'left' } })] } }; };
    return [
      TX(String.raw`\alpha_t(j)\ \propto\ b_j(o_t)\sum_{i}a_{ij}\,\alpha_{t-1}(i)`, 36, 24, { size: 21, t0: 14.5, color: 'blue' }),
      TX(String.raw`\ln b_j=-\frac{d_j^2}{2(\sigma_p^2+\sigma_r^2)}-\frac{\Delta\theta_j^2}{2(\sigma_\psi^2+\sigma_0^2)}`, 36, 78, { size: 18, t0: 27 }),
      HT('σ<sub>r</sub>² = w²/3 + 4² (ширина дороги), σ<sub>0</sub> = 10°, для «поза дорогою» ln b = −2', 36, 150, 430, { t0: 31, size: 13 }),
      TX(String.raw`A=\begin{bmatrix}.975&.005&.020\\ .005&.975&.020\\ .025&.025&.950\end{bmatrix}`, 36, 196, { size: 17, t0: 39 }),
      TX(String.raw`a_{ij}\propto e^{-|\ell_{ij}-s|/\beta}\,e^{-(\Delta\psi-\Delta\beta_{ij})^2/2\sigma_\Delta^2}`, 36, 280, { size: 16, t0: 42, color: 'purple' }),
      TX(String.raw`z=\mathbf n^{\top}\mathbf q,\quad H=\big[\mathbf n^{\top}\ 0\ \cdots\big],\quad R=\tfrac{w^2}{3}+4^2`, 36, 330, { size: 17, t0: 67, color: 'orange' }),
      TX(String.raw`\mathbf n^{\top}P\,\mathbf n<R\ \Rightarrow\ \text{пропустити}`, 36, 376, { size: 18, t0: 78, box: true }),
      PL(480, 24, 460, 200, { dyn, t0: 44 }), PL(480, 232, 460, 190, { dyn: dyn2, t0: 44 }),
    ];
  },
});

/* ============ 12. Honest uncertainty ============ */
const CAL = (() => {
  const r = mulberry32(77), M = 20000, nu = Array.from({ length: M }, () => { const a = gaussian(r), b = gaussian(r); return a * a + b * b; });
  const cover = f => nu.filter(v => v <= (K68 * f) ** 2 + 0 * f).length / M;     // claimed radius K68·σ_claimed, real σ = f·σ_claimed
  const cover2 = f => nu.filter(v => v * f * f <= K68 ** 2).length / M;
  return { nu, cover2 };
})();
fscene({
  title: 'Чесність: що означає «радіус 68%»', short: 'Чесність', question: 'Звідки береться 1,5096 — і як перевірити, що заявлена похибка чесна?', T: 92,
  beats: [
    [3.4, 'Остання тема — чесність. Радіус 68 відсотків не довільне число. Для двовимірної ізотропної гаусіани квадрат радіуса, поділений на сигма в квадраті, має розподіл хі-квадрат із двома ступенями свободи.'],
    [15, 'Отже, ймовірність потрапити в коло радіуса k сигма дорівнює одиниця мінус експонента мінус k у квадраті на два. Розв’язавши, маємо k для заданої ймовірності: для шістдесяти восьми відсотків це одна ціла п’ять тисяч дев’яносто шість, для дев’яноста п’яти — дві цілих чотири тисячі чотириста сімдесят сім.'],
    [34, 'Для еліпса те саме, тільки відстань — Махаланобіса: контур хмари — лінія рівної відстані Махаланобіса, а k лишається тим самим.'],
    [44, 'Перевірка чесності — NEES: квадратична форма реальної похибки з оберненою коваріацією. У чесному фільтрі вона має розподіл хі-квадрат з n ступенями свободи, а усереднення за N прогонами дає вузькі межі.'],
    [58, 'Якщо справжня похибка в f разів більша за заявлену, покриття падає. За f рівного двом заявлені шістдесят вісім відсотків перетворюються на двадцять п’ять; а за f рівного одній другій — на дев’яносто дев’ять. Ось чому занижена коваріація небезпечніша за завищену.'],
    [74, 'Підсумок алгоритму. Передбачення за гіроскопом і швидкістю. Кожне вимірювання проходить хі-квадрат-ворота і робастне оновлення за Джозефом. Дорога й компас — додаткові псевдовимірювання.'],
    [85, 'Результат — середнє й коваріація, чесно виражені в радіусах шістдесят вісім та дев’яносто п’ять відсотків.'],
  ],
  build() {
    const xs = range(0, 12, 240), k68 = K68 * K68, k95 = K95 * K95;
    const bins = 60, mx = 12, cnt = new Array(bins).fill(0); CAL.nu.forEach(v => { if (v < mx) cnt[Math.floor(v / mx * bins)]++; });
    const hist = { data: [{ x: range(0, mx, bins).slice(0, bins).map(v => v + mx / bins / 2), y: cnt.map(c => c / CAL.nu.length / (mx / bins)), type: 'bar', marker: { color: 'rgba(88,196,221,.45)' } },
      { x: xs, y: xs.map(v => chi2pdf(v, 2)), mode: 'lines', line: { color: FC.yellow, width: 3 } }, { x: [k68, k68], y: [0, 0.55], mode: 'lines', line: { color: FC.green, width: 2, dash: 'dot' } }, { x: [k95, k95], y: [0, 0.55], mode: 'lines', line: { color: FC.orange, width: 2, dash: 'dot' } }],
      layout: { title: 'r²/σ²: 20 000 вибірок проти χ²₂', xaxis: { range: [0, 12], title: { text: 'r² / σ²' } }, yaxis: { range: [0, 0.55] }, bargap: 0,
        annotations: [labelAnn(k68, 0.52, `k₆₈² = ${fxt(k68, 2)}`, FC.green, { size: 11, extra: { xanchor: 'left', xshift: 4 } }), labelAnn(k95, 0.43, `k₉₅² = ${fxt(k95, 2)}`, FC.orange, { size: 11, extra: { xanchor: 'left', xshift: 4 } })] } };
    const fs = range(0.4, 3, 130), fp = [0.5, 1, 1.5, 2, 3];
    const cov = { data: [{ x: fs, y: fs.map(f => 1 - Math.exp(-K68 * K68 / (2 * f * f))), mode: 'lines', line: { color: FC.blue, width: 3 } }, { x: fp, y: fp.map(f => CAL.cover2(f)), mode: 'markers', marker: { color: FC.yellow, size: 9, symbol: 'circle-open', line: { width: 2 } } },
      { x: [0.4, 3], y: [0.68, 0.68], mode: 'lines', line: { color: '#fff', width: 1, dash: 'dot' } }],
      layout: { title: 'реальне покриття «кола 68%»', xaxis: { title: { text: 'f = σ реальна / σ заявлена' }, range: [0.4, 3] }, yaxis: { range: [0, 1.02], tickformat: '.0%' },
        annotations: [labelAnn(2, 0.248 + 0.07, '25%', FC.red, { size: 12 }), labelAnn(0.5, 0.95, '99%', FC.green, { size: 12 }), labelAnn(1.0, 0.62, '68%', '#fff', { size: 12 }), labelAnn(2.3, 0.1, 'самовпевнено → покриття падає', FC.red, { size: 11, extra: { xanchor: 'left' } })] } };
    return [
      TX(String.raw`\frac{r^2}{\sigma^2}\sim\chi^2_2\ \Rightarrow\ P(r\le k\sigma)=1-e^{-k^2/2}`, 36, 24, { size: 21, t0: 4.5, t1: 74 }),
      TX(String.raw`k_p=\sqrt{-2\ln(1-p)}`, 36, 80, { size: 20, t0: 16, t1: 74, color: 'green' }),
      TX(String.raw`k_{68}=\sqrt{-2\ln 0{,}32}=${fx(K68, 4)},\quad k_{95}=${fx(K95, 4)}`, 36, 124, { size: 19, t0: 24, t1: 74, color: 'green' }),
      TX(String.raw`(\mathbf x-\hat{\mathbf x})^{\top}P^{-1}(\mathbf x-\hat{\mathbf x})\le k^2`, 36, 172, { size: 21, t0: 35, t1: 74 }),
      TX(String.raw`\epsilon=\mathbf e^{\top}P^{-1}\mathbf e\sim\chi^2_n,\qquad N\bar\epsilon\sim\chi^2_{Nn}`, 36, 232, { size: 20, t0: 45, t1: 74, color: 'blue' }),
      TX(String.raw`\mathrm{cover}(f)=1-e^{-k^2/(2f^2)}`, 36, 292, { size: 21, t0: 59, t1: 74, color: 'orange' }),
      PL(480, 24, 460, 200, Object.assign({ t0: 15, t1: 74 }, hist)), PL(480, 232, 460, 190, Object.assign({ t0: 59, t1: 74 }, cov)),
      TX(String.raw`\textbf{1.}\ \ \hat{\mathbf x}^-=f(\hat{\mathbf x}),\quad P^-=FPF^{\top}+Q`, 90, 40, { size: 23, t0: 75 }),
      TX(String.raw`\textbf{2.}\ \ \nu=\mathbf y^{\top}S^{-1}\mathbf y\ \text{у воротах}\ \chi^2\ \Rightarrow\ R\leftarrow R\max(1,\nu/9{,}21)`, 90, 110, { size: 23, t0: 77 }),
      TX(String.raw`\textbf{3.}\ \ \hat{\mathbf x}^+=\hat{\mathbf x}^-+K\mathbf y,\quad P^+=(I-KH)P^-(I-KH)^{\top}+KRK^{\top}`, 90, 180, { size: 23, t0: 79 }),
      TX(String.raw`\textbf{4.}\ \ \text{дорога, компас, ZUPT: рядки}\ H\ \text{і дисперсії}\ R`, 90, 250, { size: 23, t0: 81 }),
      TX(String.raw`\textbf{5.}\ \ r_{68}=1{,}5096\,\sigma,\qquad r_{95}=2{,}4477\,\sigma`, 90, 320, { size: 23, t0: 83, color: 'green', box: true }),
    ];
  },
});
