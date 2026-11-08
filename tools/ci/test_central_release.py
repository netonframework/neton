import argparse
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "central_release", Path(__file__).resolve().parents[2] / "scripts/central-release.py")
central = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(central)


class CentralBundleTest(unittest.TestCase):
    def test_signed_android_payload_is_included(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory) / "repository"
            payload = repository / "com/netonstream/pulsekit-android/0.3.1/pulsekit-android-0.3.1.aar"
            payload.parent.mkdir(parents=True)
            payload.write_bytes(b"fixture")
            Path(str(payload) + ".asc").write_bytes(b"fixture-signature")
            args = argparse.Namespace(repo=repository, version="0.3.1", modules="pulsekit", name="test")
            with contextlib.redirect_stdout(io.StringIO()):
                bundle = central.bundle(args)
            with zipfile.ZipFile(bundle) as archive:
                name = payload.relative_to(repository).as_posix()
                self.assertEqual(archive.read(name), b"fixture")
                self.assertIn(name + ".asc", archive.namelist())
                self.assertIn(name + ".sha256", archive.namelist())

    def test_unsigned_android_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory) / "repository"
            payload = repository / "com/netonstream/pulsekit-android/0.3.1/pulsekit-android-0.3.1.aar"
            payload.parent.mkdir(parents=True)
            payload.write_bytes(b"fixture")
            args = argparse.Namespace(repo=repository, version="0.3.1", modules="pulsekit", name="test")
            with self.assertRaisesRegex(SystemExit, "Unsigned artifact"):
                central.bundle(args)


if __name__ == "__main__":
    unittest.main()
