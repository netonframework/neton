#!/usr/bin/env python3
"""Bounded same-process regression repetition, NOT long-duration soak certification."""
import argparse
import json
from pathlib import Path
import platform
import re
import subprocess
import time

from native_transport_gate import ROOT, digest, parse_gtest, require


def verify_repetitions(text, iterations):
    chunks = re.split(r"^Repeating all tests \(iteration (\d+)\) \. \. \.\s*$", text, flags=re.M)
    require(len(chunks) == 1 + iterations * 2, "Missing or extra iterations")
    inventory = None
    for index in range(iterations):
        require(int(chunks[1 + index * 2]) == index + 1, "Out-of-order iteration")
        names = set(parse_gtest(chunks[2 + index * 2]))
        if inventory is None:
            inventory = names
        require(names == inventory, "Test inventory changed between iterations")
    return len(inventory)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--iterations", type=int, default=100)
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    require(args.iterations >= 2 and args.timeout > 0, "Require >=2 iterations and a positive timeout")
    binary = args.binary.resolve(strict=True)
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    command = [str(binary), "--ktest_logger=GTEST", f"--ktest_repeat={args.iterations}"]
    report = {
        "passed": False, "scope": "bounded-same-process-regression", "soakCertified": False,
        "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "sourceDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
        "baselineSha256": digest(ROOT / "tools/ci/release-baseline.json"),
        "binarySha256": digest(binary), "host": platform.platform(), "command": command,
        "iterations": args.iterations,
        "binaryProvenanceVerified": False,
    }
    started = time.monotonic()
    try:
        with (out / "native.log").open("w") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=args.timeout)
        require(result.returncode == 0, "Native test process failed")
        report["testsPerIteration"] = verify_repetitions((out / "native.log").read_text(), args.iterations)
        report["passed"] = True
    except Exception as exc:
        report["error"] = str(exc)
        raise
    finally:
        report["elapsedSeconds"] = time.monotonic() - started
        (out / "evidence.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
