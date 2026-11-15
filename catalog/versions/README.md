# Version index files

Each channel file is a JSON array of version records. The array is intentionally empty until there are verified, authorized installer artifacts to publish.

A record includes:
- `id`: unique identifier, for example `2.2081-android-arm64-v8a`.
- `version`: game version string.
- `channel`: stable, beta, or archived.
- `platform`: android.
- `abi`: Android ABI.
- `minAndroidApi`: minimum Android API level.
- `releasedAt`: ISO-8601 timestamp or null.
- `changelog`: release notes or null.
- `artifact`: null until a verified authorized download is available; otherwise HTTPS URL, byte size, and SHA-256.

Never show an entry as installable when `artifact` is null. The launcher must verify the downloaded size and SHA-256 before exposing an install action.
