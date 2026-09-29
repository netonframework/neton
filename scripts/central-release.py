#!/usr/bin/env python3
"""Validate and upload signed Gradle staging repositories to Central Portal.

No credentials enter argv or output. Upload is user-managed; publish is explicit.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

API = "https://central.sonatype.com/api/v1/publisher"


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.lstrip().startswith(("#", "!")))


def bundle(args):
    root = Path(args.repo).resolve()
    files = sorted(p for p in root.glob(f"com/netonstream/*/{args.version}/*")
                   if p.suffix in (".pom", ".module", ".jar", ".klib")
                   and any(p.parent.parent.name == module or p.parent.parent.name.startswith(module + "-")
                           for module in args.modules.split(",")))
    coordinates = sorted({p.parent.parent.name for p in files})
    if not coordinates:
        raise SystemExit("No artifacts for requested version")
    for path in files:
        if not Path(str(path) + ".asc").is_file():
            raise SystemExit(f"Unsigned artifact: {path.name}")
        if path.suffix in (".pom", ".module") and "SNAPSHOT" in path.read_text():
            raise SystemExit(f"Snapshot reference: {path.name}")
    output = root.parent / f"{args.name}-{args.version}.zip"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            for item in (path, Path(str(path) + ".asc")):
                data = item.read_bytes()
                name = str(item.relative_to(root))
                archive.writestr(name, data)
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    archive.writestr(name + "." + algorithm, hashlib.new(algorithm, data).hexdigest())
    print(json.dumps({"bundle": str(output), "coordinates": coordinates, "artifacts": len(files)}))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("bundle", "upload", "status", "publish"))
    parser.add_argument("--repo")
    parser.add_argument("--version")
    parser.add_argument("--name")
    parser.add_argument("--modules", help="Comma-separated artifact prefixes; excludes stale staging releases")
    parser.add_argument("--id")
    args = parser.parse_args()
    if args.action in ("bundle", "upload"):
        if not all((args.repo, args.version, args.name, args.modules)):
            parser.error("--repo, --version, --name and --modules are required")
        output = bundle(args)
        if args.action == "bundle":
            return
        receipt = output.with_suffix(".deployment-id")
        if receipt.exists():
            raise SystemExit(f"Existing deployment: {receipt}; inspect before retrying")
    props = properties(Path.home() / ".gradle/gradle.properties")
    user = os.environ.get("MAVEN_CENTRAL_USERNAME") or props.get("mavenCentralUsername") or props.get("sonatypeUsername")
    password = os.environ.get("MAVEN_CENTRAL_PASSWORD") or props.get("mavenCentralPassword") or props.get("sonatypePassword")
    if not user or not password:
        raise SystemExit("Central credentials unavailable")
    headers = {"Authorization": "Bearer " + base64.b64encode(f"{user}:{password}".encode()).decode()}
    body = b""
    if args.action == "upload":
        boundary = uuid.uuid4().hex
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="{output.name}"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n').encode()
        body += output.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        endpoint = "/upload?" + urllib.parse.urlencode({"name": output.stem, "publishingType": "USER_MANAGED"})
    else:
        if not args.id:
            parser.error("--id is required")
        endpoint = "/status?" + urllib.parse.urlencode({"id": args.id}) if args.action == "status" else "/deployment/" + args.id
    request = urllib.request.Request(API + endpoint, headers=headers, data=body, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            result = response.read().decode()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Central HTTP {error.code}: {error.read().decode()}")
    if args.action == "upload":
        receipt.write_text(result.strip() + "\n")
        print("Deployment:", result)
    elif args.action == "status":
        print(json.dumps(json.loads(result), indent=2))
    else:
        print("Publication requested")


if __name__ == "__main__":
    main()
