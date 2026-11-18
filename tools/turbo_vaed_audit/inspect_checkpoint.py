#!/usr/bin/env python3
"""Safely inspect the public Turbo-VAED-LTX checkpoint without running inference."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from collections.abc import Mapping

import torch
from huggingface_hub import hf_hub_download

REPO_ID = "hustvl/Turbo-VAED"
FILENAME = "Turbo-VAED-LTX.pth"
EXPECTED_SHA256 = "9d570c27e257bf38dd464839335a228893e0a337d518772abb39044c4092a64"


def find_tensor_maps(obj, prefix="root"):
    found = []
    if isinstance(obj, Mapping):
        tensors = {str(k): v for k, v in obj.items() if isinstance(v, torch.Tensor)}
        if tensors:
            found.append((prefix, tensors))
        for key, value in obj.items():
            if isinstance(value, Mapping):
                found.extend(find_tensor_maps(value, f"{prefix}.{key}"))
    return found


def main():
    path = Path(hf_hub_download(repo_id=REPO_ID, filename=FILENAME))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    print(f"checkpoint={path.name}")
    print(f"bytes={path.stat().st_size}")
    print(f"sha256={digest}")
    if digest != EXPECTED_SHA256:
        raise SystemExit("Checkpoint SHA-256 differs from the reviewed public file; stopping.")

    # weights_only=True prevents arbitrary Python object deserialization.
    checkpoint = torch.load(path, map_location="cpu", weights_only=True)
    maps = find_tensor_maps(checkpoint)
    if not maps:
        raise SystemExit("No tensor state dictionary found in checkpoint.")

    candidates = []
    for prefix, tensors in maps:
        entries = []
        for key, tensor in sorted(tensors.items()):
            entries.append({
                "key": key,
                "shape": list(tensor.shape),
                "dtype": str(tensor.dtype),
                "numel": tensor.numel(),
            })
        candidates.append({"path": prefix, "tensor_count": len(entries), "tensors": entries})

    report = {
        "repository": REPO_ID,
        "filename": FILENAME,
        "sha256": digest,
        "bytes": path.stat().st_size,
        "torch_version": torch.__version__,
        "state_dict_candidates": candidates,
        "note": "Metadata/key inventory only. This is NOT a strict decoder load or inference test.",
    }
    out = Path("turbo-vaed-checkpoint-manifest.json")
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"manifest={out}")
    for item in candidates:
        print(f"candidate={item['path']} tensors={item['tensor_count']}")


if __name__ == "__main__":
    main()
