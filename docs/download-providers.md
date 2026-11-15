# Download providers and catalog pipeline

## Prism-inspired metadata flow

Dash Launcher uses a two-stage catalog:
1. \`catalog/manifest.json\` maps Stable, Beta and Archived to channel index files.
2. The selected channel index lists version IDs and relative paths to per-version metadata documents.
3. A version metadata document describes Android API requirements, supported ABIs and an optional artifact.
4. A registered provider resolves the artifact. Unknown provider IDs stay disabled.
5. Android DownloadManager performs the background APK transfer.
6. Dash Launcher verifies the exact file size and SHA-256 before marking it verified.

This separates catalog discovery from artifact delivery. The app fetches detailed metadata only after a version card is opened. The metadata cache stores documents in the app cache directory and revalidates them with ETag / Last-Modified headers. If the network is unavailable, a previously parsed cached document may be used with a visible warning.

See [the Prism Launcher study](prism-launcher-study.md) for the source-code analysis and the limits of adapting its instance model to Android.

## Current direct provider

The registered provider is \`direct\`. It accepts only a complete HTTPS artifact descriptor with:
- a direct HTTPS URL,
- a positive exact file size,
- a 64-character SHA-256 digest.

Files are written to the app-specific external downloads directory. Android displays the download notification. A mismatch in size or SHA-256 causes the downloaded file to be removed.

## Provider extension boundary

Provider IDs are intended to support future adapters. An Uptodown-specific adapter is not enabled: the public documentation reviewed for this project does not provide a public third-party API contract for requesting arbitrary APK downloads. Do not scrape pages, reverse-engineer private CDN endpoints, or automate requests without express authorization.

An adapter may be added when a provider publishes a documented API and permits this use. A provider that only returns a web page, or whose distribution rights do not cover the requested APK, is not a direct-download provider.

## Current limitations

- Stable, Beta and Archived are empty until authorized version metadata is published.
- This build does not discover Geometry Dash releases from Uptodown or another APK mirror.
- It does not automatically install an APK. Android's package installer and the user's confirmation / unknown-app permission are separate.
- Android's package manager ordinarily keeps one installed app per package ID; multiple downloaded APKs do not create Prism-style side-by-side installations.
- Do not publish a Geometry Dash artifact without verifying its source, signature/provenance and distribution permission.
