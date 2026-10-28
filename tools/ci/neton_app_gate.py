"""Run pinned public neton-app against this workflow's signed candidate artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

from reference_gate import require, run_logged

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--target", required=True, choices=["macosArm64", "linuxX64", "mingwX64"])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    pin = (root / "tools/ci/neton-app.lock").read_text().strip()
    require(re.fullmatch(r"[0-9a-f]{40}", pin), "Invalid reference application pin")
    candidate = args.candidate.resolve()
    manifest = json.loads((candidate / "evidence.json").read_text())
    require(manifest["passed"] and manifest["revision"] == revision, "Candidate gate failed or revision mismatch")
    repository = candidate / "repository"
    for item in manifest["repository"]["files"]:
        file = (repository / item["path"]).resolve()
        require(file.is_relative_to(repository.resolve()) and file.is_file(), "Missing/escaping candidate file")
        require(file.stat().st_size == item["size"] and hashlib.sha256(file.read_bytes()).hexdigest() == item["sha256"], "Candidate payload changed after signature verification")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    app = output / "app"
    env = {k: v for k, v in os.environ.items() if not k.startswith("ORG_GRADLE_PROJECT_")}
    env["GRADLE_USER_HOME"] = str(output / "gradle-home")
    run_logged(["git", "clone", "--no-checkout", "https://github.com/netonframework/neton-app.git", str(app)],
               output / "checkout.log", cwd=root, env=env, timeout=120)
    run_logged(["git", "checkout", "--detach", pin], output / "pin.log", cwd=app, env=env, timeout=60)
    require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=app, text=True).strip() == pin, "Application pin mismatch")
    wrapper = app / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [str(wrapper), "--no-daemon", "--max-workers=2", f"-PnetonVersion={manifest['version']}",
               f"-Pneton.repository={repository}", "linkReleaseExecutable" + args.target[0].upper() + args.target[1:]]
    for attempt in (1, 2):
        run_logged(command, output / f"build-{attempt}.log", cwd=app, env=env, timeout=2400)
    binary = app / f"build/bin/{args.target}/releaseExecutable/neton-app.{'exe' if os.name == 'nt' else 'kexe'}"
    run_logged([os.sys.executable, str(app / "scripts/runtime_smoke.py"), "--binary", str(binary),
                "--candidate", manifest["version"], "--output", str(output / "runtime")],
               output / "runtime.log", cwd=app, env=env, timeout=300)
    evidence = json.loads((output / "runtime/evidence.json").read_text())
    require(evidence["passed"], "Application runtime failed")
    evidence.update(frameworkRevision=revision, applicationRevision=pin, target=args.target)
    (output / "evidence.json").write_text(json.dumps(evidence, indent=2))

if __name__ == "__main__":
    main()
