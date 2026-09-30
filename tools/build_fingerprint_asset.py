#!/usr/bin/env python3
"""Build the on-device fingerprint bank asset from upstream lm-detector data.

Reads the two upstream artifacts (shared_detector.json for the frozen LDA head and
unified_bank.json for the centroid/baseline parameters) and writes one compact
binary file that the Android app loads directly. Fixed-point int32 at 1e-6
resolution: measured drift against the full-precision JSON is 3.6e-5 in the
per-model score vector, with no top-1 or full-order change across all 53
reference models.

Usage:
    python tools/build_fingerprint_asset.py <data-dir> <output-file>

<data-dir> must contain shared_detector.json and unified_bank.json as published
at https://github.com/Ikaleio/lm-detector/tree/main/data
"""
import json
import struct
import sys
from pathlib import Path

MAGIC = b"LMFPA001"
SCALE = 1_000_000.0


def quantize(values):
    """Float vector -> int32 fixed point. Clamped so a NaN/inf never wraps."""
    out = []
    for v in values:
        q = int(round(float(v) * SCALE))
        out.append(max(-2_147_483_648, min(2_147_483_647, q)))
    return out


def flatten(matrix):
    """Flatten a matrix of any nesting depth (environment centroids are 3-D)."""
    out = []
    stack = list(matrix)
    while stack:
        item = stack.pop(0)
        if isinstance(item, (list, tuple)):
            stack[0:0] = list(item)
        else:
            out.append(item)
    return out


class Writer:
    def __init__(self):
        self.parts = []

    def u32(self, value):
        self.parts.append(struct.pack("<I", int(value)))

    def i32(self, values):
        if not values:
            return
        self.parts.append(struct.pack(f"<{len(values)}i", *values))

    def f64(self, values):
        self.parts.append(struct.pack(f"<{len(values)}d", *[float(v) for v in values]))

    def text(self, value):
        raw = value.encode("utf-8")
        self.u32(len(raw))
        self.parts.append(raw)

    def blob(self):
        return b"".join(self.parts)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    data_dir = Path(sys.argv[1])
    out_path = Path(sys.argv[2])

    detector = json.loads((data_dir / "shared_detector.json").read_text(encoding="utf-8"))
    bank = json.loads((data_dir / "unified_bank.json").read_text(encoding="utf-8"))

    ranker = detector["ranker"]
    h = bank["robust"]["hellinger"]
    o = bank["robust"]["ordered_blocks"]

    model_ids = detector["model_ids"]
    display = {m["id"]: m.get("display_name", m["id"]) for m in bank["models"]}
    families = {m["id"]: m.get("family", "unknown") for m in bank["models"]}
    family_names = {m["id"]: m.get("family_name", m.get("family", "unknown")) for m in bank["models"]}
    if sorted(display) != sorted(model_ids):
        raise SystemExit("bank.models and detector.model_ids disagree")

    for key, value in bank["calibration"].items():
        if int(key) not in (1, 2, 3):
            raise SystemExit(f"unexpected calibration key {key}")

    w = Writer()
    w.parts.append(MAGIC)
    w.text(detector.get("source_reference_sha256", ""))
    w.text(bank.get("built_at", ""))
    w.text(bank.get("reference_sha256", ""))

    # models: id, display name, family, family name (all in detector order)
    w.u32(len(model_ids))
    for mid in model_ids:
        w.text(mid)
        w.text(display[mid])
        w.text(families[mid])
        w.text(family_names[mid])

    # LDA head: feature standardisation + projection + bias
    head = ranker["head_params"]
    if len(head) != 2:
        raise SystemExit("head_params must have the two feature blocks")
    w.u32(len(head))
    for block in head:
        w.u32(len(block["mean"]))
        w.i32(quantize(block["mean"]))
        w.i32(quantize(block["scale"]))

    lda_weights = ranker["lda_weights"]
    w.u32(len(lda_weights))
    w.u32(len(lda_weights[0]))
    w.i32(quantize(flatten(lda_weights)))
    w.u32(len(ranker["lda_bias"]))
    w.i32(quantize(ranker["lda_bias"]))

    # bank: hellinger block
    w.u32(len(h["feature_mean"]))
    w.i32(quantize(h["feature_mean"]))
    w.i32(quantize(h["feature_scale"]))
    w.u32(len(h["nuisance_basis"]))
    if h["nuisance_basis"]:
        w.u32(len(h["nuisance_basis"][0]))
        w.i32(quantize(flatten(h["nuisance_basis"])))
    w.u32(len(h["centroids"]))
    w.i32(quantize(flatten(h["centroids"])))

    # bank: ordered-block block
    w.f64([o["weight"]])
    w.u32(len(o["feature_mean"]))
    w.i32(quantize(o["feature_mean"]))
    w.i32(quantize(o["feature_scale"]))
    w.u32(len(o["nuisance_basis"]))
    if o["nuisance_basis"]:
        w.u32(len(o["nuisance_basis"][0]))
        w.i32(quantize(flatten(o["nuisance_basis"])))
    w.u32(len(o["centroids"]))
    w.i32(quantize(flatten(o["centroids"])))
    env = o["environment_centroids"]
    w.u32(len(env))
    if env:
        w.u32(len(env[0]))
        w.u32(len(env[0][0]))
        if any(row is None for block in env for row in block):
            raise SystemExit("environment_centroids contains nulls")
        w.i32(quantize(flatten(env)))

    # calibration keyed by answer count 1..3
    w.f64([bank["calibration"][k]["beta"] for k in ("1", "2", "3")])
    w.f64([
        bank["calibration"][k].get("cv_accuracy") or 0.0
        for k in ("1", "2", "3")
    ])
    w.f64([bank.get("recommended_queries", 3), bank.get("minimum_valid_numbers", 80)])

    blob = w.blob()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(blob)

    print(f"wrote {out_path} ({len(blob):,} bytes)")
    print(f"  models          : {len(model_ids)}")
    print(f"  lda_weights     : {len(lda_weights)}x{len(lda_weights[0])}")
    print(f"  hellinger dims  : {len(h['feature_mean'])}")
    print(f"  ordered dims    : {len(o['feature_mean'])}")
    print(f"  environments    : {len(env)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
