"""Validate source bindings and packed shapes against the Android bank reader."""

import math
import struct


FORMAT_BY_MAGIC = {b"LMFPA002": 2, b"LMFPA003": 3, b"LMFPA004": 4}
FREQUENCY_DIMENSIONS = (355, 74)
POSITIONAL_DIMENSIONS = (355, 74, 102, 220)
SCHEMA = "shared-detector-v2"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_sources(detector, bank):
    """Reject mixed revisions and malformed arrays before packing or subsetting."""
    try:
        ids = detector["model_ids"]
        require(ids and len(set(ids)) == len(ids), "model ids must be nonempty and unique")
        models = {model["id"]: model for model in bank["models"]}
        require(len(models) == len(bank["models"]) == len(ids) and set(models) == set(ids),
                "bank models and detector ids disagree")
        require(detector["bank_built_at"] == bank["built_at"], "bank build stamp mismatch")
        require(detector["source_reference_sha256"] == bank["reference_sha256"],
                "source reference digest mismatch")
        require(detector["response_counts"] == [models[mid]["response_count"] for mid in ids],
                "model response counts mismatch")
        calibration = detector["calibration"]
        require(calibration["schema"] == "shared-confidence-v2" and
                calibration["method"] == "ranking-temperature", "unsupported calibration")
        binding = calibration["binding"]
        require(binding["model_ids"] == ids, "calibration model order mismatch")
        require(binding["base_sha256"] == detector["base_sha256"], "calibration base digest mismatch")
        require(binding["reference_sha256"] == bank["reference_sha256"],
                "calibration reference digest mismatch")
        require(math.isfinite(calibration["tau"]) and calibration["tau"] > 0,
                "calibration tau must be positive and finite")

        def vector(values, width, name):
            require(len(values) == width, f"{name}: expected {width} values")
            require(all(math.isfinite(value) for value in values), f"{name}: nonfinite value")

        def matrix(rows, count, width, name):
            require(len(rows) == count, f"{name}: expected {count} rows")
            for row in rows:
                vector(row, width, name)

        def params(blocks, widths, name):
            require(len(blocks) == len(widths), f"{name}: wrong block count")
            for block, width in zip(blocks, widths):
                vector(block["mean"], width, name + " mean")
                vector(block["scale"], width, name + " scale")

        ranker = detector["ranker"]
        params(ranker["head_params"], FREQUENCY_DIMENSIONS, "head")
        params(ranker["full_params"], FREQUENCY_DIMENSIONS, "full")
        matrix(ranker["lda_weights"], len(ids), 429, "LDA weights")
        vector(ranker["lda_bias"], len(ids), "LDA bias")
        require(len(ranker["references"]) == len(ids), "reference model count mismatch")
        for references in ranker["references"]:
            require(bool(references), "reference rows must not be empty")
            matrix(references, len(references), 429, "references")
        for robust in (bank["robust"], ranker["bank"]):
            require(robust["model_order"] == ids, "feature bank model order mismatch")
            for name, width in zip(("hellinger", "ordered_blocks"), FREQUENCY_DIMENSIONS):
                block = robust[name]
                vector(block["feature_mean"], width, name + " mean")
                vector(block["feature_scale"], width, name + " scale")
                matrix(block["centroids"], len(ids), width, name + " centroids")
                basis = block["nuisance_basis"]
                matrix(basis, len(basis), width, name + " nuisance basis")
            for environment in robust["ordered_blocks"]["environment_centroids"]:
                matrix(environment, len(ids), 74, "environment centroids")
        positional = ranker["positional"]
        params(positional["params"], POSITIONAL_DIMENSIONS, "positional")
        matrix(positional["weights"], len(ids), 751, "positional weights")
        vector(positional["bias"], len(ids), "positional bias")
        vector([ranker["blend"]["frequency"], ranker["blend"]["positional"]], 2, "blend")
    except (KeyError, TypeError, ValueError, OverflowError) as error:
        raise SystemExit(f"Invalid fingerprint sources: {error}") from error


class PackedReader:
    def __init__(self, blob):
        self.blob = memoryview(blob)
        self.offset = 0

    def take(self, length):
        require(0 <= length <= len(self.blob) - self.offset, "truncated fingerprint package")
        start = self.offset
        self.offset += length
        return self.blob[start:self.offset]

    def u32(self):
        value = struct.unpack("<I", self.take(4))[0]
        require(value <= 0x7FFFFFFF, "array count exceeds Android reader limit")
        return value

    def text(self):
        return self.take(self.u32()).tobytes().decode("utf-8")

    def f64(self):
        value = struct.unpack("<d", self.take(8))[0]
        require(math.isfinite(value), "nonfinite scalar")
        return value

    def vector(self, expected=None):
        count = self.u32()
        if expected is not None:
            require(count == expected, "vector length mismatch")
        self.take(count * 4)

    def matrix(self, expected_rows=None, expected_columns=None):
        rows, columns = self.u32(), self.u32()
        if expected_rows is not None:
            require(rows == expected_rows, "matrix model count mismatch")
        if expected_columns is not None:
            require(columns == expected_columns, "matrix width mismatch")
        self.take(rows * columns * 4)

    def params(self, expected=None):
        blocks = self.u32()
        require(blocks <= (len(self.blob) - self.offset) // 4, "truncated parameter blocks")
        if expected is not None:
            require(blocks == len(expected), "parameter block count mismatch")
        for index in range(blocks):
            width = self.u32()
            if expected is not None:
                require(width == expected[index], "parameter width mismatch")
            self.take(width * 8)

    def feature_bank(self, width, models):
        require(self.u32() == width, "feature bank width mismatch")
        self.take(width * 8)
        basis_rows = self.u32()
        if basis_rows:
            require(self.u32() == width, "nuisance basis width mismatch")
            self.take(basis_rows * width * 4)
        require(self.u32() == models, "centroid model count mismatch")
        self.take(models * width * 4)

    def references(self, expected_models=None, expected_columns=None):
        bits, columns, models = self.u32(), self.u32(), self.u32()
        require(bits in (8, 16), "unsupported reference code width")
        require(models <= (len(self.blob) - self.offset) // 4, "truncated reference models")
        if expected_models is not None:
            require(models == expected_models, "reference model count mismatch")
        if expected_columns is not None:
            require(columns == expected_columns, "reference width mismatch")
        for _ in range(models):
            rows = self.u32()
            scales = self.take(rows * 4)
            if expected_models is not None:
                require(rows > 0, "empty reference rows")
                require(all(math.isfinite(v[0]) for v in struct.iter_unpack("<f", scales)),
                        "nonfinite reference scale")
            self.take(rows * columns * (bits // 8))

    def gaussian(self):
        self.matrix()
        self.f64()

    def legacy_verifier(self):
        self.params()
        self.f64()
        self.vector()
        self.matrix()
        self.vector()
        self.gaussian()
        candidates = self.u32()
        require(candidates <= (len(self.blob) - self.offset) // 16, "truncated verifier candidates")
        for _ in range(candidates):
            self.vector()
            for _ in range(4):
                self.gaussian()
        self.references()
        active = self.u32()
        self.u32()
        self.take(active * 4)
        self.take(self.u32() * 12)
        self.f64()


def validate_package(blob):
    """Walk every packed block without allocating dense weights; return header metadata."""
    try:
        reader = PackedReader(blob)
        magic = reader.take(8).tobytes()
        require(magic in FORMAT_BY_MAGIC, f"unsupported fingerprint magic {magic!r}")
        header = {
            "format_version": FORMAT_BY_MAGIC[magic],
            "source_reference_sha256": reader.text(),
            "built_at": reader.text(),
            "reference_sha256": reader.text(),
            "model_count": reader.u32(),
        }
        count = header["model_count"]
        require(0 < count <= (len(reader.blob) - reader.offset) // 16, "invalid model count")
        ids = []
        for _ in range(count):
            ids.append(reader.text())
            for _ in range(3):
                reader.text()
        require(len(set(ids)) == count, "duplicate model ids")
        require(reader.f64() > 0, "calibration tau must be positive")
        reader.f64()
        reader.f64()
        reader.params(FREQUENCY_DIMENSIONS)
        reader.matrix(count, 429)
        reader.vector(count)
        reader.params(FREQUENCY_DIMENSIONS)
        reader.feature_bank(355, count)
        reader.feature_bank(74, count)
        environments = reader.u32()
        if environments:
            require(reader.u32() == count and reader.u32() == 74, "environment shape mismatch")
            reader.take(environments * count * 74 * 4)
        reader.references(count, 429)
        if magic == b"LMFPA002":
            reader.legacy_verifier()
        if magic == b"LMFPA004":
            reader.f64()
            reader.f64()
            reader.params(POSITIONAL_DIMENSIONS)
            reader.matrix(count, 751)
            reader.vector(count)
            require(reader.text() == SCHEMA, "unsupported detector schema")
        require(reader.offset == len(reader.blob), "trailing bytes in fingerprint package")
        return header
    except (TypeError, ValueError, OverflowError, struct.error) as error:
        raise SystemExit(f"Invalid fingerprint package: {error}") from error
