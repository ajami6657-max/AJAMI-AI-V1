# Galaxy A53 Vulkan capability gate

Status: test specification only. No device result is implied by this document.

Target: Samsung Galaxy A53 (SM-A536E), Exynos 1280, Mali-G68, Android 16, ARM64.

## Why this gate exists

A Vulkan-capable phone does not automatically mean a diffusion model can run on its GPU. Before selecting a generative image-to-video backend, record the actual driver capabilities and run a minimal compute workload on the target device. Do not infer success from APK compilation, Vulkan availability in Android settings, or a different Snapdragon phone.

## Required on-device checks

1. Enumerate Vulkan instance version, physical-device name, vendor/device IDs, driver version, and API version.
2. Record supported device extensions and queue families; confirm at least one compute-capable queue.
3. Record physical-device memory heaps and types. Treat heap size as a driver-reported limit, not as guaranteed free RAM.
4. Create a logical device and compute pipeline; dispatch a small deterministic storage-buffer workload.
5. Read back the output and compare every element with the CPU reference. Record dispatch duration and any validation/driver errors.
6. Repeat the workload 20 times, then repeat after closing and relaunching the app.
7. Record Android process peak PSS/RSS, available system memory, battery temperature before/after, and any thermal throttling observed.
8. Run the same deterministic workload on CPU as a fallback control.
9. Repeat in airplane mode to ensure the diagnostic has no network dependency.

## Result record

For each run, record:
- Device model, Android build, GPU/driver/API versions.
- Compute queue present: yes/no.
- Buffer allocation and compute dispatch: pass/fail, with exact error.
- CPU-vs-GPU output comparison: pass/fail and mismatch count.
- 20-run stability and cold-relaunch result.
- Peak process memory (PSS/RSS), available RAM, elapsed time, temperature delta.
- CPU fallback result and airplane-mode result.

## Acceptance gate

The Vulkan diagnostic passes only if device enumeration, compute queue creation, buffer allocation, dispatch, and exact readback comparison all pass on the A53, including the repeated and cold-relaunch runs. A pass establishes only that a small Vulkan compute workload works; it does not prove ONNX Runtime, a particular diffusion graph, or a full video model fits or performs acceptably.

Do not download multi-gigabyte video weights until this gate passes and the chosen model's operator coverage, precision, tensor shapes, and peak-memory budget are separately reviewed. Keep CPU fallback available. Do not modify the stable `main` branch or label generative image-to-video as complete based on this diagnostic alone.
