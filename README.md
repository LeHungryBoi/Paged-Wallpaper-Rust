# Paged Wallpaper Rust

A Rust-first Android fork of Paged Wallpaper. This project lives in its own
repository root; the original project remains unchanged.

## What it includes

- Launcher page calibration, home-page selection, per-page image assignment,
  crop/fill controls, parallax, and named presets.
- Per-boundary transitions: scroll, parallax, classic parallax, fade, soft blur,
  depth, slide-over, and 3D cube.
- Motion/easing controls, scheduled or system-triggered dimming, and optional
  diagnostics overlays and recording.
- Image library import, duplicate/delete, plus ZIP export/restore and optional
  automatic backups through Android's Storage Access Framework.
- A Rust-owned JSON state store and Android `Canvas` renderer, accessed through
  JNI. Kotlin is limited to Android framework lifecycle, UI, bitmap/SAF access,
  and the `WallpaperService` bridge.

The archive uses the original project's `config.json`, `originals_meta.json`,
`originals/`, and `cropped/` layout. Existing Paged Wallpaper backup ZIPs can be
restored in this fork.

## Build and releases

The GitHub Actions workflow installs the Android SDK/NDK, Rust targets, and
Gradle on the hosted runner, runs the Rust unit tests, then builds the APK.
There is no requirement to install or run a compiler on the development PC.

To use it, place the contents of this directory at the root of a separate GitHub
repository and push to `main`. The workflow publishes a debug-signed APK release
for each successful `main` build and deletes older `rust-build-*` releases and
tags, leaving the newest three. Other branches build and test without publishing
a release. The generated APK is debug-signed; use a private signing key in
GitHub Actions secrets before treating releases as a stable upgrade channel.

The first hosted Actions run is the required compile/integration check. The
project has not been built on the local PC.
