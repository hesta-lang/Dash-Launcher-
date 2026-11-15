# Internal download providers

Dash Launcher downloads only APK artifacts explicitly described by its own catalog.

## Current behavior

- The app uses Android `DownloadManager` so transfers continue outside the activity and Android displays its download notification.
- Files are written to the app-specific external downloads directory; no broad storage permission is requested.
- The catalog entry must contain a direct HTTPS URL, a positive verified file size, and a 64-character SHA-256.
- After the transfer, Dash Launcher streams the file through SHA-256 and checks its size. A mismatch causes the downloaded file to be removed.
- The current sample catalog item has no artifact metadata, so its Download button remains disabled.

## Provider boundary

`DownloadProvider` separates version metadata from its delivery source. The current implementation, `CatalogDirectDownloadProvider`, accepts only direct HTTPS artifact URLs already present in a catalog entry.

An Uptodown-specific adapter is intentionally not enabled. Uptodown's public help documents downloading through its website or its official Android app, and a Media Kit link to an app page; it does not document a public API for third-party launchers to request a particular APK. Do not scrape pages, reverse-engineer private download endpoints, or automate Uptodown requests without its express authorization. An official API/partner agreement can later be implemented as another `DownloadProvider`.

## What this does not do yet

- It does not discover versions from Uptodown.
- It does not fetch or parse Uptodown pages.
- It does not install the APK automatically. Android's package installer and the user's confirmation/unknown-app permission are separate steps.
- It does not enable any Geometry Dash APK until the catalog contains a verified, authorized artifact URL, exact size, and SHA-256.

Unit tests cover HTTPS validation, required integrity metadata, credential rejection, and safe APK filenames.
