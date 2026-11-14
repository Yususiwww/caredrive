import { test } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocket } from 'ws';
import { createRelay } from './server.js';

const open = (url) => new Promise((resolve, reject) => {
  const ws = new WebSocket(url);
  const inbox = [];
  ws.on('message', (d) => inbox.push(JSON.parse(d.toString())));
  ws.once('open', () => resolve({ ws, inbox }));
  ws.once('error', reject);
});
const until = async (fn, ms = 1000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) { const v = fn(); if (v) return v; await new Promise((r) => setTimeout(r, 10)); }
  throw new Error('timeout');
};

test('forwards phone data to car and status back, drops unknown types', async () => {
  const relay = await createRelay({ port: 0, log: () => {} });
  const base = `ws://127.0.0.1:${relay.port}`;
  try {
    const phone = await open(`${base}/phone`);
    phone.ws.send(JSON.stringify({ type: 'live', hr: 101 }));
    const car = await open(`${base}/car`);

    // Car gets the latest live value replayed on connect, and both see each other.
    await until(() => car.inbox.find((m) => m.type === 'live' && m.hr === 101));
    await until(() => phone.inbox.find((m) => m.type === 'peers' && m.car));

    phone.ws.send(JSON.stringify({ type: 'ihrn' }));
    phone.ws.send(JSON.stringify({ type: 'status', level: 'L3' })); // phone may not send status
    await until(() => car.inbox.find((m) => m.type === 'ihrn'));
    assert.equal(car.inbox.some((m) => m.type === 'status'), false);

    car.ws.send(JSON.stringify({ type: 'status', level: 'L2_WARNING' }));
    await until(() => phone.inbox.find((m) => m.type === 'status' && m.level === 'L2_WARNING'));

    phone.ws.close();
    car.ws.close();
  } finally {
    relay.close();
  }
});

test('passes the check-in messages both ways and keeps roles apart', async () => {
  const relay = await createRelay({ port: 0, log: () => {} });
  const base = `ws://127.0.0.1:${relay.port}`;
  try {
    const phone = await open(`${base}/phone`);
    const car = await open(`${base}/car`);
    await until(() => phone.inbox.find((m) => m.type === 'peers' && m.car));

    for (const m of [
      { type: 'pulse', state: 'absent' },
      { type: 'pulse', state: 'weak' },
      { type: 'drowsy', steeringIdleSec: 12, hrDrop: 10 },
      { type: 'speed', x: 10 },
      { type: 'answer', ask: 'NO_PULSE', yes: false },
    ]) phone.ws.send(JSON.stringify(m));
    await until(() => car.inbox.find((m) => m.type === 'answer'));
    assert.deepEqual(car.inbox.filter((m) => m.type === 'pulse').map((m) => m.state), ['absent', 'weak']);
    assert.ok(car.inbox.find((m) => m.type === 'drowsy' && m.steeringIdleSec === 12));
    assert.ok(car.inbox.find((m) => m.type === 'speed' && m.x === 10));

    car.ws.send(JSON.stringify({ type: 'call', to: ['contacts'], reason: 'NO_PULSE', stage: 'pre_alert' }));
    car.ws.send(JSON.stringify({ type: 'call', to: ['contacts', '119_dial'], reason: 'NO_PULSE', stage: 'call' }));
    car.ws.send(JSON.stringify({ type: 'play', playlist: 'calm' }));
    car.ws.send(JSON.stringify({ type: 'ask', ask: 'STRESS_TALK', answer: 'play' }));
    car.ws.send(JSON.stringify({ type: 'pulse', state: 'absent' })); // car may not send phone-only types
    await until(() => phone.inbox.find((m) => m.type === 'ask'));
    assert.deepEqual(phone.inbox.filter((m) => m.type === 'call').map((m) => m.stage), ['pre_alert', 'call']);
    assert.deepEqual(phone.inbox.find((m) => m.stage === 'call').to, ['contacts', '119_dial']);
    assert.ok(phone.inbox.find((m) => m.type === 'play' && m.playlist === 'calm'));
    assert.equal(phone.inbox.some((m) => m.type === 'pulse'), false);

    // A phone may not ask for a call or a playlist itself.
    phone.ws.send(JSON.stringify({ type: 'call', to: ['119_dial'] }));
    phone.ws.send(JSON.stringify({ type: 'ihrn' }));
    await until(() => car.inbox.find((m) => m.type === 'ihrn'));
    assert.equal(car.inbox.some((m) => m.type === 'call'), false);

    phone.ws.close();
    car.ws.close();
  } finally {
    relay.close();
  }
});
