import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("retry-gradle-central.sh")
FAKE_GRADLE = r"""
sleep() { printf '%s\n' "$1" >> "$CASE_DIR/delays"; }
gradle() {
    printf '%s\n' attempt >> "$CASE_DIR/calls"
    if [[ "$MODE" == recovered ]] && (( $(wc -l < "$CASE_DIR/calls") == 2 )); then
        return 0
    fi
    if [[ "$MODE" == task_failure ]]; then
        echo '> Task :neton-redis:linuxX64Test FAILED'
    fi
    echo 'Could not resolve all dependencies for configuration'
    if [[ "$MODE" != other_failure ]]; then
        echo "Could not GET 'https://repo.maven.apache.org/maven2/example.pom'. Received status code 429 from server"
    fi
    return 7
}
source "$1" "$CASE_DIR/gradle" gradle
"""


class GradleCentralRetryTest(unittest.TestCase):
    def test_retry_only_before_tasks_and_preserve_exit_status(self):
        for mode, status, calls, delays in (
            ("recovered", 0, 2, ["30"]),
            ("throttled", 7, 3, ["30", "60"]),
            ("task_failure", 7, 1, []),
            ("other_failure", 7, 1, []),
        ):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as path:
                env = dict(os.environ, MODE=mode, CASE_DIR=path)
                result = subprocess.run(["bash", "-c", FAKE_GRADLE, "fixture", str(SCRIPT)],
                                        env=env, capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, status, result.stderr)
                case = Path(path)
                self.assertEqual(len((case / "calls").read_text().splitlines()), calls)
                self.assertEqual(len(list(case.glob("gradle-*.log"))), calls)
                slept = case / "delays"
                self.assertEqual(slept.read_text().splitlines() if slept.exists() else [], delays)
