import unittest
import tempfile
from pathlib import Path
from verify_abi_policy import MODULES, NATIVE_TARGETS, verify, verify_dump


class AbiPolicyTest(unittest.TestCase):
    def database(self, extra=""):
        return ("// Targets: [linuxArm64, linuxX64, macosArm64, mingwX64]\n"
                "// Library unique name: <com.netonstream:neton-database>\n" + extra)

    def test_valid_database(self):
        verify_dump("neton-database", self.database())

    def test_missing_target_fails(self):
        with self.assertRaises(ValueError):
            verify_dump("neton-database", self.database().replace("linuxArm64, ", ""))

    def test_wrong_identity_fails(self):
        with self.assertRaises(ValueError):
            verify_dump("neton-database", self.database().replace("com.netonstream", "other"))

    def test_driver_and_legacy_exports_fail(self):
        for declaration in ("fun require(): io.github.smyrgeorge.sqlx4k/QueryExecutor",
                            "fun save(kotlin/Any)", "fun saveAll(kotlin/Any)",
                            "class neton.database/QueryRuntime", "class neton.database/EntityPersistence"):
            with self.subTest(declaration=declaration), self.assertRaises(ValueError):
                verify_dump("neton-database", self.database(declaration))

    def test_empty_ksp_fails(self):
        with self.assertRaises(ValueError):
            verify_dump("neton-ksp", "")

    def test_inventory_cannot_silently_drop_or_add_a_module(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "settings.gradle.kts").write_text('rootProject.name = "neton"')
            for module in MODULES:
                folder = root / module / "api"
                folder.mkdir(parents=True)
                if module == "neton-ksp":
                    (folder / (module + ".api")).write_text("public class Processor")
                else:
                    targets = NATIVE_TARGETS - ({"macosX64"} if module == "neton-database" else set())
                    (folder / (module + ".klib.api")).write_text(
                        "// Targets: [" + ", ".join(sorted(targets)) + "]\n"
                        + f"// Library unique name: <com.netonstream:{module}>\n")
            verify(root)
            for adapter in ("neton-http-hyper4k", "neton-http-ktor"):
                (root / "settings.gradle.kts").write_text(f'include(":{adapter}")')
                with self.assertRaisesRegex(ValueError, "External adapter"):
                    verify(root)
            (root / "settings.gradle.kts").write_text('rootProject.name = "neton"')
            file = root / "neton-ksp/api/neton-ksp.api"
            file.unlink()
            with self.assertRaises(ValueError):
                verify(root)
            file.write_text("public class Processor")
            extra = root / "unexpected/api/extra.api"
            extra.parent.mkdir(parents=True)
            extra.write_text("public class Unexpected")
            with self.assertRaises(ValueError):
                verify(root)


if __name__ == "__main__":
    unittest.main()
