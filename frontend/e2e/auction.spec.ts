import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const password = process.env.AUCTIONHOUSE_DEMO_PASSWORD;

test('real auction publishes, recovers a disconnected viewer, and closes with its confirmed winner', async ({ browser }) => {
  test.setTimeout(90_000);
  test.skip(!password, 'Set AUCTIONHOUSE_DEMO_PASSWORD and run the local backend/real PostgreSQL stack.');
  const seller = await browser.newContext();
  const bidder = await browser.newContext();
  try {
    const page = await seller.newPage();
    await page.goto('/#/login');
    await page.getByLabel('Demo account').selectOption('seller');
    await page.getByLabel('Password', { exact: true }).fill(password!);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
    await page.goto('/#/create');
    const title = 'Browser-tested vessel ' + Date.now();
    await page.getByLabel('What are you listing?').fill(title);
    await page.getByLabel('Tell its story').fill('A local full-stack browser test.');
    await page.getByLabel('Opening bid').fill('100');
    await page.getByLabel('Minimum increment').fill('10');
    const deadline = new Date(Date.now() + 45_000);
    const localDeadline = new Date(deadline.getTime() - deadline.getTimezoneOffset() * 60_000).toISOString().slice(0, 19).replace(/:00$/, '');
    await page.getByLabel('Auction ends').fill(localDeadline);
    await page.getByRole('button', { name: 'Create draft' }).click();
    await expect(page.getByRole('heading', { name: title, exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Publish your listing' }).click();
    await expect(page.getByText('Live auction', { exact: true })).toBeVisible();
    const lotPath = new URL(page.url()).hash;
    const bidPage = await bidder.newPage();
    await bidPage.goto('/#/login');
    await bidPage.getByLabel('Demo account').selectOption('bidder');
    await bidPage.getByLabel('Password', { exact: true }).fill(password!);
    await bidPage.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(bidPage.getByRole('button', { name: 'Sign out' })).toBeVisible();
    await bidPage.goto('/' + lotPath);
    await expect(page.getByText('Live updates connected', { exact: true })).toBeVisible();
    await seller.setOffline(true);
    await expect(page.getByText('Reconnecting - checking for updates', { exact: true })).toBeVisible();
    await bidPage.getByLabel('Your offer').fill('100');
    await bidPage.getByRole('button', { name: 'Place your bid' }).click();
    await expect(bidPage.getByText('Bid accepted.', { exact: true })).toBeVisible();
    await expect(bidPage.getByText('You are the current highest bidder.')).toBeVisible();
    await seller.setOffline(false);
    await expect(page.getByText('Live updates connected', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Current bid', { exact: true })).toBeVisible();
    await expect(page.locator('.detail-price > strong')).toHaveText('100units');
    const accessibility = await new AxeBuilder({ page: bidPage }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
    expect(accessibility.violations.map(issue => ({ id: issue.id, nodes: issue.nodes.map(node => ({ target: node.target, reason: node.failureSummary })) }))).toEqual([]);
    await expect(bidPage.getByText('This one is yours.', { exact: true })).toBeVisible({ timeout: 55_000 });
    await expect(bidPage.getByText('Final bid', { exact: true })).toBeVisible();
    await expect(bidPage.locator('.detail-price > strong')).toHaveText('100units');
    await expect(bidPage.getByRole('button', { name: 'Place your bid' })).toHaveCount(0);
    await expect(page.getByText('Final bid', { exact: true })).toBeVisible();
    await bidPage.reload();
    await expect(bidPage.getByText('This one is yours.', { exact: true })).toBeVisible();
  } finally {
    await seller.close();
    await bidder.close();
  }
});

test('guest navigation and forms fit a narrow viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Good things. Worth a second look.' })).toBeVisible();
  await page.getByRole('link', { name: 'Come on in' }).click();
  await expect(page.getByRole('heading', { name: 'Make yourself at home.' })).toBeVisible();
  const accessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(accessibility.violations.map(issue => ({ id: issue.id, nodes: issue.nodes.map(node => ({ target: node.target, reason: node.failureSummary })) }))).toEqual([]);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(overflow).toBe(false);
});
