# MobileI2V Android integration

This work is isolated on `feature/mobilei2v-offline-engine`. The stable `main` branch and its existing APK must not be changed until the engine passes the device acceptance tests below.

## Goal

Add a genuine, fully local image-to-video pipeline to AJAMI-AI. The feature must synthesize new video frames from one still image using the MobileI2V diffusion model. FFmpeg may encode generated frames into MP4, but pan/zoom over a still image is not an acceptable substitute.

## Verified upstream architecture

Source of truth: [hustvl/MobileI2V](https://github.com/hustvl/MobileI2V), Apache-2.0 repository license.

The official `MobileI2V_300M_img512.yaml` config specifies:

- 17 output video frames.
- A 128-channel video latent and a temporal VAE downsample factor of 8. The official inference script initializes the temporal latent depth as `num_frames // 8 + 1`, i.e. 3 latent time steps for 17 output frames.
- VAE spatial downsample factor 32.
- Qwen2-0.5B text encoder, max sequence length 300, caption width 896.
- FP16 model weights and the FlowEuler sampling path.
- The reference image is VAE-encoded and injected into the first temporal latent position before diffusion sampling; decoded video frames are then written to MP4.

Do not copy the tensor contracts in third-party ports without checking them against the official config and inference implementation. In particular, a 17-step temporal latent or a 4-channel 64x64 latent is not the contract described by the official MobileI2V config.

## Distillation status

The upstream `distillation` branch contains training code and a distillation training script. Its README documents training a distilled checkpoint; it does not document a ready-to-use Android ONNX package or a downloadable, verified distilled checkpoint. Treat distillation as a possible optimization path, not as a drop-in binary.

## Android implementation stages

1. **Export and parity:** Export the official denoiser, VAE encoder, text encoder/tokenizer, and compatible video decoder to ONNX/ORT. Record exact input/output names, dtypes, and tensor dimensions in a manifest. Compare ONNX outputs with official PyTorch outputs on fixed test inputs before Android integration.
2. **Runtime:** Add ONNX Runtime Android only after the exported graphs and signatures are stable. Use ARM64, attempt NNAPI only when the graph is supported, and keep a CPU fallback. Avoid loading every large model simultaneously; measure peak resident memory and unload components when safe.
3. **Model pack:** Download models once with resumable downloads and SHA-256 verification. Generation must work in airplane mode after the pack is installed. Never silently call a cloud API.
4. **Compose UI:** Add a separate Image-to-Video screen with image picker, optional motion prompt, progress/cancel, model status, preview, and MP4 save/share. Keep the existing slideshow and editing tools unchanged.
5. **Device acceptance:** On the Samsung Galaxy A53 / Exynos 1280 / 8 GB RAM, test cold model load, a complete 17-frame generation, memory peak, thermal behavior, cancellation, output decode/playback, and airplane-mode operation. If the phone cannot run the official graph reliably, report that and do not label the feature complete.

## Release gate

A CI APK build proves compilation only. The feature is complete only after model parity tests pass and the installed APK generates and plays a real 17-frame clip on the target phone without internet after model acquisition. Longer clips, prompt adherence, and performance claims require separate measurements.

## Critical prompt-conditioning finding (source inspection)

The official Mobiledit implementation builds its transformer stack from seven SanaBlock_cross blocks, one SanaBlock_vanila block, seven more SanaBlock_cross blocks, and one more SanaBlock_vanila block. In the currently published mobiledit.py, both block implementations comment out their cross-attention module and the residual call that would apply it. The text encoder output y is still prepared and passed to each block, but these block forwards do not consume it. Therefore, do not promise motion-prompt control from this checkpoint/code path. This is a release blocker for a user-facing prompt-controlled image-to-video feature until a matching checkpoint/model variant with verified text conditioning is identified or the intended conditioning path is established from the official inference pipeline.

This finding does not prove the reference-image conditioning is absent: the official inference pipeline also constructs the video latent and conditioning mask outside the denoiser. That path must be traced separately before implementing an exporter. The export must preserve the exact official inference semantics rather than inventing a new prompt or image-conditioning interface.
