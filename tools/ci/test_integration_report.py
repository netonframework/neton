import importlib.util
import pathlib
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("gate", pathlib.Path(__file__).with_name("verify-integration-report.py"))
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class ReportGateTest(unittest.TestCase):
    def run_report(self, modify=lambda root: None):
        root = ET.Element("testsuite")
        for classname, name in sorted(gate.REQUIRED):
            ET.SubElement(root, "testcase", classname=classname, name=name + "[linuxX64]")
        ET.SubElement(root, "system-out").text = "\n".join(sorted(gate.MARKERS))
        modify(root)
        with tempfile.TemporaryDirectory() as directory:
            ET.ElementTree(root).write(pathlib.Path(directory) / "TEST-contracts.xml")
            gate.verify(directory)

    def test_complete_reports_pass(self):
        self.run_report()

    def test_redis_requires_its_own_test_and_execution_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = ET.Element("testsuite")
            classname, name = next(iter(gate.REDIS_REQUIRED))
            ET.SubElement(root, "testcase", classname=classname, name=name + "[linuxX64]")
            report = pathlib.Path(directory) / "TEST-redis.xml"
            ET.ElementTree(root).write(report)
            with self.assertRaises(ValueError):
                gate.verify(directory, gate.REDIS_REQUIRED, gate.REDIS_MARKERS)
            ET.SubElement(root, "system-out").text = "\n".join(gate.REDIS_MARKERS)
            ET.ElementTree(root).write(report)
            gate.verify(directory, gate.REDIS_REQUIRED, gate.REDIS_MARKERS)

    def test_missing_reports_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                gate.verify(directory)

    def test_missing_case_fails(self):
        with self.assertRaises(ValueError):
            self.run_report(lambda root: root.remove(root[0]))

    def test_failed_error_or_skipped_case_fails(self):
        for state in ("failure", "error", "skipped"):
            with self.subTest(state=state), self.assertRaises(ValueError):
                self.run_report(lambda root: ET.SubElement(root[0], state))

    def test_duplicate_case_fails(self):
        with self.assertRaises(ValueError):
            self.run_report(lambda root: ET.SubElement(root, "testcase", **root[0].attrib))

    def test_return_without_database_execution_fails(self):
        with self.assertRaises(ValueError):
            self.run_report(lambda root: root.remove(root.find("system-out")))

    def test_malformed_report_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            (pathlib.Path(directory) / "TEST-malformed.xml").write_text("<testsuite>")
            with self.assertRaises(ET.ParseError):
                gate.verify(directory)


if __name__ == "__main__":
    unittest.main()
