import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const actor = { id: 'fixture-bidder', displayName: 'Alex', role: 'USER' };
const auctions = [
  ['Hand-thrown terracotta vessel', 'A warm, sculptural piece with a beautifully irregular glaze.', 4200],
  ['Quiet hours table lamp', 'Soft light, considered lines, and a little mid-century character.', 1850],
  ['Collected jazz, volume one', 'A small selection of records for very good evenings.', 3200],
  ['The architectural bookend', 'Weighty forms, useful details. A good companion to a growing shelf.', 950],
].map(([title, description, amount], index) => ({
  id: 'fixture-lot-' + index, ownerId: 'fixture-seller', title, description,
  openingPriceMinor: 100, minimumIncrementMinor: 50, endsAt: '2030-06-01T18:00:00Z',
  status: 'OPEN', highestBidAmountMinor: amount, highestBidderId: 'someone-else',
  version: 2, createdAt: '2026-01-01T00:00:00Z',
}));

test('fixture-only catalog supports search and narrow-screen navigation', async ({ page }) => {
  await page.route('**/api/auth/session', route => route.fulfill({ json: actor }));
  await page.route('**/api/auctions?*', route => route.fulfill({ json: auctions }));
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'On the auction floor' })).toBeVisible();
  await expect(page.locator('.auction-card')).toHaveCount(4);
  await page.screenshot({ path: 'test-results/catalog-desktop.png', fullPage: true });
  const catalogAccessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(catalogAccessibility.violations.map(issue => ({ id: issue.id, nodes: issue.nodes.map(node => ({ target: node.target, reason: node.failureSummary })) }))).toEqual([]);
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.locator('main')).toBeFocused();
  await page.getByRole('textbox', { name: 'Search loaded auctions' }).fill('terracotta');
  await expect(page.locator('.auction-card')).toHaveCount(1);
  await page.getByRole('textbox', { name: 'Search loaded auctions' }).clear();
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('link', { name: 'List a piece', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
  await page.screenshot({ path: 'test-results/catalog-mobile.png', fullPage: true });
  await page.getByRole('link', { name: 'List a piece', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'The details', exact: true })).toBeVisible();
  const formAccessibility = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(formAccessibility.violations.map(issue => ({ id: issue.id, nodes: issue.nodes.map(node => ({ target: node.target, reason: node.failureSummary })) }))).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false);
});
