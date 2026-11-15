# Version index files

Each channel file is a small JSON array. Dash Launcher fetches the selected channel index first, then loads an individual version's metadata only when the user expands that version. This keeps the initial catalog response small and avoids requesting every artifact record at startup.

Each index record contains:
- \`id\`: unique stable identifier, for example \`gd-2.2081-arm64\`.
- \`displayName\`: name shown in the launcher.
- \`version\`: Geometry Dash version label.
- \`channel\`: \`stable\`, \`beta\`, or \`archived\`.
- \`platform\`: \`android\`.
- \`minAndroidApi\`: minimum Android API level.
- \`releasedAt\`: ISO-8601 timestamp or null.
- \`changelog\`: short release note or null.
- \`metadata\`: relative path to the version-specific metadata JSON document.

The metadata path is resolved relative to the index file and must remain inside the catalog directory on the same HTTPS host.

The channel arrays are intentionally empty until there are verified, authorized installer artifacts to publish. Do not add a Geometry Dash APK merely because it can be downloaded from a public mirror. Confirm its provenance and redistribution permissions first.

See [the metadata format](../metadata/README.md) and [the JSON schema](schema.json).
