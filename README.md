# Dash Launcher

An Android-first Geometry Dash version manager inspired by Prism Launcher.

## Remote version catalog

Dash Launcher uses a remote JSON catalog. The launcher can refresh the catalog independently of APK releases, so new version metadata can appear without updating the launcher itself.

### Catalog capabilities

- **Channels:** Stable, Beta, and Archived.
- **Version discovery:** search, sort, filter, and inspect release metadata.
- **Android compatibility:** ABI and minimum Android API metadata.
- **Integrity checks:** SHA-256 and expected file size for every downloadable artifact.
- **Installation state:** distinguish available, installed, updateable, and unavailable entries.
- **Safe distribution:** only list artifacts from authorized distribution sources.

## Repository layout

```
catalog/
  manifest.json
  schema.json
  versions/
    stable.json
    beta.json
    archived.json
    README.md
```

## Catalog status

The catalog structure is being initialized. No game package is currently published as downloadable. A version must not be marked installable until its authorized HTTPS source, file size, and SHA-256 digest have been verified.

## Client behavior

1. Fetch the manifest from the configured catalog base URL.
2. Validate the manifest version and required fields.
3. Fetch the selected channel's version index.
4. Apply Android ABI/API compatibility filters.
5. Show release details and installation state.
6. Before installation, verify HTTPS, expected size, and SHA-256.
7. Keep installed instances separate so switching versions does not overwrite another instance.

The catalog contains metadata and links only; it does not host or redistribute Geometry Dash game files.
