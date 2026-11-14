// Records a side-by-side demo: phone simulator page (headless Chrome screencast) + car screen with sound
// (scrcpy, audio source "playback" so the car's voice and chimes are in the file; needs `brew install scrcpy`
// and the emulator started WITH audio, i.e. without -no-audio).
// Driving is real vehicle data: gear, speed, brake, steering and turn-signal telltales are written to the Pleos Connect
// Virtual Console (adb forward tcp:8088 tcp:8088), which publishes them on the emulator's vehicle bus, so the car app
// reads them through the Pleos Vehicle SDK / AAOS CarPropertyManager exactly as in a car.
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

// --- Pleos Virtual Console: real vehicle signals ---------------------------------------------------
const VC = 'http://127.0.0.1:8088';
adb('forward', 'tcp:8088', 'tcp:8088');
// The console only accepts writes while a client session is alive (3 s timeout): keep one open.
const heartbeat = setInterval(() => fetch(`${VC}/browser/session?client=caredrive-demo`, { method: 'POST' }).catch(() => {}), 1000);
await fetch(`${VC}/browser/session?client=caredrive-demo`, { method: 'POST' });
const vc = async (path) => {
  try {
    const r = await fetch(`${VC}${path}`, { method: 'PUT' });
    if (!r.ok) console.log(`[demo] console ${path} → ${r.status}`);
  } catch (e) { console.log(`[demo] console ${path} failed: ${e.message}`); }
};
let car = { speed: 0, brake: 0, steering: 0 };
const controls = (c) => { car = { ...car, ...c }; return vc(`/vehicle/controls?speed=${car.speed}&accelerator=${car.brake ? 0 : 30}&brake=${car.brake}&steering=${car.steering}`); };
const gear = (g) => vc(`/vehicle/gear?gear=${g}`);
/** Ramp the speed (km/h) smoothly over ms. */
const ramp = async (to, ms, brake = 0) => {
  const from = car.speed, steps = Math.max(1, Math.round(ms / 250));
  for (let i = 1; i <= steps; i++) { await controls({ speed: Math.round(from + (to - from) * i / steps), brake }); await sleep(ms / steps); }
};
const hazard = (on) => Promise.all(['TurnSignalLeft', 'TurnSignalRight'].map((t) => vc(`/vehicle/telltales/simulator?type=${t}&state=${on ? 'Blink' : 'Off'}&color=Green`)));
const logcatHas = (text) => adb('logcat', '-d', '-s', 'CareDrive:I').includes(text);

// Start parked and still, hazard off.
await controls({ speed: 0, brake: 60, steering: 0 });
await gear('Park');
await hazard(false);

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
// Video and sound are two scrcpy sessions: one session doing both came out silent on this emulator,
// while an audio-only session records the car's voice and chimes reliably. compose.py lines them up.
const rec = spawn('scrcpy', [
  '--no-window', '--no-playback', '--no-audio', '--max-size=1920', '--video-bit-rate=12M',
  `--record=${join(OUT, 'car.mkv')}`,
], { env: { ...process.env, ADB } });
const recAudio = spawn('scrcpy', [
  '--no-window', '--no-playback', '--no-video', '--audio-source=playback', '--audio-dup',
  `--record=${join(OUT, 'car_audio.mka')}`,
], { env: { ...process.env, ADB } });
// scrcpy buffers its log when not on a terminal, so watch the files instead: recording starts when they grow.
timeline.audioStart = 0;
for (let i = 0; i < 150 && (timeline.carStart === 0 || timeline.audioStart === 0); i++) {
  await sleep(100);
  try { if (!timeline.carStart && statSync(join(OUT, 'car.mkv')).size > 0) timeline.carStart = Date.now(); } catch {}
  try { if (!timeline.audioStart && statSync(join(OUT, 'car_audio.mka')).size > 0) timeline.audioStart = Date.now(); } catch {}
}
await sleep(1000);

const tapCar = (text) => {
  const xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty');
  const m = xml.match(new RegExp(`text="${text}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
  if (!m) { console.log(`[demo] car button not found: ${text}`); return; }
  adb('shell', 'input', 'tap', String((+m[1] + +m[3]) >> 1), String((+m[2] + +m[4]) >> 1));
};

// --- scenario ------------------------------------------------------------------------------------
const phone = (msg) => js(`send(${JSON.stringify(msg)})`);

chapter('1. 출발 전 컨디션');
await js(`slide('sleep', 280, 1200)`);
await js(`slide('energy', 45, 900)`);
await js(`tap('sendCondition')`);
await sleep(7000);

chapter('2. 주행 · 실제 기어 D와 속도');
await js(`tap('driveReal')`); // the car follows its real gear from here on
await controls({ brake: 60 });
await gear('Drive');
await sleep(800);
await ramp(60, 4000);
await sleep(1500);
await ramp(80, 3000);
await sleep(3000);

chapter('3. 긴장 · 힘든 일이 있었나요?');
await js(`slide('hr', 112, 2500)`);
await phone({ type: 'advance', minutes: 5 }); // heart rate high for 5 minutes (time skip)
await sleep(6500);
tapCar('좀 그래요');
await sleep(7000);
await js(`slide('hr', 76, 1500)`);
await sleep(1500);

chapter('4. 졸음 · 핸들 신호');
// Real steering signal: no steering for >10 s (held since chapter 2), then a sudden correction.
await controls({ steering: 28 });
await sleep(400);
await controls({ steering: 4 });
await sleep(3000);
if (!logcatHas('DROWSY_ASK')) { console.log('[demo] steering alone did not ask: adding the demo HR-drop signal'); await phone({ type: 'drowsy', hrDrop: 10 }); }
await sleep(4000);
tapCar('쉬어 갈게요');
await sleep(6000);
await phone({ type: 'drowsy', clear: true });
adb('shell', 'am', 'start', '-n', 'ai.caredrive.car/.MainActivity'); // back in front of the navigation
await sleep(1500);

chapter('5. 심장 이상 · 비상등 · 정차 · 연락');
await js(`tap('sendIhrn')`);
await sleep(6000);
tapCar('도움이 필요해요');
await sleep(4000);
adb('shell', 'am', 'start', '-n', 'ai.caredrive.car/.MainActivity');
await hazard(true);
await sleep(2000);
await ramp(0, 4000, 50);
await gear('Park');
await sleep(8000);

chapter('6. 맥박 없음 · 3분 타이머 10배속');
await hazard(false);
await js(`tap('pulseAbsent')`);
await sleep(1500);
await js(`tap('speed10')`); // 180 s timer shown 10x faster (demo only)
await sleep(19000);
await js(`tap('speed1')`);
await sleep(4000);

// --- finish --------------------------------------------------------------------------------------
timeline.carEnd = Date.now();
clearInterval(heartbeat);
await hazard(false);
await call('Page.stopScreencast');
rec.kill('SIGINT');
recAudio.kill('SIGINT');
await Promise.all([new Promise((r) => rec.once('exit', r)), new Promise((r) => recAudio.once('exit', r))]);

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
