#!/usr/bin/env python3
"""Check release ABI inventory/policy in addition to Kotlin's binary comparison."""
from pathlib import Path
import re

MODULES = {
    "neton", "neton-ai", "neton-cache", "neton-core", "neton-database", "neton-http",
    "neton-http-hyper4k", "neton-http-ktor", "neton-jobs", "neton-ksp", "neton-logging",
    "neton-redis", "neton-routing", "neton-security", "neton-storage", "neton-validation",
    "neton-websocket",
}
NATIVE_TARGETS = {"linuxArm64", "linuxX64", "macosArm64", "macosX64", "mingwX64"}


def verify_dump(module, text):
    if module == "neton-ksp":
        if "public " not in text:
            raise ValueError("Empty KSP JVM API baseline")
        return
    targets = re.search(r"^// Targets: \[([^\]]+)\]$", text, re.MULTILINE)
    expected = NATIVE_TARGETS - ({"macosX64"} if module == "neton-database" else set())
    if not targets or set(targets[1].split(", ")) != expected:
        raise ValueError(f"Incomplete ABI targets for {module}")
    if f"// Library unique name: <com.netonstream:{module}>" not in text:
        raise ValueError(f"Wrong ABI artifact identity: {module}")
    if module == "neton-database":
        forbidden = (r"io\.github\.smyrgeorge\.sqlx4k/", r"\bfun (?:save|saveAll)\(",
                     r"/(?:QueryRuntime|EntityPersistence)\b")
        if any(re.search(pattern, text) for pattern in forbidden):
            raise ValueError("Database ABI exposes a forbidden driver/legacy persistence contract")


def verify(root):
    actual = {p.parent.parent.name for p in root.glob("*/api/*.api")}
    if actual != MODULES:
        raise ValueError(f"ABI inventory mismatch: missing={MODULES - actual}, extra={actual - MODULES}")
    for module in sorted(MODULES):
        name = module + (".api" if module == "neton-ksp" else ".klib.api")
        files = list((root / module / "api").glob("*.api"))
        if len(files) != 1 or files[0].name != name:
            raise ValueError(f"Ambiguous/missing baseline for {module}")
        verify_dump(module, files[0].read_text())


if __name__ == "__main__":
    verify(Path(__file__).resolve().parents[2])
    print("All 17 published Kotlin ABI inventories and database boundary rules verified")
