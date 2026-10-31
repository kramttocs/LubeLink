"""Report Android lint results as GitHub annotations (counts by severity and issue id, plus the first
locations), so lint findings are readable through the checks API.

Usage: lint_summary.py <lint-results.xml>
"""
import collections, os, sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
if not os.path.exists(path):
    print(f'::warning title=Lint::No lint report at {path}')
    sys.exit(0)
issues = ET.parse(path).getroot().findall('issue')
by_severity = collections.Counter(i.get('severity') for i in issues)
by_id = collections.Counter((i.get('severity'), i.get('id')) for i in issues)
line = 'Lint: ' + (', '.join(f'{n} {s}' for s, n in sorted(by_severity.items())) or 'no issues')
print(f'::notice title=Lint::{line}')
details = []
for (severity, issue_id), n in by_id.most_common():
    first = next(i for i in issues if i.get('id') == issue_id and i.get('severity') == severity)
    loc = first.find('location')
    where = f"{loc.get('file', '').split('/app/', 1)[-1]}:{loc.get('line', '?')}" if loc is not None else '?'
    details.append(f'{severity} {issue_id} x{n} (first: {where}) {first.get("message", "")[:160]}')
if details:
    body = '%0A'.join(d.replace('%', '%25') for d in details)[:60000]
    print(f'::notice title=Lint issues::{body}')
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
        out.write(f'- {line}\n' + ''.join(f'  - {d}\n' for d in details))
