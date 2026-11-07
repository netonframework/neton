#!/usr/bin/env python3
"""Transfer cross-built transport tests to an ARM64 host without claiming build == execution."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ("neton-http", "neton-websocket")


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_arm_binary(path):
    header = path.read_bytes()[:20]
    require(len(header) == 20 and header[:6] == b"\x7fELF\x02\x01" and header[18:20] == b"\xb7\x00",
            "Expected a Linux ARM64 ELF executable")


def parse_gtest(text):
    started = re.findall(r"^\[\s*RUN\s*\]\s+(\S+)\s*$", text, re.M)
    passed = re.findall(r"^\[\s*OK\s*\]\s+(\S+)\s+\(\d+ ms\)\s*$", text, re.M)
    summary = re.findall(r"^\[=+\]\s+(\d+) tests? from \d+ test cases? ran\.", text, re.M)
    total = re.findall(r"^\[\s*PASSED\s*\]\s+(\d+) tests?\.", text, re.M)
    require(started and len(set(started)) == len(started), "Empty or duplicate native tests")
    require(len(passed) == len(started) and set(passed) == set(started), "Incomplete native test execution")
    require(summary == [str(len(started))] and total == summary, "Missing or inconsistent native summary")
    require(not re.search(r"\[\s*(FAILED|SKIPPED|DISABLED)\s*\]", text), "Native test failed or skipped")
    return passed


def verify_manifest(artifact, revision, baseline):
    manifest = json.loads((artifact / "manifest.json").read_text())
    require(manifest.get("target") == "linuxArm64" and manifest.get("buildPassed") is True
            and manifest.get("sourceClean") is True and manifest.get("passed") is False,
            "Incomplete cross-build evidence")
    require(manifest.get("revision") == revision and manifest.get("baselineSha256") == baseline,
            "Wrong candidate or dependency baseline")
    require(set(manifest.get("binaries", {})) == set(MODULES), "Missing or unexpected transport binary")
    for module in MODULES:
        path = artifact / (module + ".kexe")
        require(path.is_file() and not path.is_symlink(), "Missing or linked executable")
        require(digest(path) == manifest["binaries"][module], "Transport executable checksum mismatch")
        verify_arm_binary(path)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", action="store_true")
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(args.stage != bool(args.artifact), "Choose stage or artifact execution")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    baseline = digest(ROOT / "tools/ci/release-baseline.json")
    report = {"passed": False, "target": "linuxArm64", "revision": revision, "baselineSha256": baseline}
    try:
        require(revision == os.environ.get("GITHUB_SHA", revision), "Checkout is not the workflow candidate")
        if args.stage:
            require(platform.system() == "Linux" and platform.machine().lower() in {"x86_64", "amd64"},
                    "Cross-build staging requires the Linux x64 compiler host")
            require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip(),
                    "Stage only clean committed sources")
            resolved = ROOT / "build/release-baseline/resolved-linuxArm64.json"
            require(json.loads(resolved.read_text())["target"] == "linuxArm64", "Missing dependency resolution evidence")
            shutil.copyfile(resolved, out / resolved.name)
            report.update(sourceClean=True, buildPassed=True, binaries={})
            for module in MODULES:
                source = ROOT / module / "build/bin/linuxArm64/debugTest/test.kexe"
                verify_arm_binary(source)
                destination = out / (module + ".kexe")
                shutil.copyfile(source, destination)
                report["binaries"][module] = digest(destination)
            (out / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
        else:
            require(platform.system() == "Linux" and platform.machine().lower() in {"aarch64", "arm64"},
                    "Execute on a native Linux ARM64 host, without the Kotlin compiler")
            artifact = args.artifact.resolve()
            report = verify_manifest(artifact, revision, baseline)
            report["executionHost"] = platform.platform()
            report["tests"] = {}
            for module in MODULES:
                binary = artifact / (module + ".kexe")
                binary.chmod(0o700)
                log = out / (module + ".log")
                with log.open("w") as stream:
                    result = subprocess.run([str(binary), "--ktest_logger=GTEST"],
                                            stdout=stream, stderr=subprocess.STDOUT, timeout=900)
                require(result.returncode == 0, f"{module} native tests failed; see retained log")
                names = parse_gtest(log.read_text())
                suite = ET.Element("testsuite", name=module, tests=str(len(names)), failures="0", errors="0", skipped="0")
                for name in names:
                    classname, _, method = name.rpartition(".")
                    ET.SubElement(suite, "testcase", classname=classname, name=method)
                directory = out / "reports" / module
                directory.mkdir(parents=True)
                ET.ElementTree(suite).write(directory / "TEST-native.xml", encoding="utf-8", xml_declaration=True)
                report["tests"][module] = len(names)
            subprocess.run([sys.executable, str(ROOT / "tools/ci/verify-integration-report.py"),
                            str(out / "reports/neton-websocket"), "websocket"], check=True)
            report["passed"] = True
    except Exception as exc:
        report["error"] = str(exc)
        raise
    finally:
        (out / "evidence.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
