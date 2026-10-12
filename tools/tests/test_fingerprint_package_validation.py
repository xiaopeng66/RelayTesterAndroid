"""The publisher must reject packages that the Android reader cannot install."""

import copy
import io
import json
import os
import struct
import sys
import tempfile
import unittest
from contextlib import ExitStack, redirect_stdout
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1]
TEST_TMP = TOOLS.parents[2] / "tmp" if os.name == "nt" else Path(tempfile.gettempdir())
sys.path.insert(0, str(TOOLS))

import build_fingerprint_asset as builder
import publish_bank


DIMENSIONS = (355, 74, 102, 220)


def upstream_pair():
    """Small, rectangular upstream-shaped input; no network or generated binary fixture."""
    ids = ["model-a", "model-b"]

    def params(width):
        return {"mean": [0.0] * width, "scale": [1.0] * width}

    def feature_bank(width):
        return {
            "feature_mean": [0.0] * width,
            "feature_scale": [1.0] * width,
            "nuisance_basis": [[0.0] * width],
            "centroids": [[0.0] * width for _ in ids],
        }

    robust = {
        "model_order": ids[:],
        "hellinger": feature_bank(355),
        "ordered_blocks": {**feature_bank(74), "environment_centroids": [[[0.0] * 74 for _ in ids]]},
    }
    bank = {
        "built_at": "2026-10-12T00:00:00+00:00",
        "reference_sha256": "a" * 64,
        "models": [{"id": mid, "response_count": 1} for mid in ids],
        "robust": robust,
        "calibration": {},
    }
    detector = {
        "schema": "shared-detector-v2",
        "bank_built_at": bank["built_at"],
        "source_reference_sha256": bank["reference_sha256"],
        "base_sha256": "b" * 64,
        "model_ids": ids[:],
        "response_counts": [1, 1],
        "calibration": {
            "schema": "shared-confidence-v2",
            "method": "ranking-temperature",
            "tau": 1.0,
            "binding": {
                "base_sha256": "b" * 64,
                "reference_sha256": "a" * 64,
                "model_ids": ids[:],
            },
        },
        "ranker": {
            "head_params": [params(355), params(74)],
            "full_params": [params(355), params(74)],
            "lda_weights": [[0.0] * 429 for _ in ids],
            "lda_bias": [0.0, 0.0],
            "references": [[[0.0] * 429] for _ in ids],
            "bank": copy.deepcopy(robust),
            "blend": {"frequency": 0.5, "positional": 0.5},
            "positional": {
                "params": [params(width) for width in DIMENSIONS],
                "weights": [[0.0] * 751 for _ in ids],
                "bias": [0.0, 0.0],
            },
        },
    }
    return detector, bank


class BuildInputValidationTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory(dir=TEST_TMP, prefix="bank-validation-")))
        self.output = self.directory / "lite-bank.bin"
        self.stack.enter_context(redirect_stdout(io.StringIO()))
        self.stack.enter_context(patch("urllib.request.urlopen", side_effect=AssertionError("network forbidden")))

    def build(self, detector, bank, *flags):
        (self.directory / "shared_detector.json").write_text(json.dumps(detector), encoding="utf-8")
        (self.directory / "unified_bank.json").write_text(json.dumps(bank), encoding="utf-8")
        with patch.object(sys, "argv", ["build_fingerprint_asset.py", str(self.directory), str(self.output), *flags]):
            return builder.main()

    def test_valid_small_sources_build_in_both_reference_widths(self):
        for bits in (8, 16):
            with self.subTest(bits=bits):
                self.assertEqual(0, self.build(*upstream_pair(), "--ref-bits", str(bits)))
                manifest = publish_bank.build_manifest(self.output.read_bytes())
                self.assertEqual(4, manifest["formatVersion"])
                self.assertEqual(2, manifest["modelCount"])

    def test_malformed_dense_or_reference_shapes_do_not_replace_output(self):
        cases = {
            "positional model row missing": lambda d, b: d["ranker"]["positional"]["weights"].pop(),
            "ragged positional row": lambda d, b: d["ranker"]["positional"]["weights"][0].pop(),
            "ragged LDA row": lambda d, b: d["ranker"]["lda_weights"][0].pop(),
            "LDA bias missing": lambda d, b: d["ranker"]["lda_bias"].pop(),
            "head scale missing": lambda d, b: d["ranker"]["head_params"][0]["scale"].pop(),
            "position parameter width": lambda d, b: d["ranker"]["positional"]["params"][2]["mean"].pop(),
            "ragged centroid": lambda d, b: b["robust"]["hellinger"]["centroids"][0].pop(),
            "ragged nuisance basis": lambda d, b: b["robust"]["ordered_blocks"]["nuisance_basis"][0].pop(),
            "ragged environment": lambda d, b: b["robust"]["ordered_blocks"]["environment_centroids"][0][0].pop(),
            "reference model missing": lambda d, b: d["ranker"]["references"].pop(),
            "empty reference rows": lambda d, b: d["ranker"]["references"][1].clear(),
            "ragged reference row": lambda d, b: d["ranker"]["references"][1][0].pop(),
            "nonfinite blend": lambda d, b: d["ranker"]["blend"].update(frequency=float("inf")),
        }
        for name, mutate in cases.items():
            with self.subTest(case=name):
                detector, bank = upstream_pair()
                mutate(detector, bank)
                self.output.write_bytes(b"previous valid package")
                with self.assertRaises(SystemExit):
                    self.build(detector, bank)
                self.assertEqual(b"previous valid package", self.output.read_bytes())

    def test_mixed_source_or_calibration_bindings_are_rejected_before_packing(self):
        cases = {
            "bank row order": lambda d, b: b["robust"]["model_order"].reverse(),
            "bank build stamp": lambda d, b: b.update(built_at="different revision"),
            "reference digest": lambda d, b: b.update(reference_sha256="c" * 64),
            "response counts": lambda d, b: d["response_counts"].__setitem__(0, 2),
            "calibration model order": lambda d, b: d["calibration"]["binding"]["model_ids"].reverse(),
            "calibration base digest": lambda d, b: d["calibration"]["binding"].update(base_sha256="c" * 64),
            "calibration reference digest": lambda d, b: d["calibration"]["binding"].update(reference_sha256="c" * 64),
            "calibration method": lambda d, b: d["calibration"].update(method="other-method"),
            "calibration schema": lambda d, b: d["calibration"].update(schema="shared-confidence-v3"),
        }
        for name, mutate in cases.items():
            with self.subTest(case=name):
                detector, bank = upstream_pair()
                mutate(detector, bank)
                with self.assertRaises(SystemExit):
                    self.build(detector, bank)

    def test_packed_shape_gate_rejects_malformed_positional_count_and_reference_scales(self):
        self.build(*upstream_pair())
        valid = self.output.read_bytes()
        schema_size = 4 + len(b"shared-detector-v2")
        positional_params_size = 4 + sum(4 + width * 8 for width in DIMENSIONS)
        tail_size = 16 + positional_params_size + 8 + 2 * 751 * 4 + 4 + 2 * 4 + schema_size
        tail = len(valid) - tail_size
        positional_rows = tail + 16 + positional_params_size
        references = tail - (12 + 2 * (4 + 4 + 429 * 2))
        cases = {
            "position model count": (positional_rows, "<I", 1),
            "reference model count": (references + 8, "<I", 1),
            "reference code width": (references, "<I", 32),
            "zero reference rows": (references + 12, "<I", 0),
            "nonfinite reference scale": (references + 16, "<f", float("nan")),
            "nonfinite blend": (tail, "<d", float("inf")),
        }
        for name, (offset, fmt, value) in cases.items():
            with self.subTest(case=name):
                malformed = bytearray(valid)
                struct.pack_into(fmt, malformed, offset, value)
                with self.assertRaises(SystemExit):
                    publish_bank.build_manifest(malformed)


class PackedPublicationValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = TOOLS.parent / "app/src/test/resources/lm-fingerprint"
        cls.bank = (cls.fixtures / "lite-bank-small.bin").read_bytes()

    def test_all_three_existing_package_formats_still_validate(self):
        for name, expected_format in (("lite-bank-legacy.bin", 2), ("lite-bank-v1.bin", 3), ("lite-bank-small.bin", 4)):
            with self.subTest(name=name):
                manifest = publish_bank.build_manifest((self.fixtures / name).read_bytes())
                self.assertEqual(expected_format, manifest["formatVersion"])
                self.assertEqual(6, manifest["modelCount"])

    def test_truncated_or_padded_payload_cannot_get_a_manifest(self):
        offset = 8
        for _ in range(3):
            length = struct.unpack_from("<I", self.bank, offset)[0]
            offset += 4 + length
        header_end = offset + 4
        for name, blob in (("header only", self.bank[:header_end]), ("half file", self.bank[:len(self.bank) // 2]),
                           ("missing last byte", self.bank[:-1]), ("trailing byte", self.bank + b"x")):
            with self.subTest(case=name):
                with self.assertRaises(SystemExit):
                    publish_bank.build_manifest(blob)

    def test_unknown_v2_schema_is_rejected_even_when_header_is_valid(self):
        changed = self.bank.replace(b"shared-detector-v2", b"shared-detector-v3")
        self.assertNotEqual(self.bank, changed)
        with self.assertRaises(SystemExit):
            publish_bank.build_manifest(changed)

    def test_publisher_stops_before_credentials_or_requests_for_truncated_bank(self):
        with tempfile.TemporaryDirectory(dir=TEST_TMP, prefix="bank-publish-validation-") as temporary:
            bank_path = Path(temporary) / "truncated.bin"
            bank_path.write_bytes(self.bank[:-1])
            with patch.object(sys, "argv", ["publish_bank.py", "--bank", str(bank_path)]), \
                    patch.object(publish_bank, "read_token", side_effect=RuntimeError("credentials reached")) as credentials, \
                    patch.object(publish_bank, "request", side_effect=RuntimeError("network reached")) as request, \
                    redirect_stdout(io.StringIO()):
                failure = None
                try:
                    publish_bank.main()
                except (SystemExit, RuntimeError) as error:
                    failure = error
                self.assertIsInstance(failure, SystemExit)
                credentials.assert_not_called()
                request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
