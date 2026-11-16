# Private storage and per-version profiles

## Private GitHub storage through the Dash Launcher backend

Dash Launcher no longer asks users to paste a Fine-grained Personal Access Token.
The Android app signs in through the system browser using the Dash Launcher GitHub
App. A Cloudflare Worker validates the GitHub identity and accesses the private
storage repository through a GitHub App installation.

The backend lives in `backend/cloudflare-worker/`. Its setup, GitHub App
permissions, Cloudflare secrets, and deployment instructions are documented in
[the backend README](../backend/cloudflare-worker/README.md).

### Credential boundaries

- The GitHub App private key, OAuth client secret, and GitHub installation access
  tokens exist only in Cloudflare Worker secrets and server memory.
- The Worker creates an installation token limited to
  `Dash-Launcher-Private-Storage` and `Contents: read`.
- The OAuth callback checks PKCE and an explicit allowlist of GitHub usernames.
  The initial allowlist contains only `hesta-lang`.
- Android receives a short-lived Dash Launcher session, not a GitHub token. It
  stores that session encrypted with AES/GCM using a key held in Android Keystore.
- The session expires after seven days. The user must reconnect through GitHub
  when it expires or is revoked.
- The backend validates the catalog entry against its GitHub Release before it
  returns a temporary asset URL. The Android app does not forward its session to
  the GitHub asset host.

The Worker URL is configured in
`app/src/main/java/com/hestalang/dashlauncher/private_storage/DashBackendConfiguration.kt`.
The placeholder URL must be replaced with the deployed Worker URL before building
a usable Android release. Never place GitHub credentials in that file or any
other Android source file.

The 2.2091 APK currently listed in the private catalog has a valid Android
signature, but its certificate has **not** been verified as the official RobTop
signing certificate. The launcher preserves that warning and checks the exact
pinned certificate fingerprint; it does not label the APK official.

## Profile layout

Named profile containers are stored under each version:

```text
versions/
└── 2.2091/
    ├── game.apk
    ├── metadata.json
    └── profile/
        ├── active-profile.txt
        └── profiles/
            ├── default/
            │   └── profile.json
            └── <profile-id>/
                ├── profile.json
                └── game-data.zip
```

The profile manager supports create, list, activate, rename, delete, and import/export of opaque ZIP backup files. The ZIP is stored and validated as an archive; it is not extracted into the game.

## Android / game-data boundary

This is a launcher-side profile manager, not yet an in-game save isolation mechanism. Android prevents Dash Launcher from directly reading or writing Geometry Dash's private `/data/user/0/com.robtopx.geometryjump/` directory. Selecting a profile in Dash Launcher changes the selected launcher profile only. It does not switch the running game's save, nor does it create parallel installations of the same package.

To make profiles represent actual progress, a companion Geode mod must export/import the game save, settings, levels and (where appropriate) mod configuration using a documented, integrity-checked archive protocol. The current release does not pretend that this companion bridge already exists.
