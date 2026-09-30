# Authentication and authorization

The application uses Spring Security's OpenID Connect authorization-code flow with state, nonce, signed ID-token validation and S256 PKCE. After a verified identity is mapped by `(issuer, subject)`, the application creates its own opaque access and refresh tokens. Roles are read from the local `accounts` table; provider claims cannot grant the application administrator role.

Access tokens live for ten minutes, refresh tokens for seven days of inactivity, and each family has a thirty-day absolute lifetime by default. `auctionhouse.auth.access-lifetime`, `refresh-lifetime` and `family-lifetime` accept bounded durations. Both token kinds contain 256 random bits and are stored only as SHA-256 digests. The application does not use a provider refresh token to implement its own rotation rules.

Every access check queries PostgreSQL for the token deadline, family deadline and revocation state. Refresh locks the family, reads the supplied token after acquiring that lock, consumes it and issues the replacement in one transaction. Reuse of a consumed refresh token revokes the entire family, including access tokens returned to a concurrent first caller. Revocation commits before an authentication error is returned. Clients must serialize refresh attempts; a response lost after rotation or a competing refresh can require sign-in again. Logout is idempotent and revokes the presented token family; it does not log the user out of the identity provider's SSO session.

Temporary OIDC requests and CSRF tokens use JDBC-backed Spring Session. Successful login invalidates the temporary session and redirects to the fixed `/` destination. Spring Security does not retain login authentication in the HTTP session, so that session cannot bypass access-token expiry. Access and refresh cookies are HttpOnly, use SameSite Lax and Strict respectively, and are Secure by default. Disabling Secure is accepted only with the `local` or `test` profile. The access-cookie path is `/`; the refresh-cookie path is `/api/auth`.

## Browser protocol

| Request | Behavior |
|---|---|
| `GET /api/auth/csrf` | Returns `{token, headerName}` and establishes the framework CSRF session. Send the returned header on subsequent browser mutations. |
| `GET /oauth2/authorization/{registrationId}` | Starts configured OIDC sign-in. The default callback is `/login/oauth2/code/{registrationId}`. Fetch a fresh CSRF token after sign-in. |
| `GET /api/auth/session` | Returns `{id, displayName, role}` for a valid access cookie or bearer token; otherwise 401. |
| `POST /api/auth/refresh` | Requires the refresh cookie and browser CSRF header; rotates both cookies and returns 204. |
| `POST /api/auth/logout` | Requires browser CSRF protection when applicable, revokes the session family and clears application cookies; returns 204. |
| `POST /api/auth/mobile/refresh` | Accepts JSON `{refreshToken}`, returns the replacement token pair, and rejects all ambient cookies. This is a refresh interface, not a mobile sign-in implementation. |

Explicit bearer authentication does not fall back to cookies. A request carrying both an Authorization header and an access cookie is rejected. Cookie mutations require CSRF; anonymous application API mutations proceed to authentication and return 401. Cross-origin application access is not enabled. Authentication responses use `Cache-Control: no-store`. Do not place tokens in URLs, logs or analytics.

## Authorization matrix

| Route family | Anonymous | Authenticated account | Owner/role boundary |
|---|---|---|---|
| Auction list/create | 401 | Allowed | New owner is the authenticated actor. |
| Auction view, bids, snapshot and events | 401 | Allowed for visible auction | Another actor's draft returns 404. SSE rechecks access expiry/revocation. |
| Publish, close, cancel | 401 | 403 for non-owner | Owner remains subject to lifecycle rules. ADMIN is not an ownership bypass. |
| Place bid | 401 | Evaluates bid rules | Owner bidding is a rejected business outcome, not an authorization grant. |
| Bid-intent lookup | 401 | Own actor/auction/key only | Unknown or another actor's outcome returns 404. |
| `/api/admin/**` | 401 | USER receives 403 | ADMIN passes the security boundary; individual routes must still exist. |

## Local Keycloak profile

The fixture runs actual self-hosted Keycloak 26.7.4, pinned to the official Linux amd64 image digest. It is separate from the signed-token mock issuer used by the HTTP negative tests. The development instance uses an ephemeral embedded database and publishes only `127.0.0.1:8081`; it is not a production deployment or evidence of a hosted identity-provider integration.

```powershell
docker compose -f compose.yaml -f compose.auth.yaml --profile auth up -d postgres keycloak
.\gradlew.bat bootRun --args='--spring.profiles.active=local,oidc'
```

Wait until `http://localhost:8081/realms/auctionhouse-local/.well-known/openid-configuration` responds, then open `http://localhost:8080/oauth2/authorization/keycloak`. The deliberately public local fixtures are `seller` / `local-seller-fixture-only` and `bidder` / `local-bidder-fixture-only`. Their scope and disposal rules are in `test-fixtures/keycloak/README.md`. The public client requires PKCE and accepts only the local application callback. No production client secret or admin password is configured. `application-oidc.yml` applies only when both `local` and `oidc` profiles are active.

For a lighter local-only login, set `AUCTIONHOUSE_DEMO_PASSWORD_HASH` to a BCrypt hash of a password chosen for that local run, activate `local`, and POST `{username, password}` to `/api/auth/demo/login` with CSRF. Allowed usernames are seller, bidder and other. There is no built-in demo password, and this route is not registered outside the local profile.

## Verification

`TokenSecurityTest` checks token handling and credential/CSRF boundaries. `AuthServiceIntegrationTest` uses actual PostgreSQL transactions for rotation, two concurrent instances, replay revocation, expiry, role authority and logout. `AuthHttpIntegrationTest` runs HTTP requests through the full security chain against a locally signed mock issuer, covering invalid state, nonce, issuer, audience, signature and expiry plus the route/role matrix. `KeycloakIntegrationTest` uses an isolated actual Keycloak container and its login form to exercise public-client sign-in, wrong credentials, application expiry, refresh and logout.

Run the integration task explicitly; `build` does not imply it ran. These commands require Docker and the pinned images:

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest --tests 'io.auctionhouse.auth.*'
```

A test's presence does not certify a successful run. Consult the dated validation record for executed results and limitations.

The README browser path was exercised on 2026-10-01 against actual local Keycloak and PostgreSQL: all three Chromium cases in `frontend/e2e/keycloak.spec.ts` passed. They verify seller/bidder sign-in with S256 PKCE and the exact callback, HttpOnly cookie metadata, application logout, and separate accounts creating, publishing, bidding, reconnecting after an offline interval and observing the winner after the fixed deadline and page reload. This is a loopback self-hosted fixture, not hosted-provider or cloud TLS evidence. After starting the README stack, run `AUCTIONHOUSE_KEYCLOAK_SMOKE=true npx playwright test e2e/keycloak.spec.ts` from `frontend` (set the environment variable with `$env:AUCTIONHOUSE_KEYCLOAK_SMOKE='true'` in PowerShell). Authentication traces are disabled.

Implementation references: [Spring Security OIDC configuration](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Spring Session JDBC](https://docs.spring.io/spring-session/reference/guides/boot-jdbc.html), [Keycloak 26.7.4 release](https://github.com/keycloak/keycloak/releases/tag/26.7.4), and [Keycloak container configuration](https://www.keycloak.org/server/containers).


## Browser refresh and account context

The browser shares one refresh promise within a tab and uses an exclusive origin-wide Web Lock across tabs. After acquiring the lock it rechecks the session before rotating: another tab may already have updated the shared HttpOnly cookie. Browsers without Web Locks require a new sign-in when access expires. This avoids treating uncoordinated simultaneous rotation as safe under the server's strict family-reuse policy.

Public frontend resources remain available with expired or revoked access cookies, allowing the client to refresh or show sign-in. Protected API routes continue to reject those cookies. Persisted bid submission and status lookup include `X-Expected-Actor` from the original intent. The server compares that optional precondition to its authenticated principal; it never authenticates from the header. An account mismatch stops recovery and preserves the original stored bid identity for its original account.
