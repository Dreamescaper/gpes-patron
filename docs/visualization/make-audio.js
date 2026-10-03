#!/usr/bin/env node
'use strict';
/*
 * Renders the voice-over: one audio/<scene>b<beat>.m4a per subtitle, read by the macOS Ukrainian voice "Lesya".
 * Needs macOS (`say`) and ffmpeg. Run after changing any subtitle:   node docs/visualization/make-audio.js
 * Phrases are read at a natural pace; scenes whose phrases do not fit their slots are stretched (audio/stretch.js).
 */
const fs = require('fs'), vm = require('vm'), path = require('path'), cp = require('child_process');
const FORMAL = process.argv[2] === 'formal';        // node make-audio.js formal  → the formal video (audio-formal/)
const dir = __dirname, out = path.join(dir, FORMAL ? 'audio-formal' : 'audio');
fs.mkdirSync(out, { recursive: true });

// load the scenes without a browser: stub the DOM and never start the player
const stub = () => new Proxy(function () {}, { get: (t, k) => (k === Symbol.toPrimitive ? () => 0 : stub()), apply: () => stub(), set: () => true });
const sandbox = { console, Math, Object, Array, JSON, Number, String, Map, Set, Proxy, Symbol, Date, Float64Array, Uint8Array, document: { getElementById: stub, createElement: stub, addEventListener() {} }, window: {}, addEventListener() {}, requestAnimationFrame() {}, history: {}, location: { hash: '', search: '' }, URLSearchParams, Audio: function () {}, devicePixelRatio: 1, module: undefined };
vm.createContext(sandbox);
const files = FORMAL ? ['engine.js', 'formal-num.js', 'formal-player.js', 'formal-1.js', 'formal-2.js', 'formal-3.js', 'formal-4.js'] : ['engine.js', 'app.js', 'scenes-a.js', 'scenes-b.js', 'scenes-c.js', 'scenes-d.js'];
let code = files.map(f => fs.readFileSync(path.join(dir, f), 'utf8').replace(/^'use strict';/, '').replace('startApp(SCENES);', '')).join('\n;\n');
code = code.replace(/\bconst (SCENES|SUMMARY_S|TITLE_S|FSC)\b/g, 'var $1');
vm.runInContext(code + (FORMAL ? '\n;this.__S = FSC; this.__SUM = 0;' : '\n;this.__S = SCENES; this.__SUM = SUMMARY_S;'), sandbox);
const SCENES = sandbox.__S, SUMMARY_S = sandbox.__SUM;

const LAT = { A: 'а', B: 'бе', C: 'це', F: 'еф', H: 'аш', K: 'ка', M: 'ем', N: 'ен', P: 'пе', Q: 'ку', R: 'ер', S: 'ес', W: 'дубль-ве', a: 'а', b: 'бе', c: 'це', d: 'де', e: 'е', f: 'еф', h: 'аш', k: 'ка', m: 'ем', n: 'ен', o: 'о', p: 'пе', q: 'ку', r: 'ер', s: 'ес', t: 'те', u: 'у', v: 'ве', x: 'ікс', y: 'ігрек', z: 'зет' };
// how Lesya should read symbols, abbreviations and English words
const spoken = s => s
  .replace(/<[^>]+>/g, '')
  .replace(/GNSS/g, 'джі-ен-ес-ес').replace(/NEES/g, 'ен-і-і-ес').replace(/NIS/g, 'ен-ай-ес').replace(/ZUPT/g, 'зет-ю-пі-ті').replace(/hAcc/g, 'аш-акк').replace(/Android/g, 'андроїд').replace(/QUESTIONABLE|REJECTED/g, '')
  .replace(/GPS/g, 'джі-пі-ес').replace(/OBD/g, 'о-бі-ді').replace(/Wi-Fi/g, 'вай-фай').replace(/\bp95\b/g, 'пе дев’яносто п’ять')
  .replace(/1\/σ²/g, 'одиниця поділити на сигма в квадраті').replace(/σ/g, 'сигма')
  .replace(/(\d+) м\/с/g, '$1 метрів за секунду').replace(/±(\d+) м\b/g, 'плюс-мінус $1 метрів').replace(/\+(\d+) м\b/g, 'плюс $1 метрів')
  .replace(/(\d+(?:,\d+)?) км\b/g, '$1 кілометра').replace(/(\d+) м\b/g, '$1 метрів')
  .replace(/(\d+)°/g, '$1 градусів').replace(/(\d+)%/g, '$1 відсотків').replace(/×/g, ' помножити на ').replace(/ = /g, ' дорівнює ')
  .replace(/ — /g, ', ').replace(/[«»]/g, '')
  .replace(/\b([A-Za-z])\b/g, (m, c) => LAT[c] || m).replace(/\s+/g, ' ');

const duration = f => parseFloat(cp.execSync(`ffprobe -v error -show_entries format=duration -of csv=p=0 "${f}"`).toString());
const RATE = 205;   // `say` words per minute (macOS fallback)
// Preferred engine: Microsoft Edge neural voices via the `edge-tts` command (set EDGE_TTS=/path/to/edge-tts; VOICE=uk-UA-PolinaNeural for the female voice).
// Without edge-tts the macOS voice Lesya is used.
const EDGE = process.env.EDGE_TTS || '', VOICE = process.env.VOICE || 'uk-UA-OstapNeural', EDGE_RATE = process.env.EDGE_RATE || '+5%';
const ENGINE = EDGE ? `edge:${VOICE}:${EDGE_RATE}` : `say:${RATE}`;   // natural pace; the video is stretched where a phrase is longer than its slot
const MAN = path.join(out, 'manifest.json'); let man = {}; try { man = JSON.parse(fs.readFileSync(MAN, 'utf8')); } catch (e) {}
const stretch = {}; let total = 0; let vlen = 0;
SCENES.forEach((sc, i) => {
  const end = sc.summary ? sc.T - SUMMARY_S : sc.T; let k = 1; const per = [];
  sc.beats.forEach((b, j) => {
    const id = `s${i + 1}b${j}`, gap = (j + 1 < sc.beats.length ? sc.beats[j + 1][0] : end) - b[0], txt = spoken(b[2] || b[1]);
    const aiff = path.join(out, id + (EDGE ? '.mp3' : '.aiff')), m4a = path.join(out, id + '.m4a'), key = JSON.stringify([txt, ENGINE]);
    if (!(man[id] && man[id].key === key && fs.existsSync(m4a))) {
      if (EDGE) { for (let a = 1; ; a++) { try { cp.execFileSync(EDGE, ['--voice', VOICE, '--rate', EDGE_RATE, '--text', txt, '--write-media', aiff], { stdio: 'pipe' }); break; } catch (e) { if (a >= 4) throw e; } } }
      else cp.execFileSync('say', ['-v', 'Lesya', '-r', String(RATE), '-o', aiff, txt]);
      const dur = duration(aiff);
      cp.execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-i', aiff, '-ac', '1', '-c:a', 'aac', '-b:a', '56k', m4a]); fs.unlinkSync(aiff); man[id] = { key, dur };
    }
    total += man[id].dur; const need = (man[id].dur + 0.45) / gap; k = Math.max(k, need); per.push(Math.max(1, Math.ceil(need * 20) / 20));
  });
  if (FORMAL) { stretch[i + 1] = per; let L = sc.beats[0][0]; sc.beats.forEach((b, j) => { const nx = j + 1 < sc.beats.length ? sc.beats[j + 1][0] : sc.T; L += (nx - b[0]) * per[j]; }); vlen += L; }
  else { stretch[i + 1] = Math.min(2, Math.ceil(k * 20) / 20); vlen += sc.T * stretch[i + 1]; }
});
fs.writeFileSync(MAN, JSON.stringify(man, null, 1));
fs.writeFileSync(path.join(out, 'stretch.js'), `// generated by make-audio.js: per-scene slow-down so that the voice-over fits\nconst STRETCH = ${JSON.stringify(stretch)};\n`);
console.log('stretch per scene:', JSON.stringify(stretch));
console.log(`done: ${total.toFixed(0)} s of speech; video length ${vlen.toFixed(0)} s`);
