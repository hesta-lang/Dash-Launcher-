export interface Env {
  AUTH_KV: KVNamespace;
  GITHUB_APP_ID: string;
  GITHUB_INSTALLATION_ID: string;
  GITHUB_APP_PRIVATE_KEY: string;
  GITHUB_OAUTH_CLIENT_ID: string;
  GITHUB_OAUTH_CLIENT_SECRET: string;
  SESSION_HMAC_SECRET: string;
  ALLOWED_GITHUB_USERS: string;
}

const OWNER = "hesta-lang";
const REPOSITORY = "Dash-Launcher-Private-Storage";
const CATALOG_PATH = "catalog/versions.json";
const GITHUB_API = "https://api.github.com";
const API_VERSION = "2026-03-10";
const SESSION_TTL_SECONDS = 7 * 24 * 60 * 60;
const MAX_CATALOG_BYTES = 1024 * 1024;
const ALLOWED_ASSET_HOSTS = new Set([
  "release-assets.githubusercontent.com",
  "objects.githubusercontent.com"
]);

let cachedInstallationToken: { value: string; expiresAt: number } | null = null;
let cachedSigningKey: Promise<CryptoKey> | null = null;

type JsonObject = Record<string, unknown>;

function responseHeaders(extra: Record<string, string> = {}): Headers {
  const headers = new Headers({
    "Cache-Control": "no-store, max-age=0",
    "Pragma": "no-cache",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer"
  });
  for (const [key, value] of Object.entries(extra)) headers.set(key, value);
  return headers;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: responseHeaders({ "Content-Type": "application/json; charset=utf-8" })
  });
}

function html(body: string, status = 200): Response {
  return new Response(body, {
    status,
    headers: responseHeaders({ "Content-Type": "text/html; charset=utf-8" })
  });
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function fromBase64Url(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const binary = atob(padded);
  const output = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) output[i] = binary.charCodeAt(i);
  return output;
}

function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const length = parts.reduce((sum, part) => sum + part.length, 0);
  const result = new Uint8Array(length);
  let offset = 0;
  for (const part of parts) {
    result.set(part, offset);
    offset += part.length;
  }
  return result;
}

function derLength(length: number): Uint8Array {
  if (length < 0x80) return new Uint8Array([length]);
  const bytes: number[] = [];
  let remaining = length;
  while (remaining > 0) {
    bytes.unshift(remaining & 0xff);
    remaining = Math.floor(remaining / 256);
  }
  return new Uint8Array([0x80 | bytes.length, ...bytes]);
}

function pemToPkcs8(pem: string): Uint8Array {
  const clean = pem.replace(/-----BEGIN [^-]+-----/g, "")
    .replace(/-----END [^-]+-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(clean);
  const raw = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) raw[i] = binary.charCodeAt(i);

  if (pem.includes("BEGIN PRIVATE KEY")) return raw;
  if (!pem.includes("BEGIN RSA PRIVATE KEY")) {
    throw new Error("Unsupported GitHub App private key PEM format.");
  }

  const algorithmIdentifier = new Uint8Array([
    0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86,
    0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
  ]);
  const version = new Uint8Array([0x02, 0x01, 0x00]);
  const octetString = concatBytes(new Uint8Array([0x04]), derLength(raw.length), raw);
  const body = concatBytes(version, algorithmIdentifier, octetString);
  return concatBytes(new Uint8Array([0x30]), derLength(body.length), body);
}

async function githubAppSigningKey(env: Env): Promise<CryptoKey> {
  if (!cachedSigningKey) {
    cachedSigningKey = crypto.subtle.importKey(
      "pkcs8",
      pemToPkcs8(env.GITHUB_APP_PRIVATE_KEY),
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["sign"]
    );
  }
  return cachedSigningKey;
}

async function createGitHubAppJwt(env: Env): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({
    iat: now - 60,
    exp: now + 540,
    iss: env.GITHUB_APP_ID
  })));
  const signingInput = header + "." + payload;
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    await githubAppSigningKey(env),
    new TextEncoder().encode(signingInput)
  );
  return signingInput + "." + base64Url(new Uint8Array(signature));
}

async function getInstallationToken(env: Env): Promise<string> {
  const now = Date.now();
  if (cachedInstallationToken && cachedInstallationToken.expiresAt > now + 60_000) {
    return cachedInstallationToken.value;
  }

  const jwt = await createGitHubAppJwt(env);
  const response = await fetch(
    GITHUB_API + "/app/installations/" + encodeURIComponent(env.GITHUB_INSTALLATION_ID) + "/access_tokens",
    {
      method: "POST",
      headers: {
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + jwt,
        "X-GitHub-Api-Version": API_VERSION,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        repositories: [REPOSITORY],
        permissions: { contents: "read" }
      })
    }
  );
  if (!response.ok) throw new Error("Could not create the scoped GitHub installation token.");
  const result = await response.json() as JsonObject;
  const token = typeof result.token === "string" ? result.token : "";
  if (!token) throw new Error("GitHub did not return an installation token.");
  const expiresAt = typeof result.expires_at === "string"
    ? Date.parse(result.expires_at)
    : now + 50 * 60_000;
  cachedInstallationToken = { value: token, expiresAt };
  return token;
}

async function githubRequest(path: string, token: string, accept = "application/vnd.github+json"): Promise<Response> {
  const response = await fetch(GITHUB_API + path, {
    headers: {
      "Accept": accept,
      "Authorization": "Bearer " + token,
      "X-GitHub-Api-Version": API_VERSION
    },
    redirect: "manual"
  });
  return response;
}

async function loadCatalog(env: Env): Promise<{ raw: string; parsed: JsonObject }> {
  const token = await getInstallationToken(env);
  const response = await githubRequest(
    "/repos/" + OWNER + "/" + REPOSITORY + "/contents/" + CATALOG_PATH + "?ref=main",
    token
  );
  if (!response.ok) throw new Error("Private catalog could not be read.");
  const file = await response.json() as JsonObject;
  if (file.type !== "file" || file.encoding !== "base64" || typeof file.content !== "string") {
    throw new Error("Private catalog has an unsupported GitHub contents response.");
  }
  const content = file.content.replace(/\s+/g, "");
  const decoded = atob(content);
  if (decoded.length > MAX_CATALOG_BYTES) throw new Error("Private catalog exceeds the size limit.");
  const bytes = new Uint8Array(decoded.length);
  for (let i = 0; i < decoded.length; i++) bytes[i] = decoded.charCodeAt(i);
  const raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  const parsed = JSON.parse(raw) as JsonObject;
  if (parsed.schemaVersion !== 1 || parsed.repository !== OWNER + "/" + REPOSITORY ||
      !Array.isArray(parsed.versions) || parsed.versions.length > 500) {
    throw new Error("Private catalog schema or repository is invalid.");
  }
  return { raw, parsed };
}

function allowedUsers(env: Env): Set<string> {
  return new Set((env.ALLOWED_GITHUB_USERS || "")
    .split(",").map(value => value.trim().toLowerCase()).filter(Boolean));
}

function randomCode(bytes = 32): string {
  const value = new Uint8Array(bytes);
  crypto.getRandomValues(value);
  return base64Url(value);
}

async function sha256Base64Url(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return base64Url(new Uint8Array(digest));
}

async function hmacKey(env: Env): Promise<CryptoKey> {
  if (!env.SESSION_HMAC_SECRET || env.SESSION_HMAC_SECRET.length < 32) {
    throw new Error("Session signing secret is not configured.");
  }
  return crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(env.SESSION_HMAC_SECRET),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"]
  );
}

async function mintSession(env: Env, username: string): Promise<{ token: string; expiresAt: number }> {
  const now = Math.floor(Date.now() / 1000);
  const expiresAt = now + SESSION_TTL_SECONDS;
  const payload = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: "dash-launcher",
    sub: username,
    scope: "private-storage:read",
    iat: now,
    exp: expiresAt
  })));
  const signature = await crypto.subtle.sign(
    "HMAC",
    await hmacKey(env),
    new TextEncoder().encode(payload)
  );
  return { token: payload + "." + base64Url(new Uint8Array(signature)), expiresAt };
}

async function validateSession(request: Request, env: Env): Promise<string | null> {
  const header = request.headers.get("Authorization") || "";
  if (!header.startsWith("Bearer ")) return null;
  const token = header.slice(7);
  const parts = token.split(".");
  if (parts.length !== 2) return null;
  try {
    const payloadBytes = fromBase64Url(parts[0]);
    const payload = JSON.parse(new TextDecoder().decode(payloadBytes)) as JsonObject;
    const username = typeof payload.sub === "string" ? payload.sub : "";
    const now = Math.floor(Date.now() / 1000);
    if (payload.iss !== "dash-launcher" || payload.scope !== "private-storage:read" ||
        typeof payload.exp !== "number" || payload.exp <= now ||
        typeof payload.iat !== "number" || payload.iat > now + 60 ||
        !allowedUsers(env).has(username.toLowerCase())) return null;

    const key = await hmacKey(env);
    const valid = await crypto.subtle.verify(
      "HMAC",
      key,
      fromBase64Url(parts[1]),
      new TextEncoder().encode(parts[0])
    );
    return valid ? username : null;
  } catch {
    return null;
  }
}

async function login(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const clientChallenge = url.searchParams.get("code_challenge") || "";
  if (!/^[A-Za-z0-9_-]{43}$/.test(clientChallenge)) {
    return json({ error: "A valid PKCE S256 challenge is required." }, 400);
  }
  if (!env.GITHUB_OAUTH_CLIENT_ID || !env.GITHUB_OAUTH_CLIENT_SECRET) {
    return json({ error: "GitHub App OAuth is not configured on the backend." }, 503);
  }

  const state = randomCode(32);
  const githubVerifier = randomCode(32);
  const githubChallenge = await sha256Base64Url(githubVerifier);
  await env.AUTH_KV.put(
    "oauth-state:" + state,
    JSON.stringify({ clientChallenge, githubVerifier }),
    { expirationTtl: 600 }
  );

  const authorize = new URL("https://github.com/login/oauth/authorize");
  authorize.searchParams.set("client_id", env.GITHUB_OAUTH_CLIENT_ID);
  authorize.searchParams.set("redirect_uri", url.origin + "/auth/callback");
  authorize.searchParams.set("scope", "read:user");
  authorize.searchParams.set("state", state);
  authorize.searchParams.set("code_challenge", githubChallenge);
  authorize.searchParams.set("code_challenge_method", "S256");
  authorize.searchParams.set("allow_signup", "false");
  return Response.redirect(authorize.toString(), 302);
}

async function revokeGitHubUserToken(env: Env, accessToken: string): Promise<void> {
  const credentials = btoa(env.GITHUB_OAUTH_CLIENT_ID + ":" + env.GITHUB_OAUTH_CLIENT_SECRET);
  const response = await fetch(
    GITHUB_API + "/applications/" + encodeURIComponent(env.GITHUB_OAUTH_CLIENT_ID) + "/token",
    {
      method: "DELETE",
      headers: {
        "Accept": "application/vnd.github+json",
        "Authorization": "Basic " + credentials,
        "X-GitHub-Api-Version": API_VERSION,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ access_token: accessToken })
    }
  );
  if (response.status !== 204) {
    throw new Error("The temporary GitHub user token could not be revoked.");
  }
}

async function oauthCallback(request: Request, env: Env): Promise<Response> {
  const url = new URL(request.url);
  const code = url.searchParams.get("code") || "";
  const state = url.searchParams.get("state") || "";
  if (!/^[A-Za-z0-9_-]{20,200}$/.test(code) || !/^[A-Za-z0-9_-]{32,100}$/.test(state)) {
    return html("<h2>Dash Launcher</h2><p>La respuesta de GitHub no es válida. Vuelve a la aplicación e inténtalo otra vez.</p>", 400);
  }

  const stateKey = "oauth-state:" + state;
  const stateJson = await env.AUTH_KV.get(stateKey);
  await env.AUTH_KV.delete(stateKey);
  if (!stateJson) return html("<h2>Dash Launcher</h2><p>La sesión de inicio ha caducado. Vuelve a intentarlo desde la aplicación.</p>", 400);

  const stateData = JSON.parse(stateJson) as JsonObject;
  const githubVerifier = typeof stateData.githubVerifier === "string" ? stateData.githubVerifier : "";
  const clientChallenge = typeof stateData.clientChallenge === "string" ? stateData.clientChallenge : "";
  if (!githubVerifier || !/^[A-Za-z0-9_-]{43}$/.test(clientChallenge)) {
    return html("<h2>Dash Launcher</h2><p>No se pudo validar el inicio de sesión. Vuelve a intentarlo.</p>", 400);
  }

  const tokenResponse = await fetch("https://github.com/login/oauth/access_token", {
    method: "POST",
    headers: { "Accept": "application/json", "Content-Type": "application/json" },
    body: JSON.stringify({
      client_id: env.GITHUB_OAUTH_CLIENT_ID,
      client_secret: env.GITHUB_OAUTH_CLIENT_SECRET,
      code,
      redirect_uri: url.origin + "/auth/callback",
      code_verifier: githubVerifier
    })
  });
  if (!tokenResponse.ok) return html("<h2>Dash Launcher</h2><p>GitHub no pudo completar la autorización. Vuelve a intentarlo.</p>", 502);
  const tokenData = await tokenResponse.json() as JsonObject;
  const githubUserToken = typeof tokenData.access_token === "string" ? tokenData.access_token : "";
  if (!githubUserToken) return html("<h2>Dash Launcher</h2><p>GitHub no devolvió una sesión de usuario válida.</p>", 502);

  let user: JsonObject;
  try {
    const userResponse = await fetch("https://api.github.com/user", {
      headers: {
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + githubUserToken,
        "X-GitHub-Api-Version": API_VERSION
      }
    });
    if (!userResponse.ok) {
      return html("<h2>Dash Launcher</h2><p>No se pudo verificar la cuenta de GitHub.</p>", 502);
    }
    user = await userResponse.json() as JsonObject;
  } finally {
    // The OAuth token is needed only for /user. Revoke it before issuing our own session.
    await revokeGitHubUserToken(env, githubUserToken);
  }
  const username = typeof user.login === "string" ? user.login : "";
  if (!username || !allowedUsers(env).has(username.toLowerCase())) {
    return html("<h2>Dash Launcher</h2><p>Esta cuenta de GitHub no tiene autorización para el almacenamiento privado de Dash Launcher.</p>", 403);
  }

  const oneTimeCode = randomCode(32);
  await env.AUTH_KV.put(
    "exchange-code:" + oneTimeCode,
    JSON.stringify({ challenge: clientChallenge, username }),
    { expirationTtl: 120 }
  );
  const redirect = new URL("dashlauncher://github-auth");
  redirect.searchParams.set("code", oneTimeCode);
  return Response.redirect(redirect.toString(), 302);
}

async function exchangeCode(request: Request, env: Env): Promise<Response> {
  let body: JsonObject;
  try {
    const raw = await request.text();
    if (raw.length > 4096) return json({ error: "Request is too large." }, 413);
    body = JSON.parse(raw) as JsonObject;
  } catch {
    return json({ error: "Invalid JSON request." }, 400);
  }

  const code = typeof body.code === "string" ? body.code : "";
  const verifier = typeof body.codeVerifier === "string" ? body.codeVerifier : "";
  if (!/^[A-Za-z0-9_-]{32,100}$/.test(code) ||
      !/^[A-Za-z0-9._~-]{43,128}$/.test(verifier)) {
    return json({ error: "Invalid one-time code or PKCE verifier." }, 400);
  }

  const key = "exchange-code:" + code;
  const stored = await env.AUTH_KV.get(key);
  await env.AUTH_KV.delete(key);
  if (!stored) return json({ error: "The login code expired or was already used." }, 401);

  const record = JSON.parse(stored) as JsonObject;
  const expectedChallenge = typeof record.challenge === "string" ? record.challenge : "";
  const username = typeof record.username === "string" ? record.username : "";
  const actualChallenge = await sha256Base64Url(verifier);
  if (!expectedChallenge || actualChallenge !== expectedChallenge ||
      !allowedUsers(env).has(username.toLowerCase())) {
    return json({ error: "PKCE verification failed." }, 401);
  }

  const session = await mintSession(env, username);
  return json({
    accessToken: session.token,
    expiresAtEpochSeconds: session.expiresAt,
    username
  });
}

async function apiCatalog(request: Request, env: Env): Promise<Response> {
  const username = await validateSession(request, env);
  if (!username) return json({ error: "Dash Launcher session expired. Connect again." }, 401);
  const catalog = await loadCatalog(env);
  return new Response(catalog.raw, {
    status: 200,
    headers: responseHeaders({ "Content-Type": "application/json; charset=utf-8" })
  });
}

async function apiDownloadUrl(request: Request, env: Env): Promise<Response> {
  const username = await validateSession(request, env);
  if (!username) return json({ error: "Dash Launcher session expired. Connect again." }, 401);

  const url = new URL(request.url);
  const id = url.searchParams.get("id") || "";
  if (!/^[A-Za-z0-9._-]{1,96}$/.test(id)) {
    return json({ error: "Invalid version ID." }, 400);
  }

  const catalog = await loadCatalog(env);
  const versions = catalog.parsed.versions as unknown[];
  const version = versions.find(value =>
    value !== null && typeof value === "object" &&
    (value as JsonObject).id === id
  ) as JsonObject | undefined;
  if (!version) return json({ error: "Version is not present in the private catalog." }, 404);

  const tag = typeof version.tag === "string" ? version.tag : "";
  const assetName = typeof version.assetName === "string" ? version.assetName : "";
  const expectedSize = typeof version.sizeBytes === "number" ? version.sizeBytes : -1;
  const expectedSha = typeof version.sha256 === "string" ? version.sha256.toLowerCase() : "";
  if (!/^[A-Za-z0-9._-]{1,128}$/.test(tag) ||
      !/^[A-Za-z0-9._-]+\.apk$/.test(assetName) ||
      !Number.isSafeInteger(expectedSize) || expectedSize <= 0 ||
      !/^[a-f0-9]{64}$/.test(expectedSha)) {
    return json({ error: "The selected catalog entry is invalid." }, 500);
  }

  const token = await getInstallationToken(env);
  const releaseResponse = await githubRequest(
    "/repos/" + OWNER + "/" + REPOSITORY + "/releases/tags/" + encodeURIComponent(tag),
    token
  );
  if (!releaseResponse.ok) return json({ error: "The private release could not be found." }, 404);
  const release = await releaseResponse.json() as JsonObject;
  if (release.tag_name !== tag || release.draft === true || !Array.isArray(release.assets)) {
    return json({ error: "The private release is not ready for download." }, 409);
  }

  const asset = release.assets.find(value =>
    value !== null && typeof value === "object" && (value as JsonObject).name === assetName
  ) as JsonObject | undefined;
  if (!asset || typeof asset.id !== "number" || asset.size !== expectedSize) {
    return json({ error: "The APK asset does not match the private catalog." }, 409);
  }
  if (typeof asset.digest === "string" &&
      asset.digest.replace(/^sha256:/, "").toLowerCase() !== expectedSha) {
    return json({ error: "The APK digest does not match the private catalog." }, 409);
  }

  const assetResponse = await githubRequest(
    "/repos/" + OWNER + "/" + REPOSITORY + "/releases/assets/" + asset.id,
    token,
    "application/octet-stream"
  );
  if (![301, 302, 303, 307, 308].includes(assetResponse.status)) {
    return json({ error: "GitHub did not provide a temporary asset URL." }, 502);
  }
  const location = assetResponse.headers.get("Location");
  if (!location) return json({ error: "GitHub omitted the temporary asset URL." }, 502);
  let target: URL;
  try {
    target = new URL(location);
  } catch {
    return json({ error: "GitHub returned an invalid asset URL." }, 502);
  }
  if (target.protocol !== "https:" || !target.hostname ||
      !ALLOWED_ASSET_HOSTS.has(target.hostname.toLowerCase()) || target.username || target.password) {
    return json({ error: "GitHub redirected to an untrusted asset host." }, 502);
  }

  return json({ url: target.toString(), expiresInSeconds: 600, versionId: id });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return json({ ok: true, service: "dash-launcher-api" });
      }
      if (request.method === "GET" && url.pathname === "/auth/login") return await login(request, env);
      if (request.method === "GET" && url.pathname === "/auth/callback") return await oauthCallback(request, env);
      if (request.method === "POST" && url.pathname === "/auth/exchange") return await exchangeCode(request, env);
      if (request.method === "GET" && url.pathname === "/api/catalog") return await apiCatalog(request, env);
      if (request.method === "GET" && url.pathname === "/api/download-url") return await apiDownloadUrl(request, env);
      return json({ error: "Not found." }, 404);
    } catch (error) {
      console.error("Dash Launcher API request failed:", error instanceof Error ? error.name : "unknown");
      return json({ error: "The private storage service could not complete the request." }, 502);
    }
  }
};
