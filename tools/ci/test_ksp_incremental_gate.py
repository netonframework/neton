import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from ksp_incremental_gate import inspect_generated, snapshot


class GeneratedContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "AnchorTable.kt").write_text("object AnchorTable")
        self.manifest([])

    def manifest(self, dependencies):
        payload = json.dumps({"modules": [{"declaredDependencies": dependencies}]})
        digest = hashlib.sha256(payload.encode()).hexdigest()
        (self.root / "GeneratedApplicationModules.kt").write_text(
            f"const val manifestJson = {json.dumps(payload)}\nconst val manifestSha256 = \"{digest}\"\n"
        )

    def test_missing_anchor_fails(self):
        (self.root / "AnchorTable.kt").unlink()
        with self.assertRaises(RuntimeError):
            inspect_generated(self.root, "clean")

    def test_missing_added_table_fails(self):
        self.manifest(["catalog"])
        with self.assertRaises(RuntimeError):
            inspect_generated(self.root, "add")

    def test_stale_dependency_edge_fails(self):
        self.manifest(["catalog"])
        with self.assertRaisesRegex(RuntimeError, "dependency edges"):
            inspect_generated(self.root, "delete")

    def test_stale_column_fails(self):
        (self.root / "TransientTable.kt").write_text("gate_after gate_before")
        with self.assertRaises(RuntimeError):
            inspect_generated(self.root, "modify")

    def test_deleted_entity_in_aggregate_fails(self):
        (self.root / "Registry.kt").write_text("Transient")
        with self.assertRaises(RuntimeError):
            inspect_generated(self.root, "delete")

    def test_driver_import_fails(self):
        (self.root / "AnchorTable.kt").write_text("import sqlx4k.Query")
        with self.assertRaises(RuntimeError):
            inspect_generated(self.root, "clean")

    def test_snapshot_detects_content_and_deletion(self):
        first = snapshot(self.root)
        (self.root / "AnchorTable.kt").write_text("changed")
        self.assertNotEqual(first, snapshot(self.root))
        (self.root / "AnchorTable.kt").unlink()
        self.assertNotEqual(first, snapshot(self.root))

    def test_successful_stages(self):
        inspect_generated(self.root, "clean")
        inspect_generated(self.root, "no-op")
        file = self.root / "TransientTable.kt"
        file.write_text("gate_before")
        self.manifest(["catalog"])
        inspect_generated(self.root, "add")
        file.write_text("gate_after")
        self.manifest([])
        inspect_generated(self.root, "modify")
        file.unlink()
        inspect_generated(self.root, "delete")


if __name__ == "__main__":
    unittest.main()
