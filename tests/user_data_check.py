#!/usr/bin/env python3
"""No user data in the app's source. Fails if the app source contains:
- a synthetic starter value from tests/sample-values.txt or from a contract capture (vehicle, record, reminder,
  station and key names), so test data can never become a default, fallback or placeholder in the app;
- a web address whose host is not one of the fixed public services the app is built on (docs/API-CONTRACT.md);
- an IP address, an e-mail address, or a long hex/base64 string that could be a key.
Application vocabulary (screen names, domain terms, the two station field names LubeLogger addresses by name)
is allowed. Run from anywhere; exits non-zero with every hit listed."""
import json, re, sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
app = root / 'app/src/main'
VOCABULARY = {'LubeLink Gas Station', 'LubeLink Station Address'}  # upstream field names (X-02)
# The common reminders the app suggests (OD-52, OD-58): fixed wording, the same for every user. Test
# data also uses some of them ("Oil change"), so they are named here rather than read as data.
VOCABULARY |= {'Oil change', 'Tire rotation', 'Cabin air filter', 'Brake inspection', 'Wiper blades'}
HOSTS = {'schemas.android.com', 'overpass-api.de', 'overpass.private.coffee', 'api.geoapify.com',
         'tiles.openfreemap.org'}

samples = {line.strip() for line in (root / 'tests/sample-values.txt').read_text(encoding='utf-8').splitlines()
           if line.strip() and not line.startswith('#')}
def walk(value, key=None):
    if isinstance(value, dict):
        for k, v in value.items(): walk(v, k)
    elif isinstance(value, list):
        for v in value: walk(v, key)
    elif isinstance(value, str) and value and key in {'make', 'model', 'licensePlate', 'description', 'notes',
                                                      'tags', 'value', 'username', 'name'}:
        samples.add(value)
for capture in sorted((root / 'tests/contract').glob('*/*.json')):
    try: walk(json.loads(capture.read_text(encoding='utf-8')))
    except ValueError: pass  # HTML error pages
samples -= VOCABULARY

patterns = {
    'IP address': re.compile(r'(?<![\w.])(?:\d{1,3}\.){3}\d{1,3}(?![\w.])'),
    'e-mail address': re.compile(r'[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'),
    'possible key': re.compile(r'\b[0-9a-fA-F]{32,}\b|\b[A-Za-z0-9+/]{40,}={0,2}'),
}
hits = []
for path in sorted(app.rglob('*')):
    if not path.is_file() or path.suffix not in {'.kt', '.xml', '.java'}: continue
    rel = path.relative_to(root)
    for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
        for value in samples:
            if re.search(r'(?<![A-Za-z0-9])' + re.escape(value) + r'(?![A-Za-z0-9])', line):
                hits.append(f'{rel}:{number}: synthetic value "{value}"')
        for host in re.findall(r'https?://([A-Za-z0-9.-]+)', line):
            if host not in HOSTS and '$' not in line:
                hits.append(f'{rel}:{number}: web address host "{host}"')
        for name, pattern in patterns.items():
            for match in pattern.findall(line):
                hits.append(f'{rel}:{number}: {name} "{match}"')
if hits:
    print('User data or synthetic values in the app source:\n  ' + '\n  '.join(hits))
    sys.exit(1)
print(f'Passed user-data check: {len(samples)} synthetic values and {len(patterns) + 1} patterns, none in app/src/main')
