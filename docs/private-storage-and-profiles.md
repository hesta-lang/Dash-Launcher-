# Private storage and per-version profiles

## Private GitHub storage

The launcher connects to the private repository `hesta-lang/Dash-Launcher-Private-Storage` through the GitHub REST API.

Create a **fine-grained personal access token** with:
- Resource owner: `hesta-lang`
- Repository access: only `Dash-Launcher-Private-Storage`
- Repository permission: `Contents: Read-only`
- A short expiration appropriate for the device

Do not use a classic token with broad `repo` access. The token is entered in Dash Launcher settings, validated against the configured private repository, then encrypted at rest with an AES/GCM key held in Android Keystore. The app sends it only to `api.github.com`. For release downloads, the app follows GitHub's temporary HTTPS asset redirect without forwarding the token to the redirected host.

The private catalog is `catalog/versions.json`. Each entry pins the release tag, asset name, exact size, SHA-256, package, signer fingerprint, ABIs and provenance warning. The launcher downloads the named asset only from the matching Release and checks all those fields before placing it under `versions/<version>/game.apk`.

The 2.2091 APK currently listed in the private catalog has a valid Android signature, but its certificate has **not** been verified as the official RobTop signing certificate. The launcher preserves that warning and checks the exact pinned certificate fingerprint; it does not label the APK official.

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
