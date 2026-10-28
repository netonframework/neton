"""Compile immutable consumer examples and assert opt-in boundaries fail closed."""
import argparse
import json
import os
from pathlib import Path
import subprocess

CASES = {"stable": None, "opt-in": None, "internal": "internal Neton API",
         "http-internal": "internal Neton API",
         "ai": "Neton AI is experimental", "websocket": "ExperimentalWebSocketEngineApi",
         "global-identity": "currentUser"}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", default="macosArm64", choices=["macosArm64", "linuxX64", "mingwX64"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = root / "build/source-compat"
    output.mkdir(parents=True, exist_ok=True)
    evidence = {}
    try:
        for case, diagnostic in CASES.items():
            result = subprocess.run([str(root / ("gradlew.bat" if os.name == "nt" else "gradlew")), "--no-daemon", "-Pnetonstream.local=false",
                f"-Pcompat.case={case}", ":examples:api-compat:compileKotlin" + args.target[0].upper() + args.target[1:]],
                cwd=root, capture_output=True, text=True, timeout=1200)
            log = result.stdout + result.stderr
            (output / f"{case}.log").write_text(log)
            passed = result.returncode == 0 if diagnostic is None else result.returncode != 0 and diagnostic.lower() in log.lower()
            evidence[case] = {"passed": passed, "exitCode": result.returncode}
            if not passed:
                raise RuntimeError(f"Source compatibility case {case} failed; see {output / (case + '.log')}")
    finally:
        (output / "evidence.json").write_text(json.dumps(evidence, indent=2))

if __name__ == "__main__":
    main()
