# Disposable Keycloak realm

This import is a local development and integration-test fixture. It contains deliberately public test passwords: seller / local-seller-fixture-only and bidder / local-bidder-fixture-only. These identities have no access outside the disposable realm. Do not reuse these passwords or import this realm into a hosted or production identity provider.

The browser client is public and requires authorization code plus S256 PKCE; password/direct grants and implicit grants are disabled. The sole development callback is http://localhost:8080/login/oauth2/code/keycloak. Integration tests replace that loopback port with the test server's allocated port before importing their isolated copy.

Keycloak runs in development mode with HTTP, an ephemeral embedded database and a loopback-only published port. There is no configured admin bootstrap account and no persistent volume. Application roles remain local database authority; realm roles do not grant application administrator rights.
