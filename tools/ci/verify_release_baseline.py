#!/usr/bin/env python3
"""Verify pinned Central metadata, not a claim of binary or runtime certification."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tomllib
import urllib.request
import xml.etree.ElementTree as ET

from verify_abi_policy import MODULES

ROOT = Path(__file__).resolve().parents[2]
CENTRAL = "https://repo.maven.apache.org/maven2/com/netonstream/"
GROUP = "com.netonstream"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PROTOCOLS = {"io", "http", "tls", "websocket", "openssl-kotlin"}
EXPECTED = {
    "io": {"io", "io-testkit"}, "http": {"http"}, "websocket": {"websocket"},
    "openssl-kotlin": {"openssl"}, "tls": {"tls", "tls-http", "tls-websocket"},
    "quic": {"quic", "quic-testkit"}, "http3": {"http3"},
    "msgtrans-kotlin": {"msgtrans", "msgtrans-quic", "msgtrans-websocket"},
    "pulsekit": {"pulsekit"}, "neton": MODULES | {"neton-bom"},
}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def fixed_version(value):
    return isinstance(value, str) and re.fullmatch(r"\d+\.\d+\.\d+(?:-\d+)?", value) is not None


def validate(manifest):
    require(manifest.get("schemaVersion") == 1, "Unsupported baseline schema")
    require(manifest.get("group") == GROUP, "Wrong baseline group")
    require(set(manifest.get("toolchain", {})) == {"kotlin", "ksp", "jdk"}, "Missing toolchain")
    components = manifest["components"]
    require(len(components) == len(EXPECTED), "Missing or duplicate component")
    require({c["name"] for c in components} == set(EXPECTED), "Component inventory drift")
    for component in components:
        name = component["name"]
        require(re.fullmatch(r"[0-9a-f]{40}", component["revision"]) is not None,
                f"Unpinned source revision: {name}")
        require(component["repository"].startswith("https://github.com/") and component["tag"],
                f"Missing source provenance: {name}")
        artifacts = component["artifacts"]
        require(len(artifacts) == len(EXPECTED[name]) and
                {a["name"] for a in artifacts} == EXPECTED[name], f"Artifact inventory drift: {name}")
        if name == "neton":
            require(len({a["version"] for a in artifacts}) == 1, "Mixed Framework publication versions")
        for artifact in artifacts:
            require(fixed_version(artifact["version"]), f"Non-release version: {artifact['name']}")
            require(set(artifact["sha256"]) == {"pom", "module"}, "Incomplete metadata hashes")
            require(all(re.fullmatch(r"[0-9a-f]{64}", h) for h in artifact["sha256"].values()),
                    "Invalid metadata digest")


def verify_toolchain(manifest, catalog):
    versions = tomllib.loads(catalog)["versions"]
    require(all(manifest["toolchain"][key] == versions[key] for key in ("kotlin", "ksp")),
            "Compiler/KSP differs from the accepted release baseline")


def verify_source_lock(manifest, text):
    rows = [line.split() for line in text.splitlines() if line.strip()]
    require(all(len(row) == 2 for row in rows), "Malformed protocol source lock")
    expected = {c["name"]: c["revision"] for c in manifest["components"] if c["name"] in PROTOCOLS}
    require(len(rows) == len(expected) and dict(rows) == expected,
            "Protocol source lock differs from published baseline revisions")


def verify_metadata(artifact, extension, data):
    name, version = artifact["name"], artifact["version"]
    require(hashlib.sha256(data).hexdigest() == artifact["sha256"][extension],
            f"Central metadata digest mismatch: {name}:{version}.{extension}")
    if extension == "pom":
        root = ET.fromstring(data)
        identity = tuple(root.findtext("m:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version"))
        require(identity == (GROUP, name, version), f"POM identity mismatch: {name}")
        return []
    metadata = json.loads(data)
    component = metadata["component"]
    require((component["group"], component["module"], component["version"]) == (GROUP, name, version),
            f"Module identity mismatch: {name}")
    variants = metadata.get("variants", [])
    require(bool(variants), f"No published variants: {name}")
    targets = set()
    for variant in variants:
        reference = variant.get("available-at")
        if reference:
            require(reference["group"] == GROUP and reference["version"] == version,
                    f"Cross-version variant: {name}")
            targets.add(reference["module"])
        for dep in variant.get("dependencies", []) + variant.get("dependencyConstraints", []):
            if dep.get("group") == GROUP:
                require(fixed_version(dep.get("version", {}).get("requires")),
                        f"Unpinned stack dependency: {name} -> {dep['module']}")
    return sorted(targets)


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        require(response.status == 200, f"Cannot retrieve {url}")
        return response.read()


def remote_tag(repository, tag):
    return subprocess.check_output(
        ["git", "ls-remote", "--exit-code", repository, f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"],
        text=True, timeout=45)


def verify_source_revisions(manifest, lookup=remote_tag):
    evidence = []
    for component in manifest["components"]:
        ref = "refs/tags/" + component["tag"]
        refs = {name: revision for revision, name in
                (line.split() for line in lookup(component["repository"], component["tag"]).splitlines())}
        actual = refs.get(ref + "^{}", refs.get(ref))
        require(actual == component["revision"], f"Release tag moved or missing: {component['name']}")
        evidence.append({"component": component["name"], "revision": actual})
    return evidence


def verify_online(manifest, fetcher=fetch):
    evidence = []
    for component in manifest["components"]:
        for artifact in component["artifacts"]:
            name, version = artifact["name"], artifact["version"]
            targets = []
            for extension in ("pom", "module"):
                url = f"{CENTRAL}{name}/{version}/{name}-{version}.{extension}"
                found = verify_metadata(artifact, extension, fetcher(url))
                if extension == "module":
                    targets = found
            evidence.append({"coordinate": f"{GROUP}:{name}:{version}",
                             "declaredTargets": targets, "metadataVerified": True})
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    manifest = json.loads((ROOT / "tools/ci/release-baseline.json").read_text())
    report = {"status": "failed", "scope": "root publication metadata; not target binary/runtime verification",
              "checkedAt": datetime.now(timezone.utc).isoformat(),
              "candidateSha": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "worktreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
              "baselineSha256": hashlib.sha256((ROOT / "tools/ci/release-baseline.json").read_bytes()).hexdigest()}
    try:
        validate(manifest)
        verify_toolchain(manifest, (ROOT / "gradle/libs.versions.toml").read_text())
        verify_source_lock(manifest, (ROOT / "tools/ci/protocol-sources.lock").read_text())
        report["sources"] = verify_source_revisions(manifest) if args.online else []
        report["artifacts"] = verify_online(manifest) if args.online else []
        report["status"] = "passed" if args.online else "offline-policy-passed"
    except Exception as exc:
        report["error"] = str(exc)
        raise
    finally:
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
