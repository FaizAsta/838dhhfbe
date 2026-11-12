# Versioning

**Current version: 1.10.0**

## Rule

Bump the version after every major update. Nothing is released or zipped without a bump.

Format: `MAJOR.MINOR.PATCH` (for example `1.0.0` -> `1.0.1` -> `1.1.0` -> `2.0.0`).

- PATCH: small fix or tweak
- MINOR: new feature, patch or setting
- MAJOR: big change or rewrite

## Archive file name

The project archive is always named with the version, like this:

    piko v1.0.0.zip
    piko v1.0.1.zip

## Where the version lives

Update the number in all of these every time, so they never disagree:

1. `gradle.properties` (`version = X.Y.Z`). This is what Settings > About > Patch version shows in the patched app.
2. `patches-bundle.json` (`"version"` and the `## Piko X.Y.Z` line in `"description"`)
3. `patches-list.json` (top-level `"version"`)
4. `VERSIONING.md` (the "Current version" line above)
5. `CHANGELOG.md` (add the new version heading and its changes)
6. The archive file name (`piko vX.Y.Z.zip`)
