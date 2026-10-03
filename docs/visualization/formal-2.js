'use strict';
/* Formal video, scenes 4–6: the "up" vector as a complementary filter, EKF prediction, EKF update and χ² gates. */

/* ============ 4. Complementary filter for the up vector ============ */
const CF = (() => {
  const dt = 0.1, N = 6000, tau = 30, b = 5e-4, r = mulberry32(8), th0 = 0.035, ts = [], g = [], gated = [], plain = [], acc = [], dist = [];
  const brake = t => (t >= 100 && t < 110) || (t >= 250 && t < 258) || (t >= 400 && t < 412) || (t >= 520 && t < 526);
  let tg = th0, a1 = th0, a2 = th0;
  for (let i = 0; i < N; i++) {
    const t = i * dt, w = b + 0.002 * gaussian(r), ta = th0 + (brake(t) ? 0.2 : 0) + 0.01 * gaussian(r);
    tg += w * dt; a1 += w * dt; a2 += w * dt;
    a1 += (ta - a1) * dt / tau; if (!brake(t)) a2 += (ta - a2) * dt / tau;
    ts.push(t); g.push(tg); acc.push(ta); plain.push(a1); gated.push(a2);
  }
  return { dt, N, tau, b, th0, ts, g, acc, plain, gated, brake };
})();
fscene({
  title: 'Вектор «вгору»: комплементарний фільтр', short: 'Вертикаль', question: 'Як відділити повороти авто від нахилу телефона, не знаючи, як він закріплений?', T: 92,
  beats: [
    [3.4, 'Задача: знайти напрямок вертикалі в системі координат телефона. Від нього залежить усе: проєкція гіроскопа на вертикаль, тобто швидкість повороту, і горизонтальна частина прискорення.'],
    [13, 'Перше джерело — гіроскоп. Вертикаль у світі нерухома, тож у системі телефона вона обертається в протилежний бік: нове значення — старе мінус векторний добуток кутової швидкості на старе, помножене на крок, і нормування.'],
    [23.5, 'Друге — акселерометр. У спокої він вимірює силу тяжіння, тобто напрямок угору. Коригуємо оцінку до нього з коефіцієнтом крок поділити на тау u, де тау u — тридцять секунд.'],
    [34, 'Це комплементарний фільтр. Для малого кута нахилу оцінка — сума двох частин: низькочастотної за акселерометром і високочастотної за інтегралом гіроскопа. Їхні передавальні функції в сумі дають одиницю.'],
    [46, 'Частота переходу — одна на два пі тау: п’ять мілігерц. Нижче неї дрейф гіроскопа не накопичується, бо довіряємо акселерометру; вище — гальмування й повороти не псують оцінку, бо довіряємо гіроскопу.'],
    [59, 'Подивіться в часі. Гіроскоп сам дає лінійний дрейф. Акселерометр сам — шум і викиди при гальмуванні. Комплементарний фільтр тримає нахил, із сталою похибкою тау помножити на зсув.'],
    [71, 'Але під час гальмування акселерометр бреше: два метри на секунду в квадраті нахиляють уявну вертикаль на дванадцять градусів. Тож корекцію вмикають лише за умов спокою — це нелінійне перемикання підсилення, воно виходить за межі лінійної теорії.'],
    [84, 'Результат на реальних записах: кореляція бокового прискорення з добутком швидкості на швидкість повороту — від 0,97 до 0,98 з нашою вертикаллю проти 0,26–0,31 із вертикаллю Android.'],
  ],
  build() {
    const fr = logspace(1e-4, 1, 300), w = f => 2 * Math.PI * f * CF.tau, fc = 1 / (2 * Math.PI * CF.tau);
    const bode = { data: [
      { x: fr, y: fr.map(f => 1 / Math.hypot(1, w(f))), mode: 'lines', line: { color: FC.blue, width: 3 }, name: 'LP' },
      { x: fr, y: fr.map(f => w(f) / Math.hypot(1, w(f))), mode: 'lines', line: { color: FC.orange, width: 3 }, name: 'HP' },
      { x: [fc, fc], y: [0, 1.05], mode: 'lines', line: { color: '#fff', width: 1.2, dash: 'dot' } }],
      layout: { title: 'амплітуди LP та HP', xaxis: { type: 'log', title: { text: 'f, Гц' } }, yaxis: { range: [0, 1.08], title: { text: '|H|' } },
        annotations: [labelAnn(Math.log10(fc), 0.55, `f = 1/(2πτ) ≈ ${fxt(fc * 1000, 1)} мГц`, '#fff', { size: 11, extra: { xanchor: 'left', xshift: 6 } }), labelAnn(Math.log10(1.5e-4), 0.9, 'акселерометр', FC.blue, { size: 12, extra: { xanchor: 'left' } }), labelAnn(Math.log10(0.35), 0.9, 'гіроскоп', FC.orange, { size: 12 })] } };
    const ds = 5, idx = CF.ts.map((_, i) => i).filter(i => i % ds === 0), T0 = 59, T1 = 78;
    const dyn = t => { const n = Math.max(2, Math.min(idx.length, Math.floor(idx.length * (t - T0) / (T1 - T0))));
      const sel = arr => idx.slice(0, n).map(i => arr[i] * 180 / Math.PI), xs = idx.slice(0, n).map(i => CF.ts[i]);
      return { data: [
        { x: xs, y: sel(CF.acc), mode: 'lines', line: { color: FC.blue, width: 1 }, opacity: 0.55 }, { x: xs, y: sel(CF.g), mode: 'lines', line: { color: FC.red, width: 2 } },
        { x: xs, y: sel(CF.plain), mode: 'lines', line: { color: FC.yellow, width: 2, dash: 'dot' } }, { x: xs, y: sel(CF.gated), mode: 'lines', line: { color: FC.green, width: 3 } }],
        layout: { title: 'нахил, °', xaxis: { range: [0, 600], title: { text: 't, с' } }, yaxis: { range: [-5, 25] },
          annotations: [labelAnn(470, 22, 'гіроскоп сам', FC.red, { size: 11 }), labelAnn(130, 15, 'акселерометр сам', FC.blue, { size: 11 }), labelAnn(400, -3.2, 'коригування лише в спокої', FC.green, { size: 11 }), labelAnn(310, 8.5, 'без «воріт»', FC.yellow, { size: 11 })] } }; };
    return [
      TX(String.raw`\hat{\mathbf u}_k=\frac{\hat{\mathbf u}_{k-1}-(\boldsymbol\omega_k\times\hat{\mathbf u}_{k-1})\,\Delta t}{\big\lVert\cdots\big\rVert}`, 36, 24, { size: 20, t0: 13.5 }),
      TX(String.raw`\hat{\mathbf u}\leftarrow\frac{\hat{\mathbf u}+(\hat{\mathbf f}-\hat{\mathbf u})\,\Delta t/\tau_u}{\big\lVert\cdots\big\rVert},\quad \hat{\mathbf f}=\frac{\mathbf a_{\mathrm{lp}}}{\lVert\mathbf a_{\mathrm{lp}}\rVert},\ \ \tau_u=30\ \text{с}`, 36, 100, { size: 19, t0: 24 }),
      TX(String.raw`\hat\theta=\underbrace{\tfrac{1}{\tau s+1}}_{\mathrm{LP}}\theta_{\mathrm{acc}}+\underbrace{\tfrac{\tau s}{\tau s+1}}_{\mathrm{HP}}\tfrac{\omega}{s},\qquad \mathrm{LP}+\mathrm{HP}=1`, 36, 186, { size: 19, t0: 34.5 }),
      TX(String.raw`f_\times=\frac{1}{2\pi\tau_u}\approx 5{,}3\ \text{мГц}\qquad \delta\theta_\infty=\tau_u\,b`, 36, 258, { size: 20, t0: 47, color: 'blue' }),
      TX(String.raw`\Big|\lVert\mathbf a_{\mathrm{lp}}\rVert-\bar m\Big|<0{,}3\ \tfrac{\mathrm{м}}{\mathrm{c}^2},\quad |\omega_{\mathrm{lp}}|<0{,}05\ \tfrac{\text{рад}}{\mathrm{c}},\quad \lVert\mathbf a_{\mathrm{lp}}^{\perp}\rVert<0{,}5\ \tfrac{\mathrm{м}}{\mathrm{c}^2}`, 36, 312, { size: 17, t0: 72, box: true }),
      HT('«Ворота спокою»: корекція лише коли ‖a‖ близьке до середнього (τ = 120 с), згладжена швидкість повороту мала (τ = 0,5 с) і горизонтальна частина прискорення мала (крім стоянки).', 36, 360, 450, { t0: 74 }),
      PL(500, 24, 440, 185, Object.assign({ t0: 35 }, bode)), PL(500, 220, 440, 195, { dyn, t0: 59 }),
    ];
  },
});

/* ============ 5. EKF prediction ============ */
const PRED = (() => {
  const dt = 0.5, N = 120, v = 12, brg = 40 * Math.PI / 180, qp = 0.3, qpsi = 0.01, qv = 0.7, sb = 5e-4, M = 400, r = mulberry32(13), g = () => gaussian(r);
  // analytic: P over [e, n, psi, v, b] propagated with F, Q
  let P = [[9, 0, 0, 0, 0], [0, 9, 0, 0, 0], [0, 0, (2 * Math.PI / 180) ** 2, 0, 0], [0, 0, 0, 0.25, 0], [0, 0, 0, 0, sb * sb]];
  const Pt = [P.map(r_ => r_.slice())], mm = (A, B) => A.map((row, i) => B[0].map((_, j) => row.reduce((s, a, k) => s + a * B[k][j], 0))), tr = A => A[0].map((_, j) => A.map(rw => rw[j]));
  for (let k = 1; k <= N; k++) {
    const F = [[1, 0, v * Math.cos(brg) * dt, Math.sin(brg) * dt, 0], [0, 1, -v * Math.sin(brg) * dt, Math.cos(brg) * dt, 0], [0, 0, 1, 0, dt], [0, 0, 0, 1, 0], [0, 0, 0, 0, 1]];
    P = mm(mm(F, P), tr(F)); P[0][0] += qp * qp * dt; P[1][1] += qp * qp * dt; P[2][2] += qpsi * qpsi * dt; P[3][3] += qv * qv * dt; Pt.push(P.map(r_ => r_.slice()));
  }
  // Monte Carlo through the nonlinear model
  const part = Array.from({ length: M }, () => ({ e: 3 * g(), n: 3 * g(), psi: brg + 2 * Math.PI / 180 * g(), v: v + 0.5 * g(), b: sb * g() })), traj = [part.map(p => [p.e, p.n])];
  for (let k = 1; k <= N; k++) {
    part.forEach(p => { p.psi += p.b * dt + qpsi * Math.sqrt(dt) * g(); p.v += qv * Math.sqrt(dt) * g(); p.e += p.v * Math.sin(p.psi) * dt + qp * Math.sqrt(dt) * g(); p.n += p.v * Math.cos(p.psi) * dt + qp * Math.sqrt(dt) * g(); });
    traj.push(part.map(p => [p.e, p.n]));
  }
  // along/cross standard deviations from the analytic P and the cloud
  const ts = range(0, N * dt, N), sd = (k, ang) => { const u = [Math.sin(ang), Math.cos(ang)], c = traj[k], mean = [0, 1].map(j => c.reduce((s, p) => s + p[j], 0) / M), x = c.map(p => (p[0] - mean[0]) * u[0] + (p[1] - mean[1]) * u[1]); return Math.sqrt(x.reduce((s, a) => s + a * a, 0) / M); };
  const covOf = k => Pt[k], proj = (Pk, ang) => { const u = [Math.sin(ang), Math.cos(ang)]; return Math.sqrt(u[0] * u[0] * Pk[0][0] + 2 * u[0] * u[1] * Pk[0][1] + u[1] * u[1] * Pk[1][1]); };
  return { dt, N, v, brg, Pt, traj, ts, alongA: ts.map((_, k) => proj(Pt[k], brg)), crossA: ts.map((_, k) => proj(Pt[k], brg + Math.PI / 2)), alongMC: ts.map((_, k) => sd(k, brg)), crossMC: ts.map((_, k) => sd(k, brg + Math.PI / 2)), qp, qpsi, qv, sb };
})();
fscene({
  title: 'Передбачення EKF: f, F, Q', short: 'Передбачення', question: 'Як ростуть похибки між вимірюваннями — і за якими законами?', T: 80,
  beats: [
    [3.4, 'Передбачення — нелінійна модель руху. Азимут змінюється за виміряною швидкістю обертання з поправкою на зсув; координати зсуваються вздовж азимута на швидкість, помножену на крок. Це неголономне обмеження: бокового ковзання немає.'],
    [15, 'Для коваріації модель лінеаризують: F — якобіан. Зверніть увагу на важіль: помилка азимута входить у координати з множником v cos psi на крок. Мала помилка кута з часом дає велику помилку позиції.'],
    [25.5, 'Шум процесу Q діагональний: кожен елемент — квадрат інтенсивності випадкового блукання, помножений на крок. До азимута додається ще й масштабна похибка гіроскопа: два відсотки від повороту за крок.'],
    [34, 'Коваріація зростає як F P F-транспоноване плюс Q. На графіку — чотириста частинок, що пройшли нелінійну модель із випадковими шумами, і еліпси: розв’язок цього рівняння. Вони збігаються.'],
    [48, 'Аналітично видно закони росту. Якщо швидкість — випадкове блукання, дисперсія вздовж руху росте як t у третій степені; тобто сигма росте як t у степені півтора. Бічна складова від блукання азимута росте так само.'],
    [60, 'Зсув гіроскопа дає ще швидше: кут росте лінійно, а бічне зміщення — квадратично. Тому без корекції зсуву за кілька хвилин домінує саме він.'],
    [70, 'На логарифмічному графіку це прямі з нахилами півтора та два. Точки, отримані методом Монте-Карло, лягають на лінії.'],
  ],
  build() {
    const dyn = t => { const k = Math.max(0, Math.min(PRED.N, Math.floor((t - 34) / 12 * PRED.N))), c = PRED.traj[k], mean = [0, 1].map(j => c.reduce((s, p) => s + p[j], 0) / c.length), P = PRED.Pt[k], C2 = [[P[0][0], P[0][1]], [P[1][0], P[1][1]]];
      return { data: [{ x: c.map(p => p[0]), y: c.map(p => p[1]), mode: 'markers', marker: { size: 4, color: FC.blue, opacity: 0.55 }, hoverinfo: 'skip' }, ellTrace(mean[0], mean[1], C2, K68, FC.green, 'rgba(131,193,103,.12)'), ellTrace(mean[0], mean[1], C2, K95, FC.green, null, 1.5)],
        layout: { title: `частинки і еліпси 68%/95%, t = ${(k * PRED.dt).toFixed(0)} с`, xaxis: { range: [-150, 650], title: { text: 'схід, м' } }, yaxis: { range: [-100, 700], scaleanchor: 'x', title: { text: 'північ, м' } } } }; };
    const ts = PRED.ts.slice(1), lo = a => a.slice(1), law = (k, p) => ts.map(t => k * Math.pow(t, p)), cross = [PRED.alongA, PRED.crossA];
    const lg = { data: [
      { x: ts, y: lo(PRED.alongA), mode: 'lines', line: { color: FC.yellow, width: 3 } }, { x: ts, y: lo(PRED.crossA), mode: 'lines', line: { color: FC.orange, width: 3 } },
      { x: ts.filter((_, i) => i % 6 === 0), y: lo(PRED.alongMC).filter((_, i) => i % 6 === 0), mode: 'markers', marker: { color: FC.yellow, size: 6, symbol: 'circle-open' } },
      { x: ts.filter((_, i) => i % 6 === 0), y: lo(PRED.crossMC).filter((_, i) => i % 6 === 0), mode: 'markers', marker: { color: FC.orange, size: 6, symbol: 'circle-open' } },
      { x: ts, y: law(PRED.qv / Math.sqrt(3), 1.5), mode: 'lines', line: { color: '#fff', width: 1, dash: 'dot' } }],
      layout: { title: 'σ уздовж (жовтий) і впоперек (оранжевий), м', xaxis: { type: 'log', title: { text: 't, с' }, range: [Math.log10(0.5), Math.log10(60)] }, yaxis: { type: 'log', range: [-0.5, 2.7] },
        annotations: [labelAnn(Math.log10(25), Math.log10(PRED.qv / Math.sqrt(3) * Math.pow(25, 1.5)) - 0.35, 'нахил 3/2', '#fff', { size: 11 }), labelAnn(Math.log10(6), 2.2, '○ Монте-Карло, — формула P', FC.dim, { size: 11 })] } };
    return [
      TX(String.raw`\begin{aligned}\psi&\leftarrow\psi-(\omega-b)\,\Delta t\\ e&\leftarrow e+v\sin\psi\,\Delta t\\ n&\leftarrow n+v\cos\psi\,\Delta t\end{aligned}`, 36, 20, { size: 20, t0: 5 }),
      TX(String.raw`F=\begin{bmatrix}1&0&v\cos\psi\,\Delta t&\sin\psi\,\Delta t&0\\0&1&-v\sin\psi\,\Delta t&\cos\psi\,\Delta t&0\\0&0&1&0&\Delta t\\0&0&0&1&0\\0&0&0&0&1\end{bmatrix}`, 36, 112, { size: 17, t0: 15.5 }),
      TX(String.raw`Q=\mathrm{diag}\!\big(q_p^2\Delta t,\ q_p^2\Delta t,\ q_\psi^2\Delta t+(\kappa\,\omega'\Delta t)^2,\ q_v^2\Delta t,\ q_b^2\Delta t\big)`, 36, 255, { size: 16, t0: 26 }),
      HT('q<sub>p</sub> = 0,3 м/√с · q<sub>ψ</sub> = 0,01 рад/√с · κ = 0,02 · q<sub>v</sub> = 0,7 м/с/√с · q<sub>b</sub> = 2·10⁻⁴ рад/с/√с', 36, 292, 460, { t0: 29, size: 13 }),
      TX(String.raw`P^{-}=F\,P\,F^{\top}+Q`, 36, 330, { size: 22, t0: 34.5, t1: 48.5, color: 'green' }),
      TX(String.raw`\sigma_\parallel^2\approx\sigma_{v0}^2t^2+\tfrac{1}{3}q_v^2t^3`, 36, 338, { size: 19, t0: 48.5, color: 'yellow' }),
      TX(String.raw`\sigma_\perp^2\approx v^2\big(\sigma_{\psi0}^2t^2+\tfrac{1}{3}q_\psi^2t^3+\tfrac{1}{4}\sigma_b^2t^4\big)`, 36, 380, { size: 19, t0: 60.5, color: 'orange' }),
      PL(520, 24, 420, 200, { dyn, t0: 34, t1: 70 }), PL(520, 232, 420, 185, Object.assign({ t0: 48 }, lg)),
    ];
  },
});

/* ============ 6. EKF update, Joseph form, NIS and χ² gates ============ */
const UPD = (() => {
  const brg = 40 * Math.PI / 180, Pm = covAxes(brg, 30, 8), z = [20, 12];
  return { Pm, z, brg, at(r) { const R = [[r * r, 0], [0, r * r]], S = add2(Pm, R), K = mul2(Pm, inv2(S)), x = [K[0][0] * z[0] + K[0][1] * z[1], K[1][0] * z[0] + K[1][1] * z[1]], I_K = [[1 - K[0][0], -K[0][1]], [-K[1][0], 1 - K[1][1]]], Pp = mul2(I_K, Pm); return { R, S, K, x, Pp }; } };
})();
fscene({
  title: 'Оновлення: Калман, форма Джозефа, χ²-ворота', short: 'Оновлення', question: 'Як нове вимірювання зсуває оцінку — і коли йому не можна вірити?', T: 78,
  beats: [
    [3.4, 'Оновлення — байєсівське множення двох гаусіан: апріорної, з передбачення, і правдоподібності вимірювання. Добуток гаусіан — знову гаусіана.'],
    [13, 'Доповнивши до повного квадрата, отримуємо оцінку: стара плюс K на інновацію, і коваріацію — одиниця мінус K H, помножене на стару.'],
    [20.5, 'Інновація y — розбіжність між вимірюванням і прогнозом. Її коваріація S — сума невизначеності прогнозу в просторі вимірювання та шуму вимірювання. Коефіцієнт підсилення Калмана — P H-транспоноване на S у мінус першій.'],
    [31, 'Подивіться, як K залежить від точності свідка. Коли R мале, вимірювання майже замінює прогноз; коли R велике, оцінка майже не рухається. Апостеріорний еліпс завжди менший за апріорний.'],
    [44, 'У коді коваріацію оновлюють у формі Джозефа: вона зберігає симетрію й невід’ємну визначеність, навіть коли підсилення неоптимальне, наприклад у локальних оновленнях.'],
    [53, 'Нормована квадратична форма інновації, NIS, має розподіл хі-квадрат з m ступенями свободи. Для двох координат хвіст — просто експонента мінус x на два.'],
    [64, 'Звідси пороги: дев’ять і двадцять одна соті — один відсоток хвоста; тринадцять і вісім — одна десята відсотка, межа довіри до GNSS; двадцять п’ять і п’ятдесят — це вже сигнал для скидання. Це не емпіричні числа, а квантилі хі-квадрат.'],
  ],
  build() {
    const dyn = t => { const r = 3 + 55 * (0.5 - 0.5 * Math.cos(2 * Math.PI * (t - 31) / 12)), U = UPD.at(t < 31 ? 12 : r), pm = [0, 0];
      return { data: [ellTrace(0, 0, UPD.Pm, K68, FC.blue, 'rgba(88,196,221,.14)'), ellTrace(UPD.z[0], UPD.z[1], U.R, K68, FC.yellow, 'rgba(255,215,94,.12)'), ellTrace(U.x[0], U.x[1], U.Pp, K68, FC.green, 'rgba(131,193,103,.30)', 3),
        { x: [0, UPD.z[0], U.x[0]], y: [0, UPD.z[1], U.x[1]], mode: 'markers', marker: { size: [7, 7, 9], color: [FC.blue, FC.yellow, FC.green] }, hoverinfo: 'skip' }],
        layout: { title: `r = ${(t < 31 ? 12 : r).toFixed(0)} м:  K₁₁ = ${U.K[0][0].toFixed(2)},  K₂₂ = ${U.K[1][1].toFixed(2)}`, xaxis: { range: [-60, 70], title: { text: 'схід, м' } }, yaxis: { range: [-45, 70], scaleanchor: 'x', title: { text: 'північ, м' } },
          annotations: [labelAnn(-35, -30, 'прогноз P⁻', FC.blue, { size: 12 }), labelAnn(UPD.z[0] + 38, UPD.z[1] + 40, 'вимірювання R', FC.yellow, { size: 12 }), labelAnn(U.x[0] + 6, U.x[1] - 22, 'результат P⁺', FC.green, { size: 12 })] } }; };
    const xs = range(0, 30, 300), thr = [[9.21, '9,21: 1%'], [13.8, '13,8: 0,1%'], [25, '25: скид']];
    const chi = { data: [{ x: xs, y: xs.map(v => chi2pdf(v, 2)), mode: 'lines', line: { color: FC.blue, width: 3 } },
      ...thr.map(([v]) => ({ x: [v, v], y: [1e-8, 1], mode: 'lines', line: { color: '#fff', width: 1.2, dash: 'dot' } })),
      { x: xs.filter(v => v >= 9.21), y: xs.filter(v => v >= 9.21).map(v => chi2pdf(v, 2)), mode: 'lines', fill: 'tozeroy', fillcolor: 'rgba(252,98,85,.25)', line: { color: FC.red, width: 0 } }],
      layout: { title: 'χ² з двома ступенями свободи: p(ν) = ½ e^(−ν/2)', xaxis: { title: { text: 'ν = yᵀS⁻¹y' }, range: [0, 30] }, yaxis: { type: 'log', range: [-6, 0] },
        annotations: thr.map(([v, l], i) => labelAnn(v, -1.2 - i * 0.9, l, FC.red, { size: 11, extra: { xanchor: 'left', xshift: 4 } })) } };
    return [
      TX(String.raw`p(\mathbf x\mid z)\ \propto\ \mathrm{N}(\mathbf x;\hat{\mathbf x}^-,P^-)\;\mathrm{N}(z;H\mathbf x,R)`, 36, 24, { size: 21, t0: 4.5 }),
      TX(String.raw`\hat{\mathbf x}^{+}=\hat{\mathbf x}^{-}+K\mathbf y,\qquad P^{+}=(I-KH)\,P^{-}`, 36, 76, { size: 21, t0: 14, color: 'green' }),
      TX(String.raw`\mathbf y=z-H\hat{\mathbf x}^-,\quad S=HP^-H^{\top}+R,\quad K=P^-H^{\top}S^{-1}`, 36, 126, { size: 20, t0: 22 }),
      TX(String.raw`P^{+}=(I-KH)\,P^{-}(I-KH)^{\top}+K\,R\,K^{\top}`, 36, 178, { size: 21, t0: 44.5, color: 'orange', box: true }),
      TX(String.raw`\nu=\mathbf y^{\top}S^{-1}\mathbf y\ \sim\ \chi^2_m,\qquad P(\nu>x)\overset{m=2}{=}e^{-x/2}`, 36, 244, { size: 20, t0: 54, color: 'blue' }),
      HT(`<table style="border-collapse:collapse;font-size:14.5px;line-height:1.65"><tr><td>ν &gt; 9,21</td><td>&nbsp;хвіст 1%</td><td>&nbsp;кандидат (R × ν/9,21)</td></tr><tr><td>ν &gt; 13,8</td><td>&nbsp;хвіст 0,1%</td><td>&nbsp;QUESTIONABLE</td></tr><tr><td>ν &gt; 25</td><td>&nbsp;3,7·10⁻⁶</td><td>&nbsp;скидання позиції</td></tr><tr><td>ν &gt; 50</td><td>&nbsp;1,4·10⁻¹¹</td><td>&nbsp;REJECTED</td></tr></table>`, 36, 300, 460, { t0: 65 }),
      PL(520, 24, 420, 205, { dyn, t0: 22, t1: 53 }), PL(520, 24, 420, 205, Object.assign({ t0: 53 }, chi)),
      PL(520, 236, 420, 180, { t0: 31, dyn: t => { const rr = Array.from({ length: 60 }, (_, i) => 1 + i), U0 = rr.map(r => UPD.at(r)); return { data: [
        { x: rr, y: U0.map(u => u.K[0][0]), mode: 'lines', line: { color: FC.yellow, width: 3 } }, { x: rr, y: U0.map(u => u.K[1][1]), mode: 'lines', line: { color: FC.orange, width: 3 } },
        { x: rr, y: U0.map(u => Math.sqrt((u.Pp[0][0] + u.Pp[1][1]) / (UPD.Pm[0][0] + UPD.Pm[1][1]))), mode: 'lines', line: { color: FC.green, width: 3, dash: 'dot' } }],
        layout: { title: 'K₁₁, K₂₂ та σ⁺/σ⁻ залежно від r', xaxis: { title: { text: 'r = σ вимірювання, м' }, range: [0, 60] }, yaxis: { range: [0, 1.05] } } }; } }),
    ];
  },
});
