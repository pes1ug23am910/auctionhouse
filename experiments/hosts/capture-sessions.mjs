import { readFile, writeFile, realpath } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { requireThat, requireOutsideDirectory, validateManifest, UUID } from './contracts.mjs';

async function main() {
  requireThat(process.argv.length === 4, 'Usage: node experiments/hosts/capture-sessions.mjs manifest.json private-new-credentials.json');
  const manifest = validateManifest(JSON.parse(await readFile(process.argv[2], 'utf8')));
  const repo = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
  const destination = resolve(await realpath(dirname(resolve(process.argv[3]))), resolve(process.argv[3]).split(/[\\/]/).at(-1));
  requireOutsideDirectory(repo, destination);
  const { chromium } = createRequire(resolve(repo, 'frontend/package.json'))('@playwright/test');
  const browser = await chromium.launch({ headless: false });
  const result = { origin: manifest.origin };
  try {
    for (const role of ['seller', 'bidder', 'admin']) {
      const context = await browser.newContext();
      try {
        const page = await context.newPage();
        console.log(`Sign in as the ${role} in the new browser window. Credentials remain in the browser.`);
        await page.goto(manifest.origin + '/oauth2/authorization/keycloak');
        let actor;
        const deadline = Date.now() + 300000;
        while (Date.now() < deadline) {
          const response = await context.request.get(manifest.origin + '/api/auth/session', { maxRedirects: 0 });
          if (response.status() === 200) { actor = await response.json(); break; }
          await new Promise(resolve => setTimeout(resolve, 1000));
        }
        requireThat(UUID.test(actor?.id) && (role !== 'admin' || actor.role === 'ADMIN'), 'Login did not produce the expected application identity/role');
        const cookie = (await context.cookies(manifest.origin)).find(c => c.name === 'AH_ACCESS');
        requireThat(cookie?.secure && cookie.httpOnly && cookie.expires * 1000 > Date.now() + 60000,
          'No usable secure application access cookie');
        result[role] = { actorId: actor.id, accessToken: cookie.value, expiresAt: new Date(cookie.expires * 1000).toISOString() };
      } finally { await context.close(); }
    }
    requireThat(result.seller.actorId !== result.bidder.actorId, 'Use separate seller and bidder accounts');
    await writeFile(destination, JSON.stringify(result, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
    console.log('Application sessions saved locally. Run the bounded experiment before the earliest expiry: '
      + [result.seller.expiresAt, result.bidder.expiresAt, result.admin.expiresAt].sort()[0]);
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
