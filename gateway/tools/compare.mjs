import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { Parser } from '../dist/src/sse.js';
import { Observation, summarize } from './comparison-metrics.mjs';

const base = process.env.AUCTIONHOUSE_UPSTREAM ?? 'http://127.0.0.1:8080';
const gateway = process.env.AUCTIONHOUSE_GATEWAY ?? 'http://127.0.0.1:3001';
const password = process.env.AUCTIONHOUSE_DEMO_PASSWORD;
const count = Number(process.env.AUCTIONHOUSE_COMPARISON_EVENTS ?? '20');
const cadence = Number(process.env.AUCTIONHOUSE_COMPARISON_INTERVAL_MS ?? '25');
const clientCount = Number(process.env.AUCTIONHOUSE_COMPARISON_CLIENTS ?? '1');
const mode = process.env.AUCTIONHOUSE_COMPARISON_MODE ?? 'paired';
const settleMs = Number(process.env.AUCTIONHOUSE_COMPARISON_SETTLE_MS ?? '1000');
const localOrigin = value => {
  const url = new URL(value);
  return url.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(url.hostname)
    && !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash;
};
if (!password || !localOrigin(base) || !localOrigin(gateway)
  || !Number.isInteger(count) || count < 1 || count > 200
  || !Number.isInteger(cadence) || cadence < 0 || cadence > 1000
  || !Number.isInteger(clientCount) || clientCount < 1 || clientCount > 32
  || !Number.isInteger(settleMs) || settleMs < 0 || settleMs > 5000
  || !['paired', 'java', 'gateway'].includes(mode) || count * (cadence + 250) > 180000) {
  throw new Error('Provide a local demo password, loopback origins and bounded comparison settings.');
}

const startedAt = new Date().toISOString();
const t0 = performance.now();
const initialCpu = process.cpuUsage();
class Session {
  cookies = new Map();
  lastTiming;
  header() { return [...this.cookies].map(([name, value]) => name + '=' + value).join('; '); }
  async request(path, method = 'GET', body, extra = {}) {
    let secure = {};
    if (method !== 'GET') {
      const csrf = await this.request('/api/auth/csrf');
      secure = { [csrf.headerName]: csrf.token };
    }
    const began = performance.now() - t0;
    const response = await fetch(base + path, {
      method, redirect: 'error', signal: AbortSignal.timeout(10000),
      headers: { Cookie: this.header(), ...secure,
        ...(body ? { 'Content-Type': 'application/json' } : {}), ...extra },
      body: body ? JSON.stringify(body) : undefined,
    });
    for (const entry of response.headers.getSetCookie()) {
      const pair = entry.split(';', 1)[0];
      const split = pair.indexOf('=');
      const name = pair.slice(0, split);
      const value = pair.slice(split + 1);
      if (/max-age=0/i.test(entry)) this.cookies.delete(name); else this.cookies.set(name, value);
    }
    const result = response.status === 204 ? null : await response.json();
    this.lastTiming = { postStartedMs: began, postFinishedMs: performance.now() - t0 };
    if (!response.ok) throw new Error('HTTP_' + response.status);
    return result;
  }
  async login(username) { await this.request('/api/auth/demo/login', 'POST', { username, password }); }
}

const seller = new Session();
const bidder = new Session();
const controllers = [];
const watchers = [];
const observations = [];
const expected = [];
let failure = null;
let auctionId;
let streamsConnectedMs;
let submissionsEndedMs;
let measurementStartedMs;
let measurementEndedMs;
let gatewayBefore;
let gatewayDuring;
let ending = false;

async function gatewayHealth() {
  if (mode === 'java') return null;
  const response = await fetch(gateway + '/health', { signal: AbortSignal.timeout(3000), redirect: 'error' });
  if (!response.ok) throw new Error('GATEWAY_HEALTH_' + response.status);
  const result = await response.json();
  return { groups: result.groups, clients: result.clients, metrics: result.metrics };
}

async function watch(path, origin, client, snapshot) {
  const observer = new Observation(path, client, snapshot.auction.id, snapshot.auction.version);
  observations.push(observer);
  const abort = new AbortController();
  controllers.push(abort);
  const timeout = setTimeout(() => abort.abort(), 10000);
  let response;
  try {
    response = await fetch(origin + '/api/auctions/' + snapshot.auction.id + '/events?cursor=' + encodeURIComponent(snapshot.cursor),
      { headers: { Cookie: bidder.header(), 'Last-Event-ID': snapshot.cursor }, signal: abort.signal, redirect: 'error' });
  } finally { clearTimeout(timeout); }
  if (!response.ok || !response.body) throw new Error(path.toUpperCase() + '_STREAM_' + response.status);
  observer.connectedMs = performance.now() - t0;
  const reader = response.body.getReader();
  const parser = new Parser();
  const task = (async () => {
    try {
      for (;;) {
        const chunk = await reader.read();
        if (chunk.done) {
          if (!ending) { observer.diagnostics.earlyEof++; failure ??= path.toUpperCase() + '_EARLY_EOF'; }
          break;
        }
        for (const frame of parser.feed(chunk.value)) observer.record(frame, performance.now() - t0);
      }
    } catch (error) {
      if (!abort.signal.aborted) failure ??= path.toUpperCase() + '_STREAM_' + (error?.name ?? 'ERROR');
    } finally { await reader.cancel().catch(() => {}); }
  })();
  watchers.push(task);
}

try {
  gatewayBefore = await gatewayHealth();
  await seller.login('seller');
  await bidder.login('bidder');
  const auction = await seller.request('/api/auctions', 'POST', {
    title: 'SSE gateway comparison ' + startedAt, description: 'Local paired-stream experiment; no payment.',
    openingPriceMinor: 100, minimumIncrementMinor: 10,
    endsAt: new Date(Date.now() + 600000).toISOString(),
  });
  auctionId = auction.id;
  await seller.request('/api/auctions/' + auction.id + '/publish', 'POST');
  const snapshot = await bidder.request('/api/auctions/' + auction.id + '/snapshot');
  const paths = mode === 'paired' ? [['java', base], ['gateway', gateway]]
    : [[mode, mode === 'java' ? base : gateway]];
  await Promise.all(paths.flatMap(([path, origin]) =>
    Array.from({ length: clientCount }, (_, client) => watch(path, origin, client, snapshot))));
  streamsConnectedMs = performance.now() - t0;
  if (settleMs) await delay(settleMs);
  measurementStartedMs = performance.now() - t0;
  for (let index = 0; index < count; index++) {
    if (failure) break;
    const outcome = await bidder.request('/api/auctions/' + auction.id + '/bids', 'POST',
      { amountMinor: 100 + index * 10 }, { 'Idempotency-Key': 'comparison-' + index });
    if (!outcome.accepted) throw new Error('BID_REJECTED');
    expected.push({ version: outcome.auctionVersion, ...bidder.lastTiming });
    if (cadence) await delay(cadence);
  }
  submissionsEndedMs = performance.now() - t0;
  const deadline = performance.now() + 30000;
  while (performance.now() < deadline && !failure
    && observations.some(observer => observer.missing(expected).length > 0)) await delay(20);
  measurementEndedMs = performance.now() - t0;
  gatewayDuring = await gatewayHealth();
} catch (error) {
  failure ??= /^[A-Z_0-9]+$/.test(error?.message ?? '') ? error.message : (error?.name ?? 'ERROR');
} finally {
  ending = true;
  for (const controller of controllers) controller.abort();
  await Promise.allSettled(watchers);
}
const summary = summarize(observations, expected);
const completedWorkload = expected.length === count;
const complete = completedWorkload && summary.complete;
const clean = complete && summary.clean && !failure;
if (!clean) {
  summary.pairedArrivalDifferenceMs = null;
  for (const client of summary.clients) client.postStartToArrivalMs = null;
}
const cpu = process.cpuUsage(initialCpu);
const report = {
  formatVersion: 2, startedAt, finishedAt: new Date().toISOString(),
  node: process.version, platform: process.platform, architecture: process.arch,
  workload: 'sequential-bids-v2', mode, clientsPerPath: clientCount,
  auctionId, expectedEvents: count, acceptedEvents: expected.length, intervalAfterResponseMs: cadence,
  settleMs, streamsConnectedMs, measurementStartedMs, submissionsEndedMs, measurementEndedMs,
  phases: { setupMs: streamsConnectedMs, settleMs: measurementStartedMs - streamsConnectedMs,
    submissionsMs: submissionsEndedMs - measurementStartedMs, drainMs: measurementEndedMs - submissionsEndedMs },
  workloadWallMs: measurementEndedMs - measurementStartedMs,
  ...summary, complete, clean, failure,
  gatewayBefore, gatewayDuring,
  collector: { elapsedMs: performance.now() - t0, cpuUserMs: cpu.user / 1000, cpuSystemMs: cpu.system / 1000,
    finalRssBytes: process.memoryUsage().rss },
  limits: [
    'One local actor and auction; same-actor clients model tabs, not independent users.',
    'Sequential bids wait for response then cadence; this is not a constant-arrival-rate capacity workload.',
    'POST-start-to-arrival includes mutation, database/poll scheduling and transport; it is not commit-to-arrival latency.',
    'Paired arrival deltas share one collector clock but separate Java source streams have independent poll phases.',
    'Samples from clients sharing one event are correlated; percentiles are descriptive, not confidence bounds.',
    'No automatic reconnect is attempted: disconnects/gaps/duplicates remain explicit and suppress clean timing claims.',
    'Collector CPU/RSS includes setup and observation; external resource sampling must name its interval and processes.',
  ],
};
process.stdout.write(JSON.stringify(report, null, 2) + '\n');
if (!clean) process.exitCode = 1;
