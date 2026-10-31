#!/usr/bin/env python3
"""Print passed / failed / skipped counts from JUnit XML files, per suite and in total.

Fails (exit 1) when there are no JUnit XML files, when the files hold no <testcase> element (a
test job with no test case fails, however Gradle reported it), or when a suite's `tests` count disagrees with the
test cases it lists (the results cannot be trusted). Failed tests are reported, not turned into a failure here: the
test task itself fails the job.

With --annotate, also writes GitHub Actions annotations: one ::error per failed test (its first lines, capped) and one
::notice with the totals, so the counts and reasons can be read through the checks API without the run's logs.

Usage: junit_counts.py [--annotate] <directory with JUnit XML files>
"""
import pathlib
import sys
import xml.etree.ElementTree as ElementTree

ANNOTATION_LIMIT = 900


def annotation_text(text: str) -> str:
    """Escapes a message for a workflow command, cut to ANNOTATION_LIMIT characters."""
    text = text[:ANNOTATION_LIMIT]
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv: list[str]) -> int:
    annotate = "--annotate" in argv
    args = [arg for arg in argv if arg != "--annotate"]
    if len(args) != 1:
        print(__doc__)
        return 2
    files = sorted(pathlib.Path(args[0]).rglob("*.xml"))
    if not files:
        print(f"No JUnit XML files under {args[0]}: tests not run")
        return 1
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    cases = 0
    inconsistent = []
    for path in files:
        root = ElementTree.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            counts = {key: int(suite.get(key, 0)) for key in totals}
            for key in totals:
                totals[key] += counts[key]
            listed = len(suite.findall("testcase"))
            cases += listed
            if listed != counts["tests"]:
                inconsistent.append(f"{path.name}: suite {suite.get('name')} says tests={counts['tests']} but lists {listed}")
            passed = counts["tests"] - counts["failures"] - counts["errors"] - counts["skipped"]
            print(f"{suite.get('name')}: passed={passed} failed={counts['failures'] + counts['errors']} "
                  f"skipped={counts['skipped']}")
            for case in suite.iter("testcase"):
                for problem in list(case.iter("failure")) + list(case.iter("error")):
                    detail = (problem.get("message") or problem.text or "").strip().splitlines()[:3]
                    print(f"  FAILED {case.get('name')}: " + " | ".join(detail))
                    if annotate:
                        full = (problem.get("message") or "") + "\n" + (problem.text or "")
                        title = annotation_text(f"{suite.get('name')}.{case.get('name')}").replace(",", "%2C")
                        print(f"::error title={title}::{annotation_text(full.strip())}")
    passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
    summary = (f"run={totals['tests']} passed={passed} failed={totals['failures'] + totals['errors']} "
               f"skipped={totals['skipped']}")
    print(f"TOTAL: {summary}")
    if annotate:
        print(f"::notice title=Test counts::{summary}")
    for problem in inconsistent:
        print(f"Inconsistent results: {problem}")
    if cases == 0:
        print(f"No test case in the JUnit XML files under {args[0]}: tests not run")
        return 1
    return 1 if inconsistent else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
