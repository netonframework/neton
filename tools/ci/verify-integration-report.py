#!/usr/bin/env python3
"""Fail closed when required native integration contracts did not actually execute."""
import pathlib
import sys
import xml.etree.ElementTree as ET


REQUIRED = {
    ("neton.database.adapter.sqlx.SqlxPostgresTypesDbTest", "numericColumnsDecodeWithoutCrashing"),
    ("neton.database.adapter.sqlx.PostgresReleaseGateTest", "requiredDatabaseCannotSilentlySkipOrUseExternalDatabase"),
    ("neton.database.adapter.sqlx.PostgresReleaseGateTest", "realPostgresMigrationsAndTransactionIsolation"),
}
MARKERS = {"NETON_POSTGRES_TRANSACTIONS_EXECUTED", "NETON_POSTGRES_TYPES_EXECUTED"}
REDIS_REQUIRED = {("neton.redis.RedisPipelineIntegrationTest", "orderedBatchPreservesNamespaceAndReportsErrors")}
REDIS_MARKERS = {"NETON_REDIS_PIPELINE_EXECUTED"}


def verify(directory, required=REQUIRED, markers=MARKERS):
    reports = sorted(pathlib.Path(directory).glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No native integration test reports found")
    passed = set()
    executed = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for output in root.iter("system-out"):
            executed.update(line.strip() for line in (output.text or "").splitlines() if line.strip() in markers)
        for case in root.iter("testcase"):
            # Gradle's native runner may append [linuxX64] to a test name.
            key = (case.get("classname"), case.get("name", "").split("[")[0])
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed integration test: {key}")
            if key in required:
                if case.find("skipped") is not None:
                    raise ValueError(f"Skipped required integration test: {key}")
                if key in passed:
                    raise ValueError(f"Duplicate required integration test: {key}")
                passed.add(key)
    missing = required - passed
    if missing:
        raise ValueError(f"Missing required integration tests: {sorted(missing)}")
    if markers - executed:
        raise ValueError("Execution markers missing: optional test returns are not a gate pass")


if __name__ == "__main__":
    try:
        if len(sys.argv) == 3 and sys.argv[2] == "redis":
            verify(sys.argv[1], REDIS_REQUIRED, REDIS_MARKERS)
        elif len(sys.argv) == 2:
            verify(sys.argv[1])
        else:
            raise ValueError("Usage: verify-integration-report.py REPORT_DIRECTORY [redis]")
    except (IndexError, ValueError, OSError, ET.ParseError) as error:
        sys.exit(f"Integration gate failed: {error}")
    print("Required integration test reports and execution markers verified")
