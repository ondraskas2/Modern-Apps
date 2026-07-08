#!/usr/bin/env python3
"""Prepare the on-device ML model assets for the Photos app.

Produces one binary the app loads from `photos/src/main/assets/`:

  EdgeFace face embedder (INT8) -> `edgeface.onnx`
     EdgeFace `edgeface-s-gamma-05` loaded from the transformers-format Hub
     mirror `anjith2006/edgeface` (bundles the model code + safetensors, so no
     GitHub checkout), exported to ONNX and INT8 weight-quantized
     (`onnxruntime.quantization.quantize_dynamic`). Input NCHW [1,3,112,112]
     normalised (px-127.5)/127.5; output a 512-d embedding.

     NOTE: EdgeFace ships under a NON-COMMERCIAL research licence. This project
     uses it deliberately (see the plan / FaceRecognizer docs). Swap it out if
     you need a permissive licence.

Semantic photo search (image/text CLIP embedding) is **no longer prepared here**:
it moved out of Photos into the OpenAssistant app, which downloads SigLIP2
(`onnx-community/siglip2-base-patch16-224-ONNX`) directly from HuggingFace at
runtime, on demand. Photos ships no CLIP assets.

The step runs a smoke inference and asserts the expected output before the file
is accepted, then prints the final on-disk size.

Usage:
    pip install -r requirements.txt
    python prepare_models.py

Needs `huggingface_hub`, `onnx`, `onnxruntime`, `numpy`, and `torch`. If your
environment lacks network access or torch, run this in an environment that has
them; the produced binary is all the app needs.
"""
from __future__ import annotations

import argparse
import os
import sys
import tempfile

# Repo layout: scripts/photos/prepare_models.py -> repo root is two levels up.
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS_DIR = os.path.join(REPO_ROOT, "photos", "src", "main", "assets")

# EdgeFace variant: "s" (small, gamma 0.5) is the size/accuracy sweet spot and
# outputs a 512-d embedding. We load the transformers-format mirror
# (anjith2006/edgeface), which bundles the model code + safetensors weights on
# the HuggingFace Hub, so no GitHub checkout is needed. Its preprocessor uses
# rescale 1/255 + normalize mean/std 0.5 == (px-127.5)/127.5, matching
# FaceRecognizer.embed(). See https://github.com/otroshi/edgeface for the source.
EDGEFACE_HF_REPO = "anjith2006/edgeface"
EDGEFACE_SUBFOLDER = "edgeface-s-gamma-05"
EDGEFACE_OUT = os.path.join(ASSETS_DIR, "edgeface.onnx")
FACE_INPUT_SIZE = 112
EMBED_DIM = 512


def _mb(path: str) -> str:
    return f"{os.path.getsize(path) / (1024 * 1024):.1f} MB"


def _load_edgeface_torch():
    """Load the pretrained EdgeFace model (eval mode) from the HuggingFace mirror.

    `anjith2006/edgeface` ships the model definition (`modeling_edgeface.py`) and
    safetensors weights on the Hub, so `trust_remote_code=True` builds the model
    without a GitHub checkout. Returns an nn.Module whose forward takes an NCHW
    `[N,3,112,112]` float tensor and yields a 512-d embedding.
    """
    import torch
    from torch import nn
    from transformers import AutoModel

    print(f"[EdgeFace] loading '{EDGEFACE_SUBFOLDER}' from {EDGEFACE_HF_REPO} ...")
    base = AutoModel.from_pretrained(
        EDGEFACE_HF_REPO,
        subfolder=EDGEFACE_SUBFOLDER,
        trust_remote_code=True,
    )
    base.eval()

    class EmbeddingWrapper(nn.Module):
        """Normalise the model output to a single 512-d embedding tensor."""

        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, pixel_values):
            out = self.model(pixel_values)
            if isinstance(out, torch.Tensor):
                return out
            for attr in ("image_embeds", "embeddings", "pooler_output", "last_hidden_state"):
                val = getattr(out, attr, None)
                if val is not None:
                    return val
            if isinstance(out, (tuple, list)):
                return out[0]
            raise TypeError(f"Unrecognised EdgeFace output type: {type(out)}")

    wrapper = EmbeddingWrapper(base)
    wrapper.eval()

    # Probe the output shape so we fail fast on a wrong variant/wrapper.
    with torch.no_grad():
        probe = wrapper(torch.zeros(1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE))
    assert probe.shape[-1] == EMBED_DIM, (
        f"[EdgeFace] expected {EMBED_DIM}-d embedding, got {tuple(probe.shape)}"
    )
    print(f"[EdgeFace] loaded, embedding shape {tuple(probe.shape)}")
    return wrapper


def prepare_edgeface() -> None:
    """Export EdgeFace to ONNX, INT8-quantize it, and validate."""
    import numpy as np
    import onnxruntime as ort
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic

    model = _load_edgeface_torch()

    os.makedirs(ASSETS_DIR, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        fp32_path = os.path.join(tmp, "edgeface_fp32.onnx")
        dummy = torch.zeros(1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE, dtype=torch.float32)
        print(f"[EdgeFace] exporting fp32 ONNX ({fp32_path}) ...")
        # Static [1,3,112,112] via the legacy (TorchScript) exporter: the app only
        # ever embeds one crop at a time, and a static graph avoids the sequence /
        # shape-inference ops the dynamo exporter emits that break quantize_dynamic.
        torch.onnx.export(
            model,
            dummy,
            fp32_path,
            input_names=["input"],
            output_names=["embedding"],
            opset_version=17,
            do_constant_folding=True,
            dynamo=False,
        )

        print(f"[EdgeFace] INT8 dynamic quantization -> {EDGEFACE_OUT} ...")
        quantize_dynamic(
            fp32_path,
            EDGEFACE_OUT,
            weight_type=QuantType.QInt8,
        )
    print(f"[EdgeFace] wrote {EDGEFACE_OUT} ({_mb(EDGEFACE_OUT)})")

    sess = ort.InferenceSession(EDGEFACE_OUT, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    dummy = np.zeros((1, 3, FACE_INPUT_SIZE, FACE_INPUT_SIZE), dtype=np.float32)
    out = sess.run(None, {in_name: dummy})[0]
    assert out.shape[-1] == EMBED_DIM, f"[EdgeFace] expected {EMBED_DIM}-d output, got {out.shape}"
    print(f"[EdgeFace] smoke inference OK, output shape {out.shape}")


def main() -> int:
    argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    ).parse_args()

    try:
        prepare_edgeface()
    except Exception as e:  # noqa: BLE001
        print(f"[EdgeFace] FAILED: {e}", file=sys.stderr)
        print("\n=== Summary ===")
        state = _mb(EDGEFACE_OUT) if os.path.exists(EDGEFACE_OUT) else "missing"
        print(f"  {'edgeface.onnx':26s} {state}")
        return 1

    print("\n=== Summary ===")
    state = _mb(EDGEFACE_OUT) if os.path.exists(EDGEFACE_OUT) else "missing"
    print(f"  {'edgeface.onnx':26s} {state}")
    print("\nEdgeFace model prepared.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
