# AJAMI-AI v1.1: Offline audio generation and true image-to-video

Target device: Samsung Galaxy A53 / SM-A536E, Exynos 1280, Mali-G68, ARM64, Android 16, 8 GB RAM.

This document is an engineering decision record, not a claim that either engine is already integrated. A model is accepted only after its complete inference contract, weights, license, Android runtime, and target-device run are verified.

## Track A — generated music and sound effects

### Selected first candidate: Stable Audio 3 Small

Official upstream: https://github.com/Stability-AI/stable-audio-3

Why this candidate:
- The upstream project publishes small-music and small-sfx models, each in the small 433M-parameter family.
- The official optimized TFLite/LiteRT implementation supports ARM CPU inference through XNNPACK and does not require PyTorch at runtime. The official Android LiteRT SDK has a `CompiledModel` API and supports API 23+ in its current 2.3.2 release, so the app's API 26 minimum is compatible at the SDK level.
- The small DiT rung is documented at about 0.5 GB (w8a8) or 1.8 GB (fp32), with about 0.8 GB DiT peak RAM; the T5Gemma text encoder is about 0.6 GB, plus the SAME-S codec. These are upstream host measurements, not A53 measurements.
- Small music and SFX checkpoints support text-to-audio, and the documented small-family duration ceiling is 120 seconds. Do not promise this maximum on the A53 until measured.
- The upstream repository code is MIT licensed, but model weights are governed by the Stability AI Community License; the T5Gemma encoder has a separate Google license/notice. Include all three license/attribution notices with the optional model pack and verify the Community License terms for the intended distribution.

### Android port stages
0. Use the standalone Google LiteRT Android runtime, not the Python `ai_edge_litert` package. Official Android guidance now lists LiteRT 2.3.2 as the current release with `CompiledModel` and minimum API 23; this app's minSdk 26 is compatible on paper. Pin `com.google.ai.edge.litert:litert:2.3.2` and test the exact AAR/ABI on the A53 before selecting a GPU delegate. The model graphs are documented as requiring LiteRT >= 2.2.0. This is API compatibility, not proof of A53 inference.
1. Pin an upstream commit and download the exact small-music/small-sfx TFLite rung files, tokenizer/text encoder files, and SAME-S encoder/decoder files. Record URLs, SHA-256, sizes, and individual licenses.
2. Inspect the exported LiteRT signatures and tensor contracts. Do not assume the desktop Python CLI is directly usable in Android.
3. Implement a native Android LiteRT runner (Kotlin/JNI as appropriate) that reproduces prompt tokenization, text conditioning, sampler schedule, noise generation, and SAME-S decoding.
4. Add resumable model downloads with SHA-256 verification. Once installed, airplane-mode generation must work.
5. Begin with 5-second, batch-1, small-music and small-sfx tests. Record peak RSS, wall time, temperature, battery drain, and output validity on the A53. Increase duration only after stability tests.
6. Add generation progress/cancel, preview, WAV export, and model deletion controls only after inference works end-to-end.

### Voice and full songs
Stable Audio 3 Small is a text-to-audio music/SFX engine, not a guaranteed natural-language singing/voice-cloning model. Keep Persian TTS as a separate capability. Do not claim generated Persian vocals or 30 distinct voices without a separate verified model and rights review.

### Rejected for this phone as first integration
ACE-Step 1.5's published GPU compatibility guidance assumes VRAM tiers and recommends GPU/CPU offload. Its 6–8 GB tier uses a 0.6B language model, and its larger configurations need substantially more memory. It is not an Android ARM64/Mali LiteRT package. Do not port it ahead of the Stable Audio 3 small ARM path.

## Track B — true image-to-video

A valid result must synthesize new intermediate frames from an input image. Slideshow, zoompan, camera movement over a still, and frame interpolation alone do not satisfy this requirement.

### Candidate review
- MobileI2V (HUST, 0.27B) is the closest model-sized research candidate. Its official code includes image/reference latent conditioning and a 17-frame pipeline, but the repository does not provide a ready Android ONNX package. Its active transformer blocks currently do not apply the text cross-attention path, so prompt-controlled motion is not established. Do not claim prompt adherence. A no-prompt image-conditioned experiment is possible only after the official reference pipeline is reproduced and parity-tested.
- LLMEdge provides Android Wan 2.1 video APIs with sequential loading, but its documented public example uses a text prompt and Wan 2.1 T2V 1.3B. This is not proof of a supported input-image conditioning path. Its docs recommend 12 GB+ for standard video and say 8 GB+ can use sequential loading; this is not a verified Exynos 1280 benchmark.
- Nightmare Mobile explicitly requires Qualcomm Snapdragon/Hexagon for its video models. Exynos is not supported for video downloads, so it is not an A53 solution.
- Qualcomm MobileVD / MobileWan performance claims are not transferable to Mali-G68; no verified Exynos 1280 model package/runtime was identified in this review.

### Implementation stages
1. Reproduce official MobileI2V reference inference on a pinned host environment with a fixed test image and seed. Capture all tensor shapes, dtypes, scheduler values, image latent injection, VAE decode, and generated frames.
2. Establish PyTorch reference outputs before any export. If the text conditioning path remains inactive upstream, explicitly test image-only conditioning; do not fabricate prompt embeddings.
3. Export denoiser and VAE components to ONNX or another Android-supported runtime. Validate every output against the PyTorch reference with documented tolerances.
4. Add ARM64 Android runtime and test on the A53 at reduced resolution/frame count first. Measure peak RSS, latency, thermal throttling, cancellation, and output playback.
5. Only then expose the feature in the app. Require a complete 17-frame image-conditioned generation in airplane mode as the release gate.

### No false completion
A successful CI build proves compilation only. An image picker, progress bar, MP4 encoder, or successful slideshow is not an I2V engine. If no candidate completes the device acceptance test, keep the I2V feature marked experimental/unavailable rather than shipping a fake engine.

## Shared release gates
- Stable main remains unchanged until a tested pull request is reviewed and merged.
- Model downloads are optional, resumable, checksum-verified, and separate from the APK.
- Generation never silently calls a cloud API.
- Unload the chat/image models before heavy audio/video inference; restore them after completion if memory permits.
- CI must build the ARM64 APK and run available host-side tensor/parity tests.
- Final acceptance requires installed-app tests on the actual SM-A536E, not just CI.