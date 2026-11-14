// Records a side-by-side demo: phone simulator page (headless Chrome screencast) + car screen with sound
// (scrcpy, audio source "playback" so the car's voice and chimes are in the file; needs `brew install scrcpy`
// and the emulator started WITH audio, i.e. without -no-audio).
// Usage: node demo/record.mjs <outDir>     (relay server and Pleos emulator with the car app must be running)
// Then:  python3 demo/compose.py <outDir>  → <outDir>/caredrive-demo.mp4

import { spawn, execFileSync } from 'node:child_process';
import { mkdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { WebSocket } from 'ws';

const OUT = process.argv[2] ?? '/tmp/caredrive-demo';
const ADB = `${process.env.HOME}/Library/Android/sdk/platform-tools/adb`;
const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const PAGE = 'http://localhost:8787/';
const PORT = 9333;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const adb = (...args) => execFileSync(ADB, args, { encoding: 'utf8' });

mkdirSync(join(OUT, 'frames'), { recursive: true });
const timeline = { frames: [], chapters: [], carStart: 0, carEnd: 0 };
const chapter = (label) => { timeline.chapters.push({ t: Date.now(), label }); console.log(`[demo] ${label}`); };

// --- phone page in headless Chrome -------------------------------------------------------------
const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${join(OUT, 'chrome')}`,
  '--hide-scrollbars', '--no-first-run', 'about:blank',
], { stdio: 'ignore' });

let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try { target = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page'); } catch {}
}
const cdp = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => cdp.once('open', r));
let seq = 0;
const pending = new Map();
const call = (method, params = {}) => new Promise((resolve) => {
  const id = ++seq;
  pending.set(id, resolve);
  cdp.send(JSON.stringify({ id, method, params }));
});
let frameNo = 0;
cdp.on('message', (raw) => {
  const m = JSON.parse(raw.toString());
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m.result); pending.delete(m.id); }
  if (m.method === 'Page.screencastFrame') {
    const file = `f_${String(++frameNo).padStart(5, '0')}.jpg`;
    writeFileSync(join(OUT, 'frames', file), Buffer.from(m.params.data, 'base64'));
    timeline.frames.push({ file, t: Date.now() });
    call('Page.screencastFrameAck', { sessionId: m.params.sessionId });
  }
});
const js = (expr) => call('Runtime.evaluate', { expression: expr, awaitPromise: true });

await call('Emulation.setDeviceMetricsOverride', { width: 430, height: 932, deviceScaleFactor: 2, mobile: true });
await call('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'light' }] });
await call('Page.enable');
await call('Page.navigate', { url: PAGE });
await sleep(1500);
// Helpers inside the page: visible tap highlight, smooth slider, scroll a section into view.
await js(`
  window.tap = async (id) => {
    const el = document.getElementById(id);
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    await new Promise(r => setTimeout(r, 600));
    el.style.transition = 'box-shadow .2s'; el.style.boxShadow = '0 0 0 6px rgba(49,130,246,.45)';
    await new Promise(r => setTimeout(r, 350));
    el.click();
    setTimeout(() => { el.style.boxShadow = ''; }, 500);
  };
  window.slide = async (id, to, ms) => {
    const el = document.getElementById(id);
    el.scrollIntoView({ block: 'center', behavior: 'smooth' });
    await new Promise(r => setTimeout(r, 600));
    const from = +el.value, steps = Math.max(1, Math.round(ms / 80));
    for (let i = 1; i <= steps; i++) {
      el.value = Math.round(from + (to - from) * i / steps);
      el.dispatchEvent(new Event('input'));
      await new Promise(r => setTimeout(r, ms / steps));
    }
  };
  window.check = (id, on) => { const el = document.getElementById(id); if (el.checked !== on) el.click(); };
  'ok'`);

// --- car app: fresh start, then record ----------------------------------------------------------
adb('shell', 'am', 'force-stop', 'ai.caredrive.car');
adb('shell', 'am', 'start', '-n', 'ai.caredrive.car/.MainActivity');
await sleep(6000); // TTS init + relay connect
adb('logcat', '-c');

await call('Page.startScreencast', { format: 'jpeg', quality: 85 });
const rec = spawn('scrcpy', [
  '--no-window', '--no-playback', '--audio-source=playback', '--audio-dup', '--max-size=1920', '--video-bit-rate=12M',
  `--record=${join(OUT, 'car.mkv')}`,
], { env: { ...process.env, ADB } });
// scrcpy buffers its log when not on a terminal, so watch the file instead: recording starts when it grows.
for (let i = 0; i < 100 && timeline.carStart === 0; i++) {
  await sleep(100);
  try { if (statSync(join(OUT, 'car.mkv')).size > 0) timeline.carStart = Date.now(); } catch {}
}
await sleep(1000);

const tapCar = (text) => {
  const xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty');
  const m = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  if (!m) { console.log(`[demo] car button not found: ${text}`); return; }
  adb('shell', 'input', 'tap', String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1));
};

// --- scenario ------------------------------------------------------------------------------------
chapter('1. 출발 전 · 폰이 오늘 컨디션을 차로 보내요');
await js(`slide('sleep', 280, 1200)`);
await js(`slide('energy', 45, 900)`);
await js(`tap('sendCondition')`);
await sleep(7000);

chapter('2. 주행 시작');
await js(`tap('driveOn')`);
await sleep(5000);

chapter('3. 심박이 오르면 · 차가 공조를 시원하게');
await js(`slide('hr', 116, 3000)`);
await js(`tap('adv10')`);
await sleep(9000);

chapter('4. 운전 1시간 · 수면이 짧은 날은 휴식을 앞당겨요');
await js(`slide('hr', 80, 2000)`);
await js(`tap('adv30')`);
await sleep(1200);
await js(`tap('adv30')`);
await sleep(9000);

chapter('5. 워치의 부정맥 알림 · 휴게소 안내를 제안해요');
await js(`tap('sendIhrn')`);
await sleep(6500);
tapCar('휴게소 안내');
await sleep(7000);

chapter('6. 정차하면 · 이유에 맞는 안내');
adb('shell', 'am', 'start', '-n', 'ai.caredrive.car/.MainActivity');
await js(`tap('driveOff')`);
await sleep(9000);

// --- finish --------------------------------------------------------------------------------------
timeline.carEnd = Date.now();
await call('Page.stopScreencast');
rec.kill('SIGINT');
await new Promise((r) => rec.once('exit', r));

timeline.speaks = adb('logcat', '-d', '-v', 'epoch', '-s', 'CareDrive:I')
  .split('\n')
  .map((line) => line.match(/^\s*(\d+\.\d+).*speak: (.*)$/))
  .filter(Boolean)
  .map((m) => ({ t: Math.round(parseFloat(m[1]) * 1000), text: m[2] }));

writeFileSync(join(OUT, 'timeline.json'), JSON.stringify(timeline, null, 2));
cdp.close();
chrome.kill();
console.log(`[demo] frames=${timeline.frames.length} speaks=${timeline.speaks.length} → ${OUT}`);
process.exit(0);
