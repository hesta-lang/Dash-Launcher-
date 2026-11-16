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

## Important installation limitation

This storage layer preserves separate APK files and per-version profile directories. It does **not** make Android run several ordinary installations of the same Geometry Dash package at once. Android identifies installed apps by package name and signing identity; a collection of APK files in `versions/` is not the same as separately installed/running game instances.

The profile storage is therefore the foundation for a later version-isolation layer. Any future launch or switching mechanism must separately solve app identity, package/signature compatibility, and authorized import/export of the game's private data. Dash Launcher does not read another app's private `/data/user/0/` directory directly.
