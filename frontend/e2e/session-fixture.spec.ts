import { expect, test } from '@playwright/test';

const owner = { id: '11111111-1111-1111-1111-111111111111', displayName: 'Owner', role: 'USER' };
const bidder = { id: '22222222-2222-2222-2222-222222222222', displayName: 'Bidder', role: 'USER' };

for (const actor of [owner, bidder]) {
  test(`fixture-only final confirmation is owner-only (${actor.displayName})`, async ({ page }) => {
    const auction = { id: 'fixture-final', ownerId: owner.id, title: 'Deadline fixture', description: '',
      openingPriceMinor: 100, minimumIncrementMinor: 10, endsAt: '2020-01-01T00:00:00Z', status: 'OPEN',
      highestBidAmountMinor: 100, highestBidderId: bidder.id, version: 2, createdAt: '2019-01-01T00:00:00Z' };
    await page.route('**/api/auth/session', route => route.fulfill({ json: actor }));
    await page.route('**/api/auctions/fixture-final/snapshot', route => route.fulfill({ json: { auction, cursor: 'v1:fixture-final:2' } }));
    await page.route('**/api/auctions/fixture-final/events?*', route => route.fulfill({ contentType: 'text/event-stream', body: ': fixture\n\n' }));
    await page.goto('/#/lot/fixture-final');
    await expect(page.getByRole('heading', { name: auction.title, exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Confirm the final result' })).toHaveCount(actor.id === owner.id ? 1 : 0);
  });
}

test('fixture-only two real browser tabs coordinate one refresh using Web Locks', async ({ context }) => {
  let valid = false;
  let refreshes = 0;
  await context.route('**/refresh-fixture', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><body>Refresh fixture</body></html>' }));
  await context.route('**/api/auth/session', route => route.fulfill({ status: valid ? 200 : 401, json: valid ? bidder : {} }));
  await context.route('**/api/auth/csrf', route => route.fulfill({ json: { headerName: 'X-CSRF-TOKEN', token: 'fixture' } }));
  await context.route('**/api/auth/refresh', async route => {
    refreshes++;
    await new Promise(resolve => setTimeout(resolve, 100));
    valid = true;
    await route.fulfill({ status: 204 });
  });
  const pages = [await context.newPage(), await context.newPage()];
  await Promise.all(pages.map(page => page.goto('/refresh-fixture')));
  const results = await Promise.all(pages.map(page => page.evaluate(async () => {
    // Distinct page module instances and one actual browser-origin lock manager.
    const modulePath = '/src/api.ts';
    const api = await import(modulePath);
    return api.getSession();
  })));
  expect(results).toEqual([bidder, bidder]);
  expect(refreshes).toBe(1);
});


test('fixture-only account change keeps the persisted bid identity untouched', async ({ page }) => {
  const intent = { actorId: bidder.id, auctionId: 'fixture-final', key: 'original-identity', amountMinor: 110,
    phase: 'unknown', updatedAt: '2026-01-01T00:00:00Z' };
  const storageKey = `auctionhouse:bid:v1:${intent.actorId}:${intent.auctionId}:${intent.key}`;
  const auction = { id: intent.auctionId, ownerId: owner.id, title: 'Account switch fixture', description: '',
    openingPriceMinor: 100, minimumIncrementMinor: 10, endsAt: '2030-01-01T00:00:00Z', status: 'OPEN',
    highestBidAmountMinor: 100, highestBidderId: owner.id, version: 2, createdAt: '2026-01-01T00:00:00Z' };
  await page.addInitScript(({ key, value }) => localStorage.setItem(key, value), { key: storageKey, value: JSON.stringify(intent) });
  await page.route('**/api/auth/session', route => route.fulfill({ json: bidder }));
  await page.route('**/api/auctions/fixture-final/snapshot', route => route.fulfill({ json: { auction, cursor: 'v1:fixture-final:2' } }));
  await page.route('**/api/auctions/fixture-final/events?*', route => route.fulfill({ contentType: 'text/event-stream', body: ': fixture\n\n' }));
  let requests = 0;
  await page.route('**/api/auctions/fixture-final/bid-intents/original-identity', async route => {
    requests++;
    expect(route.request().headers()['x-expected-actor']).toBe(bidder.id);
    await route.fulfill({ status: 409, json: { code: 'ACTOR_CHANGED' } });
  });
  await page.goto('/#/lot/fixture-final');
  await page.getByRole('button', { name: 'Check & recover this bid' }).click();
  await expect(page.getByText('Sign in to the account that created this bid', { exact: false })).toBeVisible();
  expect(requests).toBe(1);
  expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBe(JSON.stringify(intent));
});
