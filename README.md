# AJAMI-AI V1.0.0

Persian-first Android AI assistant with a strong offline-first goal.

## User packaging preference
The user accepts a very large APK. Therefore, prefer bundling compatible offline engines and models inside the install package when technically and legally feasible, rather than excluding them merely to keep the APK small. If a model is too large for reliable APK packaging, provide an optional offline model pack that can be downloaded once and then used without internet. Do not claim that every model is embedded until the final APK contents are inspected.

## Offline feature scope
## Offline image studio requirements (no API)
The image maker must work entirely on-device after installation/model acquisition. It must not call any cloud image API, proxy, remote inference endpoint, or require an API key. It should support:
- Text-to-image generation from Persian or English prompts using a locally running image-generation model.
- Poster, advertising creative, social-media post/story, banner, flyer, invitation, thumbnail, logo/concept and product-promo layouts.
- Local text placement, typography, shapes, frames, stickers, backgrounds, and composition tools so Persian text can be rendered legibly and accurately without relying on a generative model to spell it.
- Importing a photo from the phone and editing/compositing it locally; optional AI-assisted edits only when a compatible local model is installed.
- Export to common image formats supported by Android.
The UI must clearly distinguish true AI image generation from template-based local design tools. No cloud/API fallback is allowed. If the required local model is missing, explain that the model must be installed; never silently send prompts or images to a server.


- Persian chat and coding assistant using local language models.
- Text-to-image generation using a local image model.
- Offline video creation: practical photo-to-video editing (motion, pan/zoom, transitions, captions, audio) and experimental generative text/image-to-video only where a compatible model and hardware permit.
- Persian text-to-speech, speech-to-text, narration, synthetic voice options, and audio export.
- Offline music and melody generation, and a maddahi/recitation workflow using synthetic voices; no unauthorized real-person voice imitation.
- Offline translation and other local helper tools.
- Online weather and optional online services, with clear offline/online indicators.

## Device and performance constraints
Target phone: Samsung Galaxy A53, Exynos 1280, 8 GB RAM, arm64. APK size is not a user concern. RAM, compute throughput, thermal limits, Android package/install limits, model licensing, and available storage remain real constraints. Large video-generation models may be impractical on this device even if the user accepts a huge APK. Prefer smaller compatible models and test each engine on the actual phone.

## Current implementation status
- Basic Jetpack Compose chat interface and configurable OpenAI-compatible online endpoint: implemented.
- Qwen2.5-0.5B-Instruct Q4_K_M local chat model downloader and chat UI: implemented; on-device behavior still requires phone verification.
- Offline assistant task modes (general chat, coding, translation, summarization, writing, tutoring): implemented as task prompts over the same small local language model, not separate specialist models.
- Offline Stable Diffusion 1.5 image studio UI and local prompt translation hook: added in source; native Android ARM64 engine is being built in CI and the complete flow is not yet verified on the target phone.
- Stable Diffusion 1.5 Q4_0 model: optional one-time local download (about 3.05 GB); not bundled in the APK.
- Offline video generation, audio/music generation, and speech engines: not yet implemented or device-verified.

## Delivery policy
No placeholder buttons or fake capability claims. A feature is complete only after its real local engine is integrated, its model is available offline, and an airplane-mode test on the target Android phone succeeds. Include model files in the APK when practical; otherwise provide a clearly identified one-time model pack. All engines must work without paid APIs after installation/model acquisition. Check and preserve model and library licenses.

Medical information, if added, is educational and safety-oriented, not a diagnosis or prescription.

## Build
The Android project is built with Gradle and GitHub Actions. A successful CI build proves compilation only; it does not prove on-device functionality.
