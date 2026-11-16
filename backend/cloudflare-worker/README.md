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
- The Worker uses the GitHub user OAuth token only to read the signed-in username,
  then revokes that individual token before issuing a Dash Launcher session.
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

## Cloudflare setup and deployment from GitHub Actions

### 1. Create the KV namespace

1. Open the Cloudflare dashboard and select **Workers & Pages → KV**.
2. Create a namespace named `DASH_LAUNCHER_AUTH`.
3. Copy the namespace ID and replace
   `REPLACE_WITH_CLOUDFLARE_KV_NAMESPACE_ID` in `wrangler.toml`.
4. Commit the change to the deployment branch.

The KV namespace stores only short-lived OAuth state and one-time exchange
codes; it does not store APKs, GitHub tokens, or the App private key.

### 2. Configure GitHub Actions deployment credentials

Create a Cloudflare API token limited to the relevant account and the permissions
needed to deploy Workers and use the already-created KV binding. In the GitHub
repository `hesta-lang/Dash-Launcher-`, open **Settings → Secrets and variables
→ Actions → New repository secret** and add:

- `CLOUDFLARE_API_TOKEN`
- `CLOUDFLARE_ACCOUNT_ID`

Do not add any GitHub App private key or OAuth secret as repository secrets.
The deploy workflow only needs Cloudflare deployment credentials.

### 3. Deploy the Worker

The deploy workflow is added on the development branch. Merge the pull request
 to the repository's default branch before using the Actions UI; GitHub only
 displays the manual **Run workflow** control once the `workflow_dispatch` file
 exists on the default branch. Then open **Actions → Deploy Dash Launcher
 private-storage backend → Run workflow**. The workflow publishes the Worker from
 `backend/cloudflare-worker/`.

After the first deployment, copy the Worker URL. It will look like:

`https://dash-launcher-api.<your-account-subdomain>.workers.dev`

Open that URL followed by `/health`; it should return a small JSON response
with `ok: true`.

### 4. Register and install the GitHub App

Create a GitHub App owned by `hesta-lang`:

1. Set the homepage URL to the Dash Launcher repository.
2. Set the user authorization callback URL to the deployed Worker's exact
   `/auth/callback` URL.
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

### 5. Add Worker secrets

In Cloudflare, open **Workers & Pages → dash-launcher-api → Settings → Variables
and Secrets**. Add the following as **encrypted secrets**, not plain variables:

- `GITHUB_APP_ID`
- `GITHUB_INSTALLATION_ID`
- `GITHUB_APP_PRIVATE_KEY` (the complete PEM contents)
- `GITHUB_OAUTH_CLIENT_ID`
- `GITHUB_OAUTH_CLIENT_SECRET`
- `SESSION_HMAC_SECRET` (at least 32 random characters)

Keep `ALLOWED_GITHUB_USERS` set to the GitHub accounts allowed to connect.
The initial allowlist contains only `hesta-lang`; add other usernames only
when you deliberately want to grant them access.

Do not put any secret values in GitHub files, Android resources, BuildConfig,
workflow YAML, screenshots, issues, or chat messages. Use Cloudflare's encrypted
Secrets interface. The Worker source contains no credentials.

### 6. Point the Android app at the deployed Worker

After the Worker is deployed, update
`app/src/main/java/com/hestalang/dashlauncher/private_storage/DashBackendConfiguration.kt`
to use the exact `workers.dev` URL from your deployment. Commit the change and
let Android CI build the APK. The app's GitHub sign-in cannot work while the
placeholder URL is still present.

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
