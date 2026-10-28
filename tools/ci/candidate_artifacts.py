#!/usr/bin/env python3
"""Validate the complete local candidate repository before consuming it."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

from verify_abi_policy import MODULES, NATIVE_TARGETS

GROUP = "com.netonstream"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def coordinates():
    result = {name: name for name in MODULES | {"neton-bom"}}
    for name in MODULES - {"neton-ksp"}:
        for target in NATIVE_TARGETS - ({"macosX64"} if name == "neton-database" else set()):
            result[f"{name}-{target.lower()}"] = name
    return result


def local_reference(base, value, repository):
    require(isinstance(value, str) and ":" not in value and "\\" not in value,
            "Metadata contains a non-local artifact reference")
    path = (base / value).resolve()
    require(path.is_relative_to(repository.resolve()) and path.is_file(),
            f"Missing or escaping artifact reference: {value}")
    return path


def verify_signature(file, home, fingerprint):
    result = subprocess.run(["gpg", "--homedir", str(home), "--batch", "--status-fd", "1",
                             "--verify", str(file) + ".asc", str(file)],
                            capture_output=True, text=True, timeout=30)
    require(result.returncode == 0 and f"[GNUPG:] VALIDSIG {fingerprint} " in result.stdout,
            f"Invalid candidate signature: {file.name}")


def verify_repository(repository, version, signature_verifier):
    repository = Path(repository).resolve()
    expected = coordinates()
    group = repository / "com/netonstream"
    actual = {p.name for p in group.iterdir() if p.is_dir()}
    require(actual == set(expected), f"Candidate coordinate inventory mismatch: {actual ^ set(expected)}")
    manifest = []
    for artifact, owner in sorted(expected.items()):
        directory = group / artifact / version
        require(directory.is_dir(), f"Missing candidate version: {artifact}")
        require({p.name for p in (group / artifact).iterdir() if p.is_dir()} == {version},
                f"Mixed candidate versions: {artifact}")
        prefix = f"{artifact}-{version}"
        pom = directory / (prefix + ".pom")
        root = ET.parse(pom).getroot()
        require((root.findtext("m:groupId", namespaces=NS), root.findtext("m:artifactId", namespaces=NS),
                 root.findtext("m:version", namespaces=NS)) == (GROUP, artifact, version),
                f"POM identity mismatch: {artifact}")
        for field in ("m:name", "m:description", "m:url", "m:licenses/m:license/m:name", "m:scm/m:url"):
            require(bool(root.findtext(field, namespaces=NS)), f"Missing POM {field}: {artifact}")
        for dependency in root.findall(".//m:dependency", NS):
            if dependency.findtext("m:groupId", namespaces=NS) == GROUP:
                name = dependency.findtext("m:artifactId", namespaces=NS)
                if name in expected:
                    require(dependency.findtext("m:version", namespaces=NS) == version,
                            f"Framework POM dependency version drift: {artifact} -> {name}")
        if artifact == "neton-bom":
            constraints = {d.findtext("m:artifactId", namespaces=NS)
                           for d in root.findall("m:dependencyManagement/m:dependencies/m:dependency", NS)
                           if d.findtext("m:groupId", namespaces=NS) == GROUP}
            # Ktor is deliberately outside the BOM (http-engine spec).
            require(constraints == MODULES - {"neton", "neton-http-ktor"}, "BOM inventory mismatch")

        metadata = json.loads((directory / (prefix + ".module")).read_text())
        component = metadata["component"]
        require((component["group"], component["module"], component["version"]) == (GROUP, owner, version),
                f"Gradle component identity mismatch: {artifact}")
        if "url" in component:
            target = local_reference(directory, component["url"], repository)
            require(target == group / owner / version / f"{owner}-{version}.module", "Wrong component owner")
        available = set()
        for variant in metadata["variants"]:
            for dependency in variant.get("dependencies", []) + variant.get("dependencyConstraints", []):
                if dependency.get("group") == GROUP and dependency["module"] in expected:
                    require(dependency.get("version", {}).get("requires") == version,
                            f"Gradle dependency version drift: {artifact} -> {dependency['module']}")
            if "available-at" in variant:
                reference = variant["available-at"]
                require(reference["group"] == GROUP and reference["version"] == version
                        and reference["module"] in expected, "Wrong available-at coordinate")
                target = local_reference(directory, reference["url"], repository)
                require(target == group / reference["module"] / version / f"{reference['module']}-{version}.module",
                        "Wrong available-at path")
                available.add(reference["module"])
            for file in variant.get("files", []):
                target = local_reference(directory, file["url"], repository)
                content = target.read_bytes()
                require(len(content) == file["size"] and hashlib.sha256(content).hexdigest() == file["sha256"],
                        f"Gradle artifact hash mismatch: {target.name}")
        if artifact == owner and artifact not in {"neton-bom", "neton-ksp"}:
            require(available == {name for name, parent in expected.items() if parent == owner and name != owner},
                    f"Missing native publication links: {artifact}")

        payloads = [p for p in directory.iterdir() if p.suffix in {".pom", ".module", ".jar", ".klib"}]
        if artifact != "neton-bom":
            require(any(p.name.endswith("-sources.jar") for p in payloads), f"Missing sources: {artifact}")
            require(any(p.suffix == (".jar" if artifact == owner else ".klib")
                        and not p.name.endswith(("-sources.jar", "-javadoc.jar")) for p in payloads),
                    f"Missing executable/metadata artifact: {artifact}")
        for file in payloads:
            require(file.is_file() and not file.is_symlink(), "Unsafe candidate payload")
            content = file.read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            require(Path(str(file) + ".sha256").read_text().strip().lower() == digest,
                    f"Repository checksum mismatch: {file.name}")
            require(Path(str(file) + ".asc").is_file(), f"Missing signature: {file.name}")
            signature_verifier(file)
            if file.suffix in {".jar", ".klib"}:
                require(zipfile.is_zipfile(file), f"Invalid artifact archive: {file.name}")
                with zipfile.ZipFile(file) as archive:
                    require(archive.testzip() is None, f"Corrupt artifact archive: {file.name}")
                    if artifact == "neton-ksp" and file.name == prefix + ".jar":
                        require("META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
                                in archive.namelist(), "Missing published KSP providers")
            manifest.append({"path": str(file.relative_to(repository)), "sha256": digest, "size": len(content)})
    return {"coordinates": len(expected), "signedPayloads": len(manifest), "files": manifest}
