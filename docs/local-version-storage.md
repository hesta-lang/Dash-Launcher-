# Local version storage

Dash Launcher keeps every saved Geometry Dash APK and its per-version profile root under one app-specific directory named `versions`.

## Directory location

The app first uses Android's app-specific external files directory:

```text
/storage/emulated/0/Android/data/com.hestalang.dashlauncher/files/versions/
```

If Android does not provide that directory, Dash Launcher falls back to:

```text
/data/user/0/com.hestalang.dashlauncher/files/versions/
```

This is app-specific storage. Dash Launcher can read and write it without requesting broad storage permissions. Other apps and file managers may not be able to browse it freely. Android removes app-specific files when Dash Launcher is uninstalled, so users should export or back up files before uninstalling the launcher.

## Layout

Each stored version receives its own folder:

```text
versions/
├── 2.2081/
│   ├── game.apk
│   ├── metadata.json
│   └── profile/
└── 2.2091/
    ├── game.apk
    ├── metadata.json
    └── profile/
```

- `game.apk` is written only after the download has passed the catalog's size, SHA-256, package, signing-certificate, and ABI checks and the stored copy passes its own size and SHA-256 checks.
- `metadata.json` stores the version, display name, APK size/hash, package name, signing-certificate fingerprint, and metadata schema version.
- `profile/` is the reserved per-version data root. A companion integration still needs to export/import actual Geometry Dash progress, settings, levels, and Geode configuration. The folder existing does not mean those game-private files have already been captured.

## Independent installation for each version

Dash Launcher can create one Android installation per Geometry Dash version. The verified source APK remains at `versions/<version>/game.apk`; the separately installable clone is kept in that version's `profile/` folder.

The package ID is deterministic from the version string:

- `2.2081` → `com.hestalang.dashlauncher.gd.v2_2081`
- `2.2091` → `com.hestalang.dashlauncher.gd.v2_2091`

Android treats these as different apps, so installing one does not replace another. The original `com.robtopx.geometryjump` package is not uninstalled or modified. Each installed package receives its own Android application sandbox.

### APK transformation and limitations

To create a second installable package, Dash Launcher rewrites the binary manifest and resource-table package identity, then signs the resulting APK with a persistent local Android Keystore key owned by Dash Launcher. The downloaded `game.apk` remains unchanged and keeps the source APK's original bytes and signature.

The current cloner does **not** rewrite package-name strings inside DEX files or native libraries. It reports detected references as warnings, because they may be harmless or may indicate code that expects the original package ID. A Geometry Dash version that validates or relies on `com.robtopx.geometryjump` internally may fail to start or may have broken features after cloning. Package-identity checks do not substitute for testing each real APK on a device.

The derived package is signed by Dash Launcher, not by RobTop. It is a separate installation, not an official RobTop update. Since Android gives each package its own private data directory, progress and settings are not automatically shared between versions. Dash Launcher does not read another app's private `/data/user/0/` directory directly.

The signing key is stored in Dash Launcher's app data. Removing Dash Launcher's data or uninstalling it may remove that key; an already installed clone could then reject a future in-place update signed with a newly generated key.
