from pathlib import Path
import tempfile
import unittest
from verify_api_stability import require_markers

class ApiStabilityTest(unittest.TestCase):
    def test_missing_marker_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "api.kt"
            for source in ("class Example", "suspend fun work() {}", "typealias Example = String", "val setting = 1"):
                file.write_text(source)
                with self.assertRaises(ValueError):
                    require_markers(file, "Experimental")
                file.write_text("@Experimental\n" + source)
                require_markers(file, "Experimental")

if __name__ == "__main__":
    unittest.main()
