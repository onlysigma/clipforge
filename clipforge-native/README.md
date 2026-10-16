# ClipForge Native

Pure native Android video assembler — no Expo, no FFmpeg, no WASM.

## Why native

The old app used FFmpegKit (software encoding, retired binaries, native
crashes) and the web app used FFmpeg.wasm (3–5x slower than native). This app
uses **AndroidX Media3 Transformer** — Google's official video editing stack —
which encodes on the phone's **hardware H.264 encoder** (MediaCodec). That is
the same reason CapCut feels instant.

## Features (v1)

- Add multiple video clips (system picker)
- Reorder with up/down buttons, remove clips
- Format: 16:9 / 9:16 / 1:1, Resolution: 1080p / 720p
- **Noise cancellation** toggle — one-pole high-pass (~120 Hz) + RMS noise
  gate on the decoded PCM before AAC encoding (v1; full RNNoise NDK
  integration is future work)
- **Premium filter** toggle — saturation (1.28x) + contrast (1.06x) grade
  applied on the GPU via a custom `RgbMatrix` (Media3 effect)
- Hardware H.264 + AAC output to MP4, live progress, cancel, real error
  messages, share the result
- CapCut-style black & white Jetpack Compose UI

## Build

Push to `main` (or run manually): `.github/workflows/build-native-apk.yml`
builds `app-debug.apk` with Gradle 8.7 + JDK 17 and uploads it as an artifact.

Local: `gradle :app:assembleDebug` (needs Android SDK 34).

## Limitations (v1, honest)

- Noise reduction is a high-pass + noise gate, **not** full RNNoise AI
  denoising. It kills rumble/hiss between speech but won't match RNNoise on
  heavy background noise.
- Premium grade is saturation + contrast on GPU. No unsharp/curves (those are
  spatial/1D-LUT ops; a LUT effect can be added later).
- No fade in/out yet (the old app had 1s fades) — easy to add with Media3
  effects later.
- Clips are letterboxed (padded) to the output size, never cropped.
- minSdk 26.
