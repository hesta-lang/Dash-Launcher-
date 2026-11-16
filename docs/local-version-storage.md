# Local version and instance storage

Dash Launcher uses a Prism-style layout: a shared library of verified game versions, a separate directory for every instance, and a cache area for rebuildable temporary files.

## Directory location

The storage root is the app-specific external files directory:

```text
/storage/emulated/0/Android/data/com.hestalang.dashlauncher/files/
```

If Android does not provide that directory, Dash Launcher falls back to:

```text
/data/user/0/com.hestalang.dashlauncher/files/
```

This is app-specific storage. Dash Launcher can read and write it without requesting broad storage permissions. Other apps and file managers may not be able to browse it freely. Android removes app-specific files when Dash Launcher is uninstalled, so export or back up important files before uninstalling the launcher.

## Layout

```text
files/
├── versions/
│   ├── 2.2081/
│   │   ├── game.apk
│   │   ├── metadata.json
│   │   └── active-profile.txt
│   └── 2.2091/
│       ├── game.apk
│       └── metadata.json
├── instances/
│   ├── v322e32303831--default/
│   │   ├── instance.json
│   │   ├── instance.apk
│   │   └── backups/
│   │       └── game-data.zip
│   └── v322e32303831--<instance-id>/
│       ├── instance.json
│       └── backups/
└── cache/
```

### Shared version library

- `versions/<version>/game.apk` is the single verified source APK for that game version. It is checked against the catalog's size, SHA-256, package, signing-certificate fingerprint, and ABI before storage.
- `metadata.json` records the version identity, display name, APK size/hash, package name, and signing-certificate fingerprint.
- Several instances of the same game version reuse this source APK. Creating a new instance does not download another copy of the original APK.

### Isolated instances

- Each version/instance pair has a unique directory under `instances/`. The folder name is based on the version and stable instance ID, not the editable display name.
- `instance.json` stores the instance name, version, creation/update times, and backup metadata.
- `instance.apk` is the profile-specific APK generated from the shared source when the user requests installation or update. It has its own Android package identity and local Dash Launcher signature.
- `backups/game-data.zip` is an opaque backup imported or exported by the launcher. It is not extracted into the game's private data directory.
- Android stores each installed package's private data in its own sandbox. The launcher-side `instances/` folder does not replace or directly access that sandbox.

### Cache

The sibling `cache/` folder is reserved for temporary or rebuildable data. It is not the authoritative copy of a game version or instance.

## Safe migration from older layouts

Older builds stored profiles below:

```text
versions/<version>/profile/profiles/<profile-id>/
```

When a version's instances are opened, Dash Launcher copies recognized metadata, generated instance APKs, and ZIP backups into the new `instances/` layout. The old files are deliberately retained; a successful migration does not delete the previous copy. The active-instance pointer is moved to `versions/<version>/active-profile.txt`, while the old pointer remains as a fallback source.

The existing `versions/<version>/game.apk` files are not moved or rewritten by this layout change.

## Android / game-data boundary

Android identifies installed apps by package name and signing identity. The original Geometry Dash package remains separate from each Dash Launcher-created package; no installed app is automatically uninstalled or overwritten by instance creation. The Android installer still requires user confirmation.

Changing an APK's package identity does not guarantee that every game feature will work. Dash Launcher currently changes the binary manifest and resource-table package identity, but does not rewrite package references inside DEX files or native libraries. Those references are analyzed and reported as warnings. A real Geometry Dash version must still be tested on-device; a CI fixture only validates the packaging mechanics.

Dash Launcher cannot directly read or write another app's private `/data/user/0/` directory. A supported Geode companion bridge is still needed to export/import actual progress, settings, created levels, and mod configuration. The current ZIP import/export functions store backups only and do not claim to restore in-game progress.
