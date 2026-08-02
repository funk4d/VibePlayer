# VibePlayer

Minimal Android TV player built for one device: TCL EP680 / Android 9 / RTD2851.

## Build without Android Studio

Requirements already installed on the development Mac:

- JDK 17
- Android command-line SDK, Platform 36, Build Tools 36.0.0

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

```bash
adb connect 192.168.68.111:5555
adb -s 192.168.68.111:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

VibePlayer accepts `ACTION_VIEW` intents for HTTP/HTTPS HLS, DASH, and progressive video. It forwards caller-provided HTTP headers to playlists and segments.

## Lampa plugin

Install the short GitHub Pages URL in Lampa:

```text
https://funk4d.github.io/VibePlayer/v.js
```

The bridge is passive: it observes the playback object already produced by `Lampa.Player.play` and forwards its URL, variants, playlist metadata, and source-provided headers to VibePlayer. It does not probe media URLs, call MODS APIs, proxy streams, or synthesize authorization headers.

## Remote

- Center: play/pause
- Left/right: seek 10 seconds
- Up/down: show controls
- Menu: toggle PCM night mode
- Back: return to caller

## Codec policy on this TCL

- 4K H.264, HEVC, and VP9: Realtek hardware decoder
- Dolby Vision: native attempt, first-frame watchdog, one compatible base-layer retry
- AV1 up to 1080p: bundled Media3/dav1d software decoder
- AV1 above 1080p: choose an available <=1080p rendition; if none exists, pause with a visible error

The last rule is a measured hardware limit, not an arbitrary quality cap. A real 4K 10-bit AV1 stream saturated all four CPU cores and dropped nearly every frame. Output downscaling cannot reduce AV1 bitstream decode cost.

The checked-in AV1 AAR contains only `armeabi-v7a`, matching the television. NDK, Meson, and Ninja are needed only to rebuild that AAR, not for normal VibePlayer builds.
