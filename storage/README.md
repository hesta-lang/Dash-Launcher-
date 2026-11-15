# Dash Launcher version storage

This directory documents the binary storage layout. The catalog metadata lives in `/catalog`; APK files do not belong in the Android app or in Git history.

## Storage layout

Use an object-storage bucket (for example, an S3-compatible service) with this key layout:

```text
geometry-dash/
  android/
    arm64-v8a/
      <version-id>.apk
    armeabi-v7a/
      <version-id>.apk
    x86/
      <version-id>.apk
    x86_64/
      <version-id>.apk
```

The bucket name, credentials, and public base URL must be configured only when a storage provider is selected. Do not commit credentials, signed URLs, or private keys.

## Catalog-to-file relationship

Each entry in `catalog/versions/{stable,beta,archived}.json` identifies one build. Its `artifact` field stays `null` until an authorized, verified installer is available.

When publishing an artifact, set:

- `url`: HTTPS download URL for the exact file.
- `sizeBytes`: exact byte size.
- `sha256`: lowercase SHA-256 digest of the complete file.

The launcher must check the downloaded byte count and SHA-256 before offering installation. A missing artifact or failed verification means the version is not installable.

## Request flow

1. The launcher fetches `catalog/manifest.json`.
2. It reads the channel index referenced by `versionFiles`.
3. The user selects a version and the launcher requests that entry's exact `artifact.url`.
4. The launcher downloads to temporary storage, verifies `sizeBytes` and `sha256`, and only then makes the file available to Android's package installer.

The catalog is static metadata, not a download proxy or an API that accepts arbitrary paths. The client must only download URLs supplied by a valid catalog entry and must require HTTPS.

## Publishing checklist

Before adding an artifact to a channel index:

- Confirm that the file is an Android APK for the declared ABI and version.
- Confirm that its source and redistribution are authorized.
- Record the exact file size and SHA-256.
- Upload the file to the matching object-storage key.
- Verify the uploaded object can be downloaded and its digest matches.
- Update the channel JSON with the final HTTPS URL and metadata.
- Never publish placeholders as installable entries.

## Current status

The storage layout and catalog contract are prepared, but no APK binaries or storage-provider credentials have been added. The three channel indexes intentionally remain empty until there are verified artifacts that may be distributed.
