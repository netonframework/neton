#!/usr/bin/env python3
"""Native compiler/linker acceptance of KSP clean/no-op/add/modify/delete.

Only writes generated test inputs in a newly-created output directory. Never
edits tracked sources. Run on a fresh checkout; retain logs on failure.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess


def require(value, message):
    if not value:
        raise RuntimeError(message)


def snapshot(directory):
    return {str(p.relative_to(directory)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(directory.rglob("*.kt"))}


def inspect_generated(directory, stage):
    files = list(directory.rglob("*.kt"))
    require(any(p.name == "AnchorTable.kt" for p in files), "Anchor table was not generated")
    text = "\n".join(p.read_text() for p in files)
    require("import sqlx4k" not in text, "Generated sources expose the SQL driver")
    if stage in ("add", "modify"):
        require(any(p.name == "TransientTable.kt" for p in files), "Added table missing")
        require(("gate_before" if stage == "add" else "gate_after") in text,
                "Column change absent from generated code")
    if stage == "modify":
        require("gate_before" not in text, "Old column survived modification")
    if stage == "delete":
        require("Transient" not in text and "gate_after" not in text,
                "Deleted entity remains in generated sources")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True,
                        choices=["macosArm64", "linuxX64", "linuxArm64", "mingwX64"])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--defer-native-execution", action="store_true")
    parser.add_argument("--run-artifact", type=Path)
    args = parser.parse_args()
    hosts = {"macosArm64": ("Darwin", {"arm64", "aarch64"}),
             "linuxX64": ("Linux", {"x86_64", "amd64"}),
             "linuxArm64": ("Linux", {"arm64", "aarch64"}),
             "mingwX64": ("Windows", {"amd64", "x86_64"})}
    system, arches = hosts[args.target]
    native_host = platform.system() == system and platform.machine().lower() in arches
    cross_arm = (args.defer_native_execution and args.target == "linuxArm64"
                 and platform.system() == "Linux" and platform.machine().lower() == "x86_64")
    require(native_host or cross_arm, "Build on a supported compiler host; execute on the target's native host")
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    if args.run_artifact:
        require(native_host and not args.defer_native_execution, "Artifact execution must be native")
        evidence = json.loads((args.run_artifact / "evidence.json").read_text())
        require(evidence.get("target") == args.target and evidence.get("buildPassed") is True
                and evidence.get("sourceClean") is True and evidence.get("passed") is False,
                "Wrong target or incomplete build evidence")
        require(evidence.get("revision") == os.environ.get("GITHUB_SHA"), "Artifact candidate mismatch")
        require([s["stage"] for s in evidence["stages"]] == ["clean", "no-op", "add", "modify", "delete"],
                "Missing incremental stages")
        try:
            for stage in evidence["stages"]:
                binary = args.run_artifact / "binaries" / (stage["stage"] + ".kexe")
                require(hashlib.sha256(binary.read_bytes()).hexdigest() == stage["binarySha256"],
                        "Artifact checksum mismatch")
                binary.chmod(0o700)
                run = subprocess.run([str(binary.resolve())], capture_output=True, text=True, timeout=30)
                (out / (stage["stage"] + ".log")).write_text(run.stdout + run.stderr)
                require(run.returncode == 0 and "NETON_KSP_INCREMENTAL_OK" in run.stdout,
                        "Native artifact execution failed")
                stage["exitCode"] = run.returncode
            evidence["executionHost"] = platform.platform()
            evidence["passed"] = True
        finally:
            (out / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
        return
    sources = out / "sources"
    sources.mkdir()
    fixture = root / "examples/ksp-incremental"
    require(not (fixture / "build").exists(), "Use fresh fixture outputs")
    generated = fixture / f"build/generated/ksp/{args.target}/{args.target}Main/kotlin"
    evidence = {"passed": False, "buildPassed": False, "target": args.target, "stages": [],
                "buildHost": platform.platform(),
                "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()}
    wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
    capital = args.target[0].upper() + args.target[1:]
    command = [str(root / wrapper) if os.name == "nt" else wrapper,
               "--console=plain", "--warning-mode=fail", "--no-build-cache",
               "-Pnetonstream.local=false", "-Phyper4k.local=false",
               f"-Pgate.target={args.target}", f"-Pgate.sources={sources}",
               "-Pksp.incremental=true",
               f":examples:ksp-incremental:linkReleaseExecutable{capital}"]
    binary = fixture / f"build/bin/{args.target}/releaseExecutable" / (
        "ksp-incremental.exe" if os.name == "nt" else "ksp-incremental.kexe")
    previous = None
    try:
        require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip(),
                "Incremental release evidence requires clean committed inputs")
        evidence["sourceClean"] = True
        for stage in ("clean", "no-op", "add", "modify", "delete"):
            entity = sources / "Transient.kt"
            if stage in ("add", "modify"):
                column = "gate_before" if stage == "add" else "gate_after"
                entity.write_text('''package fixture
import neton.database.annotations.Id
import neton.database.annotations.Table
import neton.database.annotations.Column
@Table("gate_transient")
data class Transient(@Id val id: Long = 0,
    @Column(name = "%s") val value: String = "")
fun compiledTransient() = TransientTable.toString()
''' % column)
            elif stage == "delete":
                entity.unlink()
            with (out / f"{stage}.log").open("w") as log:
                result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
            require(result.returncode == 0, f"{stage}: Native build failed; see retained log")
            inspect_generated(generated, stage)
            current = snapshot(generated)
            if stage == "no-op":
                require(current == previous, "No-op build changed generated code")
            exit_code = None
            if args.defer_native_execution:
                (out / "binaries").mkdir(exist_ok=True)
                shutil.copy2(binary, out / "binaries" / (stage + ".kexe"))
            else:
                result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)
                require(result.returncode == 0 and "NETON_KSP_INCREMENTAL_OK" in result.stdout,
                        f"{stage}: Native executable failed: {result.stderr}")
                exit_code = result.returncode
            evidence["stages"].append({"stage": stage, "generated": current,
                                      "binarySha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                                      "exitCode": exit_code, "command": command})
            previous = current
        require(subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
                == evidence["revision"], "Candidate revision changed during acceptance")
        require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip(),
                "Candidate sources changed during acceptance")
        evidence["buildPassed"] = True
        evidence["passed"] = not args.defer_native_execution
    except Exception as error:
        evidence["failure"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        (out / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    main()
