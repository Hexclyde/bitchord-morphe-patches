#!/usr/bin/env python3
"""Convert Deezer's 2stems checkpoint to the native separation tensor contract.

I run this tool in an isolated Python 3.11 environment. Model outputs stay outside the MPP.
Install spleeter==2.4.2, tf2onnx==1.16.1, onnx==1.17.0,
onnxconverter-common==1.16.0 and onnxruntime==1.20.1 first.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import tarfile
import time
import urllib.request

URL = "https://github.com/deezer/spleeter/releases/download/v1.4.0/2stems.tar.gz"
ARCHIVE_SHA256 = "f3a90b39dd2874269e8b05a48a86745df897b848c61f3958efc80a39152bd692"
SHAPE = (1, 2, 2049, 960)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def download_checkpoint(directory):
    directory.mkdir(parents=True, exist_ok=True)
    archive = directory / "2stems.tar.gz"
    if not archive.exists() or sha256(archive) != ARCHIVE_SHA256:
        partial = archive.with_suffix(".part")
        try:
            with urllib.request.urlopen(URL, timeout=60) as source, partial.open(
                "wb"
            ) as target:
                total = 0
                for block in iter(lambda: source.read(65536), b""):
                    total += len(block)
                    if total > 256 * 1024 * 1024:
                        raise ValueError("Checkpoint archive exceeds the size limit")
                    target.write(block)
            if sha256(partial) != ARCHIVE_SHA256:
                raise ValueError("Checkpoint checksum does not match Deezer's release")
            partial.replace(archive)
        finally:
            partial.unlink(missing_ok=True)
    with tarfile.open(archive) as source:
        members = source.getmembers()
        if len(members) > 64:
            raise ValueError("Unexpected checkpoint archive")
        for member in members:
            target = (directory / member.name).resolve()
            if not target.is_relative_to(directory.resolve()) or not (
                member.isfile() or member.isdir()
            ):
                raise ValueError("Unsafe checkpoint archive entry")
        source.extractall(directory, members=members, filter="data")
    return directory


def convert(checkpoint, output):
    import numpy as np
    import onnx
    import onnxruntime as ort
    import tensorflow as tf
    import tf2onnx
    from onnxconverter_common import float16
    from spleeter.model import get_model_function

    tf.compat.v1.disable_eager_execution()
    graph = tf.Graph()
    with graph.as_default():
        mixture = tf.compat.v1.placeholder(tf.float32, SHAPE, name="mix_magnitude")
        low_band = tf.transpose(mixture[:, :, :1024, :], (0, 3, 2, 1))
        partitioned = tf.reshape(
            tf.pad(low_band, ((0, 0), (0, 64), (0, 0), (0, 0))), (2, 512, 1024, 2)
        )
        estimates = get_model_function("unet.unet")(
            partitioned, ["vocals", "accompaniment"], {}
        )
        vocals = tf.reshape(estimates["vocals_spectrogram"], (1, 1024, 1024, 2))[
            :, :960, :, :
        ]
        vocals = tf.transpose(vocals, (0, 3, 2, 1))
        target = tf.identity(
            tf.pad(vocals, ((0, 0), (0, 0), (0, 1025), (0, 0))), name="target_magnitude"
        )
        saver = tf.compat.v1.train.Saver()
        latest = tf.train.latest_checkpoint(str(checkpoint))
        if latest is None:
            raise ValueError("No TensorFlow checkpoint was found")
        config = tf.compat.v1.ConfigProto(
            intra_op_parallelism_threads=2,
            inter_op_parallelism_threads=1,
            device_count={"GPU": 0},
        )
        with tf.compat.v1.Session(graph=graph, config=config) as session:
            saver.restore(session, latest)
            frozen = tf.compat.v1.graph_util.convert_variables_to_constants(
                session, graph.as_graph_def(), [target.op.name]
            )
    model, _ = tf2onnx.convert.from_graph_def(
        frozen,
        input_names=["mix_magnitude:0"],
        output_names=["target_magnitude:0"],
        opset=17,
    )
    for value in model.graph.input:
        value.name = "mix_magnitude"
    for node in model.graph.node:
        for index, name in enumerate(node.input):
            if name == "mix_magnitude:0":
                node.input[index] = "mix_magnitude"
    # Keep the graph output edge in sync when assigning the public output name.
    for node in model.graph.node:
        for index, name in enumerate(node.output):
            if name == "target_magnitude:0":
                node.output[index] = "target_magnitude"
    model.graph.output[0].name = "target_magnitude"
    onnx.checker.check_model(model)
    fp32 = output / "spleeter-2stems-fp32.onnx"
    fp16 = output / "spleeter-2stems-fp16.onnx"
    onnx.save(model, fp32)
    half = float16.convert_float_to_float16(model, keep_io_types=True)
    onnx.checker.check_model(half)
    onnx.save(half, fp16)
    sample = np.random.default_rng(18).random(SHAPE, dtype=np.float32)
    result = {
        "developer": "Deezer Research",
        "checkpoint_url": URL,
        "checkpoint_archive_sha256": ARCHIVE_SHA256,
        "code_license": "MIT",
        "sample_rate": 44100,
        "fft": 4096,
        "hop": 1024,
        "input_shape": SHAPE,
        "processed_bins": 1024,
        "window_seconds": 960 * 1024 / 44100,
        "listening": "Not evaluated",
        "models": [],
    }
    for path in (fp32, fp16):
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False
        session = ort.InferenceSession(
            str(path), options, providers=["CPUExecutionProvider"]
        )
        durations = []
        for _ in range(4):
            start = time.monotonic()
            answer = session.run(None, {"mix_magnitude": sample})[0]
            durations.append(time.monotonic() - start)
        if (
            answer.shape != SHAPE
            or not np.isfinite(answer).all()
            or np.any(answer[:, :, 1024:, :])
        ):
            raise ValueError("Converted output failed the native tensor contract")
        result["models"].append(
            {
                "file": path.name,
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
                "median_seconds": float(np.median(durations[1:])),
                "finite": True,
            }
        )
    (output / "conversion.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path)
    parser.add_argument("--download-checkpoint", action="store_true")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if not args.checkpoint and not args.download_checkpoint:
        parser.error("Pass --checkpoint or explicitly select --download-checkpoint")
    args.out.mkdir(parents=True, exist_ok=True)
    if list(args.out.glob("*.onnx")):
        parser.error("Choose an output directory without existing converted models")
    os.environ.setdefault("CUDA_VISIBLE_DEVICES", "-1")
    checkpoint = (
        download_checkpoint(args.out / "checkpoint")
        if args.download_checkpoint
        else args.checkpoint
    )
    convert(checkpoint, args.out)


if __name__ == "__main__":
    main()
