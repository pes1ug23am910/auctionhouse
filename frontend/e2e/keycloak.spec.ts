import { expect, test, type Page } from '@playwright/test';

test.use({ trace: 'off' }); // Authentication artifacts should not retain raw transient cookies or codes.

async function signIn(page: Page, username: 'seller' | 'bidder') {
  await page.goto('http://localhost:8080/');
  await page.getByRole('link', { name: 'Sign in', exact: true }).click();
  await page.getByRole('link', { name: 'Continue with your identity provider' }).click();
  await page.waitForURL(url => url.hostname === 'localhost' && url.port === '8081');
  const authorization = new URL(page.url());
  expect(authorization.searchParams.get('code_challenge_method')).toBe('S256');
  expect(authorization.searchParams.get('redirect_uri')).toBe('http://localhost:8080/login/oauth2/code/keycloak');
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(`local-${username}-fixture-only`);
  await page.locator('#kc-login').click();
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
}

for (const username of ['seller', 'bidder'] as const) {
  test(`real local Keycloak browser sign-in and app logout (${username})`, async ({ page, context }) => {
    test.skip(process.env.AUCTIONHOUSE_KEYCLOAK_SMOKE !== 'true',
      'Start the README localhost:8080 application plus the disposable Keycloak realm, then opt in.');
    await signIn(page, username);
    const session = await page.request.get('http://localhost:8080/api/auth/session');
    expect(session.status()).toBe(200);
    const actor = await session.json() as { id: string; displayName: string; role: string };
    expect(actor.displayName).toBe(username === 'seller' ? 'Seller Fixture' : 'Bidder Fixture');
    expect(actor.role).toBe('USER');
    const cookieMetadata = (await context.cookies()).filter(cookie => cookie.name.startsWith('AH_'))
      .map(({ name, httpOnly, sameSite }) => ({ name, httpOnly, sameSite }));
    expect(cookieMetadata).toEqual(expect.arrayContaining([
      { name: 'AH_ACCESS', httpOnly: true, sameSite: 'Lax' },
      { name: 'AH_REFRESH', httpOnly: true, sameSite: 'Strict' },
    ]));
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
    const afterLogout = await page.request.get('http://localhost:8080/api/auth/session');
    expect(afterLogout.status()).toBe(401);
  });
}

test('README OIDC accounts publish, bid, reconnect and reach the fixed-deadline result', async ({ browser }) => {
  test.skip(process.env.AUCTIONHOUSE_KEYCLOAK_SMOKE !== 'true',
    'Start the README localhost:8080 application plus the disposable Keycloak realm, then opt in.');
  test.setTimeout(100_000);
  const seller = await browser.newContext();
  const bidder = await browser.newContext();
  try {
    const sellerPage = await seller.newPage();
    const bidderPage = await bidder.newPage();
    await signIn(sellerPage, 'seller');
    await signIn(bidderPage, 'bidder');
    const sellerSession = await (await sellerPage.request.get('http://localhost:8080/api/auth/session')).json();
    const bidderSession = await (await bidderPage.request.get('http://localhost:8080/api/auth/session')).json();
    expect(sellerSession.id).not.toBe(bidderSession.id);
    await sellerPage.goto('http://localhost:8080/#/create');
    const title = `OIDC browser-tested vessel ${Date.now()}`;
    await sellerPage.getByLabel('What are you listing?').fill(title);
    await sellerPage.getByLabel('Tell its story').fill('A real local identity-provider browser flow.');
    await sellerPage.getByLabel('Opening bid').fill('100');
    await sellerPage.getByLabel('Minimum increment').fill('10');
    const deadline = new Date(Date.now() + 45_000);
    const localDeadline = new Date(deadline.getTime() - deadline.getTimezoneOffset() * 60_000).toISOString().slice(0, 19);
    await sellerPage.getByLabel('Auction ends').fill(localDeadline);
    await sellerPage.getByRole('button', { name: 'Create draft' }).click();
    await expect(sellerPage.getByRole('heading', { name: title, exact: true })).toBeVisible();
    await sellerPage.getByRole('button', { name: 'Publish your listing' }).click();
    await expect(sellerPage.getByText('Live auction', { exact: true })).toBeVisible();
    await bidderPage.goto(sellerPage.url());
    await expect(sellerPage.getByText('Live updates connected', { exact: true })).toBeVisible();
    await seller.setOffline(true);
    await expect(sellerPage.getByText('Reconnecting - checking for updates', { exact: true })).toBeVisible();
    await bidderPage.getByLabel('Your offer').fill('100');
    await bidderPage.getByRole('button', { name: 'Place your bid' }).click();
    await expect(bidderPage.getByText('Bid accepted.', { exact: true })).toBeVisible();
    await expect(bidderPage.getByText('You are the current highest bidder.')).toBeVisible();
    await seller.setOffline(false);
    await expect(sellerPage.getByText('Live updates connected', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(sellerPage.locator('.detail-price > strong')).toHaveText('100units');
    await expect(bidderPage.getByText('This one is yours.', { exact: true })).toBeVisible({ timeout: 55_000 });
    await expect(bidderPage.getByText('Final bid', { exact: true })).toBeVisible();
    await expect(bidderPage.getByRole('button', { name: 'Place your bid' })).toHaveCount(0);
    await expect(sellerPage.getByText('Final bid', { exact: true })).toBeVisible();
    await bidderPage.reload();
    await expect(bidderPage.getByText('This one is yours.', { exact: true })).toBeVisible();
    await expect(bidderPage.locator('.detail-price > strong')).toHaveText('100units');
  } finally {
    await seller.close();
    await bidder.close();
  }
});
