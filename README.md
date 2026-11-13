# Piko

**Version 1.10.4**

Morphe patches for **Instagram**, based on the official Piko dev build (3.11.0-dev.5).
All Instagram patches and settings from Piko are included, plus an optional floating navigation bar (Settings > Navigation bar > Floating navigation bar, off by default) whose width and height can be set with sliders that show a live preview (Settings > Navigation bar > Floating bar size), a switch that removes the rounded pill behind the selected tab of the bottom bar (Settings > Navigation bar > Remove selected tab pill, on by default), and an experimental switch that turns on the floating bar built into Instagram itself (Settings > Navigation bar > Instagram's own floating bar, off by default), whose width and height follow the same sliders, and an optional switch that gives that bar a live blur, so the page behind it shows through, blurred (Settings > Navigation bar > Blur behind Instagram's own floating bar, off by default, Android 12 or newer), with two sliders from 0 to 100% under it for the blur amount and the transparency of that bar (the live preview shows both).
The Piko settings button (and the launcher icon's long-press shortcut) now opens a slide-up menu (at least half a screen high, with the page behind it blurred) with three cards: Piko settings, Developers, and the patch and Instagram versions.
The menu and the Piko settings page open from the bottom of the screen with a blur-fade (they rise out of a blur while fading in) and close the same way in reverse.

## Build

```sh
./gradlew build
```

The patch bundle (`.mpp`) is produced in `patches/build/libs/`. Add it to Morphe Manager as a local source, then patch the Instagram APKM.

## Instagram version support

The patches are built for **Instagram 452.1.0.53.64**, and that version is required: the bundle pins it, so
Morphe Manager asks for exactly this build.

- Pick the Instagram 452.1.0.53.64 APKM in Morphe Manager.
- To support another build later, change the version in `patches/src/main/kotlin/app/crimera/patches/instagram/utils/Constants.kt`.

## Notes

- Licensed under GPLv3 (see `LICENSE` and `NOTICE`). Piko is by crimera; upstream attribution and links keep the original name.

## Versioning

Bump the version after every major update, and name the archive `piko vX.Y.Z.zip` (for example `piko v1.0.1.zip`).
The version must be updated inside the files too. See `VERSIONING.md` for the rule and the list of files.
