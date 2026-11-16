# Download providers and catalog pipeline

## Catalog flow

Dash Launcher uses a two-stage, read-only catalog:
1. `catalog/manifest.json` maps Stable, Beta and Archived to channel index files.
2. The selected index lists version IDs and relative metadata paths.
3. Per-version metadata describes Android API requirements, verified ABIs and an optional artifact.
4. A registered provider resolves the artifact. Unknown providers remain disabled.
5. Android DownloadManager performs a direct APK transfer only when the catalog contains a complete, authorized artifact.
6. Dash Launcher checks file size, APK SHA-256, package ID, signing-certificate SHA-256 and the native ABI set before it marks a file as verified.

The app fetches per-version metadata only after a version card is opened. Metadata is cached in the app cache directory and revalidated with ETag / Last-Modified headers. A previously parsed cache may be used offline with a visible warning.

## Direct APK requirements

The registered provider ID is `direct`. It is intentionally strict. A direct artifact requires:
- An HTTPS APK URL from a source that is legally authorized to distribute that file.
- Exact positive `sizeBytes` and a 64-character APK `sha256`.
- `packageName` equal to `com.robtopx.geometryjump`.
- A verified 64-character `signingCertificateSha256`, calculated from the APK signing certificate.
- A non-empty list of supported native ABIs matching the libraries actually included in the APK.

The app rejects direct metadata with missing package identity, signing fingerprint, size, checksum, or ABI information. After download it inspects the APK itself and deletes it when any declared value does not match. A checksum alone does not establish publisher authenticity; the expected signing certificate must be independently verified from a trusted official build or source.

The project must not publish or host Geometry Dash APKs unless distribution is authorized. A provider listing, attribution, or disclaimer is not permission to redistribute.

## External provider pages

The `external-page` provider is not a download adapter. It opens the provider's own HTTPS page through Android's browser or the provider app. External page entries must not claim an APK size, APK checksum, signing certificate, package identity, or ABI compatibility.

The public Storage catalog currently contains only external references: Google Play for the current official store listing, and historical Uoldown / APKCombo references. These entries are explicitly non-downloadable from Dash Launcher. Their package/signing information has not been independently verified. Beta remains empty.

No Uptodown-specific adapter is enabled. The public documentation reviewed for this project does not provide a third-party API contract for requesting arbitrary APK downloads. Do not scrape provider pages, reverse-engineer private CDN endpoints, or automate requests without express authorization.

A provider may be integrated as a direct source only when it publishes a documented API, permits the intended use, and the specific file can be authenticated and legally distributed.

## Explicit installation and updates

A verified direct APK is saved in Dash Launcher's app-specific download directory. The app then exposes an **Install / update** action. The user must tap it; Android's package installer performs the final installation and asks for confirmation. If Android's unknown-app permission is not enabled for Dash Launcher, the app opens the relevant system settings page first.

Dash Launcher does not silently install, re-sign, patch, or rewrite the game. Android accepts an update only when the package ID and signing identity satisfy Android's update rules. An update using the official package ID and compatible signing identity is the normal route for preserving the existing app's data; Android remains responsible for enforcing compatibility and retaining data.

Android ordinarily permits one installed app per package ID. Multiple side-by-side copies require distinct package IDs and separate app data. Dash Launcher must not alter or re-sign Geometry Dash packages to create unofficial parallel copies.

## Provider extension boundary

Provider IDs are an extension point, not permission to automate a site. An adapter may be added when a provider publishes a documented API and authorizes the intended use. A page-only provider or a provider without redistribution rights must remain an external reference.

## Current limitations

- The catalog's current Stable and Archived entries are external references only; there is no authorized direct APK configured.
- Beta is empty until a public beta for the full game can be verified.
- The app can download and verify a properly authorized direct artifact, but no such Geometry Dash APK is currently published in Storage.
- The final installation is handled by Android and requires the user's explicit confirmation.
- A successful GitHub Actions build does not prove that a third-party APK is authentic or that it can legally be redistributed.

See [the Prism Launcher study](prism-launcher-study.md) for the source-code analysis and the limits of adapting its instance model to Android.
