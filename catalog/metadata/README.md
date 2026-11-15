# Per-version metadata

Every version index entry points to one JSON document in this directory. The launcher downloads that document only when the user opens the version card.

Required fields:
- \`id\`: must exactly match the ID in the channel index.
- \`description\`: user-facing description (may be empty).
- \`platform\`: \`android\`.
- \`minAndroidApi\`: minimum Android API level.
- \`supportedAbis\`: supported Android ABIs, such as \`arm64-v8a\` and \`armeabi-v7a\`.
- \`artifact\`: null until an authorized download is configured; otherwise an artifact descriptor.

A direct artifact has:
- \`provider\`: \`direct\`.
- \`url\`: direct HTTPS APK URL.
- \`sizeBytes\`: exact expected file size in bytes.
- \`sha256\`: 64-character hexadecimal SHA-256 digest.

Example of metadata without an available artifact:

\`\`\`json
{
  "id": "gd-version-arm64",
  "description": "Download source not configured.",
  "platform": "android",
  "minAndroidApi": 23,
  "supportedAbis": ["arm64-v8a"],
  "artifact": null
}
\`\`\`

Provider IDs are extension points. A provider adapter must be explicitly implemented using a documented and authorized API before an artifact can be downloaded through it. Unknown provider IDs remain disabled.

The launcher checks platform compatibility before enabling the download, then verifies the downloaded file's exact size and SHA-256. Android's package installer remains a separate user-confirmed step.
