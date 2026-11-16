# Dash Launcher private-storage API

This Cloudflare Worker is the server-side bridge between the Android launcher and
`hesta-lang/Dash-Launcher-Private-Storage`. It keeps the GitHub App private key,
OAuth client secret, and installation credentials off Android devices.

## Security model

- GitHub sign-in is performed in the system browser.
- The OAuth callback uses state, GitHub PKCE (S256), and a second PKCE exchange
  bound to the Android installation flow.
- Only usernames listed in `ALLOWED_GITHUB_USERS` receive a Dash Launcher session.
  The initial value is `hesta-lang`; add accounts deliberately.
- Dash Launcher sessions are HMAC-signed and expire after seven days.
- The Worker generates GitHub App installation tokens on the server, scoped to
  this one repository and `Contents: read`.
- The Android client never receives a GitHub App token, GitHub user token, PAT,
  App private key, or OAuth client secret.
- APK downloads use short-lived GitHub release-asset URLs. The Worker checks the
  catalog entry, release tag, asset name, byte size, digest, and redirect host
  before returning the URL.
- The APK client still independently verifies file size, SHA-256, Android package,
  signing-certificate fingerprint, ABI, and minimum Android API.

## GitHub App registration

Create a GitHub App owned by `hesta-lang`:

1. Set the homepage URL to the Dash Launcher repository.
2. Set the user authorization callback URL to
   `https://YOUR-WORKER.YOUR-SUBDOMAIN.workers.dev/auth/callback`.
3. Disable webhook delivery unless you later add a webhook feature.
4. Under Repository permissions, set **Contents: Read-only**.
5. Do not request write permissions, organization permissions, or unrelated account permissions.
6. Generate a private key and keep the downloaded PEM file private.
7. Install the app on **only** `Dash-Launcher-Private-Storage`.
8. Under the installation, confirm the selected repository is only
   `Dash-Launcher-Private-Storage`.

Use the GitHub App's **App ID** (not Client ID) for `GITHUB_APP_ID`, its
**Client ID** and **Client Secret** for the OAuth variables, and the installation
ID for `GITHUB_INSTALLATION_ID`.

## Cloudflare setup

1. Create a Worker named `dash-launcher-api`.
2. Create a Workers KV namespace named `DASH_LAUNCHER_AUTH`.
3. Copy its namespace ID into `wrangler.toml`, replacing
   `REPLACE_WITH_CLOUDFLARE_KV_NAMESPACE_ID`.
4. Configure the following Worker secrets in Cloudflare:
   - `GITHUB_APP_ID`
   - `GITHUB_INSTALLATION_ID`
   - `GITHUB_APP_PRIVATE_KEY` (the complete PEM contents)
   - `GITHUB_OAUTH_CLIENT_ID`
   - `GITHUB_OAUTH_CLIENT_SECRET`
   - `SESSION_HMAC_SECRET` (at least 32 random characters)
5. Keep `ALLOWED_GITHUB_USERS` set to the GitHub accounts allowed to connect.
6. Deploy this Worker. The health endpoint is `/health`.

Do not put any of the secret values in GitHub files, Android resources, BuildConfig,
workflow YAML, screenshots, issues, or chat messages. Use Cloudflare's encrypted
Secrets interface. The Worker source contains no credentials.

## API routes

- `GET /health` — public health check.
- `GET /auth/login?code_challenge=...` — begins GitHub sign-in.
- `GET /auth/callback` — GitHub App OAuth callback; creates a one-time code
  and returns to `dashlauncher://github-auth`.
- `POST /auth/exchange` — exchanges that one-time code and Android PKCE verifier
  for a seven-day Dash Launcher session.
- `GET /api/catalog` — authenticated catalog retrieval.
- `GET /api/download-url?id=VERSION_ID` — authenticated, catalog-pinned
  release asset URL resolution.

## Important limitation

The first deployment is intentionally allowlisted to `hesta-lang`. This avoids
turning a private GitHub repository into an unauthenticated public download proxy.
Additional testers must be added to `ALLOWED_GITHUB_USERS` intentionally. Do not
make the catalog/download routes public to avoid building an account-access policy.
