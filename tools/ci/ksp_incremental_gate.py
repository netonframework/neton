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
    args = parser.parse_args()
    hosts = {"macosArm64": ("Darwin", {"arm64", "aarch64"}),
             "linuxX64": ("Linux", {"x86_64", "amd64"}),
             "linuxArm64": ("Linux", {"arm64", "aarch64"}),
             "mingwX64": ("Windows", {"amd64", "x86_64"})}
    system, arches = hosts[args.target]
    require(platform.system() == system and platform.machine().lower() in arches,
            "This gate requires the target's native host")
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sources = out / "sources"
    sources.mkdir()
    fixture = root / "examples/ksp-incremental"
    require(not (fixture / "build").exists(), "Use fresh fixture outputs")
    generated = fixture / f"build/generated/ksp/{args.target}/{args.target}Main/kotlin"
    evidence = {"passed": False, "target": args.target, "stages": [],
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
            result = subprocess.run([str(binary)], capture_output=True, text=True, timeout=30)
            require(result.returncode == 0 and "NETON_KSP_INCREMENTAL_OK" in result.stdout,
                    f"{stage}: Native executable failed: {result.stderr}")
            evidence["stages"].append({"stage": stage, "generated": current,
                                      "binarySha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                                      "exitCode": result.returncode, "command": command})
            previous = current
        evidence["passed"] = True
    finally:
        (out / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")


if __name__ == "__main__":
    main()
