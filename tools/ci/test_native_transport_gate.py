import json
from pathlib import Path
import tempfile
import unittest

from native_transport_gate import MODULES, digest, parse_gtest, verify_manifest


class NativeTransportGateTest(unittest.TestCase):
    LOG = """[==========] Running 1 tests from 1 test cases.
[ RUN      ] neton.example.Test.contract
[       OK ] neton.example.Test.contract (12 ms)
[==========] 1 tests from 1 test cases ran. (12 ms total)
[  PASSED  ] 1 tests.
"""

    def test_complete_execution(self):
        self.assertEqual(["neton.example.Test.contract"], parse_gtest(self.LOG))

    def test_missing_duplicate_skipped_and_inconsistent_execution(self):
        for text in ("", self.LOG.replace("[       OK ]", "[  FAILED  ]"),
                     self.LOG.replace("[ RUN      ]", "[  SKIPPED ]"),
                     self.LOG.replace("1 tests from 1 test cases ran", "2 tests from 1 test cases ran"),
                     self.LOG + "[ RUN      ] neton.example.Test.contract\n",
                     self.LOG.replace("[  PASSED  ] 1 tests.\n", ""),
                     self.LOG + "[ DISABLED ] neton.example.Test.other\n"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_gtest(text)

    def fixture(self, directory):
        binary = b"\x7fELF\x02\x01" + b"\x00" * 12 + b"\xb7\x00"
        manifest = {"target": "linuxArm64", "revision": "revision", "baselineSha256": "baseline",
                    "sourceClean": True, "buildPassed": True, "passed": False, "binaries": {}}
        for module in MODULES:
            path = directory / (module + ".kexe")
            path.write_bytes(binary)
            manifest["binaries"][module] = digest(path)
        return manifest

    def test_valid_and_invalid_manifest(self):
        changes = [lambda m: m.update(revision="wrong"), lambda m: m.update(baselineSha256="wrong"),
                   lambda m: m.update(target="linuxX64"), lambda m: m.update(sourceClean=False),
                   lambda m: m.update(buildPassed=False), lambda m: m.update(passed=True),
                   lambda m: m["binaries"].pop("neton-http")]
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            manifest = self.fixture(directory)
            (directory / "manifest.json").write_text(json.dumps(manifest))
            verify_manifest(directory, "revision", "baseline")
            for change in changes:
                manifest = self.fixture(directory)
                change(manifest)
                (directory / "manifest.json").write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    verify_manifest(directory, "revision", "baseline")

    def test_tampering_and_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            manifest = self.fixture(directory)
            (directory / "manifest.json").write_text(json.dumps(manifest))
            path = directory / "neton-http.kexe"
            path.write_bytes(b"wrong")
            with self.assertRaisesRegex(ValueError, "checksum"):
                verify_manifest(directory, "revision", "baseline")
            manifest["binaries"]["neton-http"] = digest(path)
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError, "ELF"):
                verify_manifest(directory, "revision", "baseline")


if __name__ == "__main__":
    unittest.main()
