import http from 'k6/http';
import execution from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { validateSession } from './session-config.js';

function positive(name, fallback, maximum) {
  const value = Number(__ENV[name] || fallback);
  if (!Number.isInteger(value) || value < 1 || value > maximum) throw new Error('Invalid ' + name);
  return value;
}
const base = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const authMode = __ENV.AUTH_MODE || 'local-demo';
if (!['local-demo', 'session'].includes(authMode)) throw new Error('Invalid AUTH_MODE');
if (authMode === 'local-demo' && !/^http:\/\/(127\.0\.0\.1|localhost|host\.docker\.internal)(:\d+)?$/.test(base))
  throw new Error('Demo authentication is restricted to local URLs.');
if (authMode === 'session' && !/^https:\/\/[a-z0-9.-]+(?::[0-9]+)?$/.test(base))
  throw new Error('Session workloads require an exact HTTPS origin.');
const sessionInput = authMode === 'session' ? JSON.parse(open(__ENV.SESSION_FILE)) : null;
const rate = positive('RATE', 20, 10000);
const durationSeconds = positive('DURATION_SECONDS', 60, 300);
const seed = positive('SEED', 42, 2147483647);
const initialVUs = positive('PREALLOCATED_VUS', 40, 2000);
const maxVUs = positive('MAX_VUS', 80, 2000);
if (maxVUs < initialVUs) throw new Error('MAX_VUS must be at least PREALLOCATED_VUS');

export const options = {
  noCookiesReset: true,
  scenarios: { auction: {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: durationSeconds + 's',
    preAllocatedVUs: initialVUs, maxVUs, gracefulStop: '15s',
  } },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: { infrastructure_error_rate: ['rate<0.01'], dropped_iterations: ['count==0'] },
};
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 422));
const started = new Counter('logical_started');
const completed = new Counter('logical_completed');
const browseCount = new Counter('browse_operations');
const bidCount = new Counter('bid_operations');
const accepted = new Counter('accepted_bids');
const rejected = new Counter('business_rejected_bids');
const retries = new Counter('retry_attempts');
const recovered = new Counter('recovered_bid_outcomes');
const errors = new Rate('infrastructure_error_rate');
const latency = new Trend('logical_latency', true);
const browseLatency = new Trend('browse_latency', true);
const bidLatency = new Trend('bid_latency', true);
let installedCookies = false;

function decoded(response) { try { return response.json(); } catch (_) { return null; } }
function params(name, headers = {}) {
  return { headers, timeout: '10s', redirects: 0, tags: { name } };
}
function requireStatus(response, status, operation) {
  if (response.status !== status) throw new Error(operation + ' failed with HTTP ' + response.status);
  return decoded(response);
}
function csrf() {
  const body = requireStatus(http.get(base + '/api/auth/csrf', params('setup/csrf')), 200, 'CSRF setup');
  if (!body || !body.headerName || !body.token) throw new Error('Incomplete CSRF response');
  return { [body.headerName]: body.token };
}
function mutate(path, body, expected) {
  const response = http.post(base + path, body === undefined ? null : JSON.stringify(body),
    params('setup' + path.replace(/\/[0-9a-f-]{36}/g, '/{id}'), { ...csrf(), 'Content-Type': 'application/json' }));
  return requireStatus(response, expected, 'Setup mutation');
}
function login(username) {
  http.cookieJar().clear(base);
  mutate('/api/auth/demo/login', { username, password: __ENV.AUCTIONHOUSE_DEMO_PASSWORD }, 200);
}
export function setup() {
  if (authMode === 'session') {
    validateSession(sessionInput, base, durationSeconds, __ENV.FIXTURE_AUCTION_ID);
    http.cookieJar().set(base, 'AH_ACCESS', sessionInput.accessToken, { secure: true, path: '/' });
    const actor = requireStatus(http.get(base + '/api/auth/session', params('setup/session')), 200, 'Bidder session');
    if (actor.id !== sessionInput.actorId) throw new Error('Bidder identity changed');
    const auction = requireStatus(http.get(base + '/api/auctions/' + __ENV.FIXTURE_AUCTION_ID,
      params('setup/auction')), 200, 'Prepared fixture');
    if (auction.status !== 'OPEN' || auction.version !== 2 || auction.ownerId === actor.id
      || auction.openingPriceMinor !== 100 || auction.minimumIncrementMinor !== 10
      || Date.parse(auction.endsAt) < Date.now() + (durationSeconds + 60) * 1000)
      throw new Error('Session workload requires a fresh, compatible published auction');
    const headers = csrf();
    return { auctionId: auction.id, actorId: actor.id, cookies: http.cookieJar().cookiesForURL(base), headers };
  }
  if (!__ENV.AUCTIONHOUSE_DEMO_PASSWORD) throw new Error('Supply AUCTIONHOUSE_DEMO_PASSWORD through the environment');
  login('seller');
  const auction = mutate('/api/auctions', {
    title: 'Load experiment seed ' + seed,
    description: 'Isolated local measurement fixture. No payments.',
    openingPriceMinor: 100, minimumIncrementMinor: 10,
    endsAt: new Date(Date.now() + (durationSeconds + 600) * 1000).toISOString(),
  }, 201);
  mutate('/api/auctions/' + auction.id + '/publish', undefined, 200);
  login('bidder');
  const headers = csrf();
  const actor = requireStatus(http.get(base + '/api/auth/session', params('setup/session')), 200, 'Bidder session');
  // setup data remains in k6 memory; handleSummary never exports credentials or cookies.
  return { auctionId: auction.id, actorId: actor.id, cookies: http.cookieJar().cookiesForURL(base), headers };
}
function bidOutcome(response, data, key, amount) {
  if (![200, 422].includes(response.status)) return null;
  const body = decoded(response);
  return body && body.actorId === data.actorId && body.auctionId === data.auctionId
    && body.key === key && body.amountMinor === amount && typeof body.accepted === 'boolean'
    ? body : null;
}
function sendBid(data, index) {
  const amount = 100 + index * 10;
  const key = 'load-' + seed + '-' + index;
  const path = '/api/auctions/' + data.auctionId + '/bids';
  const body = JSON.stringify({ amountMinor: amount });
  const headers = { ...data.headers, 'Content-Type': 'application/json', 'Idempotency-Key': key, 'X-Expected-Actor': data.actorId };
  let response = http.post(base + path, body, params('auction/bid', headers));
  let outcome = bidOutcome(response, data, key, amount);
  if (!outcome && (response.status === 0 || response.status >= 500 || response.status === 408 || response.status === 429)) {
    retries.add(1);
    // Ambiguous writes reconcile the original identity before one bounded same-key retry.
    response = http.get(base + '/api/auctions/' + data.auctionId + '/bid-intents/' + key, params('auction/bid-intent', { 'X-Expected-Actor': data.actorId }));
    outcome = bidOutcome(response, data, key, amount);
    if (outcome) recovered.add(1);
    else if (response.status === 404 && decoded(response)?.code === 'INTENT_UNKNOWN') {
      response = http.post(base + path, body, params('auction/bid-retry', headers));
      outcome = bidOutcome(response, data, key, amount);
    }
  }
  if (!outcome) return false;
  if (authMode === 'session') console.log('AUCTIONHOUSE_OUTCOME ' + JSON.stringify(outcome));
  if (outcome.accepted) accepted.add(1); else rejected.add(1);
  return true;
}
function chooseBid(index) {
  let value = (index ^ seed) >>> 0;
  value ^= value << 13; value ^= value >>> 17; value ^= value << 5;
  return (value >>> 0) % 4 === 0;
}
export default function (data) {
  if (!installedCookies) {
    const jar = http.cookieJar();
    for (const [name, values] of Object.entries(data.cookies)) for (const value of values) jar.set(base, name, value);
    installedCookies = true;
  }
  const index = execution.scenario.iterationInTest;
  const isBid = chooseBid(index);
  const beginning = Date.now();
  started.add(1); retries.add(0); recovered.add(0); accepted.add(0); rejected.add(0);
  let ok = false;
  try {
    if (isBid) { bidCount.add(1); ok = sendBid(data, index); }
    else {
      browseCount.add(1);
      const response = http.get(base + '/api/auctions/' + data.auctionId, params('auction/browse'));
      ok = response.status === 200 && decoded(response)?.id === data.auctionId;
    }
  } finally {
    const elapsed = Date.now() - beginning;
    latency.add(elapsed); (isBid ? bidLatency : browseLatency).add(elapsed);
    errors.add(!ok); completed.add(1);
  }
}
export function handleSummary(data) {
  const value = name => data.metrics[name]?.values || {};
  const result = {
    formatVersion: 1, recordedAt: new Date().toISOString(),
    configuration: { authMode, seed, ratePerSecond: rate, durationSeconds, preAllocatedVUs: initialVUs, maxVUs,
      mix: 'deterministic seeded approximately 75% browse / 25% bid',
      comparisonLabel: __ENV.COMPARISON_LABEL || 'unspecified', dataset: 'one newly created auction',
      auctionId: authMode === 'session' ? __ENV.FIXTURE_AUCTION_ID : undefined },
    nominalScheduledTarget: rate * durationSeconds,
    actualOffered: (value('logical_started').count || 0) + (value('dropped_iterations').count || 0),
    started: value('logical_started').count || 0, completed: value('logical_completed').count || 0,
    dropped: value('dropped_iterations').count || 0,
    browse: value('browse_operations').count || 0, bids: value('bid_operations').count || 0,
    acceptedBids: value('accepted_bids').count || 0,
    businessRejectedBids: value('business_rejected_bids').count || 0,
    retryAttempts: value('retry_attempts').count || 0,
    recoveredOutcomes: value('recovered_bid_outcomes').count || 0,
    infrastructureErrorCount: value('infrastructure_error_rate').passes || 0,
    successfulOperationCount: value('infrastructure_error_rate').fails || 0,
    infrastructureErrorRate: value('infrastructure_error_rate').rate || 0,
    infrastructureErrors: value('infrastructure_error_rate'),
    latencyMilliseconds: value('logical_latency'), browseLatencyMilliseconds: value('browse_latency'),
    bidLatencyMilliseconds: value('bid_latency'), httpRequests: value('http_reqs'),
    thresholds: Object.fromEntries(Object.entries(data.metrics).filter(([, metric]) => metric.thresholds)
      .map(([name, metric]) => [name, metric.thresholds])),
    scope: 'logical-operation timings include bounded recovery; HTTP totals also include setup requests',
  };
  const serialized = JSON.stringify(result, null, 2) + '\n';
  return { stdout: serialized, [__ENV.SUMMARY_PATH || 'k6-summary.json']: serialized };
}
