# AJAMI-AI V1.0.0

Persian-first Android AI assistant for online and offline workflows.

## Confirmed offline feature scope
- Offline Persian chat and coding assistant using a locally stored language model.
- Offline image generation using a separately downloaded image model, subject to device memory and thermal limits.
- Offline video creation in two modes:
  1. Text-to-video with a local generative video model, experimental and hardware-dependent.
  2. Photo-to-video using local motion effects, pan/zoom, transitions, captions and optional audio. This is a practical offline editor workflow, not the same as generative text-to-video.
- Model downloads and storage are separate from the APK so users can choose which large models to install.

## Offline audio feature scope
- Persian text-to-speech with selectable male/female voice options where supported by the installed local voice model.
- Offline narration and spoken responses.
- Offline maddahi/recitation creation workflow with selectable male/female synthetic voices, subject to a locally installed voice model and lawful use of any reference voice; no imitation of a real person without permission.
- Experimental local music and melody generation using a separate downloaded audio model; performance and quality depend on device compatibility.
- Audio export and playback (WAV first; MP3 only when a verified encoder is integrated).
- Speech synthesis and music generation are separate engines and must be tested independently. No cloud API or paid subscription is required for local inference after model download.

## Device compatibility
Target device currently discussed: Samsung Galaxy A53 (Exynos 1280, 8 GB RAM). Full generative text-to-video and large image models may be too slow or may fail due to RAM, GPU/NPU and thermal constraints. The app must label experimental functions honestly and never present a placeholder as a working AI engine.

## Implementation status
- Basic Compose chat interface and configurable OpenAI-compatible online endpoint: implemented.
- Offline inference engine: not yet integrated; LiteRT dependency was temporarily removed because it caused Kotlin compiler metadata incompatibility in CI. Reintroduce only after selecting a compatible runtime/compiler combination and verifying an actual on-device model run.
- Offline coding model: planned, not implemented.
- Offline image generation: planned, not implemented.
- Offline video modes (text-to-video and photo-to-video): planned, not implemented.
- Persistent conversation history, media generation and model manager: not yet implemented.

## Important implementation policy
A feature is marked ready only after its engine is integrated and tested on a real Android device. Online services require internet and provider credentials. Large offline models are downloaded separately, not bundled into the initial APK. Medical features, if added, provide educational information and safety guidance, not diagnosis or prescriptions.

## Build
The Android project is built with Gradle and GitHub Actions. An APK is a deliverable only after a successful workflow build and installation test.
