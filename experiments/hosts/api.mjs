import { canonical, historyChecks, requireThat, UUID } from './contracts.mjs';

export class Api {
  constructor(origin, credential, fetcher = fetch) {
    this.origin = origin;
    this.credential = credential;
    this.cookies = new Map([['AH_ACCESS', credential.accessToken]]);
    this.fetcher = fetcher;
  }
  async request(path, { method = 'GET', body, expected = 200, csrf = true } = {}) {
    requireThat(path.startsWith('/api/') && !path.includes('://'), 'Only same-origin application API paths are allowed');
    requireThat(Date.parse(this.credential.expiresAt) > Date.now() + 10000, 'Application credential expired during experiment');
    let csrfHeaders = {};
    if (method !== 'GET' && csrf) {
      const token = await this.request('/api/auth/csrf');
      requireThat(token.headerName === 'X-XSRF-TOKEN' && typeof token.token === 'string', 'Invalid CSRF response');
      csrfHeaders = { [token.headerName]: token.token };
    }
    const response = await this.fetcher(this.origin + path, {
      method, redirect: 'manual', signal: AbortSignal.timeout(15000),
      headers: { Cookie: [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; '),
        'X-Expected-Actor': this.credential.actorId, ...csrfHeaders,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    requireThat(response.status === expected, `Application API ${method} ${path.split('?')[0]} returned HTTP ${response.status}`);
    for (const cookie of response.headers.getSetCookie()) {
      const pair = cookie.split(';')[0]; const offset = pair.indexOf('=');
      const name = pair.slice(0, offset);
      if (['AH_ACCESS', 'XSRF-TOKEN'].includes(name)) this.cookies.set(name, pair.slice(offset + 1));
    }
    return expected === 204 ? null : response.json();
  }
  async identity(expectedRole) {
    const actual = await this.request('/api/auth/session');
    requireThat(actual.id === this.credential.actorId && (!expectedRole || actual.role === expectedRole), 'Actual session identity/role mismatch');
    return { id: actual.id, role: actual.role };
  }
}

export async function prepareAuction(seller, seconds, seed, label) {
  const auction = await seller.request('/api/auctions', { method: 'POST', expected: 201, body: {
    title: `Host comparison ${label} seed ${seed}`, description: 'Isolated measurement fixture. No payments.',
    openingPriceMinor: 100, minimumIncrementMinor: 10,
    endsAt: new Date(Date.now() + (seconds + 600) * 1000).toISOString(),
  } });
  requireThat(UUID.test(auction.id), 'Invalid prepared auction identity');
  await seller.request(`/api/auctions/${auction.id}/publish`, { method: 'POST' });
  return auction.id;
}

async function pages(api, path, key, limit, descending = false) {
  const records = []; let cursor;
  for (let page = 0; page < 2000; page++) {
    const result = await api.request(path + `?limit=${limit}` + (cursor === undefined ? ''
      : `&${descending ? 'beforeVersion' : 'after'}=${encodeURIComponent(cursor)}`));
    requireThat(Array.isArray(result), 'Invalid paged result');
    for (const record of result) {
      const next = record[key];
      requireThat(descending ? Number.isInteger(next) && (cursor === undefined || next < cursor)
        : UUID.test(next) && (cursor === undefined || next > cursor), 'Nonmonotonic or duplicate pagination cursor');
      cursor = next; records.push(record);
    }
    if (result.length < limit) return records;
  }
  throw new Error('Reconciliation exceeds the bounded page limit');
}

export async function reconcile(bidder, admin, auctionId, outcomes, summary) {
  const snapshot = await bidder.request(`/api/auctions/${auctionId}`);
  const history = await pages(bidder, `/api/auctions/${auctionId}/bids`, 'auctionVersion', 100, true);
  let durableOutcomeMatches = 0;
  for (let offset = 0; offset < outcomes.length; offset += 8) {
    const batch = outcomes.slice(offset, offset + 8);
    await Promise.all(batch.map(async outcome => {
      requireThat(/^[A-Za-z0-9._:-]{1,128}$/.test(outcome.key), 'Invalid recorded intent key');
      const durable = await bidder.request(`/api/auctions/${auctionId}/bid-intents/${outcome.key}`);
      requireThat(canonical(durable) === canonical(outcome), 'Client outcome differs from durable replay');
      durableOutcomeMatches++;
    }));
  }
  const cut = await admin.request('/api/admin/event-cuts', { method: 'POST' });
  requireThat(UUID.test(cut.cutId), 'Invalid source cut');
  const events = await pages(admin, `/api/admin/event-cuts/${cut.cutId}/events`, 'eventId', 1000);
  const source = events.filter(e => e.aggregateId === auctionId);
  let notifications = [], report;
  for (let attempt = 0; attempt < 60; attempt++) {
    report = await admin.request(`/api/admin/event-cuts/${cut.cutId}`);
    const missing = new Set(report.missing);
    if (!source.some(e => missing.has(e.eventId))) {
      notifications = (await pages(admin, `/api/admin/event-cuts/${cut.cutId}/notifications`, 'eventId', 1000))
        .filter(e => e.aggregateId === auctionId);
      break;
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
  }
  const effects = new Map(notifications.map(e => [e.eventId, e]));
  const versions = [...source].sort((a, b) => a.aggregateVersion - b.aggregateVersion);
  const checks = { ...historyChecks(snapshot, history, outcomes, summary, bidder.credential.actorId),
    everyOutcomeDurable: durableOutcomeMatches === outcomes.length,
    sourceVersionSet: source.length === history.length + 2 && versions.every((e, i) => e.aggregateVersion === i + 1),
    exactSinkEffects: effects.size === source.length && notifications.length === source.length
      && source.every(e => canonical(e) === canonical(effects.get(e.eventId))),
    noOrphanSinkEffects: report.unexpected.length === 0,
  };
  return { recordedAt: new Date().toISOString(), auctionId, cutId: cut.cutId, checks,
    passed: Object.values(checks).every(Boolean), snapshot, history, outcomes, source, notifications,
    sourceCutReport: report, durableOutcomeMatches,
    scope: 'API histories and durable client outcomes for this fixture; exact immutable source-cut envelopes and one sink effect per event. Duplicate delivery attempts are permitted and reported.' };
}
