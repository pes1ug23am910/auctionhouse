import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import type { ServerResponse } from 'node:http';
import { setTimeout as delay } from 'node:timers/promises';
import { createGateway } from '../src/server.js';
import { encode, Parser } from '../src/sse.js';
import type { Frame } from '../src/sse.js';

async function listen(server: http.Server): Promise<string> {
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  assert.ok(address && typeof address !== 'string');
  return 'http://127.0.0.1:' + address.port;
}
async function until(check: () => boolean, timeout = 3000) {
  const end = Date.now() + timeout;
  while (!check()) { if (Date.now() >= end) throw new Error('condition timed out'); await delay(10); }
}

class Upstream {
  version = 1;
  tokens = new Map([['a', 'actor'], ['b', 'actor'], ['other', 'other-actor']]);
  denied = new Set<string>();
  streams = new Map<ServerResponse, string>();
  opened: string[] = [];
  authChecks = 0;
  perTokenChecks = new Map<string, number>();
  pauseSecondA = false;
  releaseSecondA: (() => void) | undefined;
  server = http.createServer((request, response) => {
    const token = request.headers.cookie?.replace('session=', '') ?? '';
    const actor = this.tokens.get(token);
    const path = new URL(request.url ?? '/', 'http://fixture').pathname;
    if (!actor) { response.writeHead(401).end(); return; }
    if (path === '/api/auth/session') {
      this.authChecks++;
      const checks = (this.perTokenChecks.get(token) ?? 0) + 1;
      this.perTokenChecks.set(token, checks);
      const send = () => { if (response.writableEnded || response.destroyed) return; response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ id: actor })); };
      if (token === 'a' && checks === 2 && this.pauseSecondA) this.releaseSecondA = send; else send();
      return;
    }
    if (this.denied.has(actor)) { response.writeHead(403).end(); return; }
    if (path === '/api/auctions/lot/snapshot') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(this.snapshot())); return;
    }
    if (path === '/api/auctions/lot/events') {
      response.writeHead(200, { 'Content-Type': 'text/event-stream' });
      response.write(': fixture connected\n\n');
      this.streams.set(response, token); this.opened.push(token);
      response.on('close', () => this.streams.delete(response)); return;
    }
    response.writeHead(404).end();
  });
  snapshot() { return { auction: { id: 'lot', version: this.version, status: 'OPEN' }, cursor: 'v1:lot:' + this.version, serverTime: new Date().toISOString() }; }
  publish(version: number) {
    this.version = Math.max(version, this.version);
    const frame: Frame = { event: 'auction', id: 'v1:lot:' + version, data: JSON.stringify({
      eventId: 'event-' + version, eventType: 'bid.accepted', schemaVersion: 1,
      aggregateId: 'lot', aggregateVersion: version, occurredAt: '2026-01-01T00:00:00Z',
      payload: { id: 'lot', version, status: 'OPEN' },
    }) };
    for (const response of this.streams.keys()) response.write(encode(frame));
  }
  async close() {
    for (const response of this.streams.keys()) response.destroy();
    await new Promise<void>(resolve => { this.server.close(() => resolve()); this.server.closeIdleConnections(); });
  }
}

class Connection {
  private parser = new Parser();
  private frames: Frame[] = [];
  constructor(readonly abort: AbortController, private reader: ReadableStreamDefaultReader<Uint8Array>) {}
  async next(timeout = 3000): Promise<Frame | null> {
    const deadline = Date.now() + timeout;
    for (;;) {
      if (this.frames.length) return this.frames.shift()!;
      let timer: NodeJS.Timeout | undefined;
      try {
        const result = await Promise.race([
          this.reader.read(),
          new Promise<never>((_, reject) => { timer = setTimeout(() => { this.abort.abort(); reject(new Error('frame timeout')); }, Math.max(1, deadline - Date.now())); }),
        ]);
        if (result.done) return null;
        this.frames.push(...this.parser.feed(result.value));
      } finally { if (timer) clearTimeout(timer); }
    }
  }
  async end() { this.abort.abort(); await this.reader.cancel().catch(() => {}); }
  async isClosed() {
    try { while (await this.next()) { /* Control frames may precede EOF. */ } return true; }
    catch (error) { if (error instanceof Error && error.message === 'frame timeout') throw error; return true; }
  }
}

async function open(base: string, token: string, last?: string) {
  const abort = new AbortController();
  const response = await fetch(base + '/api/auctions/lot/events' + (last ? '?cursor=' + encodeURIComponent(last) : ''),
    { headers: { Cookie: 'session=' + token }, signal: abort.signal });
  assert.equal(response.status, 200);
  assert.ok(response.body);
  return new Connection(abort, response.body.getReader());
}

async function setup() {
  const upstream = new Upstream();
  const upstreamUrl = await listen(upstream.server);
  const gateway = createGateway({ upstream: upstreamUrl, authIntervalMs: 50, authTimeoutMs: 500, idleTimeoutMs: 10000 });
  const base = await listen(gateway.server);
  return { upstream, upstreamUrl, gateway, base,
    async close() { await gateway.close(); await upstream.close(); } };
}

test('unauthenticated, unauthorized and wrong-auction cursors never open an upstream stream', async () => {
  const env = await setup();
  try {
    assert.equal((await fetch(env.base + '/api/auctions/lot/events')).status, 401);
    assert.equal((await fetch(env.base + '/api/auctions/lot/events?cursor=v1:other:1', { headers: { Cookie: 'session=a' } })).status, 400);
    env.upstream.denied.add('actor');
    assert.equal((await fetch(env.base + '/api/auctions/lot/events', { headers: { Cookie: 'session=a' } })).status, 403);
    assert.equal(env.upstream.opened.length, 0);
  } finally { await env.close(); }
});

test('fan-out shares by actor and auction while validating each client credential', async () => {
  const env = await setup();
  const clients: Connection[] = [];
  try {
    const a = await open(env.base, 'a'); clients.push(a); assert.equal((await a.next())?.event, 'snapshot');
    await until(() => env.gateway.metrics.upstreamOpened === 1);
    const b = await open(env.base, 'b'); clients.push(b); await b.next();
    const other = await open(env.base, 'other'); clients.push(other); await other.next();
    await until(() => env.gateway.metrics.upstreamOpened === 2);
    env.upstream.publish(2);
    for (const client of clients) assert.equal((await client.next())?.id, 'v1:lot:2');
    assert.equal(env.gateway.metrics.upstreamOpened, 2);
    assert.ok(env.upstream.authChecks >= 3);
  } finally { for (const client of clients) await client.end(); await env.close(); }
});

test('resume replays a retained contiguous suffix and ignores duplicate/out-of-order old input', async () => {
  const env = await setup();
  const clients: Connection[] = [];
  try {
    const a = await open(env.base, 'a'); clients.push(a); await a.next();
    await until(() => env.gateway.metrics.upstreamOpened === 1);
    env.upstream.publish(2); assert.equal((await a.next())?.id, 'v1:lot:2');
    env.upstream.publish(2); env.upstream.publish(1);
    env.upstream.publish(3); assert.equal((await a.next())?.id, 'v1:lot:3');
    const b = await open(env.base, 'b', 'v1:lot:1'); clients.push(b);
    assert.equal((await b.next())?.id, 'v1:lot:2');
    assert.equal((await b.next())?.id, 'v1:lot:3');
    assert.equal(env.gateway.metrics.replayed, 2);
    assert.equal(env.gateway.metrics.duplicates, 2);
  } finally { for (const client of clients) await client.end(); await env.close(); }
});

test('a sequence gap disconnects clients and reconnect restores an authoritative snapshot', async () => {
  const env = await setup();
  const clients: Connection[] = [];
  try {
    const a = await open(env.base, 'a'); clients.push(a); await a.next();
    await until(() => env.gateway.metrics.upstreamOpened === 1);
    env.upstream.publish(3);
    assert.equal(await a.isClosed(), true);
    assert.equal(env.gateway.metrics.gaps, 1);
    const b = await open(env.base, 'b', 'v1:lot:1'); clients.push(b);
    assert.equal((await b.next())?.id, 'v1:lot:3');
  } finally { for (const client of clients) await client.end(); await env.close(); }
});

test('revoking one credential disconnects that client and switches the shared source to a valid remaining client', async () => {
  const env = await setup();
  const clients: Connection[] = [];
  try {
    const a = await open(env.base, 'a'); clients.push(a); await a.next();
    await until(() => env.gateway.metrics.upstreamOpened === 1);
    const b = await open(env.base, 'b'); clients.push(b); await b.next();
    const revokedAt = Date.now();
    env.upstream.tokens.delete('a');
    assert.equal(await a.isClosed(), true);
    assert.ok(Date.now() - revokedAt < 1000);
    await until(() => env.upstream.opened.includes('b'));
    env.upstream.publish(2);
    assert.equal((await b.next())?.id, 'v1:lot:2');
    assert.ok(env.gateway.metrics.authDisconnected >= 1);
  } finally { for (const client of clients) await client.end(); await env.close(); }
});

test('permission revocation is checked at the snapshot boundary, not just session identity', async () => {
  const env = await setup();
  let connection: Connection | undefined;
  try {
    connection = await open(env.base, 'a'); await connection.next();
    env.upstream.denied.add('actor');
    assert.equal(await connection.isClosed(), true);
    assert.ok(env.gateway.metrics.authDisconnected >= 1);
  } finally { await connection?.end(); await env.close(); }
});

test('gateway restart loses only its buffer and resumes from an authorized current snapshot', async () => {
  const env = await setup();
  let first: Connection | undefined;
  let second: Connection | undefined;
  let replacement: ReturnType<typeof createGateway> | undefined;
  try {
    first = await open(env.base, 'a'); await first.next();
    await until(() => env.gateway.metrics.upstreamOpened === 1);
    env.upstream.publish(2); await first.next();
    await env.gateway.close();
    assert.equal(await first.isClosed(), true);
    env.upstream.publish(3);
    replacement = createGateway({ upstream: env.upstreamUrl, authIntervalMs: 50, authTimeoutMs: 500 });
    const address = await listen(replacement.server);
    second = await open(address, 'b', 'v1:lot:2');
    const recovered = await second.next();
    assert.equal(recovered?.event, 'snapshot');
    assert.equal(recovered?.id, 'v1:lot:3');
    await until(() => replacement!.metrics.upstreamOpened === 1);
    env.upstream.publish(4);
    assert.equal((await second.next())?.id, 'v1:lot:4');
  } finally { await first?.end(); await second?.end(); await replacement?.close(); await env.close(); }
});


test('a client leaving during source authorization does not strand newly joined clients', async () => {
  const env = await setup();
  const clients: Connection[] = [];
  try {
    env.upstream.pauseSecondA = true;
    const a = await open(env.base, 'a'); clients.push(a); await a.next();
    await until(() => Boolean(env.upstream.releaseSecondA));
    const b = await open(env.base, 'b'); clients.push(b); await b.next();
    await a.end();
    await delay(10);
    env.upstream.releaseSecondA!();
    await until(() => env.upstream.opened.includes('b'));
    env.upstream.publish(2);
    assert.equal((await b.next())?.id, 'v1:lot:2');
  } finally { env.upstream.releaseSecondA?.(); for (const client of clients) await client.end(); await env.close(); }
});


test('authorization traffic counter records both identity and snapshot attempts, including rejected requests', async () => {
  const upstream = new Upstream();
  const upstreamUrl = await listen(upstream.server);
  const gateway = createGateway({ upstream: upstreamUrl, authIntervalMs: 1000 });
  const base = await listen(gateway.server);
  let client: Connection | undefined;
  try {
    client = await open(base, 'a'); await client.next();
    await until(() => gateway.metrics.upstreamOpened === 1);
    assert.equal(gateway.metrics.authorizationRequests, 4);
    assert.equal((await fetch(base + '/api/auctions/lot/events', { headers: { Cookie: 'session=invalid' } })).status, 401);
    assert.equal(gateway.metrics.authorizationRequests, 6);
  } finally { await client?.end(); await gateway.close(); await upstream.close(); }
});
