import copy
import hashlib
import json
from pathlib import Path
import unittest

from verify_release_baseline import validate, verify_metadata, verify_online, verify_source_lock, verify_toolchain, verify_source_revisions


class ReleaseBaselineTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(Path(__file__).with_name("release-baseline.json").read_text())

    def test_committed_inventory_and_source_lock(self):
        validate(self.manifest)
        verify_source_lock(self.manifest, Path(__file__).with_name("protocol-sources.lock").read_text())

    def test_missing_and_duplicate_components_rejected(self):
        for change in (lambda m: m["components"].pop(),
                       lambda m: m["components"].append(m["components"][0])):
            manifest = copy.deepcopy(self.manifest)
            change(manifest)
            with self.assertRaises(ValueError):
                validate(manifest)

    def test_missing_artifact_rejected(self):
        self.manifest["components"][0]["artifacts"].pop()
        with self.assertRaises(ValueError):
            validate(self.manifest)

    def test_mixed_framework_versions_rejected(self):
        component = next(c for c in self.manifest["components"] if c["name"] == "neton")
        component["artifacts"][0]["version"] = "1.0.0"
        with self.assertRaises(ValueError):
            validate(self.manifest)

    def test_dynamic_and_preview_versions_rejected(self):
        for version in ("latest.release", "0.+", "[0.3,1.0)", "0.4.1-SNAPSHOT", "1.0.0-beta1"):
            with self.subTest(version=version):
                manifest = copy.deepcopy(self.manifest)
                manifest["components"][0]["artifacts"][0]["version"] = version
                with self.assertRaises(ValueError):
                    validate(manifest)

    def test_branch_name_instead_of_commit_rejected(self):
        self.manifest["components"][0]["revision"] = "main"
        with self.assertRaises(ValueError):
            validate(self.manifest)

    def test_compiler_drift_rejected(self):
        with self.assertRaises(ValueError):
            verify_toolchain(self.manifest, '[versions]\nkotlin = "2.0.0"\nksp = "2.3.12"')

    def test_lightweight_and_annotated_tags_use_commit_revision(self):
        for annotated in (False, True):
            def lookup(repository, tag):
                component = next(c for c in self.manifest["components"] if c["repository"] == repository)
                ref = "refs/tags/" + tag
                if annotated:
                    return "0" * 40 + "\t" + ref + "\n" + component["revision"] + "\t" + ref + "^{}\n"
                return component["revision"] + "\t" + ref + "\n"
            self.assertEqual(10, len(verify_source_revisions(self.manifest, lookup)))

    def test_missing_and_moved_tags_rejected(self):
        for result in ("", "0" * 40 + "\trefs/tags/io-v0.4.1\n"):
            with self.assertRaises(ValueError):
                verify_source_revisions(self.manifest, lambda repository, tag: result)

    def test_pom_identity_checked_even_with_valid_digest(self):
        data = b'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.netonstream</groupId><artifactId>wrong</artifactId><version>0.4.1</version></project>'
        artifact = {"name": "io", "version": "0.4.1", "sha256": {"pom": hashlib.sha256(data).hexdigest()}}
        with self.assertRaisesRegex(ValueError, "POM identity"):
            verify_metadata(artifact, "pom", data)

    def test_missing_or_duplicate_source_lock_rows_rejected(self):
        text = Path(__file__).with_name("protocol-sources.lock").read_text()
        for value in ("", text + text.splitlines()[0] + "\n", text.replace("io ", "unknown ")):
            with self.assertRaises(ValueError):
                verify_source_lock(self.manifest, value)

    def fixture(self, metadata):
        data = json.dumps(metadata).encode()
        artifact = {"name": "io", "version": "0.4.1",
                    "sha256": {"module": hashlib.sha256(data).hexdigest()}}
        return artifact, data

    def metadata(self):
        return {"component": {"group": "com.netonstream", "module": "io", "version": "0.4.1"},
                "variants": [{"name": "metadataApiElements"}]}

    def test_valid_metadata(self):
        artifact, data = self.fixture(self.metadata())
        self.assertEqual([], verify_metadata(artifact, "module", data))

    def test_tampering_rejected(self):
        artifact, data = self.fixture(self.metadata())
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            verify_metadata(artifact, "module", data + b" ")

    def test_wrong_identity_empty_variants_and_dynamic_dependency_rejected(self):
        for change in (lambda m: m["component"].update(version="0.4.0"),
                       lambda m: m.update(variants=[]),
                       lambda m: m["variants"][0].update(dependencies=[{
                           "group": "com.netonstream", "module": "http", "version": {"requires": "0.+"}}]),
                       lambda m: m["variants"][0].update({"available-at": {
                           "group": "com.netonstream", "module": "io-jvm", "version": "0.4.0"}})):
            metadata = self.metadata()
            change(metadata)
            artifact, data = self.fixture(metadata)
            with self.assertRaises(ValueError):
                verify_metadata(artifact, "module", data)

    def test_unavailable_central_is_failure_not_skip(self):
        def unavailable(url):
            raise OSError("repository unavailable")
        with self.assertRaises(OSError):
            verify_online(self.manifest, unavailable)


if __name__ == "__main__":
    unittest.main()
