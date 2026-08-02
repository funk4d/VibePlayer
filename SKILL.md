---
name: build-vibeplayer-tv
description: Build, debug, package, install, and device-tune VibePlayer, a minimal Kotlin and Media3 Android TV video player made specifically for Dmytro's TCL EP680. Use for any work in the VibePlayerTV project involving playback, HTTP streams and headers, Dolby Vision base-layer fallback, AV1 software fallback, PCM night-mode compression, D-pad UI, command-line Android builds, ADB deployment, logcat diagnosis, or validation on the target television.
---

# Build VibePlayer TV

## Mission

Build a small, reliable standalone video player named **VibePlayer** for exactly one target device: Dmytro's 4K TCL 50EP680. Optimize for observed behavior on that television, not for generic Android compatibility. Maximize playback coverage across the user's real streaming catalog; do not reject a format merely to keep the implementation small.

Treat an external `ACTION_VIEW` intent as a thin input boundary. Do not couple the app to Lampa, a specific catalog, or a caller-specific playlist API. Any app or ADB command may supply a video URI.

Use Kotlin, classic Android Views, and stable AndroidX Media3. Keep the project buildable entirely from the command line. Do not require Android Studio.

## Fixed product decisions

- Use app label `VibePlayer` and application ID `com.vibeplayer.tv` unless an existing project has already established a different ID.
- Support direct HTTP/HTTPS HLS, DASH, and progressive formats handled by Media3 and the television's platform decoders. Prioritize HLS and MP4 because they are the main catalog path, but do not impose an artificial 1080p ceiling.
- Accept `Intent.ACTION_VIEW` with the media URI in `intent.data` and MIME types `video/*`, `application/vnd.apple.mpegurl`, and `application/x-mpegURL`.
- Expose a normal `MAIN` / `LEANBACK_LAUNCHER` television tile without adding the Leanback library. A launcher start with no URI should show a small VibePlayer status/instruction screen, which also proves that installation succeeded.
- Keep the UI full-screen and television-first: video, minimal controls, useful loading/error state, and D-pad operation.
- Make night mode enabled by default, with an in-player toggle for A/B testing.
- Keep the APK lean, but treat 10-15 MB as a measurement goal rather than a reason to lose content coverage.
- Do not add Compose, Leanback, a media browser, a file picker, accounts, analytics, ads, SMB, DLNA, NFS, torrent support, or custom FFmpeg. The one deliberate native exception is the checked-in Media3 1.10.1 dav1d AV1 extension for `armeabi-v7a`, added after a real Alloha sample proved to be AV1-only.
- Do not add DRM, external subtitles, background playback, or a media session until a real input sample requires them. The one caller-extension exception is the optional, prefix-encoded Lampa bridge for voiceovers, episode URLs, and display metadata; VibePlayer must remain a normal generic `ACTION_VIEW` player when those labels are absent.

## Verified target facts

These facts were read from the actual television over ADB on 2026-08-02. Recheck them only after a firmware update.

- ADB serial: `192.168.68.111:5555`
- Reported product: TCL `BeyondTV`; user-confirmed retail model: TCL EP680
- Android 9, API 28, build `PPR1.180610.011`, firmware `AP08` from 2021
- Realtek RTD2851 platform: `rtd285o` / `RTD2851`
- 32-bit ARM only: `armeabi-v7a`; no 64-bit ABI
- 2 GB RAM
- Physical television panel and target playback path: 3840x2160 (4K UHD)
- Android reports a 1920x1080 at 60 Hz application/UI framebuffer. This is not the panel resolution and must never be used as a 1080p playback cap; the Realtek hardware video path is separate.
- Hardware video decoder: `OMX.realtek.video.decoder`
- Advertised hardware limits: AVC 4096x2176, HEVC 4096x2176, VP9 4096x2176; the vendor codec performance table reports HEVC and VP9 at 3840x2160 up to 60 fps
- No platform AV1 decoder is advertised in the vendor codec XML. 4K in H.264, HEVC, or VP9 working does not imply that 4K AV1 works.
- Advertised Dolby Vision decoders: `OMX.realtek.video.dvhe.*` and `OMX.realtek.video.dvav.*`
- Google software H.264/HEVC decoders are not a viable 4K fallback; the vendor performance table reports software HEVC at only about 20 fps even at 1080p
- Hardware audio decoder `OMX.RTK.audio.decoder` advertises AC-3, E-AC-3, AC-4, DTS, DTS-HD, and DTS-HD LBR; Google decoders cover AAC, MP3, Vorbis, Opus, FLAC, and common PCM formats
- The firmware exposes `DynamicsProcessing` through `/vendor/lib/soundfx/libdynproc.so`, but the real API rejects construction with `AudioEffect: bad parameter value`. Treat the effect as broken, not available.
- The active media output is the analog headphone device
- The four-core CPU is fully saturated by four dav1d workers on a measured 3840x2160 23.976 fps AV1 10-bit stream. It dropped 102 frames in the first 5 seconds even with Media3's recommended decoder GL output. 4K AV1 software playback is not viable.
- The same Alloha master contained exactly one 3840x2160 AV1 rendition, so a player-side quality selector had no 1080p track to choose.
- Official Lampa `top.rootu.lampa` 1.12.9 is installed alongside the older customized `ru.twicker.lampa` 7.7.9. The official build resolves VibePlayer as an external HLS player and passes `position`, `quality_levels`, `quality_urls`, and headers.
- A real official-Lampa launch exposed 2160p, 1440p, 1080p, 720p, 480p, and 360p as separate external variants. The 1080p rendition was H.264 (`avc1.640028`) at 1920x1080/23.974 fps and played correctly through `OMX.realtek.video.decoder`.
- Lampa's `quality_urls` values are not type-stable: an entry can be a direct `Uri`/string or a JSON object string containing `url`, such as `{"label":"2K","url":"..."}`. Parse both without reordering labels.
- `adb` exists at `/opt/homebrew/bin/adb`. JDK 17, Android command-line tools, NDK r27, Meson, and Ninja are now installed; Android Studio is not installed or required.

## Correct the Gemini draft

Retain the useful direction from `gemini-code.md`: Media3, header forwarding, decoder initialization fallback, immersive playback, and night-mode processing.

Do not repeat its unsupported assumptions:

- `DefaultTrackSelector` cannot convert a single Dolby Vision track into HDR10 or SDR. It can select an alternate non-DV rendition when the HLS/DASH manifest actually contains one.
- `setEnableDecoderFallback(true)` only helps when codec initialization fails. It does not detect a codec that initializes successfully and then renders a black picture.
- Software decode cannot rescue 4K HEVC/Dolby Vision on this television, and Android 9 cannot provide a general real-time DV-to-HDR10/SDR tone-mapping pipeline.
- A limiter alone is not a good night mode. Use moderate broadband compression followed by a peak limiter.
- Do not hard-code only channel 0 and 1 when the decoded source may contain more channels.
- Leanback adds no value to a single `PlayerView` activity with explicit D-pad handling.
- The manifest also needs `INTERNET`, cleartext policy for HTTP sources, exported activity rules, and categories/schemes that make `ACTION_VIEW` resolution work.

## Pin a reproducible toolchain

Prefer the following known-current stable baseline unless the repository already pins another working combination:

- JDK 17
- Android Gradle Plugin 9.3.0 with built-in Kotlin; do not also apply `org.jetbrains.kotlin.android`
- Gradle 9.5.0 through the project wrapper
- `compileSdk` 36, `minSdk` 28, and `targetSdk` 28
- Android SDK Platform 36 and Build Tools 36.0.0
- AndroidX Media3 1.10.1 for `media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, and `media3-ui`
- Media3 1.10.1 `decoder_av1` built against dav1d 1.5.4, packaged only for `armeabi-v7a`

Never use dynamic dependency versions. Normal app builds consume the checked-in AV1 AAR and need only JDK 17 plus Android command-line SDK tools. Rebuilding the AV1 AAR additionally requires NDK r27, Meson, Ninja, and the official Media3 `decoder_av1` sources. Do not install the full Android Studio application.

Keep the Gradle wrapper in the repository so a clean checkout builds with `./gradlew`. Configure the SDK path locally without committing a machine-specific `local.properties`.

## Implement the smallest correct architecture

Use one exported playback `Activity`, one layout containing `PlayerView` plus a small status/error overlay, and narrowly focused helpers only where they make behavior testable:

- `PlaybackRequest`: parse URI, incoming MIME type and whether it was declared or guessed, optional title/source display metadata, prefixed bridge variants, reserve addresses, and sanitized HTTP headers from the intent.
- `PlayerFactory`: construct the Media3 player, data source, renderer policy, and track constraints.
- `PlaybackRecoveryController`: own first-frame detection, exactly-once DV base-layer retry, saved position, and terminal error state.
- `SourceLadder`: own the codec-independent axis of recovery — which address and which container hint to open next when a stream fails.
- `NightModeAudioProcessor`: compress decoded 16-bit PCM with linked-channel envelope/gain control before `AudioTrack`.
- `NightModeController`: own and toggle that processor without depending on TCL audio effects.
- `PlaybackActivity`: own lifecycle, `PlayerView`, D-pad dispatch, new intents, and user-visible errors.

Avoid repositories, dependency injection frameworks, services, fragments, navigation libraries, databases, and abstractions with only one implementation.

## Parse intents defensively

Require a valid URI from `intent.data` before starting playback. When the launcher starts the activity without one, show a concise ready/instruction screen instead of crashing or inventing a content browser.

Accept HTTP headers in these generic compatibility forms, in priority order:

1. A `Bundle` under `android.media.intent.extra.HTTP_HEADERS`
2. A `Bundle` under `android.intent.extra.HTTP_HEADERS`
3. A `String[]` under `headers`, either alternating `name, value` entries or `Name: value` entries

Keep only non-blank string names and values. Reject header names or values containing CR/LF. Do not log header values, bearer tokens, cookies, or full query strings. Preserve the caller's `User-Agent`, `Referer`, `Origin`, `Authorization`, and `Cookie`; supply a VibePlayer user agent only when the caller did not provide one.

Use an HTTP data source factory with finite connect/read timeouts, cross-protocol redirects enabled, and `setDefaultRequestProperties`. Feed it through `DefaultMediaSourceFactory` so the same headers reach the master playlist, HLS variants, media segments, and progressive requests.

`OkHttpDataSource` applies its `setUserAgent` value with `addHeader` *after* the request properties, so setting both emits two `User-Agent` lines per request — the caller's and VibePlayer's. Some CDNs read that as a bot. Set the factory user agent only when the caller supplied none, which is also what the header rule above already requires.

Accept optional external quality variants as paired `quality_levels` and `quality_urls` arrays. Preserve array positions: parse each label/value pair together and discard only that pair when malformed. A URL value may be a direct `Uri`, a direct URL string, or a JSON object string whose `url` property is the real media URL. Accept only `http`, `https`, `content`, and `file` schemes. Display the caller's outer quality label because it is the actual menu contract; do not replace it with an incidental label inside the JSON wrapper.

The optional Lampa bridge lives in `docs/VibePlayer-Lampa-Plugin.js` and is intended for GitHub Pages. Official Lampa's Android `@JavascriptInterface openPlayer(link, jsonStr)` receives the playback payload as a JSON string, and the generic external-player path exports only the current item's `quality` map as paired `quality_levels` / `quality_urls` arrays. The bridge must therefore accept both JSON-string and object payloads, preserve the original payload type when forwarding, and serialize additional data into that existing quality contract.

Use these reserved labels:

- `@VIBEVOICE@<encoded-name>|<encoded-quality>` for direct/per-quality voiceover URLs.
- `@VIBEEPISODE@<season>|<episode>|<percent>|<timeline-seconds>|<encoded-title>|<encoded-quality>` for playable entries already present in `data.playlist`.
- `@VIBEMETA@<encoded-title>|<encoded-source>` for top-overlay display metadata; pair it with the current URL, then filter it out of VibePlayer's quality menu.
- `@VIBERESERVE@<order>|<encoded-label>` for the backup addresses a source ships in `url_reserve` and `quality_reserve`. Order them with the reserve for the playing quality first, drop any that repeat the primary URL, and filter them out of VibePlayer's quality menu — they are transport for `SourceLadder`, not user-selectable qualities.

The bridge may also carry data over from the payload Lampa last handed its built-in player. Scope that strictly: find the entry that *owns* the launched URL — the capture itself, or one entry of its `playlist` — and take stream-specific data (`quality`, `timeline`, `url_reserve`, `quality_reserve`) only from that entry. Card-level context (`title`, `playlist`, `voiceovers`, `subtitles`) may come from the top level. A hit anywhere in the capture is not sufficient: matching on the playlist and then copying the top-level `quality` map hands VibePlayer a menu of *sibling episode* streams, so switching quality plays the previous episode.

VibePlayer parses these prefixes back into separate UI models. Show Season/Episode controls only when episode labels are actually present. Mark an episode watched only at more than 90%, show its number/title/progress, and resume from its serialized timeline time. The bridge code is source-agnostic, but playable voiceover and episode URLs still have to exist in the source payload; it cannot manufacture URLs hidden behind asynchronous provider callbacks. Never log stream URLs, headers, JSON callback bodies, or metadata payloads; structural counts are sufficient. Verify the installed bridge against the real source before claiming complete voiceover or episode coverage. Keep `docs/plugin.test.js` passing for JSON-string forwarding and all reserved label types.

Allow cleartext HTTP deliberately in the manifest because the real source ecosystem may use it. Do not disable TLS validation.

## Trust the response, not the address

Lampa launches VibePlayer with a wildcard video type, so the file extension is the only container hint available. It is a guess, and balancers break it routinely: a `.m3u8` alias may redirect onto a plain CDN file, at which point Media3 has already committed to `HlsMediaSource` and dies parsing MP4 bytes as a playlist — `ERROR_CODE_PARSING_MANIFEST_MALFORMED` with a `.mp4` address in the preceding `onLoadError`.

Record whether the MIME type was declared by the caller or guessed from the address, then let `SourceLadder` revise the guess when the response contradicts it. Two rules, each usable at most once, three media opens per playback in total:

1. **Container fallback.** On a parsing error, reopen the address the data actually came from — `LoadEventInfo.uri` in `onLoadError` already carries it past redirects — with a MIME type Media3 does not route to a manifest parser, so the extractors sniff the real container.
2. **Reserve fallback.** On an address that does not deliver at all, move to the next `@VIBERESERVE@` candidate.

Rebuild the ladder whenever the playing URL changes — new intent, quality switch, episode switch — so one bad stream never spends another stream's budget. Keep this axis separate from the Dolby Vision axis in `PlaybackRecoveryController`: one is about which bytes to fetch, the other about which decoder to use.

Never probe a source to answer these questions. No HEAD requests, no speculative GETs, no polling, no retry loops. Sources rate-limit and ban by IP, and a diagnostic request storm has already cost this project access once. Everything above is derived from information Media3 reports about loads that happened anyway.

## Define what "all content" means

Treat "open all content" as a measured coverage target for the user's catalog, not as a physically impossible promise to decode every media format ever made. Media3 extracts many containers, but actual sample decoding still depends on RTD2851. A single 4K Dolby Vision Profile 5 stream with no backward-compatible base layer and no alternate rendition cannot be converted to HDR10/SDR in real time by this Android 9 television.

Build and maintain a private playback corpus containing at least:

- 1080p H.264
- 4K H.264 when available
- 4K HEVC SDR
- 4K HEVC HDR10/HLG
- each Dolby Vision profile encountered in the catalog, including a known black-screen sample
- HLS master manifests with and without alternate SDR/HDR10 renditions
- progressive MP4 and Matroska/WebM samples used by the catalog
- AAC, AC-3, E-AC-3, DTS, and any other audio format actually encountered
- streams requiring `Referer`, `Cookie`, and authorization headers

Do not commit signed URLs, cookies, or tokens. Record only redacted sample identifiers and expected behavior. A claim that "all catalog content works" requires a clean pass over this corpus on the physical television.

If an encountered sample has no platform decoder, make the constraint explicit. Choose with the user between a server-provided alternate rendition, remote transcoding, or deliberately adding a decoder extension. Do not smuggle FFmpeg/NDK into the app while claiming the original no-native-code scope still holds.

## Handle AV1 without lying about 4K

Bundle only the official Media3 dav1d decoder extension for the television's sole ABI, `armeabi-v7a`. Enable it after platform renderers so hardware codecs continue to win. Use `video_decoder_gl_surface_view` for selected software AV1 because Media3 reports that GL output performs better than the native-window path. Keep normal `SurfaceView` output for MediaCodec H.264, HEVC, VP9, and Dolby Vision.

Prefer a separate 1080p H.264/HEVC rendition supplied by the caller over software AV1 when the user selects it. Do not apply an automatic 1080p cap or silently switch quality: the user explicitly chose manual quality control. 4K hardware H.264/HEVC/VP9 must remain selectable.

If the user explicitly selects a 4K AV1 variant, let it run through dav1d and report dropped frames in diagnostics. Do not auto-pause, auto-fallback, or display a `4K too slow` warning. This is a product decision, not evidence that playback is viable. Downscaling the output does not make AV1 decoding cheaper because the full 4K bitstream must be decoded first.

Log AV1 renderer loading, selected dimensions/codec string, output path, dropped frames, and whether a <=1080p rendition was selected or absent. A first rendered frame proves functional decoding only; it does not prove real-time playback.

## Preserve the 4K video path

Start with `DefaultRenderersFactory.setEnableDecoderFallback(true)` and hardware decoding. Do not prefer software decoders.

Media3's default track constraints may use the Android display size, which this firmware reports as 1920x1080 even though the panel/video path is 4K. Explicitly clear viewport and video-size constraints, then cap only at the verified decoder envelope of 4096x2176. Do not set `setMaxVideoSizeSd`, a 1920x1080 viewport, or another constraint that makes an adaptive manifest choose 1080p merely because the Android UI framebuffer is 1080p.

Verify 4K by logging the selected `Format` dimensions and `OMX.realtek.video.decoder`, not by inspecting the resolution of an Android screenshot. The vendor video plane may render 4K while UI and screenshots remain 1080p.

Do not force tunneling, codec asynchronous queueing, frame-rate matching, or arbitrary buffer sizes before measuring the baseline. The old Realtek firmware is the deciding evidence. Change one media setting at a time and retain it only when logs plus on-screen behavior improve.

## Recover from Dolby Vision without losing working DV

Do not disable every Dolby Vision decoder globally on the first attempt. That would prevent playback of profiles that the television handles correctly. Use this ordered recovery pipeline:

1. **Alternate rendition first:** When an adaptive HLS/DASH manifest contains 4K HDR10, HLG, HEVC SDR, or AVC alongside Dolby Vision, prefer the best non-DV rendition using video MIME preferences or a track override. Preserve 3840x2160 when the alternate rendition provides it.
2. **Native DV when required:** If no suitable alternate rendition exists, allow the advertised Realtek DV decoder and log the parsed DV profile/level plus exact decoder name.
3. **Black-screen watchdog:** Arm a first-frame watchdog only after the player reaches a state where video should be rendering. If audio/position advances but `onRenderedFirstFrame` does not arrive within a measured timeout, classify it as a suspected decoder black screen. Do not fire merely because the network is buffering.
4. **Single base-layer retry:** Rebuild the player once at the saved position with a TCL fallback `MediaCodecSelector` that returns no decoder for `MimeTypes.VIDEO_DOLBY_VISION` and delegates all other MIME types to `MediaCodecSelector.DEFAULT`. Media3 can then query HEVC or AVC as an alternative for backward-compatible DV profiles.
5. **Visible terminal error:** If Media3 exposes no compatible base layer, stop retrying and display the URI-redacted codec/profile reason. Never leave indefinite black video with working audio.

Prevent retry loops with an explicit playback-attempt state such as `NATIVE`, `BASE_LAYER`, and `TERMINAL`. Preserve the media position and HTTP headers across the retry. Release the old player, surface, and audio effect before building the replacement.

Do not describe the base-layer retry as universal DV-to-HDR10/SDR conversion. It works only when the source contains a backward-compatible layer. True live tone mapping or transcoding is a different pipeline and is not feasible for 4K on this target without external compute.

Log, with secrets redacted:

- detected input MIME type, codec string, DV profile/level, resolution, frame rate, and color metadata
- available video renditions and the reason the chosen rendition won
- selected decoder name and playback-attempt state
- decoder initialization and fallback events
- first rendered video frame and watchdog decision
- dropped frames
- player state transitions and `PlaybackException.errorCodeName`

Never pass a playback/load exception object to `Log.e`/`Log.w`, and never log its message or cause message: Java and Media3 network exceptions can embed the full signed media URL. Log only exception class names and stable error codes. Clear logcat after any accidental leakage.

## Implement night mode in decoded PCM

Keep Media3 audio output as 16-bit PCM for the analog path. Do not enable passthrough, audio offload, tunneling, or float output because they can bypass the custom processor. Inject `NightModeAudioProcessor` through a custom `DefaultAudioSink`.

Use one gain envelope linked across every decoded channel so stereo balance and surround downmix relationships do not pump independently. The current measured starting preset is threshold -20 dBFS, ratio 3:1, makeup +8 dB, envelope attack 5 ms/release 180 ms, gain attack 4 ms/release 250 ms, and output ceiling 27820 PCM (-1.42 dBFS). Smooth gain changes and reset envelope state on flush/reset.

The optional A/V correction is a separate `AudioOffsetProcessor` before night mode in the 16-bit PCM chain. It is inactive at 0 ms. Positive values prepend silence so audio is heard later; negative values drop decoded frames after stream start/seek so audio is heard earlier. Clamp to ±5000 ms, step by 50 ms, persist the choice, and rebuild the player at the same position when it changes. Positive delay was user-confirmed audible on the physical TCL; retain PCM frame-count tests for both signs and never infer more precision than was actually heard. Media3 has no built-in playback A/V-offset control.

The processor must copy PCM bit-exactly when disabled, raise a steady quiet signal when enabled, and never exceed the output ceiling; cover these properties with JVM tests. Do not retry Android `DynamicsProcessing` unless a firmware update changes the observed failure.

Provide an immediate A/B toggle using a remote key such as `KEYCODE_MENU`, with a brief on-screen `Night mode: On/Off` indication. Tune the preset only on the analog 3.5 mm output using quiet-dialogue and loud-action samples. Listen for pumping, breathing, noise lift, and clipping.

## Keep television controls deterministic

- D-pad center: play/pause
- D-pad left/right: seek backward/forward 10 seconds
- D-pad center while controls are hidden: play/pause and reveal the full overlay with Play/Pause focused
- Controls have exactly three vertical focus levels: centered rewind/play/forward row; progress bar; bottom quality/audio/optional voiceover/subtitles/settings row. Down advances one level and Up returns one level.
- Left/Right on the playback row seeks by the persisted user setting (5/10/15/30/60/90 seconds) while focus remains on Play/Pause. The side icons have no numeric labels and are vertically centered with Play/Pause. Left/Right on the focused progress bar scrubs in 1% duration steps. Left/Right on the bottom row moves only between visible actions.
- Use the gradient focus circle and white player icons exported from Sketch frame IDs `03A75D88-DAE9-46E1-9AC6-BB8DE02D1FD6` and `40473F7C-CDD2-4C7A-8CD4-AF0929AF68D1`; do not redraw or substitute them. The source gradient is yellow → coral → periwinkle → teal.
- Quality, audio, optional voiceover, and subtitles open their selection dialogs directly; Settings opens the full nested menu including night mode. Show the voiceover button only when at least one parsed voiceover variant exists for the current Intent/source. Use the white `voiceprint-line` icon exported from Sketch layer `D5DB6DB0-0144-4B7F-B1E6-AF5BC9BAB4F0`.
- Switching voiceover replaces the media URI at the current playback position, preserves play state and request headers, and selects the closest available resolution. Keep voiceovers scoped to the current source; embedded audio tracks remain a separate action.
- The quality action is a live compact text badge: ≥1441p `4K`, 1081–1440p `2K`, 721–1080p `1080`, then `720`, `576`, `480`, or the exact lower height. Restore focus to the bottom action that opened a dialog rather than jumping back to Play/Pause.
- Keep the upper controls area transparent and add only a bottom black fade behind the timeline and settings row. Inactive rewind/play/forward controls have a subtle soft shadow; the focused control has no shadow.
- Match the Sketch top overlay: technical status is a left-aligned multiline block; the content title is centered with source name beneath it; Season/Episode selectors and the current episode title stay at the right. Use `active-element.png` as the focused selector surface without a white stroke, and leave enough container padding for its focus pulse to scale without clipping.
- While buffering, show buffered percentage in the left technical block; while controls are visible, also show selected quality/codec/frame rate, HW/SW decoder path, and audio codec/channel count on separate lines. Never include a URL or header value.
- A ±seek action uses a short gradient pulse animation showing the configured number of seconds. With controls visible, center the pulse over the corresponding rewind/forward icon and temporarily hide the Play/Pause focus surface; with controls hidden, keep the pulse near the center.
- Increase only the progress-bar thumb to roughly 1.35× while the timeline is focused; do not scale the whole bar.
- On focus changes, give Play/Pause and visible bottom actions one subtle 1.0→1.10→1.0 scale pulse. Do not scale the timeline or repeatedly animate controls whose focus did not change.
- Menu key: open Settings
- Back: finish playback and return to the caller

Avoid double handling between `PlayerView` and the activity. Consume a key only once. Keep the screen on while playback is active and restore immersive mode after focus changes.

Handle `onNewIntent` so a second URI replaces the current item cleanly. Preserve position across a harmless activity recreation, but release the player and audio effect when the activity genuinely stops/finishes. Use Media3 audio attributes with automatic audio-focus handling.

## Build and deploy through ADB

Use the device serial explicitly in every command:

```bash
adb connect 192.168.68.111:5555
./gradlew --no-daemon lint testDebugUnitTest assembleDebug
adb -s 192.168.68.111:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.68.111:5555 shell am force-stop com.vibeplayer.tv
adb -s 192.168.68.111:5555 shell am start -W \
  -a android.intent.action.VIEW \
  -d 'MEDIA_URL' \
  -t 'video/*' \
  com.vibeplayer.tv
```

Do not put a secret production URL or token into a committed script. Use a user-provided test URI or a disposable local fixture. Clear logcat immediately before a focused reproduction and capture enough context after it:

```bash
adb -s 192.168.68.111:5555 logcat -c
adb -s 192.168.68.111:5555 logcat -v threadtime
```

Filter captured output afterward by the VibePlayer tag, Media3, `MediaCodec`, `AudioTrack`, `DynamicsProcessing`, and fatal exceptions. Do not filter so aggressively during capture that the causal error disappears.

## Validate behavior, not just compilation

Before declaring a change complete, verify the relevant subset of this matrix on the actual television:

- clean command-line build without Android Studio
- APK installs and launches on API 28 / 32-bit ARM
- HTTPS HLS
- HTTP HLS when a legitimate sample is available
- progressive MP4
- a stream that needs caller-supplied headers
- seek, pause/resume, Back, relaunch, and replacement through `onNewIntent`
- 4K H.264/HEVC remains selected despite the 1920x1080 Android UI framebuffer and uses `OMX.realtek.video.decoder`
- working native Dolby Vision still renders through the appropriate Realtek DV decoder when no safer alternate rendition exists
- an adaptive manifest with a non-DV 4K rendition selects that rendition and retains 3840x2160
- a known native-DV black-screen case triggers exactly one HEVC/AVC base-layer retry and renders a first frame when the profile is backward-compatible
- unsupported Dolby Vision reports a clear error instead of black video
- night-mode A/B works through the 3.5 mm output and survives audio-session replacement
- no header/token leakage in logs
- 1080p AV1 through `libdav1d`, including dropped-frame measurement
- explicit 4K AV1 selection is allowed without automatic downgrade/pause; diagnostics still state that measured real-time playback is not viable
- official Lampa external launch exposes its quality menu, 1080p selects H.264 hardware decode, and switching 1080p/1440p/720p opens the correctly paired URL
- no leaked player or audio processor after repeated open/close cycles

Add unit tests for intent/header parsing, playback-attempt transitions, watchdog gating, and pure decoder-policy logic. Use device tests and logs for codec and audio-effect claims; a JVM test cannot validate the Realtek firmware.

Report exact commands run, APK path and size, device observations, decoder name, and remaining untested cases. Never turn “build succeeded” into “playback works.”
