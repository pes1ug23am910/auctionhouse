import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, reconcileOrSend } from './api';
import type { BidOutcome, Intent } from './types';

const intent: Intent = { actorId: 'bidder', auctionId: 'auction', key: 'persistent-key', amountMinor: 200, phase: 'unknown', updatedAt: '2026-01-01T00:00:00Z' };
const outcome: BidOutcome = { actorId: 'bidder', auctionId: 'auction', key: intent.key, amountMinor: 200, accepted: true, rejection: null, bidId: 'bid', auctionVersion: 2, decidedAt: '2026-01-01T00:01:00Z' };
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
afterEach(() => vi.unstubAllGlobals());

describe('ambiguous bid reconciliation', () => {
  it('uses status first and does not resend an already committed bid', async () => {
    const fetch = vi.fn().mockResolvedValue(json(outcome));
    vi.stubGlobal('fetch', fetch);
    expect(await reconcileOrSend(intent, true)).toEqual(outcome);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0][0]).toContain('/bid-intents/persistent-key');
  });
  it('retries unknown status with precisely the original identity and amount', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ code: 'INTENT_UNKNOWN' }, 404))
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }))
      .mockResolvedValueOnce(json(outcome));
    vi.stubGlobal('fetch', fetch);
    expect(await reconcileOrSend(intent, true)).toEqual(outcome);
    const [path, options] = fetch.mock.calls[2];
    expect(path).toBe('/api/auctions/auction/bids');
    expect(new Headers(options.headers).get('Idempotency-Key')).toBe('persistent-key');
    expect(new Headers(options.headers).get('X-CSRF-TOKEN')).toBe('test-csrf');
    expect(JSON.parse(options.body)).toEqual({ amountMinor: 200 });
  });
  it('reconciles response loss after commit without a second POST', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }))
      .mockRejectedValueOnce(new TypeError('connection lost'))
      .mockResolvedValueOnce(json(outcome));
    vi.stubGlobal('fetch', fetch);
    expect(await reconcileOrSend(intent, false)).toEqual(outcome);
    expect(fetch.mock.calls.filter(([, options]) => options.method === 'POST')).toHaveLength(1);
  });
  it('leaves unknown status unresolved after network loss instead of inferring rejection', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }))
      .mockRejectedValueOnce(new TypeError('connection lost'))
      .mockResolvedValueOnce(json({ code: 'INTENT_UNKNOWN' }, 404));
    vi.stubGlobal('fetch', fetch);
    await expect(reconcileOrSend(intent, false)).rejects.toThrow('not yet known');
  });
  it('treats a server-confirmed 422 outcome as rejection and a key conflict as an error', async () => {
    const rejected = { ...outcome, accepted: false, rejection: 'AUCTION_CLOSED', bidId: null };
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }))
      .mockResolvedValueOnce(json(rejected, 422)));
    expect(await reconcileOrSend(intent, false)).toEqual(rejected);
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }))
      .mockResolvedValueOnce(json({ code: 'IDEMPOTENCY_CONFLICT', message: 'Different payload' }, 409)));
    await expect(reconcileOrSend(intent, false)).rejects.toBeInstanceOf(ApiError);
  });
});


it('does not resend or replace a retained intent when the server reports replay expiry', async () => {
  const fetch = vi.fn().mockResolvedValue(json({ code: 'INTENT_EXPIRED', message: 'Replay window ended' }, 410));
  vi.stubGlobal('fetch', fetch);
  await expect(reconcileOrSend(intent, true)).rejects.toMatchObject({ status: 410, code: 'INTENT_EXPIRED' });
  expect(fetch).toHaveBeenCalledTimes(1);
});

describe('session recovery coordination', () => {
  const actor = { id: 'bidder', displayName: 'Bidder', role: 'USER' };
  function locks() {
    let tail: Promise<unknown> = Promise.resolve();
    const request = vi.fn((_name: string, _options: unknown, callback: () => unknown) => {
      const next = tail.then(callback);
      tail = next.catch(() => undefined);
      return next;
    });
    vi.stubGlobal('navigator', { locks: { request } });
    return request;
  }
  it('shares one rotation across simultaneous requests in one tab', async () => {
    locks();
    let valid = false;
    const fetch = vi.fn(async (path: string) => {
      if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'fixture' });
      if (path === '/api/auth/refresh') { await new Promise(resolve => setTimeout(resolve, 5)); valid = true; return new Response(null, { status: 204 }); }
      return valid ? json(path === '/api/auth/session' ? actor : []) : json({}, 401);
    });
    vi.stubGlobal('fetch', fetch);
    const { getSession, request } = await import('./api');
    expect(await Promise.all([getSession(), getSession(), request('/api/auctions')])).toEqual([actor, actor, []]);
    expect(fetch.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(1);
  });
  it('rechecks session inside the cross-tab lock and skips a rotation completed by another tab', async () => {
    const lock = locks();
    let valid = false;
    const fetch = vi.fn(async (path: string) => {
      if (path === '/api/auth/csrf') return json({ headerName: 'X-CSRF-TOKEN', token: 'fixture' });
      if (path === '/api/auth/refresh') { await new Promise(resolve => setTimeout(resolve, 5)); valid = true; return new Response(null, { status: 204 }); }
      return valid ? json(actor) : json({}, 401);
    });
    vi.stubGlobal('fetch', fetch);
    vi.resetModules();
    const firstTab = await import('./api');
    vi.resetModules();
    const secondTab = await import('./api');
    expect(await Promise.all([firstTab.getSession(), secondTab.getSession()])).toEqual([actor, actor]);
    expect(lock).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(1);
  });
  it('requires sign-in on expiry if the browser cannot coordinate cross-tab refresh', async () => {
    vi.stubGlobal('navigator', {});
    const fetch = vi.fn().mockImplementation(async (path: string) => path === '/api/auth/csrf'
      ? json({ headerName: 'X-CSRF-TOKEN', token: 'fixture' }) : json({}, 401));
    vi.stubGlobal('fetch', fetch);
    const { getSession } = await import('./api');
    expect(await getSession()).toBeNull();
    expect(fetch.mock.calls.filter(([path]) => path === '/api/auth/refresh')).toHaveLength(0);
  });
  it('does not rotate credentials in response to a server outage', async () => {
    locks();
    const fetch = vi.fn().mockImplementation(async () => json({}, 503));
    vi.stubGlobal('fetch', fetch);
    const { getSession } = await import('./api');
    await expect(getSession()).rejects.toMatchObject({ status: 503 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

it('sends the original actor precondition on both intent recovery and submission', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(json({ code: 'INTENT_UNKNOWN' }, 404))
    .mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' }))
    .mockResolvedValueOnce(json(outcome));
  vi.stubGlobal('fetch', fetch);
  await reconcileOrSend(intent, true);
  expect(new Headers(fetch.mock.calls[0][1].headers).get('X-Expected-Actor')).toBe(intent.actorId);
  expect(new Headers(fetch.mock.calls[2][1].headers).get('X-Expected-Actor')).toBe(intent.actorId);
});

it('keeps the original intent intact when the active account has changed', async () => {
  const before = structuredClone(intent);
  const fetch = vi.fn().mockResolvedValue(json({ code: 'ACTOR_CHANGED' }, 409));
  vi.stubGlobal('fetch', fetch);
  await expect(reconcileOrSend(intent, true)).rejects.toThrow('account that created this bid');
  expect(intent).toEqual(before);
  expect(fetch).toHaveBeenCalledTimes(1);
});
