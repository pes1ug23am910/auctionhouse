import http from 'node:http';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { randomUUID } from 'node:crypto';
import { ClientBuffer } from './buffer.js';
import { cursor, encode, EventRing, Parser } from './sse.js';
import type { Cursor, Frame, StoredEvent } from './sse.js';

interface Snapshot {
  auction: { id: string; version: number; [key: string]: unknown };
  cursor: string;
  serverTime?: string;
}
interface Authorization { actorId: string; snapshot: Snapshot }
interface Client {
  id: string; actorId: string; auctionId: string; credentials: Record<string, string>;
  response: ServerResponse; writer: ClientBuffer; position: Cursor;
  lastAuthorizedAt: number; validating: boolean; closed: boolean;
  authTimer?: NodeJS.Timeout; heartbeat?: NodeJS.Timeout; group: Group;
}
interface Group {
  key: string; actorId: string; auctionId: string; clients: Map<string, Client>;
  ring: EventRing; epoch: number; abort?: AbortController; sourceClient?: string;
  disposed: boolean;
}
export interface GatewayOptions {
  upstream: string;
  maxEvents?: number;
  maxBytes?: number;
  authIntervalMs?: number;
  authTimeoutMs?: number;
  idleTimeoutMs?: number;
}
export interface Metrics {
  connected: number; upstreamOpened: number; events: number; replayed: number;
  duplicates: number; gaps: number; slowDisconnected: number; authDisconnected: number;
  upstreamFailures: number; authorizationRequests: number;
}
class Rejected extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}

export function createGateway(options: GatewayOptions) {
  const upstream = new URL(options.upstream);
  if (!['http:', 'https:'].includes(upstream.protocol)) throw new Error('UPSTREAM_PROTOCOL');
  const maxEvents = options.maxEvents ?? 128;
  const maxBytes = options.maxBytes ?? 262_144;
  const authIntervalMs = options.authIntervalMs ?? 750;
  const authTimeoutMs = options.authTimeoutMs ?? 650;
  const idleTimeoutMs = options.idleTimeoutMs ?? 30_000;
  if (maxEvents < 1 || maxBytes < 512 || authIntervalMs < 25 || authIntervalMs > 1000
    || authTimeoutMs < 10 || authTimeoutMs > 1000) throw new Error('INVALID_GATEWAY_LIMITS');
  const groups = new Map<string, Group>();
  const metrics: Metrics = { connected: 0, upstreamOpened: 0, events: 0, replayed: 0,
    duplicates: 0, gaps: 0, slowDisconnected: 0, authDisconnected: 0, upstreamFailures: 0, authorizationRequests: 0 };
  let stopping = false;

  function url(path: string) { return new URL(path, upstream).toString(); }
  function credentials(request: IncomingMessage): Record<string, string> {
    const result: Record<string, string> = {};
    if (typeof request.headers.cookie === 'string') result.Cookie = request.headers.cookie;
    if (typeof request.headers.authorization === 'string') result.Authorization = request.headers.authorization;
    return result;
  }

  async function readJson(path: string, headers: Record<string, string>): Promise<unknown> {
    metrics.authorizationRequests++;
    const response = await fetch(url(path), { headers, redirect: 'error', signal: AbortSignal.timeout(authTimeoutMs) });
    if (!response.ok) {
      await response.body?.cancel();
      throw new Rejected([401, 403, 404].includes(response.status) ? response.status : 502,
        response.status === 401 ? 'AUTHENTICATION_REQUIRED' : response.status === 403 ? 'FORBIDDEN' : response.status === 404 ? 'NOT_FOUND' : 'UPSTREAM_UNAVAILABLE');
    }
    return response.json();
  }

  async function authorize(headers: Record<string, string>, auctionId: string): Promise<Authorization> {
    const [identity, snapshot] = await Promise.all([
      readJson('/api/auth/session', headers),
      readJson(`/api/auctions/${encodeURIComponent(auctionId)}/snapshot`, headers),
    ]);
    const actor = identity as { id?: unknown };
    const state = snapshot as Snapshot;
    const position = state && typeof state.cursor === 'string' ? cursor(state.cursor) : null;
    if (!actor || typeof actor.id !== 'string' || !state?.auction
      || state.auction.id !== auctionId || !position || position.auctionId !== auctionId
      || position.version !== state.auction.version) throw new Rejected(502, 'INVALID_UPSTREAM_STATE');
    return { actorId: actor.id, snapshot: state };
  }

  function remove(client: Client, restart = true) {
    if (client.closed) return;
    client.closed = true;
    if (client.authTimer) clearInterval(client.authTimer);
    if (client.heartbeat) clearInterval(client.heartbeat);
    client.writer.close();
    const group = client.group;
    group.clients.delete(client.id);
    if (!group.clients.size) {
      group.disposed = true; group.epoch++; group.abort?.abort(); groups.delete(group.key);
    } else if (restart && group.sourceClient === client.id) {
      startSource(group);
    }
  }

  function fail(group: Group, code: string) {
    if (group.disposed) return;
    group.disposed = true; group.epoch++; group.abort?.abort(); groups.delete(group.key);
    for (const client of [...group.clients.values()]) {
      client.writer.send(encode({ event: 'gap', id: '', data: JSON.stringify({ code, action: 'snapshot' }) }));
      remove(client, false);
    }
  }

  function authorizedForDelivery(client: Client): boolean {
    if (client.closed) return false;
    if (Date.now() - client.lastAuthorizedAt > 1000) {
      metrics.authDisconnected++; remove(client); return false;
    }
    return true;
  }

  function deliver(client: Client, event: StoredEvent) {
    if (!authorizedForDelivery(client)) return;
    if (event.cursor.generation !== client.position.generation
      || event.cursor.version > client.position.version + 1) {
      metrics.gaps++;
      client.writer.send(encode({ event: 'gap', id: '', data: '{"code":"SNAPSHOT_REQUIRED","action":"snapshot"}' }));
      remove(client); return;
    }
    if (event.cursor.version <= client.position.version) return;
    if (client.writer.send(event.wire)) client.position = event.cursor;
  }

  function sourceFrame(group: Group, frame: Frame) {
    if (frame.event === 'auction') {
      const result = group.ring.accept(frame);
      if (result.kind === 'gap') { metrics.gaps++; fail(group, 'UPSTREAM_GAP'); return; }
      if (result.kind === 'duplicate') { metrics.duplicates++; return; }
      metrics.events++;
      for (const client of [...group.clients.values()]) deliver(client, result.value);
    } else if (frame.event === 'snapshot') {
      let snapshot: Snapshot;
      try { snapshot = JSON.parse(frame.data); } catch { fail(group, 'INVALID_SNAPSHOT'); return; }
      const position = snapshot && typeof snapshot.cursor === 'string' ? cursor(snapshot.cursor) : null;
      if (!position || position.auctionId !== group.auctionId
        || snapshot.auction?.id !== group.auctionId || snapshot.auction.version !== position.version) {
        fail(group, 'INVALID_SNAPSHOT'); return;
      }
      if (position.generation === group.ring.head.generation && position.version < group.ring.head.version) return;
      group.ring.reset(snapshot.cursor);
      for (const client of [...group.clients.values()]) {
        if (!authorizedForDelivery(client)) continue;
        if (position.generation === client.position.generation && position.version < client.position.version) continue;
        if (client.writer.send(encode({ ...frame, id: snapshot.cursor }))) client.position = position;
      }
    } else if (frame.event === 'gap') {
      metrics.gaps++; fail(group, 'UPSTREAM_GAP');
    }
  }

  async function pump(group: Group, epoch: number, abort: AbortController) {
    const current = () => !group.disposed && group.epoch === epoch && !abort.signal.aborted;
    let chosen: Client | undefined;
    for (const candidate of [...group.clients.values()]) {
      try {
        const result = await authorize(candidate.credentials, candidate.auctionId);
        if (!current()) return;
        if (candidate.closed) continue;
        if (result.actorId !== candidate.actorId) throw new Rejected(401, 'IDENTITY_CHANGED');
        candidate.lastAuthorizedAt = Date.now(); chosen = candidate; break;
      } catch {
        if (!current()) return;
        metrics.authDisconnected++; remove(candidate, false);
      }
    }
    if (!current()) return;
    if (!chosen) { if (group.clients.size) startSource(group); return; }
    group.sourceClient = chosen.id;
    const headers: Record<string, string> = { ...chosen.credentials, Accept: 'text/event-stream',
      'Last-Event-ID': group.ring.lastCursor };
    const connectionTimeout = setTimeout(() => abort.abort(), 5_000);
    try {
      const response = await fetch(url(`/api/auctions/${encodeURIComponent(group.auctionId)}/events?cursor=${encodeURIComponent(group.ring.lastCursor)}`),
        { headers, redirect: 'error', signal: abort.signal });
      clearTimeout(connectionTimeout);
      if (!current()) { await response.body?.cancel(); return; }
      if (response.status === 401 || response.status === 403) {
        await response.body?.cancel();
        metrics.authDisconnected++; remove(chosen, false);
        if (!group.disposed) startSource(group);
        return;
      }
      if (!response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
        await response.body?.cancel(); metrics.upstreamFailures++; fail(group, 'UPSTREAM_UNAVAILABLE'); return;
      }
      metrics.upstreamOpened++;
      const parser = new Parser(maxBytes);
      let lastData = Date.now();
      const idle = setInterval(() => {
        if (Date.now() - lastData > idleTimeoutMs) { metrics.upstreamFailures++; fail(group, 'UPSTREAM_IDLE'); }
      }, Math.min(1000, idleTimeoutMs));
      const reader = response.body.getReader();
      try {
        while (current()) {
          const read = await reader.read();
          if (read.done) break;
          lastData = Date.now();
          for (const frame of parser.feed(read.value)) {
            if (!current()) break;
            sourceFrame(group, frame);
          }
        }
      } finally {
        clearInterval(idle);
        await reader.cancel().catch(() => {});
        reader.releaseLock();
      }
      if (current()) { metrics.upstreamFailures++; fail(group, 'UPSTREAM_ENDED'); }
    } catch {
      if (!group.disposed && group.epoch === epoch) { metrics.upstreamFailures++; fail(group, 'UPSTREAM_UNAVAILABLE'); }
    } finally { clearTimeout(connectionTimeout); }
  }

  function startSource(group: Group) {
    if (group.disposed || !group.clients.size) return;
    group.epoch++; group.abort?.abort();
    group.abort = new AbortController();
    const epoch = group.epoch;
    void pump(group, epoch, group.abort);
  }

  async function revalidate(client: Client) {
    if (client.closed || client.validating) return;
    client.validating = true;
    try {
      const result = await authorize(client.credentials, client.auctionId);
      if (client.closed) return;
      if (result.actorId !== client.actorId) throw new Rejected(401, 'IDENTITY_CHANGED');
      client.lastAuthorizedAt = Date.now();
    } catch {
      if (!client.closed) { metrics.authDisconnected++; remove(client); }
    } finally { client.validating = false; }
  }

  async function route(request: IncomingMessage, response: ServerResponse) {
    response.setHeader('X-Content-Type-Options', 'nosniff');
    if (request.method !== 'GET') { response.writeHead(405, { Allow: 'GET' }).end(); return; }
    const target = new URL(request.url ?? '/', 'http://gateway.local');
    if (target.pathname === '/health') {
      response.writeHead(stopping ? 503 : 200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ status: stopping ? 'stopping' : 'ok', groups: groups.size,
        clients: [...groups.values()].reduce((count, group) => count + group.clients.size, 0), metrics }));
      return;
    }
    const match = /^\/api\/auctions\/([A-Za-z0-9-]{1,80})\/events$/.exec(target.pathname);
    if (!match || stopping) { response.writeHead(404).end(); return; }
    const auctionId = match[1];
    const requested = typeof request.headers['last-event-id'] === 'string'
      ? request.headers['last-event-id'] : target.searchParams.get('cursor');
    if (requested) {
      const position = cursor(requested);
      if (!position || position.auctionId !== auctionId) throw new Rejected(400, 'INVALID_CURSOR');
    }
    const headers = credentials(request);
    if (!headers.Cookie && !headers.Authorization) throw new Rejected(401, 'AUTHENTICATION_REQUIRED');
    const authorization = await authorize(headers, auctionId);
    if (response.destroyed) return;
    if (stopping) { response.writeHead(503).end(); return; }
    const key = JSON.stringify([auctionId, authorization.actorId]);
    let group = groups.get(key);
    const isNew = !group;
    if (!group) {
      group = { key, actorId: authorization.actorId, auctionId, clients: new Map(),
        ring: new EventRing(authorization.snapshot.cursor, maxEvents, maxBytes), epoch: 0, disposed: false };
      groups.set(key, group);
    }
    response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-store', Connection: 'keep-alive', 'X-Accel-Buffering': 'no' });
    response.flushHeaders();
    request.socket.setNoDelay(true);
    const client = { id: randomUUID(), actorId: authorization.actorId, auctionId, credentials: headers,
      response, position: cursor(authorization.snapshot.cursor)!, lastAuthorizedAt: Date.now(),
      validating: false, closed: false, group } as Client;
    client.writer = new ClientBuffer(response, maxEvents, maxBytes, () => { metrics.slowDisconnected++; remove(client); });
    group.clients.set(client.id, client);
    response.on('close', () => remove(client));
    metrics.connected++;
    const replay = requested ? group.ring.replay(requested, authorization.snapshot.auction.version) : null;
    if (replay) {
      client.position = cursor(requested!)!;
      client.writer.send(': connected\n\n');
      for (const event of replay) { metrics.replayed++; deliver(client, event); }
    } else {
      client.writer.send(encode({ event: 'snapshot', id: authorization.snapshot.cursor, data: JSON.stringify(authorization.snapshot) }));
    }
    if (client.closed) return;
    client.authTimer = setInterval(() => { void revalidate(client); }, authIntervalMs);
    client.heartbeat = setInterval(() => { if (authorizedForDelivery(client)) client.writer.send(': keepalive\n\n'); }, 10_000);
    if (isNew && !client.closed) startSource(group);
  }

  const server = http.createServer((request, response) => {
    void route(request, response).catch(error => {
      if (response.headersSent) { response.destroy(); return; }
      const failure = error instanceof Rejected ? error : new Rejected(502, 'UPSTREAM_UNAVAILABLE');
      response.writeHead(failure.status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify({ code: failure.code }));
    });
  });
  server.requestTimeout = 15_000;
  server.headersTimeout = 10_000;
  return {
    server, metrics,
    async close() {
      if (stopping) return;
      stopping = true;
      for (const group of [...groups.values()]) fail(group, 'GATEWAY_RESTART');
      await new Promise<void>((resolve, reject) => {
        server.close(error => error ? reject(error) : resolve());
        server.closeIdleConnections();
      });
    },
  };
}
