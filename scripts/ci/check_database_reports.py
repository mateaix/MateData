"""Fail CI if a required real-database suite was absent, skipped, or unsuccessful."""
from pathlib import Path
import xml.etree.ElementTree as ET

suites = ("ExternalPostgresTest", "ExternalMysqlTest", "PostgresQueryBudgetTest", "PostgresSystemBoundaryTest")
for name in suites:
    path = Path(f"matedata-server/target/surefire-reports/TEST-io.matedata.{name}.xml")
    suite = ET.parse(path).getroot()
    assert int(suite.get("tests", 0)) > 0, f"No tests executed: {name}"
    for field in ("failures", "errors", "skipped"):
        assert int(suite.get(field, 0)) == 0, f"{name}: {field}={suite.get(field)}"
    print(f"{name}: {suite.get('tests')} passed, no skips")
