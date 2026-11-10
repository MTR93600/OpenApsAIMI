#!/usr/bin/env python3
"""
Generate minimal valid ONNX models for AIMI Advisor OREF ONNX path.

These are **placeholders** (neutral probabilities / zero regression): they make
`OrefOnnxScorer.assetModelsPresent()` pass and inference run without crash.
Replace with real LightGBM exports when available (same input layout as OrefModelFeatures).

Requires: pip install onnx
"""
from __future__ import annotations

import argparse
import os
import sys

import numpy as np

try:
    import onnx
    from onnx import TensorProto, helper
except ImportError:
    print("Install onnx: pip install onnx", file=sys.stderr)
    sys.exit(1)

# Must match OrefModelFeatures.COUNT in Kotlin
FEATURES = 35


def build_binary_stub(name: str) -> onnx.ModelProto:
    """Output [N, 2] float32 with [0.5, 0.5] per row (Tile from [1,2])."""
    input_x = helper.make_tensor_value_info(
        "input", TensorProto.FLOAT, ["batch", FEATURES]
    )
    out = helper.make_tensor_value_info("output", TensorProto.FLOAT, ["batch", 2])

    const_init = helper.make_tensor(
        "const_proba",
        TensorProto.FLOAT,
        [1, 2],
        np.array([[0.5, 0.5]], dtype=np.float32).tobytes(),
        raw=True,
    )

    # repeats = [ N, 1 ] from Shape(X)
    nodes = [
        helper.make_node("Shape", inputs=["input"], outputs=["shape_x"]),
        helper.make_node(
            "Slice",
            inputs=[
                "shape_x",
                "slice_starts",
                "slice_ends",
                "slice_axes",
                "slice_steps",
            ],
            outputs=["n_dim"],
        ),
        helper.make_node("Concat", inputs=["n_dim", "one_i64"], outputs=["repeats"], axis=0),
        helper.make_node("Tile", inputs=["const_proba", "repeats"], outputs=["output"]),
    ]

    slice_starts = helper.make_tensor(
        "slice_starts", TensorProto.INT64, [1], np.array([0], dtype=np.int64).tobytes(), raw=True
    )
    slice_ends = helper.make_tensor(
        "slice_ends", TensorProto.INT64, [1], np.array([1], dtype=np.int64).tobytes(), raw=True
    )
    slice_axes = helper.make_tensor(
        "slice_axes", TensorProto.INT64, [1], np.array([0], dtype=np.int64).tobytes(), raw=True
    )
    slice_steps = helper.make_tensor(
        "slice_steps", TensorProto.INT64, [1], np.array([1], dtype=np.int64).tobytes(), raw=True
    )
    one_i64 = helper.make_tensor(
        "one_i64", TensorProto.INT64, [1], np.array([1], dtype=np.int64).tobytes(), raw=True
    )

    graph = helper.make_graph(
        nodes,
        name,
        [input_x],
        [out],
        initializer=[const_init, slice_starts, slice_ends, slice_axes, slice_steps, one_i64],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    # IR 8: compatible with onnxruntime-android 1.20 and older ORT builds
    model.ir_version = 8
    onnx.checker.check_model(model)
    return model


def build_regression_stub(name: str) -> onnx.ModelProto:
    """Output [N, 1] float32 zeros (predicted BG change stub)."""
    input_x = helper.make_tensor_value_info(
        "input", TensorProto.FLOAT, ["batch", FEATURES]
    )
    out = helper.make_tensor_value_info("output", TensorProto.FLOAT, ["batch", 1])

    const_zero = helper.make_tensor(
        "const_zero",
        TensorProto.FLOAT,
        [1, 1],
        np.array([[0.0]], dtype=np.float32).tobytes(),
        raw=True,
    )

    nodes = [
        helper.make_node("Shape", inputs=["input"], outputs=["shape_x"]),
        helper.make_node(
            "Slice",
            inputs=[
                "shape_x",
                "slice_starts",
                "slice_ends",
                "slice_axes",
                "slice_steps",
            ],
            outputs=["n_dim"],
        ),
        helper.make_node("Concat", inputs=["n_dim", "one_i64"], outputs=["repeats"], axis=0),
        helper.make_node("Tile", inputs=["const_zero", "repeats"], outputs=["output"]),
    ]

    slice_starts = helper.make_tensor(
        "slice_starts", TensorProto.INT64, [1], np.array([0], dtype=np.int64).tobytes(), raw=True
    )
    slice_ends = helper.make_tensor(
        "slice_ends", TensorProto.INT64, [1], np.array([1], dtype=np.int64).tobytes(), raw=True
    )
    slice_axes = helper.make_tensor(
        "slice_axes", TensorProto.INT64, [1], np.array([0], dtype=np.int64).tobytes(), raw=True
    )
    slice_steps = helper.make_tensor(
        "slice_steps", TensorProto.INT64, [1], np.array([1], dtype=np.int64).tobytes(), raw=True
    )
    one_i64 = helper.make_tensor(
        "one_i64", TensorProto.INT64, [1], np.array([1], dtype=np.int64).tobytes(), raw=True
    )

    graph = helper.make_graph(
        nodes,
        name,
        [input_x],
        [out],
        initializer=[const_zero, slice_starts, slice_ends, slice_axes, slice_steps, one_i64],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)])
    model.ir_version = 8
    onnx.checker.check_model(model)
    return model


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument(
        "-o",
        "--out-dir",
        default=os.path.join(
            os.path.dirname(__file__),
            "..",
            "plugins",
            "aps",
            "src",
            "main",
            "assets",
            "oref",
        ),
        help="Output directory (default: plugins/aps/.../assets/oref)",
    )
    args = p.parse_args()
    out_dir = os.path.normpath(args.out_dir)
    os.makedirs(out_dir, exist_ok=True)

    paths = {
        "hypo_lgbm.onnx": build_binary_stub("hypo_stub"),
        "hyper_lgbm.onnx": build_binary_stub("hyper_stub"),
        "bg_change_lgbm.onnx": build_regression_stub("bg_change_stub"),
    }
    for fname, model in paths.items():
        path = os.path.join(out_dir, fname)
        onnx.save(model, path)
        size = os.path.getsize(path)
        print(f"Wrote {path} ({size} bytes)")

    print("\nStub models: neutral class proba 0.5/0.5 and zero bg_change.")
    print("Replace with trained exports when ready.")


if __name__ == "__main__":
    main()
