# Prism Launcher architecture study and Dash Launcher adaptation

Research reference: the Prism Launcher source tree and its official data-location documentation. This document records the architectural ideas relevant to a mobile APK launcher; it is not a port of Prism's code.

## How Prism finds and installs Minecraft versions

### 1. Metadata is organized as an index hierarchy

The source under \`launcher/meta\` uses an \`Index\` to identify packages/components. Each package has a \`VersionList\`, and individual versions have their own metadata entity.

In the current source:
- \`meta/Index.cpp\` loads the package index and resolves a package by UID.
- \`meta/VersionList.cpp\` loads a package's \`index.json\`, sorts the known versions, and resolves individual version IDs.
- \`meta/Version.cpp\` represents one version and points to its detailed version data.
- \`meta/JsonFormat.cpp\` parses the index, version list, and version document separately.

The loader therefore does not need to download every version document merely to display the version picker. It starts with the index and loads more detail when required.

Minecraft's own Java Edition metadata follows a related pattern: the version manifest identifies the versions and links to a per-version JSON document. That document declares the client download and other resources.

### 2. Metadata has a local cache and integrity checks

\`meta/BaseEntity.cpp\` first checks whether a local metadata file is available. When online data is needed, it uses the metadata cache and HTTP validators. Where a SHA-256 is declared, it checks the downloaded metadata against that digest before accepting it. Parsed documents are persisted for future use.

\`net/HttpMetaCache.cpp\` tracks cache entries, local file changes, ETags, remote change timestamps and expiration. A manual refresh can invalidate validators and request fresh data.

### 3. Downloads are tasks, not UI code

\`net/NetJob.cpp\` schedules multiple network requests, tracks pending/active/completed/failed work and retries eligible failures. HTTP 404 responses are not retried indefinitely.

The instance creation flow is separate:
- \`minecraft/VanillaInstanceCreationTask.cpp\` creates an instance in a staging directory, writes its component profile and then begins retrieving game files.
- \`ResourceDownloadTask.cpp\` represents an individual resource transfer and can validate a supplied checksum.
- A finished resource replaces the previous local resource only after a successful transfer.

Prism's data-location documentation also describes separated directories for instances, libraries, assets, cached downloads, metadata and logs.

## What transfers to Android APK distribution

Dash Launcher adopts the general design, not Prism's Minecraft-specific file model:

| Prism concept | Dash Launcher equivalent |
|---|---|
| Package index | Stable / Beta / Archived channel index |
| Per-version JSON | One metadata JSON document per Geometry Dash release |
| Metadata cache | App-private JSON cache with ETag / Last-Modified revalidation |
| Component/download provider | Registered APK download provider |
| NetJob / resource task | Android DownloadManager plus a verification stage |
| Instance profile | Version metadata and downloaded APK record |

The index is fetched first. When a user expands a version, the launcher requests that version's metadata. Those details declare Android API requirements, supported ABIs and an artifact descriptor. The direct provider is enabled only when the descriptor provides an HTTPS URL, exact size and SHA-256. Unknown provider IDs remain disabled until an authorized adapter is implemented.

The current catalog has no published releases, so the UI correctly shows empty channels. This implementation does not scrape Uptodown, reverse engineer private endpoints, or invent a download URL.

## Important Android difference: instances are not side-by-side app installations

Prism can keep many Minecraft instances because each instance has its own game directory and profile. Android normally identifies an installed app by its package/application ID. Two APKs with the same package ID and signing identity cannot be installed side-by-side as separate versions through the ordinary package installer.

Dash Launcher can manage multiple downloaded APK files and their metadata, but replacing the installed Geometry Dash version remains a separate Android package-installer operation. Installing a different version may update or replace the existing app; it is not equivalent to Prism's isolated instances. The launcher must never imply that it provides parallel installations unless a legitimate Android packaging/isolation design is implemented.

## Implementation boundary

This adaptation was written independently around the existing Kotlin/Jetpack Compose project. It does not copy Prism Launcher implementation code. Prism Launcher contains GPL-3.0-only components as well as files retaining earlier Apache-2.0 notices; the Dash Launcher implementation uses the documented architecture as a design reference rather than importing those source files.

Useful references:
- Prism Launcher repository: https://github.com/PrismLauncher/PrismLauncher
- Prism metadata index: \`launcher/meta/Index.cpp\`, \`VersionList.cpp\`, \`Version.cpp\`, and \`JsonFormat.cpp\`
- Prism cache and requests: \`launcher/meta/BaseEntity.cpp\`, \`launcher/net/HttpMetaCache.cpp\`, and \`launcher/net/NetJob.cpp\`
- Prism instance/resource path: \`launcher/minecraft/VanillaInstanceCreationTask.cpp\` and \`launcher/ResourceDownloadTask.cpp\`
- Prism data storage documentation: https://www.prismlauncher.org/wiki/getting-started/data-location/
- Minecraft Java version manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
