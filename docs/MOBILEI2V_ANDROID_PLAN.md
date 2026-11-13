# MobileI2V Android integration

This work is isolated on `feature/mobilei2v-offline-engine`. The stable `main` branch and its existing APK must not be changed until the engine passes the device acceptance tests below.

## Goal

Add a genuine, fully local image-to-video pipeline to AJAMI-AI. The feature must synthesize new video frames from one still image using the MobileI2V diffusion model. FFmpeg may encode generated frames into MP4, but pan/zoom over a still image is not an acceptable substitute.

## Verified upstream architecture

Source of truth: [hustvl/MobileI2V](https://github.com/hustvl/MobileI2V), official upstream code repository. The inspected GitHub tree has no top-level LICENSE file; the Hugging Face model card lists MIT metadata for the checkpoint. Do not infer the code repository license from the checkpoint metadata.

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

## Inference sampler verification

The official FlowEuler sampler concatenates unconditional and conditional text embeddings for classifier-free guidance, then calls Mobiledit with both. It also re-injects the reference-image VAE latent into the first temporal latent slice after every Euler update. The model forward accepts guide_image and y, but the published block forward path does not apply cross-attention to y. Therefore the reference image is structurally preserved through the sampler, while prompt-based motion steering is not demonstrated by this code path; the CFG subtraction may be ineffective if the two branches differ only in y. A valid Android port must reproduce the first-frame latent reinjection, conditional mask, timestep/flow-score embeddings, and exact sampler schedule. A denoiser-only ONNX export is not a complete engine.


## Follow-up verification: upstream distillation branch

Inspected upstream branch `distillation` at commit `884f40a9c58338ad36299b9a4ef625392c2bd994` on 2026-11-12:

- Its README describes a training workflow, not a released distilled inference package. The script `train_scripts/train_i2v_distill.sh` launches training and expects teacher/DMD checkpoint paths; it does not fetch or provide a ready-to-run mobile checkpoint.
- The branch config `MobileI2V_300M_img512_3.yaml` still points `load_from`, `teacher_model_path`, and `dmd_model_path` to `./model/hybrid_371.pth`; it also expects local video-VAE and Qwen2-0.5B directories. This is a training setup, not a self-contained Android model pack.
- The distillation branch's `mobiledit.py` still builds the active transformer stack from seven `SanaBlock_cross` blocks, one `SanaBlock_vanila`, seven more `SanaBlock_cross`, and one more `SanaBlock_vanila`.
- In that same file, `SanaBlock_cross.forward` comments out `self.cross_attn` and its residual call. `SanaBlock_vanila` also has no active text cross-attention. Although the separate `SanaBlock` class contains an active cross-attention call, the active Mobiledit constructor does not instantiate that class.
- Therefore the distillation branch does not resolve the prompt-conditioning blocker, and no Android export or device integration should be based on assuming its text embeddings steer motion.

Decision: keep the feature isolated and do not add a fake/partial MobileI2V runtime to the stable APK. Next viable research step is to locate a checkpoint plus architecture whose actual forward path consumes text conditioning, then establish a reproducible PyTorch reference test before export.


## Alternative model and target-device compatibility review (2026-11-12)

Target device: Samsung Galaxy A53 / Exynos 1280 / 8 GB RAM (ARM64). This is not a Snapdragon/Qualcomm Hexagon device.

### Qualcomm Mobile Video Diffusion (MobileVD)

- The official project page reports 14 frames at 512x256 and 1.7 seconds on a Xiaomi 14 Pro, but its page still labels source code and checkpoint as "soon". The public GitHub repository is only the project-page website, not the inference implementation or model weights.
- This is not currently a reproducible model package that can be integrated into AJAMI-AI. The published speed result is not evidence of Exynos 1280 support.

### Qualcomm MobileWan / Neodragon and Nightmare Mobile

- MobileWan is an official Qualcomm mobile adaptation of Wan2.2-5B, but its released model card targets Snapdragon-powered mobile phones and BF16; the documented pipeline is text-to-video and the optimized video decoder is not released with the model card.
- Nightmare Mobile explicitly gates its video-generation features behind Qualcomm Snapdragon/Hexagon hardware. Its documentation says non-Snapdragon devices such as Exynos can use image generation but are not offered video model downloads.
- Therefore neither is a viable drop-in video backend for the A53.

### Decision

No verified, downloadable, truly generative offline image-to-video model/runtime has been found that is documented to run on the Exynos 1280 / 8 GB A53. Do not add a nonfunctional video model button or imply Qualcomm benchmark results apply to this phone. Continue searching for a CPU/Vulkan/ARM64 implementation with published model weights and a reproducible inference path; otherwise report the hardware/model limitation honestly and keep the existing slideshow/video-editing tools available.


## Rapid follow-up review (2026-11-12, 11:53 Asia/Tehran)

### MobileWan paper and implementation claims

- The MobileWan paper (arXiv:2607.06173, July 2026) describes a 5B-parameter Wan2.2-derived model and reports 5-second 480x832, 16 FPS generation in 20 seconds on a commercial mobile device. This is a research result, not evidence of compatibility with Exynos 1280 / Mali.
- The public project material currently surfaced is Qualcomm AI Research-oriented and does not establish a complete, downloadable ARM64 CPU/Vulkan/NNAPI pipeline for this target. Do not port it based on the paper abstract or headline benchmark alone.
- The paper's reported deployment must not be conflated with a generic Android APK or an Exynos build. Require exact device/chip, runtime, weights, license, memory, and reproducible inference evidence before accepting it.

### Third-party MobileI2VConsole audit

- The third-party repository advertises fully local Android inference through ONNX Runtime, but it is not the official HUST release and its implementation is not accepted as a reference implementation.
- Earlier source inspection found material mismatches against the official MobileI2V path: raw UTF-8 prompt bytes rather than the official Qwen tokenizer; inconsistent text-embedding widths (official config caption width 896 versus the export script's Qwen output width 1024); altered temporal latent construction and reference-frame conditioning; and an abbreviated two-step sampler with constants not shown to match official FlowEuler.
- Therefore its README's 720p / 17-frame claims do not constitute parity evidence. Do not copy its inference pipeline into AJAMI-AI without a fixed-input PyTorch-vs-ONNX parity test and an actual target-device run.

### Immediate engineering decision

No newly reviewed source provides a verified, downloadable, text-conditioned, fully offline I2V pipeline proven on Exynos 1280 / Mali. Keep the experimental work isolated. Next work must be a reproducible reference inference and export/parity harness, not a UI button or placeholder engine. A successful CI build remains only a compilation check.

## Further Android runtime candidate audit (2026-11-12)

### Saient / Quartz + Wan2.1

Source inspected: https://github.com/SaientAI/saient/blob/main/docs/VIDEO_PIPELINE.md

- This is stronger evidence of a genuine offline Android video pipeline than a UI-only demo: the project documents a live airplane-mode run on Samsung SM-S921B (Galaxy S24), a native stable-diffusion.cpp fork, a foreground service, progress/cancel, and a Vulkan diffusion backend.
- However, the documented engine is Wan2.1 T2V 1.3B, not image-to-video. It cannot be treated as the requested I2V engine without a separate image-conditioning implementation and verified model path.
- The pinned model pack is 4.73 GB: Q4_K diffusion transformer 816 MB, Q4_K_M UMT5-XXL encoder 3.66 GB, FP16 VAE 254 MB. The project explicitly unloads the chat model before video generation, uses CPU for text encoder and VAE, Vulkan for diffusion, disk-backed parameters and VAE tiling.
- The live device proof is for Galaxy S24, not Galaxy A53. Its successful Vulkan execution does not prove Mali/Exynos compatibility. No A53 benchmark, peak RAM, thermal, or stability result was found.
- Engineering value: inspect its pinned stable-diffusion.cpp Vulkan backend and memory management as a possible reference for a future Mali experiment, but do not copy its T2V pipeline into the I2V screen or claim A53 support. First verify the exact Vulkan backend supports the A53 Mali driver and measure memory with a tiny smoke test.

### Mobile Video Diffusion (MobileVD)

Source inspected: https://openaccess.thecvf.com/content/ICCV2025/papers/Yahia_Mobile_Video_Diffusion_ICCV_2025_paper.pdf

- The paper is genuinely image-to-video: it starts from Stable Video Diffusion, prunes/optimizes its spatio-temporal UNet, and uses adversarial finetuning for one denoising step.
- The headline 1.7 s result is specifically on Xiaomi 14 Pro / Snapdragon 8 Gen 3 / Hexagon. The paper's method section confirms phone latency measurements use that platform.
- The paper's table also reports OOM for several baselines at 1024x576 and does not establish any A53/Mali run.
- A public reproducible Android implementation, downloadable model checkpoint, exact model-pack size, and Mali-compatible runtime were not verified in this review. Therefore this is a research lead, not an integrable backend yet.

### LocalMotion

Source inspected: https://github.com/Tiggy-Chan/LocalMotion

- The repository describes itself as a prototype for Snapdragon 8 Gen 3.
- It explicitly says QNN SDK assets and model weights are not shipped and its native inference core is gated by Qualcomm SDK-bound code not committed.
- The present fallback is image import/crop, lightweight stylization, progress and MP4 encoding; the first realistic NPU milestone is SD1.5 still-image generation, not I2V.
- Rejected as an A53 drop-in; no verified I2V runtime or Mali backend.

### Current decision and next engineering step

No candidate in this review meets all four required gates for AJAMI-AI: genuine I2V conditioning, downloadable licensed weights, reproducible ARM64/Mali runtime, and verified execution on Exynos 1280. Saient is a useful Vulkan/mobile-memory reference but is T2V on a different phone; MobileVD is true I2V research but its reported device is Snapdragon and a reproducible A53 package was not found. Keep both as research leads only. Next implement a small Vulkan capability/driver smoke test on the actual A53 before investing in a multi-gigabyte video model pack. Do not change the stable main branch.

## Weekly model audit (2026-11-13)

### New research lead: CineMobile

Sources: [arXiv paper 2607.03803](https://arxiv.org/abs/2607.03803), [Infinix NOTE 60 Ultra specifications](https://mx.infinixmobility.com/specs/note-60-ultra), [MediaTek Dimensity 8400 specifications](https://www.mediatek.com/products/smartphones/mediatek-dimensity-8400).

- This is a genuine I2V research model, not a slideshow: the paper describes a 4-step video diffusion generator conditioned on an input frame and evaluates generated 49-frame 480p sequences. Its supported effects are specialized cinematic modes (bullet time, dolly zoom, slow motion), not an unrestricted general-purpose video editor.
- The reported device is Infinix NOTE 60 Ultra with Dimensity 8400 Ultimate, Mali-G720 MC7 and MediaTek NPU 880. The paper claims a model footprint below 1 GB, peak memory about 1.8 GB, and roughly 20 seconds for a 49-frame 480p clip. These are paper results on that device, not A53 measurements.
- The student starts from Wan2.1-v1.1-Fun-I2V (1.5B parameters); the teacher is Wan2.1-I2V-14B. The final model uses 4-step distillation and hybrid quantization (4-bit FFN weights, FP8 other weights, FP16 activations). Effect-specific LoRAs are used for the three motion types.
- No official source repository, downloadable CineMobile checkpoint/model pack, explicit model license, Android runtime package, ONNX/TFLite/Vulkan export, or reproducible install instructions were found in this review. The paper mentions text-embedding projections, but without released inference code the exact user-prompt contract and whether arbitrary free-form prompts are consumed cannot be independently verified.
- Hardware transfer is not established: the A53 has Exynos 1280 / Mali-G68 MC4 and does not have the Dimensity 8400's Mali-G720 MC7 or NPU 880. Both being Mali-family GPUs is not evidence of equivalent performance or runtime support.

Decision: keep CineMobile on the research watchlist only. It is the strongest new non-Snapdragon Android/Mali I2V research lead found this week, but it is not currently integrable because the code, weights, license, runtime and target-device proof are missing.

### MobileI2V release and architecture re-audit

Sources: [official GitHub repository](https://github.com/hustvl/MobileI2V), [official Hugging Face checkpoint](https://huggingface.co/hustvl/MobileI2V), [official Turbo-VAED weights](https://huggingface.co/hustvl/Turbo-VAED), [LTX-Video VAE files](https://huggingface.co/Lightricks/LTX-Video/tree/main/vae), [Qwen2-0.5B weights](https://huggingface.co/Qwen/Qwen2-0.5B).

- Correction to earlier status: an official `hybrid_371.pth` checkpoint is downloadable from HUST's Hugging Face repository. Its listed size is 1.07 GB, model-card license metadata is MIT, and its published SHA-256 is `bc6a545302b342b87d83a4d78e9b74d47ca59fbf908fd8e13d9ecedbe1a37f2d`. The checkpoint was uploaded on 2026-05-20.
- This is not evidence that the distilled two-step student is released. The official main inference script defaults FlowEuler to 30 steps. The distillation-branch config still points its teacher and DMD paths to `hybrid_371.pth`; no separate final two-step student checkpoint was found. The paper's 2.24-second iPhone result is a research result for the distilled variant, not a verified download/run of the available checkpoint.
- The official paper reports 17 frames at 1280x720; on iPhone 16 Pro Max it reports 20.13 s for the 30-step model and 2.24 s for the two-step distilled model. Its mobile deployment path is PyTorch -> TorchScript -> Core ML (.mlpackage), benchmarked through Core ML on iPhone 16 Pro. No official Android, ONNX Runtime, TFLite, NNAPI or Vulkan package was found.
- Rechecked `mobiledit.py`: the active stack is seven `SanaBlock_cross`, one `SanaBlock_vanila`, seven more `SanaBlock_cross`, and one more `SanaBlock_vanila`. In both active block forward methods, the cross-attention residual call is commented out. The separate `SanaBlock` class has active cross-attention but is not instantiated by the Mobiledit constructor. Thus Qwen embeddings are computed and passed, but the active forward path does not consume them; arbitrary text prompts are not demonstrated as a real motion control. The actual controls documented by the paper/code are the reference image and scalar optical-flow/motion score.
- Known downloadable component sizes: MobileI2V checkpoint 1.07 GB; LTX video VAE 1.68 GB; Turbo-VAED-LTX decoder 169 MB; Qwen2-0.5B 988 MB. A straightforward full stack is approximately 3.9 GB before temporary files. Since the active denoiser blocks ignore text embeddings, omitting Qwen may be possible after a controlled parity test, but it must not be removed based on assumption alone.
- License caution: the MobileI2V and Turbo-VAED Hugging Face model cards show MIT metadata, and Qwen2-0.5B is Apache-2.0. The LTX-Video VAE repository is marked `other` and contains version-specific license terms; do not treat the complete model stack as uniformly MIT or assume unrestricted redistribution. The upstream MobileI2V GitHub repository itself has no top-level LICENSE file in the inspected tree, so the model-card license should not be silently presented as a confirmed code-repository license.
- The upstream requirements pin a CUDA PyTorch stack (including torch 2.4.0+cu121 and NVIDIA CUDA packages). This is not an Android runtime. No official ONNX/TFLite export or Android operator coverage has been demonstrated.

Decision: MobileI2V is a genuine image-conditioned frame generator with an available base checkpoint, but it is not a prompt-controlled, ready-to-run Android engine. Keep it isolated from main. It may be evaluated as a motion-score-only research prototype only after export parity, runtime smoke tests and A53 memory/thermal measurements.

### Other mobile video candidates rechecked

- **Taming Diffusion Transformer / Snap mobile DiT:** official Snap paper/site reports about 15 FPS on iPhone 16 Pro Max for a 0.9B, four-step text-to-video model at 288x512. It is T2V, not the requested I2V path, and no verified Android/Mali package was found. Reject for this feature.
- **MobileWan / Neodragon:** Qualcomm-oriented Snapdragon/Hexagon mobile work; the public MobileWan pipeline is text-to-video, not I2V, and does not establish Exynos/Mali support. Reject as an A53 drop-in.
- **MobileVD:** genuine I2V research, but the published 1.7-second result is on Xiaomi 14 Pro / Snapdragon 8 Gen 3; a complete public Android implementation and downloadable checkpoint were not verified. Research lead only.
- **MOVD / On-device Sora:** mobile text-to-video work with iPhone/Core ML deployment, not a verified Android I2V package. Reject for the current target.
- **Saient / Quartz:** useful evidence for offline Android Vulkan T2V on Galaxy S24, but its documented model is Wan2.1 T2V and its phone is not the A53. It is not an I2V backend.

### Engineering decision for AJAMI-AI

No option this week passes all release gates for the Galaxy A53: true I2V, downloadable weights with usable license, reproducible Android ARM64/Mali runtime, and actual Exynos 1280 execution. The next low-risk step remains a real Vulkan compute/driver smoke test on the A53 (not just checking PackageManager feature flags), recording GPU/driver/API details and memory behavior. Only after that should a small MobileI2V export-parity experiment be attempted. Do not download a multi-gigabyte pack, change the stable main branch, or claim a new APK/model works until device inference is verified.


## A53 Vulkan probe result and backend risk (2026-11-13)

The user reports that the in-app Vulkan probe completed successfully on the Galaxy A53. Source inspection of `app/src/main/cpp/vulkan_probe.cpp` shows what this proves: Vulkan instance/device creation, discovery of a compute-capable queue, logical-device creation, 4 KiB buffer allocation/binding, command-buffer recording of `vkCmdFillBuffer`, queue submission, and fence completion. It does **not** dispatch a compute shader, test shader compilation, exercise ggml's Vulkan kernels, or run any diffusion model. Do not describe it as proof that stable-diffusion.cpp or MobileI2V inference works on the phone.

A recent public Android-native inference issue reports a Galaxy A53 / Mali-G68 Vulkan crash in ggml Vulkan buffer setup (`vkGetBufferDeviceAddress` null-pointer SIGSEGV), while that project's Android build is documented as CPU-only. This is a warning signal, not a conclusive test of AJAMI-AI's exact pinned stable-diffusion.cpp revision; nevertheless it makes blindly flipping `SD_VULKAN=ON` unsafe. See https://github.com/leehack/stable-diffusion-native/issues/2 (release triage dated 2026-10-05).

Decision: retain the passing low-level probe as a device capability check, but do not enable Vulkan in the production image backend or assume it accelerates video generation. Next GPU experiment must build an isolated backend branch and run an actual shader/ggml operator smoke test with crash-safe fallback to CPU. It must record GPU/driver/API, shader initialization, operator output correctness, peak memory and thermal behavior. Only after that may a model-level test be attempted. The stable APK remains unchanged.
