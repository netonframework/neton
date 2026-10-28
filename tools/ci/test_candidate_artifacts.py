import hashlib
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import zipfile

from candidate_artifacts import GROUP, MODULES, coordinates, verify_repository


class CandidateRepositoryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.version = "1.0.0-candidate.test"
        self.group = self.root / "com/netonstream"
        for artifact, owner in coordinates().items():
            directory = self.group / artifact / self.version
            directory.mkdir(parents=True)
            prefix = artifact + "-" + self.version
            constraints = ""
            if artifact == "neton-bom":
                constraints = "<dependencyManagement><dependencies>" + "".join(
                    f"<dependency><groupId>{GROUP}</groupId><artifactId>{m}</artifactId>"
                    f"<version>{self.version}</version></dependency>"
                    for m in sorted(MODULES - {"neton", "neton-http-ktor"})) + "</dependencies></dependencyManagement>"
            (directory / (prefix + ".pom")).write_text(
                '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                f"<groupId>{GROUP}</groupId><artifactId>{artifact}</artifactId><version>{self.version}</version>"
                "<name>fixture</name><description>fixture</description><url>https://example.invalid</url>"
                "<licenses><license><name>Apache-2.0</name></license></licenses><scm><url>https://example.invalid</url></scm>"
                + constraints + "</project>")
            variants = []
            if artifact != "neton-bom":
                for suffix in ("-sources.jar", ".jar" if artifact == owner else ".klib"):
                    file = directory / (prefix + suffix)
                    with zipfile.ZipFile(file, "w") as archive:
                        archive.writestr("fixture", "fixture")
                        if artifact == "neton-ksp" and suffix == ".jar":
                            archive.writestr("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider", "fixture.Provider")
                    content = file.read_bytes()
                    variants.append({"files": [{"url": file.name, "size": len(content),
                                                "sha256": hashlib.sha256(content).hexdigest()}]})
            component = {"group": GROUP, "module": owner, "version": self.version}
            if artifact != owner:
                component["url"] = f"../../{owner}/{self.version}/{owner}-{self.version}.module"
            elif artifact not in {"neton-ksp", "neton-bom"}:
                for name, parent in coordinates().items():
                    if parent == owner and name != owner:
                        variants.append({"available-at": {"group": GROUP, "module": name, "version": self.version,
                            "url": f"../../{name}/{self.version}/{name}-{self.version}.module"}})
            (directory / (prefix + ".module")).write_text(json.dumps({"component": component, "variants": variants}))
            for file in list(directory.iterdir()):
                self.seal(file)

    def seal(self, file):
        Path(str(file) + ".sha256").write_text(hashlib.sha256(file.read_bytes()).hexdigest())
        Path(str(file) + ".asc").write_text("unit-test-only signature")

    def payload(self, artifact, suffix):
        return self.group / artifact / self.version / (artifact + "-" + self.version + suffix)

    def verify(self, signature=lambda file: None):
        return verify_repository(self.root, self.version, signature)

    def test_complete_inventory(self):
        report = self.verify()
        self.assertEqual(report["coordinates"], 97)
        self.assertGreater(report["signedPayloads"], 300)

    def test_missing_native_coordinate_fails(self):
        shutil.rmtree(self.group / "neton-core-mingwx64")
        with self.assertRaises(ValueError):
            self.verify()

    def test_missing_signature_fails(self):
        Path(str(self.payload("neton", ".pom")) + ".asc").unlink()
        with self.assertRaises(ValueError):
            self.verify()

    def test_signature_failure_is_not_ignored(self):
        def invalid(file):
            raise ValueError("invalid cryptographic signature")
        with self.assertRaisesRegex(ValueError, "cryptographic"):
            self.verify(invalid)

    def test_corrupt_payload_fails(self):
        self.payload("neton", ".jar").write_bytes(b"corrupt")
        with self.assertRaises(ValueError):
            self.verify()

    def test_bom_version_drift_fails(self):
        file = self.payload("neton-bom", ".pom")
        file.write_text(file.read_text().replace("<version>" + self.version + "</version></dependency>",
                                                "<version>0.0.1</version></dependency>"))
        self.seal(file)
        with self.assertRaises(ValueError):
            self.verify()

    def test_remote_metadata_reference_fails(self):
        file = self.payload("neton", ".module")
        data = json.loads(file.read_text())
        data["variants"][0]["files"][0]["url"] = "https://example.invalid/fallback.jar"
        file.write_text(json.dumps(data))
        self.seal(file)
        with self.assertRaises(ValueError):
            self.verify()


if __name__ == "__main__":
    unittest.main()
