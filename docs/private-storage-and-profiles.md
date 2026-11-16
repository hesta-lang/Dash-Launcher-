# Private storage and instances

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

## Prism-style storage model

Prism Launcher separates a shared launcher root from individual game instances. Its
root contains shared assets, libraries, metadata, and cache, while the `instances/`
folder contains user instances. Within a Minecraft instance, the game root supplies
instance-specific settings and save directories. Dash Launcher adapts that model
to Android's package sandbox:

```text
files/
├── versions/
│   └── <game-version>/
│       ├── game.apk
│       ├── metadata.json
│       └── active-profile.txt
├── instances/
│   └── <version-token>--<instance-id>/
│       ├── instance.json
│       ├── instance.apk
│       └── backups/
│           └── game-data.zip
└── cache/
```

### Shared version library

`versions/<game-version>/game.apk` is stored once after its catalog verification.
All named instances for that version use this same source APK to produce their own
package-specific install artifact. The source APK is not changed when an instance
is created or updated.

### Independent instances

Each directory below `instances/` represents one version/instance pair. Its
`instance.json` keeps the instance ID, version, editable name, timestamps, and
backup metadata. The stable ID is independent of the display name, so renaming an
instance does not change its Android package name.

The generated `instance.apk` is stored with that instance. Each generated package
is signed with the persistent Dash Launcher Android Keystore identity. Android
installs it as a different package and allocates a separate private app-data
sandbox. The original Geometry Dash package and other instances are not removed
or replaced by this operation.

### Android / game-data boundary

The app-specific `instances/` folder is launcher-managed storage; it is not the
game's private data directory. Android prevents Dash Launcher from directly
reading or writing another app's `/data/user/0/` directory. Selecting an active
instance in Dash Launcher is a launcher preference, while starting an installed
instance launches the corresponding Android package.

The current APK rewriter updates the binary manifest and resource-table package
identity, but does not rewrite package strings in DEX or native libraries. Such
references are reported as warnings, and some game features may still expect the
original package. A successful fixture test does not prove that a real Geometry
Dash build will work after repackaging.

The backup ZIP is stored at `instances/<instance-id>/backups/game-data.zip`.
Import/export validates and stores the archive but does not extract it into the
game or restore progress. A companion Geode bridge with an explicit backup
protocol is still required for actual save/settings/mod-data transfer.

## Safe migration

Older builds stored profile metadata and backup files below
`versions/<version>/profile/profiles/<profile-id>/`. On opening a version,
Dash Launcher copies recognized files into the new top-level `instances/`
folder. It retains the legacy files and the original shared `game.apk`; the
migration is non-destructive. The old per-version active pointer is read and
copied to `versions/<version>/active-profile.txt`.
