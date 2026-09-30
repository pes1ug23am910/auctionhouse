# Auctionhouse web application

React/TypeScript client for the Java auction service. It implements browse,
search/sort within loaded lots, owned listings, draft creation, publication,
cancellation, bidding, closure/result display and authentication screens.

The client displays exact integer bidding units; it does not infer a currency
or convert amounts through floating-point arithmetic. CSS artwork is abstract
decoration, not a photograph or representation of a listed object.

## Run

Use Node 22.12 or newer (Node 24.15.0 was used for the initial build).

```sh
npm ci
npm run dev
npm test
npm run build
```

Vite binds the local UI at http://127.0.0.1:5173 and proxies API and identity
routes to http://127.0.0.1:8080. Start the actual Java local profile and
PostgreSQL separately. Local demonstration login needs the backend's configured
demo password; the UI does not embed or persist passwords/tokens. A Keycloak
login link uses the separately configured provider.

The exact registry versions and transitive dependency graph are pinned in
`package.json` and `package-lock.json`. `npm run build` checks TypeScript and
creates `dist/`; a successful bundle alone does not verify the backend.

## Bid and live-state behavior

Before submitting, the client persists the actor/auction/key/amount identity.
An interrupted bid remains unknown. Recovery checks durable status first, then
reuses precisely the same identity and amount if status is unknown. A pending
or locally accepted response never fabricates a new current price; current
state comes from the authorized snapshot/event path.

Unresolved records are filtered by actor and auction. Browser storage is
required for creating a new recoverable intent. Signing out does not silently
forget an unresolved operation. A server 410 INTENT_EXPIRED is terminal: retain the identity, show that the
original result may have committed, and do not create a replacement key.
Completed records remain available locally;
tokens and passwords are never stored with them.

Snapshots apply state and cursor together. Per-auction versions reject
duplicates/old events and detect gaps. The client refreshes snapshots on gaps,
resumes with the cursor, and periodically checks state while SSE reconnects.
The server clock from a snapshot adjusts the countdown; server validation,
not the displayed countdown, decides whether a bid meets the deadline.

## Browser verification

```sh
npx playwright install chromium
npm run test:e2e
```

The full-stack test requires the running Java/PostgreSQL stack and
`AUCTIONHOUSE_DEMO_PASSWORD` supplied in the test environment. It skips with
an explicit prerequisite message when that value is absent. Use only the
isolated local demo environment for these tests; browser diagnostics must not
contain real account credentials.

`catalog-fixture.spec.ts` uses explicit synthetic API fixtures to check
responsive layout and interaction. It does not establish database, login or
end-to-end evidence. `auction.spec.ts` contains the real-stack flow and a guest
mobile smoke check. Playwright reports distinguish skipped, failed and passed
cases.

`keycloak.spec.ts` exercises the README's actual local Keycloak sign-in and
logout for both fixture accounts, then the full auction/reconnect/deadline
flow through those identities. Start the bundled README application first,
set `AUCTIONHOUSE_KEYCLOAK_SMOKE=true`, and run
`npx playwright test e2e/keycloak.spec.ts`. It skips explicitly without that
opt-in and disables authentication traces.

The unit suite exercises exact-unit boundaries, server-confirmed state,
actor/auction isolation, gap/duplicate recovery and response loss around bid
commit. Test results support only the commands and conditions actually run.
