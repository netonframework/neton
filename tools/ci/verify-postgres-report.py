#!/usr/bin/env python3
"""Fail closed when a required native PostgreSQL contract did not run successfully."""
import pathlib
import sys
import xml.etree.ElementTree as ET


REQUIRED = {
    ("neton.database.adapter.sqlx.SqlxPostgresTypesDbTest", "numericColumnsDecodeWithoutCrashing"),
    ("neton.database.adapter.sqlx.PostgresReleaseGateTest", "requiredDatabaseCannotSilentlySkipOrUseExternalDatabase"),
    ("neton.database.adapter.sqlx.PostgresReleaseGateTest", "realPostgresMigrationsAndTransactionIsolation"),
}
MARKERS = {"NETON_POSTGRES_TRANSACTIONS_EXECUTED", "NETON_POSTGRES_TYPES_EXECUTED"}


def verify(directory):
    reports = sorted(pathlib.Path(directory).glob("TEST-*.xml"))
    if not reports:
        raise ValueError("No native PostgreSQL test reports found")
    passed = set()
    executed = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for output in root.iter("system-out"):
            executed.update(line.strip() for line in (output.text or "").splitlines() if line.strip() in MARKERS)
        for case in root.iter("testcase"):
            # Gradle's native runner may append [linuxX64] to a test name.
            key = (case.get("classname"), case.get("name", "").split("[")[0])
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed database test: {key}")
            if key in REQUIRED:
                if case.find("skipped") is not None:
                    raise ValueError(f"Skipped required database test: {key}")
                if key in passed:
                    raise ValueError(f"Duplicate required database test: {key}")
                passed.add(key)
    missing = REQUIRED - passed
    if missing:
        raise ValueError(f"Missing required database tests: {sorted(missing)}")
    if MARKERS - executed:
        raise ValueError("Database execution markers missing: optional test returns are not a gate pass")


if __name__ == "__main__":
    try:
        verify(sys.argv[1])
    except (IndexError, ValueError, OSError, ET.ParseError) as error:
        sys.exit(f"PostgreSQL gate failed: {error}")
    print("Required PostgreSQL test reports verified")
