'use strict';
/* Formal video, scenes 1–3: the problem statement, sensor signals and spectra, first-order filters. */

/* An arrow as a Plotly annotation, in data coordinates. */
const arrowAnn = (x0, y0, x1, y1, color, o = {}) => Object.assign({ x: x1, y: y1, ax: x0, ay: y0, xref: 'x', yref: 'y', axref: 'x', ayref: 'y', showarrow: true, arrowhead: 2, arrowsize: 1.1, arrowwidth: o.w ?? 2.5, arrowcolor: color, text: '' }, o.extra || {});
const labelAnn = (x, y, txt, color, o = {}) => Object.assign({ x, y, xref: 'x', yref: 'y', text: txt, showarrow: false, font: { size: o.size ?? 13, color }, xanchor: o.xanchor ?? 'center', yanchor: o.yanchor ?? 'middle' }, o.extra || {});

/* ============ 1. Problem statement ============ */
fscene({
  title: 'Постановка задачі', short: 'Постановка', question: 'Що саме ми оцінюємо і в якому сенсі «оцінюємо»?', T: 66,
  beats: [
    [3.4, 'Опишемо задачу формально. Стан автомобіля — вектор із семи компонент: східна й північна координати, азимут, швидкість, зсув гіроскопа, похибка масштабу спідометра і зсув поздовжнього прискорення.'],
    [14, 'Система задається двома рівняннями: еволюції стану з гаусовим шумом процесу, коваріація Q, та спостереження з шумом вимірювання, коваріація R.'],
    [24, 'Мета — не точка, а розподіл: апостеріорна щільність стану за всіма вимірюваннями до моменту k. Вона задається рекурсією Байєса.'],
    [34, 'Інтеграл за попереднім станом — це крок передбачення, множник правдоподібності — крок оновлення.'],
    [42, 'Якщо лінеаризувати f і h в поточній оцінці, а шуми вважати гаусовими, щільність лишається гаусовою: її повністю задають середнє і коваріація. Це розширений фільтр Калмана.'],
    [55, 'Умовності. Азимут відраховується за годинниковою стрілкою від півночі, а гіроскоп дає швидкість проти годинникової, тому похідна азимуту дорівнює мінус швидкості обертання. Радіус точності Android — це 68-відсотковий радіус, тож сигма дорівнює hAcc, поділити на 1,5096.'],
  ],
  build() {
    const dyn = t => {
      const psi = (35 + 25 * Math.sin((t - 4) / 3)) * Math.PI / 180, s = Math.sin(psi), c = Math.cos(psi), arc = range(0, psi, 30);
      return { data: [{ x: arc.map(a => 0.55 * Math.sin(a)), y: arc.map(a => 0.55 * Math.cos(a)), mode: 'lines', line: { color: FC.yellow, width: 2.5 }, hoverinfo: 'skip' }],
        layout: { margin: { l: 4, r: 4, t: 4, b: 4 }, xaxis: { range: [-1.5, 1.5], visible: false }, yaxis: { range: [-1.2, 1.5], visible: false, scaleanchor: 'x' },
          annotations: [arrowAnn(-1.3, 0, 1.3, 0, '#55639a', { w: 1.5 }), arrowAnn(0, -1.0, 0, 1.3, '#55639a', { w: 1.5 }), arrowAnn(0, 0, s * 1.0, c * 1.0, FC.blue, { w: 3.5 }),
            labelAnn(1.38, -0.12, 'схід  e', FC.dim), labelAnn(0.2, 1.4, 'північ  n', FC.dim), labelAnn(0.62 * Math.sin(psi / 2) + 0.08, 0.62 * Math.cos(psi / 2) + 0.08, 'ψ', FC.yellow, { size: 16 }),
            labelAnn(s * 1.12 + 0.12, c * 1.12, 'v', FC.blue, { size: 16 }), labelAnn(0, -1.15, 'ψ̇ = −ω_up', FC.green, { size: 13 })] } };
    };
    return [
      TX(String.raw`\mathbf{x}=\begin{pmatrix}e & n & \psi & v & b & s & b_a\end{pmatrix}^{\!\top}\in\mathbb{R}^{7}`, 36, 34, { size: 26, t0: 4.6 }),
      HT('<i>e, n</i> — схід і північ, м (локальна площина ENU) · <i>ψ</i> — азимут, рад · <i>v</i> — швидкість уздовж курсу, м/с · <i>b</i> — зсув гіроскопа, рад/с · <i>s</i> — похибка масштабу спідометра · <i>b<sub>a</sub></i> — зсув поздовжнього прискорення, м/с²', 36, 80, 560, { t0: 8 }),
      TX(String.raw`\mathbf{x}_k=f(\mathbf{x}_{k-1},\mathbf{u}_k)+\mathbf{w}_k,\qquad \mathbf{w}_k\sim\mathrm{N}(0,\,Q_k)`, 36, 160, { size: 22, t0: 14.5 }),
      TX(String.raw`\mathbf{z}_k=h(\mathbf{x}_k)+\mathbf{v}_k,\qquad \mathbf{v}_k\sim\mathrm{N}(0,\,R_k)`, 36, 206, { size: 22, t0: 19.5 }),
      TX(String.raw`p(\mathbf{x}_k\mid z_{1:k})\;\propto\;\underbrace{p(z_k\mid \mathbf{x}_k)}_{\text{оновлення}}\int \underbrace{p(\mathbf{x}_k\mid \mathbf{x}_{k-1})}_{\text{передбачення}}\,p(\mathbf{x}_{k-1}\mid z_{1:k-1})\,d\mathbf{x}_{k-1}`, 36, 262, { size: 19, t0: 24.5 }),
      TX(String.raw`p(\mathbf{x}_k\mid z_{1:k})\approx\mathrm{N}(\hat{\mathbf{x}}_k,\,P_k)`, 36, 340, { size: 22, t0: 42.5, color: 'green' }),
      TX(String.raw`F=\frac{\partial f}{\partial\mathbf{x}}\Big|_{\hat{\mathbf{x}}},\qquad H=\frac{\partial h}{\partial\mathbf{x}}\Big|_{\hat{\mathbf{x}}}`, 36, 385, { size: 22, t0: 47 }),
      PL(640, 24, 290, 270, { dyn, t0: 4.6 }),
      HT('<b>Конвенції.</b> ω<sub>up</sub> &gt; 0 — проти годинникової; ψ — за годинниковою від півночі. σ = hAcc / 1,5096 (hAcc — радіус 68%). Час — <i>elapsedRealtimeNanos</i>.', 640, 306, 290, { t0: 55.5 }),
    ];
  },
});

/* ============ 2. Sensor signals and their spectra ============ */
const SIG = (() => {
  const dt = 0.05, N = 1 << 17, q = 2e-4, sg = 0.004, rnd = mulberry32(21), g = () => gaussian(rnd), nz = [], bb = []; let b = 0;
  for (let i = 0; i < N; i++) { nz.push(sg * g()); b += q * Math.sqrt(dt) * g(); bb.push(b); }
  const sn = psd(nz, dt, 8192), sb = psd(bb, dt, 8192);
  return { dt, q, sg, nz, bb, sn, sb, fx: q / (2 * Math.PI * sg * Math.sqrt(dt)) };
})();
fscene({
  title: 'Сигнали датчиків і їхні спектри', short: 'Сигнали', question: 'З чого складається показання гіроскопа — і що кажуть про нього частоти?', T: 76,
  beats: [
    [3.4, 'Почнемо із сигналів. Гіроскоп вимірює кутову швидкість навколо вертикалі. Вона містить корисний сигнал — похідну азимуту, масштабну похибку, повільно змінний зсув і білий шум.'],
    [14, 'Зсув — випадкове блукання: його похідна є білим шумом з інтенсивністю q b. Саме тому похибка курсу, накопичена за цим зсувом, росте з часом.'],
    [23.5, 'Інші датчики простіші. Швидкість з OBD має мультиплікативну похибку масштабу, а координати GNSS — адитивний шум; його сигму приймач повідомляє як радіус точності.'],
    [33, 'Подивимось на сигнали в часі. Шум — швидкі випадкові коливання навколо нуля. Зсув — повільний дрейф, що накопичується, і до півтори тисячі секунд він досягає вже порівнянних з шумом величин.'],
    [46, 'Фур’є-перетворення показує різницю. Спектральна щільність потужності білого шуму плоска, стала на всіх частотах. Спектр випадкового блукання спадає як одиниця на частоту в квадраті: нахил мінус два в логарифмічних координатах.'],
    [61, 'Лінії перетинаються біля трьох сотих герца. Нижче цієї частоти, тобто на періодах понад пів хвилини, домінує дрейф зсуву, вище — шум. Це підказує, які постійні часу фільтрів мають сенс далі.'],
  ],
  build() {
    const ds = 20, t = SIG.bb.map((_, i) => i * SIG.dt).filter((_, i) => i % ds === 0).filter(x => x <= 1600), n = t.length;
    const series = { data: [
      { x: t, y: SIG.nz.filter((_, i) => i % ds === 0).slice(0, n), mode: 'lines', line: { color: FC.gyro, width: 1 }, name: 'n_g' },
      { x: t, y: SIG.bb.filter((_, i) => i % ds === 0).slice(0, n), mode: 'lines', line: { color: FC.orange, width: 2.5 }, name: 'b' }],
      layout: { title: 'сигнали, рад/с', xaxis: { title: { text: 't, с' }, range: [0, 1600] }, yaxis: { range: [-0.016, 0.016], tickformat: '.3f' }, annotations: [labelAnn(1250, 0.0125, 'зсув  b(t)', FC.orange, { size: 13 }), labelAnn(250, -0.0125, 'шум  n_g(t)', FC.gyro, { size: 13 })] } };
    const k = SIG.sn.f.map((f, i) => i).filter(i => i > 0), fs = k.map(i => SIG.sn.f[i]);
    const spec = { data: [
      { x: fs, y: k.map(i => SIG.sn.p[i]), mode: 'lines', line: { color: FC.gyro, width: 1.5 } },
      { x: fs, y: k.map(i => SIG.sb.p[i]), mode: 'lines', line: { color: FC.orange, width: 1.5 } },
      { x: [fs[0], fs[fs.length - 1]], y: [2 * SIG.sg ** 2 * SIG.dt, 2 * SIG.sg ** 2 * SIG.dt], mode: 'lines', line: { color: '#fff', width: 1.2, dash: 'dot' } },
      { x: fs, y: fs.map(f => 2 * SIG.q ** 2 / (2 * Math.PI * f) ** 2), mode: 'lines', line: { color: '#fff', width: 1.2, dash: 'dot' } }],
      layout: { title: 'спектральна щільність потужності, (рад/с)²/Гц', xaxis: { type: 'log', title: { text: 'f, Гц' }, range: [-2.6, 1.3] }, yaxis: { type: 'log', range: [-9, -4.5], exponentformat: 'power' },
        annotations: [labelAnn(Math.log10(0.3), Math.log10(2 * SIG.sg ** 2 * SIG.dt) + 0.3, 'плоский: 2σ²Δt', FC.gyro, { extra: { xref: 'x', yref: 'y' } }), labelAnn(Math.log10(0.0105), -6.9, 'нахил −2', FC.orange)] } };
    spec.layout.annotations = [{ x: Math.log10(0.5), y: Math.log10(2 * SIG.sg ** 2 * SIG.dt) + 0.35, xref: 'x', yref: 'y', text: 'білий шум: плоский', showarrow: false, font: { size: 12, color: FC.gyro } },
      { x: Math.log10(0.0075), y: Math.log10(2 * SIG.q ** 2 / (2 * Math.PI * 0.0075) ** 2) + 0.3, xref: 'x', yref: 'y', text: 'випадкове блукання: ∝ f⁻²', showarrow: false, font: { size: 12, color: FC.orange }, xanchor: 'left' },
      { x: Math.log10(SIG.fx), y: -8.4, xref: 'x', yref: 'y', text: `f× ≈ ${fxt(SIG.fx, 3)} Гц`, showarrow: false, font: { size: 12, color: '#fff' } }];
    return [
      TX(String.raw`\omega_{\mathrm{meas}}=-(1+\varepsilon_g)\,\dot\psi+b(t)+n_g(t)`, 36, 30, { size: 23, t0: 4.5 }),
      TX(String.raw`\dot b=w_b,\qquad \mathrm{E}\big[w_b(t)\,w_b(t')\big]=q_b^{2}\,\delta(t-t')`, 36, 78, { size: 21, t0: 15 }),
      TX(String.raw`z_v=(1+s)\,v+n_v \qquad\quad \mathbf{z}_{\mathrm{pos}}=\mathbf{p}+\mathbf{n},\ \ \sigma=\tfrac{\mathrm{hAcc}}{1{,}5096}`, 36, 126, { size: 19, t0: 24.5 }),
      TX(String.raw`S_x(f)=\lim_{T\to\infty}\frac{1}{T}\,\mathrm{E}\,\big|X_T(f)\big|^{2}`, 36, 186, { size: 22, t0: 47, color: 'blue' }),
      TX(String.raw`S_{n}=2\sigma_n^{2}\Delta t`, 36, 250, { size: 22, t0: 51, color: 'gyro' }),
      TX(String.raw`S_{b}(f)=\frac{2\,q_b^{2}}{(2\pi f)^{2}}`, 36, 292, { size: 22, t0: 55, color: 'orange' }),
      TX(String.raw`f_\times=\frac{q_b}{2\pi\,\sigma_n\sqrt{\Delta t}}\approx ${fx(SIG.fx, 3)}\ \text{Гц}`, 36, 362, { size: 22, t0: 62.5, box: true }),
      HT(`q<sub>b</sub> = 2·10⁻⁴ рад/с/√с (параметр <i>biasRandomWalk</i>), σ<sub>n</sub> = 0,004 рад/с, Δt = 0,05 с (приклад)`, 36, 418, 420, { t0: 62.5, size: 13 }),
      PL(470, 24, 470, 185, Object.assign({ t0: 34 }, series)), PL(470, 218, 470, 195, Object.assign({ t0: 47 }, spec)),
    ];
  },
});

/* ============ 3. First-order filters ============ */
const FTAU = [[0.2, 'компас: EMA поля', FC.teal], [0.5, 'yaw-gate', FC.yellow], [0.095, 'a_lp (0,1 за відлік, ≈100 Гц)', FC.green], [5, 'гравітація (старий режим)', FC.purple], [30, 'корекція «вгору»', FC.blue], [120, 'середня ‖a‖', FC.orange]];
const FD = (() => {
  const dt = 0.01, N = 4096, tau = 0.5, a = 1 - Math.exp(-dt / tau), r = mulberry32(5), x = [], y = []; let s = 0;
  for (let i = 0; i < N; i++) { const t = i * dt, v = 0.3 * Math.sin(2 * Math.PI * 0.1 * t) + 0.15 * Math.sin(2 * Math.PI * 8 * t) + 0.02 * gaussian(r); x.push(v); s += a * (v - s); y.push(s); }
  const spec = u => { const re = u.map((v, i) => v * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N))), im = new Array(N).fill(0); fft(re, im); return re.slice(0, N / 2).map((v, k) => 2 * Math.hypot(v, im[k]) / (N / 2)); };
  return { dt, N, x, y, sx: spec(x), sy: spec(y), f: range(0, 1 / (2 * dt), N / 2).slice(0, N / 2) };
})();
fscene({
  title: 'Фільтри першого порядку', short: 'Фільтри', question: 'Що таке «постійна часу» в коді — і як вона виглядає в частотній області?', T: 82,
  beats: [
    [3.4, 'Більшість згладжувань у коді — фільтри першого порядку, експоненціальне ковзне середнє. У неперервному часі це диференціальне рівняння з постійною часу тау, а передавальна функція — одиниця поділити на одиницю плюс тау s.'],
    [14, 'У дискретному часі код робить точний перехід: коефіцієнт альфа дорівнює одиниці мінус експонента від мінус крок за часом, поділити на тау. Тож фільтр коректний і при нерівномірній дискретизації датчика.'],
    [24, 'Амплітудна характеристика спадає як одиниця на корінь з одиниці плюс омега тау в квадраті; фаза — мінус арктангенс; частота зрізу — одна на два пі тау. Нижче зрізу сигнал проходить, вище послаблюється на двадцять децибел за декаду.'],
    [38.5, 'Ось шість постійних часу, що реально є в коді: від двох десятих секунди для магнітометра до двох хвилин для середнього модуля прискорення.'],
    [48, 'Перевіримо на сигналі. Поворот автомобіля — повільна складова, десята частка герца, а вібрація двигуна — вісім герц. Фільтр з тау пів секунди пропускає поворот і гасить вібрацію.'],
    [62.5, 'У спектрі це видно прямо: пік на восьми герцах впав приблизно в двадцять п’ять разів, тобто на двадцять вісім децибел, а пік повороту майже не змінився.'],
    [73, 'Зауважте: перетворення Фур’є в самому алгоритмі не обчислюється. Ми використовуємо його як інструмент аналізу, щоб обирати постійні часу й пояснювати їх.'],
  ],
  build() {
    const fr = logspace(1e-3, 1e2, 300), bode = { data: FTAU.map(([tau, , col]) => ({ x: fr, y: fr.map(f => -10 * Math.log10(1 + (2 * Math.PI * f * tau) ** 2)), mode: 'lines', line: { color: col, width: 2 } }))
      .concat([{ x: [1e-3, 1e2], y: [-3, -3], mode: 'lines', line: { color: '#fff', width: 1, dash: 'dot' } }]),
      layout: { title: '|H|, дБ', xaxis: { type: 'log', title: { text: 'f, Гц' } }, yaxis: { range: [-60, 3] }, annotations: [labelAnn(Math.log10(5e-3), -5, '−3 дБ', '#fff', { size: 11 })] } };
    const T0 = 48, ts = FD.x.map((_, i) => i * FD.dt);
    const dyn = t => { const n = Math.min(FD.N, Math.max(2, Math.floor((t - T0) / 13 * 3000))); return { data: [
      { x: ts.slice(0, n), y: FD.x.slice(0, n), mode: 'lines', line: { color: '#6b7690', width: 1 } }, { x: ts.slice(0, n), y: FD.y.slice(0, n), mode: 'lines', line: { color: FC.yellow, width: 2.5 } }],
      layout: { title: 'вхід (сірий) і вихід фільтра τ = 0,5 с (жовтий), рад/с', xaxis: { range: [0, 30], title: { text: 't, с' } }, yaxis: { range: [-0.6, 0.6] } } }; };
    const sp = { data: [{ x: FD.f.slice(1), y: FD.sx.slice(1), mode: 'lines', line: { color: '#6b7690', width: 1.5 }, name: 'вхід' }, { x: FD.f.slice(1), y: FD.sy.slice(1), mode: 'lines', line: { color: FC.yellow, width: 2.5 }, name: 'вихід' }],
      layout: { title: 'амплітудний спектр, рад/с', xaxis: { type: 'log', range: [-1.7, 1.8], title: { text: 'f, Гц' } }, yaxis: { type: 'log', range: [-4, 0], exponentformat: 'power' },
        annotations: [labelAnn(Math.log10(0.1), -0.3, '0,1 Гц: поворот', FC.dim, { size: 11 }), labelAnn(Math.log10(8), -0.9, '8 Гц: вібрація\n−28 дБ', FC.yellow, { size: 11 })] } };
    const tabRows = FTAU.map(([tau, lab, col]) => `<tr><td style="color:${col}">●</td><td>τ = ${String(tau).replace('.', ',')} с</td><td>${lab}</td><td>f<sub>c</sub> = ${(1 / (2 * Math.PI * tau)) >= 0.1 ? fxt(1 / (2 * Math.PI * tau), 2) + ' Гц' : fxt(1000 / (2 * Math.PI * tau), 1) + ' мГц'}</td></tr>`).join('');
    return [
      TX(String.raw`\tau\,\dot y(t)+y(t)=x(t)\quad\Rightarrow\quad H(s)=\frac{1}{1+\tau s}`, 36, 30, { size: 22, t0: 4.5 }),
      TX(String.raw`y_k=y_{k-1}+\alpha_k\,(x_k-y_{k-1}),\qquad \alpha_k=1-e^{-\Delta t_k/\tau}`, 36, 92, { size: 21, t0: 15 }),
      TX(String.raw`H\!\left(e^{j\omega\Delta t}\right)=\frac{\alpha}{1-(1-\alpha)\,e^{-j\omega\Delta t}}`, 36, 146, { size: 21, t0: 21 }),
      TX(String.raw`\big|H(j\omega)\big|=\frac{1}{\sqrt{1+(\omega\tau)^{2}}},\quad \varphi=-\arctan(\omega\tau),\quad f_c=\frac{1}{2\pi\tau}`, 36, 196, { size: 21, t0: 25.5, box: true }),
      HT(`<table style="border-collapse:collapse;font-size:14px;line-height:1.6">${tabRows}</table>`, 36, 300, 430, { t0: 40 }),
      PL(470, 24, 470, 190, Object.assign({ t0: 25 }, bode)),
      PL(470, 224, 470, 190, { dyn, t0: 48, t1: 62.5 }), PL(470, 224, 470, 190, Object.assign({ t0: 62.5 }, sp)),
      TX(String.raw`|H(j\,2\pi\cdot 8)|=\frac{1}{\sqrt{1+(2\pi\cdot 8\cdot 0{,}5)^2}}\approx\frac{1}{25}\;(-28\ \text{дБ})`, 36, 262, { size: 17, t0: 63.5, color: 'yellow' }),
    ];
  },
});
