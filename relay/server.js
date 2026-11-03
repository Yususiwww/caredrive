// CareDrive relay: forwards messages between one phone and one car over WebSocket.
//   phone (or phone-sim page)  ws://<host>:8787/phone
//   car (Pleos emulator)       ws://10.0.2.2:8787/car   (10.0.2.2 = the Mac, seen from the emulator)
// Also serves ../phone-sim at http://localhost:8787/ so the simulator needs no separate server.
// See docs/protocol.md for message types.

import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';

const PORT = Number(process.env.PORT ?? 8787);
const STATIC_DIR = fileURLToPath(new URL('../phone-sim/', import.meta.url));
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css' };

/** Messages each side may send. Anything else is dropped. */
export const ALLOWED = {
  phone: new Set(['condition', 'live', 'ihrn', 'drive', 'advance', 'scenario']),
  car: new Set(['status']),
};

export function createRelay({ port = PORT, log = console.log } = {}) {
  const peers = { phone: null, car: null };
  // Last condition/live from the phone, replayed when the car (re)connects.
  const latest = { condition: null, live: null };

  const http = createServer(async (req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(/^(\.\.[/\\])+/, '');
    const file = join(STATIC_DIR, path === '/' ? 'index.html' : path);
    if (!file.startsWith(STATIC_DIR)) { res.writeHead(403).end(); return; }
    try {
      const body = await readFile(file);
      res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' }).end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });

  const wss = new WebSocketServer({ noServer: true });

  const send = (ws, msg) => { if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg)); };
  const announcePeers = () => {
    const msg = { type: 'peers', phone: !!peers.phone, car: !!peers.car };
    send(peers.phone, msg);
    send(peers.car, msg);
  };

  http.on('upgrade', (req, socket, head) => {
    const role = new URL(req.url, 'http://x').pathname.slice(1);
    if (role !== 'phone' && role !== 'car') { socket.destroy(); return; }
    wss.handleUpgrade(req, socket, head, (ws) => {
      peers[role]?.close(4000, 'replaced');
      peers[role] = ws;
      log(`[relay] ${role} connected`);
      announcePeers();
      if (role === 'car') {
        if (latest.condition) send(ws, latest.condition);
        if (latest.live) send(ws, latest.live);
      }

      ws.on('message', (data) => {
        let msg;
        try { msg = JSON.parse(data.toString()); } catch { return; }
        if (!msg || !ALLOWED[role].has(msg.type)) return;
        if (role === 'phone' && (msg.type === 'condition' || msg.type === 'live')) latest[msg.type] = msg;
        send(peers[role === 'phone' ? 'car' : 'phone'], msg);
      });

      ws.on('close', () => {
        if (peers[role] === ws) peers[role] = null;
        log(`[relay] ${role} disconnected`);
        announcePeers();
      });
    });
  });

  return new Promise((resolve) => {
    http.listen(port, () => {
      const { port: actual } = http.address();
      resolve({ port: actual, close: () => { wss.clients.forEach((c) => c.terminate()); http.close(); } });
    });
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { port } = await createRelay();
  console.log(`[relay] listening on :${port}`);
  console.log(`[relay] phone simulator: http://localhost:${port}/`);
  console.log(`[relay] car (emulator):  ws://10.0.2.2:${port}/car`);
}
