#!/usr/bin/env python3
"""Build the on-device fingerprint detection package from upstream lm-detector data.

Reads the upstream artifacts and writes one binary file the Android app loads
directly. The package carries everything upstream's `shared-detector-v1` scoring
needs, so the app can reproduce its ranking and its calibrated probability without
any upstream code at runtime:

  * the LDA head (head_params + lda_weights + lda_bias)
  * the centroid/"baseline" feature bank (hellinger + ordered blocks)
  * the kNN reference matrix per model (ranker.references)
  * the confidence calibration scalar (`calibration.tau`)

Format `LMFPA003`. Upstream used to ship a second scorer — a logistic "verifier" whose
only job was to say whether its own best candidate agreed with the ranking — and it is
gone from `shared-detector-v1` as of upstream `d53d3f5b` (58 models, probability from
`softmax(tau · ranking)`). `LMFPA002` is the same package with that verifier block still
in the middle; the app reads both (see `FingerprintBank`), so a device that has not
re-downloaded the package keeps working.

Encoding: every dense block is int32 fixed point at 1e-6, which was measured to move a
per-model score by at most 3.6e-5 and to change no ordering. The reference tensor (one
matrix per model, values within +-0.6) is stored as int8/int16 with one float32 scale per
row: that is where the package would otherwise be most of its size.

Usage:
    python tools/build_fingerprint_asset.py <data-dir> <output-file>
    python tools/build_fingerprint_asset.py <data-dir> <out> --models 6 \
        --emit-detector-json <path> --emit-bank-json <path>
    python tools/build_fingerprint_asset.py <data-dir> <out> \
        --pick gpt-5.4,claude-sonnet-4.6,gemini-3.7-flash

<data-dir> must contain shared_detector.json and unified_bank.json as published at
https://github.com/Ikaleio/lm-detector/tree/main/data

--models N (or --pick a,b,c) restricts the package to a subset of the models. That is
how the unit-test fixture is built: a small package that still exercises every code
path (the family roll-up needs at least two families to mean anything). The emitted JSON
files carry the *unquantized* subset, so the golden vectors generated from them measure
the real cost of quantization.
"""
import json
import struct
import sys
from pathlib import Path

MAGIC = b"LMFPA003"
SCALE = 1_000_000.0
INT8_MAX = 127
INT16_MAX = 32767

# 16 bits is the default because it makes the package indistinguishable from the
# full-precision artifact: on all golden cases the ranking scores move by at most 4.5e-5
# and no candidate changes place. 8 bits halves the package but reorders near-tied
# candidates (38 of 75,790 pairs, 0.05%), which is visible in the panel's top-8 list.
DEFAULT_REFERENCE_BITS = 16


def quantize(values):
    """Float vector -> int32 fixed point. Clamped so a NaN/inf never wraps."""
    out = []
    for v in values:
        q = int(round(float(v) * SCALE))
        out.append(max(-2_147_483_648, min(2_147_483_647, q)))
    return out


def quantize_rows(rows, bits=8):
    """Rows of floats -> (packed integers, per-row float32 scales).

    The scale is per row so a row that spans only +-0.05 keeps its resolution; the
    two reference tensors hold unit-normalised, sqrt-weighted features, so a row's
    own maximum is the only sensible range. 8 bits keeps the package at 2.1 MB,
    16 bits at 3.7 MB and removes the long-tail reordering int8 causes among
    near-tied candidates.
    """
    limit = INT8_MAX if bits == 8 else INT16_MAX
    payload = bytearray()
    scales = []
    for row in rows:
        peak = max((abs(float(x)) for x in row), default=0.0)
        scale = peak / limit if peak > 0 else 1.0
        scales.append(scale)
        for x in row:
            q = int(round(float(x) / scale)) if scale else 0
            q = max(-limit, min(limit, q))
            payload += struct.pack("<b" if bits == 8 else "<h", q)
    return bytes(payload), scales


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

    def f32(self, values):
        if not values:
            return
        self.parts.append(struct.pack(f"<{len(values)}f", *[float(v) for v in values]))

    def f64(self, values):
        self.parts.append(struct.pack(f"<{len(values)}d", *[float(v) for v in values]))

    def bytes(self, payload):
        self.parts.append(bytes(payload))

    def text(self, value):
        raw = value.encode("utf-8")
        self.u32(len(raw))
        self.parts.append(raw)

    def matrix(self, rows):
        self.u32(len(rows))
        self.u32(len(rows[0]))
        self.i32(quantize(flatten(rows)))

    def feature_bank(self, block):
        self.u32(len(block["feature_mean"]))
        self.i32(quantize(block["feature_mean"]))
        self.i32(quantize(block["feature_scale"]))
        basis = block["nuisance_basis"]
        self.u32(len(basis))
        if basis:
            self.u32(len(basis[0]))
            self.i32(quantize(flatten(basis)))
        self.u32(len(block["centroids"]))
        self.i32(quantize(flatten(block["centroids"])))

    def gaussian(self, block):
        precision = block["precision"]
        self.u32(len(precision))
        self.u32(len(precision[0]))
        self.i32(quantize(flatten(precision)))
        self.f64([block["constant"]])

    def quantized_references(self, references, bits):
        """Per-model blocks of transformed reference vectors, quantized per row."""
        self.u32(bits)
        self.u32(len(references[0][0]))
        self.u32(len(references))
        for block in references:
            payload, scales = quantize_rows(block, bits)
            self.u32(len(block))
            self.f32(scales)
            self.bytes(payload)

    def blob(self):
        return b"".join(self.parts)


def subset_models(detector, bank, ids):
    """Restrict every per-model array to `ids`, in the order given.

    Used to build the unit-test fixture: a package small enough to commit that still
    runs the whole algorithm (the panel's family roll-up needs at least two families to
    mean anything). A `verifier` block, if the artifact still carries one, is subset the
    same way so the emitted detector JSON stays self-consistent.
    """
    order = detector["model_ids"]
    positions = []
    for mid in ids:
        if mid not in order:
            raise SystemExit(f"{mid} is not a detector model")
        positions.append(order.index(mid))

    def pick(rows):
        return [rows[index] for index in positions]

    ranker = detector["ranker"]
    bank_block = ranker["bank"]
    detector = dict(detector)
    detector["model_ids"] = list(ids)
    detector["response_counts"] = pick(detector["response_counts"])
    detector["ranker"] = dict(ranker)
    detector["ranker"]["lda_weights"] = pick(ranker["lda_weights"])
    detector["ranker"]["lda_bias"] = pick(ranker["lda_bias"])
    detector["ranker"]["references"] = pick(ranker["references"])
    detector["ranker"]["bank"] = {
        **bank_block,
        "model_order": list(ids),
        "hellinger": {**bank_block["hellinger"],
                      "centroids": pick(bank_block["hellinger"]["centroids"])},
        "ordered_blocks": {**bank_block["ordered_blocks"],
                           "centroids": pick(bank_block["ordered_blocks"]["centroids"]),
                           "environment_centroids": [
                               pick(block) for block in
                               bank_block["ordered_blocks"]["environment_centroids"]]},
    }
    if isinstance(detector.get("verifier"), dict):
        verifier = dict(detector["verifier"])
        verifier["references"] = pick(verifier["references"])
        verifier["candidates"] = pick(verifier["candidates"])
        detector["verifier"] = verifier
    if isinstance(detector.get("calibration"), dict):
        calibration = dict(detector["calibration"])
        binding = dict(calibration.get("binding") or {})
        if binding.get("model_ids"):
            binding["model_ids"] = list(ids)
        calibration["binding"] = binding
        detector["calibration"] = calibration

    by_id = {m["id"]: m for m in bank["models"]}
    bank = dict(bank)
    bank["models"] = [by_id[mid] for mid in ids]
    robust = dict(bank["robust"])
    robust["model_order"] = list(ids)
    robust["hellinger"] = {**robust["hellinger"],
                           "centroids": pick(robust["hellinger"]["centroids"])}
    robust["ordered_blocks"] = {**robust["ordered_blocks"],
                                "centroids": pick(robust["ordered_blocks"]["centroids"]),
                                "environment_centroids": [
                                    pick(block) for block in
                                    robust["ordered_blocks"]["environment_centroids"]]}
    bank["robust"] = robust
    return detector, bank


def main():
    argv = sys.argv[1:]
    options = {"--models": None, "--pick": None, "--ref-bits": None,
               "--emit-detector-json": None, "--emit-bank-json": None}
    positional = []
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg in options:
            index += 1
            if index >= len(argv):
                print(f"{arg} needs a value\n{__doc__}")
                return 2
            options[arg] = argv[index]
        elif arg in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            positional.append(arg)
        index += 1
    if len(positional) != 2:
        print(__doc__)
        return 2
    data_dir = Path(positional[0])
    out_path = Path(positional[1])

    detector = json.loads((data_dir / "shared_detector.json").read_text(encoding="utf-8"))
    bank = json.loads((data_dir / "unified_bank.json").read_text(encoding="utf-8"))

    ref_bits = int(options["--ref-bits"] or DEFAULT_REFERENCE_BITS)
    if ref_bits not in (8, 16):
        raise SystemExit("--ref-bits must be 8 or 16")
    if options["--models"] and options["--pick"]:
        raise SystemExit("--models and --pick are mutually exclusive")
    if options["--models"]:
        detector, bank = subset_models(detector, bank, detector["model_ids"][:int(options["--models"])])
    elif options["--pick"]:
        picked = [mid.strip() for mid in options["--pick"].split(",") if mid.strip()]
        detector, bank = subset_models(detector, bank, picked)

    ranker = detector["ranker"]
    h = bank["robust"]["hellinger"]
    o = bank["robust"]["ordered_blocks"]

    model_ids = detector["model_ids"]
    display = {m["id"]: m.get("display_name", m["id"]) for m in bank["models"]}
    families = {m["id"]: m.get("family", "unknown") for m in bank["models"]}
    family_names = {m["id"]: m.get("family_name", m.get("family", "unknown")) for m in bank["models"]}
    if sorted(display) != sorted(model_ids):
        raise SystemExit("bank.models and detector.model_ids disagree")
    if ranker["bank"]["model_order"] != model_ids:
        raise SystemExit("ranker.bank.model_order and detector.model_ids disagree")

    for key, value in bank["calibration"].items():
        if int(key) not in (1, 2, 3):
            raise SystemExit(f"unexpected calibration key {key}")

    head = ranker["head_params"]
    full = ranker["full_params"]
    if len(head) != 2 or len(full) != 2:
        raise SystemExit("head_params and full_params must each have two feature blocks")
    # Upstream dropped its verifier scorer (see the module docstring). Older artifacts
    # still carry one, and the app can still read a package that has it, but there is
    # nothing here to write from it any more: the ranking and the calibrated probability
    # never depended on it.
    calibration = detector.get("calibration") or {}
    tau = calibration.get("tau")
    if not isinstance(tau, (int, float)):
        raise SystemExit("shared_detector.json carries no calibration.tau")

    w = Writer()
    w.parts.append(MAGIC)
    w.text(detector.get("source_reference_sha256", ""))
    w.text(detector.get("bank_built_at", bank.get("built_at", "")))
    w.text(bank.get("reference_sha256", ""))

    # models: id, display name, family, family name (all in detector order)
    w.u32(len(model_ids))
    for mid in model_ids:
        w.text(mid)
        w.text(display[mid])
        w.text(families[mid])
        w.text(family_names[mid])

    # calibration + the two knobs the panel shows while the user is still pasting
    w.f64([tau])
    w.f64([bank.get("recommended_queries", 3), bank.get("minimum_valid_numbers", 80)])

    # LDA head: feature standardisation + projection + bias
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

    # full_params: the standardisation the kNN references and the centroid bank live in
    w.u32(len(full))
    for block in full:
        w.u32(len(block["mean"]))
        w.i32(quantize(block["mean"]))
        w.i32(quantize(block["scale"]))

    # centroid bank
    w.feature_bank(h)
    w.feature_bank(o)
    env = o["environment_centroids"]
    w.u32(len(env))
    if env:
        w.u32(len(env[0]))
        w.u32(len(env[0][0]))
        if any(row is None for block in env for row in block):
            raise SystemExit("environment_centroids contains nulls")
        w.i32(quantize(flatten(env)))

    # kNN references: one matrix per candidate model
    w.quantized_references(ranker["references"], ref_bits)

    blob = w.blob()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(blob)

    if options["--emit-detector-json"]:
        path = Path(options["--emit-detector-json"])
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(detector), encoding="utf-8")
    if options["--emit-bank-json"]:
        path = Path(options["--emit-bank-json"])
        path.parent.mkdir(parents=True, exist_ok=True)
        # The golden generator only needs the label metadata; the scoring blocks all
        # live in the detector artifact.
        trimmed = {
            "built_at": bank.get("built_at", ""),
            "reference_sha256": bank.get("reference_sha256", ""),
            "models": [
                {
                    "id": m["id"],
                    "display_name": m.get("display_name", m["id"]),
                    "family": m.get("family", "unknown"),
                    "family_name": m.get("family_name", m.get("family", "unknown")),
                    "response_count": m.get("response_count", 0),
                }
                for m in bank["models"]
            ],
        }
        path.write_text(json.dumps(trimmed), encoding="utf-8")

    print(f"wrote {out_path} ({len(blob):,} bytes)")
    print(f"  models          : {len(model_ids)}")
    print(f"  lda_weights     : {len(lda_weights)}x{len(lda_weights[0])}")
    print(f"  knn references  : {sum(len(r) for r in ranker['references'])}x{len(ranker['references'][0][0])}")
    print(f"  hellinger dims  : {len(h['feature_mean'])}")
    print(f"  ordered dims    : {len(o['feature_mean'])}")
    print(f"  environments    : {len(env)}")
    print(f"  reference bits  : {ref_bits}")
    print(f"  calibration tau : {tau}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
