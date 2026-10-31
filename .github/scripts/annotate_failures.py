"""Surface the first build/test errors of a CI log as GitHub annotations, so the failure is readable
through the checks API without downloading the full log.

Usage: annotate_failures.py <label> <log>
"""
import re, sys

label, path = sys.argv[1], sys.argv[2]
try:
    lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
except FileNotFoundError:
    sys.exit(0)
pattern = re.compile(r'^(e: |w: .*error|.*error:|\* What went wrong|FAILURE:|.*FAILED$|.*Exception)')
picked = []
for i, line in enumerate(lines):
    if pattern.match(line):
        picked.extend(lines[i:i + 3])
    if len(picked) > 400:
        break
if not picked:
    picked = lines[-60:]
text = '\n'.join(dict.fromkeys(picked))  # de-duplicate, keep order
chunks = [text[i:i + 3500] for i in range(0, len(text), 3500)][:10]
for n, chunk in enumerate(chunks, 1):
    body = chunk.replace('%', '%25').replace('\r', '').replace('\n', '%0A')
    print(f'::error title={label} ({n}/{len(chunks)})::{body}')
