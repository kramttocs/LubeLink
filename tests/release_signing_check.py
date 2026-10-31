#!/usr/bin/env python3
"""The signed release is built only from the commit being released, and its secrets are read in a stated order (OD-64).
Runs scripts/build-signed-release.sh in a throwaway Git repository with synthetic inputs; no key, no build, no network.
- Source: the checked-out commit must be the commit named (the tag's in CI), with no local changes; a different
  commit, a branch whose tip differs, a name that is not a commit, a changed file and an added file each stop it
  before any secret is read.
- Secrets, every one of the 16 present/absent combinations: without the encoded key, a notice and exit 0 whatever the
  other three hold; with it, exit 1 naming the first missing one; with all four (a key that is not a key), exit 1
  because it cannot be read. Nothing is written to the output folder and no key folder is left behind.
- The workflow's own steps, run in order by a small stand-in for the runner (not GitHub Actions itself): the build
  job's checkout and revision step, then every step of the release job, against a repository whose tag carries this
  script and whose older revision carries a signing script with the old interface (it ignores the commit argument and
  would sign). Started on the tag with no revision, with the tag's commit, or with a branch that moves after the build
  job resolved it, the release reaches this script on the tag's commit. Started with the older commit or a branch at
  it, the job stops at its first step, before checkout: the older script never runs and sees no secret. The same
  older revision does reach its script when that first step is left out, so the check tells the two apart.
- The workflow (text): one job resolves the revision to a commit, every other job checks out that commit, and the
  release job's first step compares it with the tag's commit before checkout.
Run from anywhere; exits non-zero with every failure listed."""
import itertools, os, re, shutil, subprocess, sys, tempfile
from pathlib import Path

root = Path(__file__).resolve().parent.parent
SCRIPT = root / 'scripts/build-signed-release.sh'
SECRETS = ['LUBELINK_RELEASE_KEYSTORE_B64', 'LUBELINK_RELEASE_STORE_PASSWORD', 'LUBELINK_RELEASE_KEY_ALIAS',
           'LUBELINK_RELEASE_KEY_PASSWORD']
failures, passed = [], 0

def check(name, ok, detail=''):
    global passed
    if ok: passed += 1
    else: failures.append(f'{name}: {detail}')

def git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True, text=True).stdout.strip()

work = Path(tempfile.mkdtemp(prefix='release-signing-check.'))
try:
    repo = work / 'repo'
    (repo / 'scripts').mkdir(parents=True)
    shutil.copy2(SCRIPT, repo / 'scripts/build-signed-release.sh')
    (repo / 'app.txt').write_text('synthetic source 1\n')
    env0 = {k: v for k, v in os.environ.items() if k not in SECRETS and not k.startswith('GIT_')}
    env0.update(GIT_AUTHOR_NAME='Synthetic', GIT_AUTHOR_EMAIL='synthetic@example.invalid',
                GIT_COMMITTER_NAME='Synthetic', GIT_COMMITTER_EMAIL='synthetic@example.invalid')
    subprocess.run(['git', 'init', '-q', '-b', 'main', str(repo)], check=True, env=env0)
    git(repo, 'add', '-A'); subprocess.run(['git', '-C', str(repo), 'commit', '-qm', 'one'], check=True, env=env0)
    tagged = git(repo, 'rev-parse', 'HEAD')
    (repo / 'app.txt').write_text('synthetic source 2\n')
    subprocess.run(['git', '-C', str(repo), 'commit', '-qam', 'two'], check=True, env=env0)
    other = git(repo, 'rev-parse', 'HEAD')
    git(repo, 'branch', 'moved', other)
    git(repo, 'checkout', '-q', tagged)
    # An SDK folder with no tools, so a run that got past the secrets would stop at the key, never at a build.
    sdk = work / 'sdk/build-tools/36.0.0'; sdk.mkdir(parents=True)
    temp = work / 'temp'; temp.mkdir()
    out = work / 'out'

    def run(commit, secrets=()):
        env = dict(env0, ANDROID_HOME=str(work / 'sdk'), RUNNER_TEMP=str(temp))
        env.update({k: 'synthetic-value' for k in secrets})
        if SECRETS[0] in secrets: env[SECRETS[0]] = 'bm90IGEga2V5'  # base64 of "not a key"
        r = subprocess.run(['bash', str(repo / 'scripts/build-signed-release.sh'), str(out), *([commit] if commit else [])],
                           env=env, capture_output=True, text=True, timeout=60)
        return r.returncode, r.stdout + r.stderr

    def clean(name):
        check(f'{name}: nothing written', not out.exists(), 'the output folder exists')
        check(f'{name}: no key folder left', not any(temp.iterdir()), f'left {sorted(p.name for p in temp.iterdir())}')

    # Source: stopped before any secret is read, so all four are present here.
    for name, commit, expect in [('the tag commit', tagged, None),
                                 ('a different commit', other, 'not the commit to release'),
                                 ('a branch whose tip differs', 'moved', 'not the commit to release'),
                                 ('a name that is not a commit', 'no-such-revision', 'not the commit to release'),
                                 ('no commit named', None, 'usage')]:
        code, text = run(commit, SECRETS)
        if expect is None:
            check(f'source {name}', code == 1 and 'could not be read' in text, f'exit {code}: {text.strip()}')
        else:
            check(f'source {name}', code != 0 and expect in text and 'could not be read' not in text,
                  f'exit {code}: {text.strip()}')
        clean(f'source {name}')
    (repo / 'app.txt').write_text('changed after the commit\n')
    code, text = run(tagged, SECRETS)
    check('source with a changed file', code == 1 and 'local changes' in text, f'exit {code}: {text.strip()}')
    git(repo, 'checkout', '-q', '--', 'app.txt')
    (repo / 'added.txt').write_text('not committed\n')
    code, text = run(tagged, SECRETS)
    check('source with an added file', code == 1 and 'local changes' in text, f'exit {code}: {text.strip()}')
    (repo / 'added.txt').unlink()
    clean('source with local changes')

    # Secrets: all 16 combinations, on the tag commit.
    for present in itertools.chain.from_iterable(itertools.combinations(SECRETS, n) for n in range(5)):
        name = 'secrets ' + ('+'.join(s.removeprefix('LUBELINK_RELEASE_') for s in present) or 'none')
        code, text = run(tagged, present)
        if SECRETS[0] not in present:
            check(name, code == 0 and 'No release signing key secret' in text, f'exit {code}: {text.strip()}')
        elif len(present) < 4:
            first_missing = next(s for s in SECRETS if s not in present)
            check(name, code == 1 and f'{first_missing} is missing' in text, f'exit {code}: {text.strip()}')
        else:
            check(name, code == 1 and 'could not be read' in text, f'exit {code}: {text.strip()}')
        clean(name)
finally:
    shutil.rmtree(work, ignore_errors=True)

workflow = (root / '.github/workflows/android-ci.yml').read_text(encoding='utf-8')
jobs = dict(re.findall(r'(?ms)^  ([a-z-]+):\n(.*?)(?=^  [a-z-]+:\n|\Z)', workflow.split('\njobs:\n', 1)[1]))

def steps_of(job_text):
    """The job's steps as dicts (uses, name, id, if, ref, env, run), read from this workflow's own layout."""
    body = job_text.split('\n    steps:\n', 1)[1]
    out = []
    for block in re.split(r'(?m)^      - ', body)[1:]:
        lines = ('        ' + block).splitlines()  # the step's first line, after '      - ', as its other keys
        step, i = {'env': {}}, 0
        while i < len(lines):
            m = re.match(r'^        (uses|name|id|if|run): ?(.*)$', lines[i])
            if m and m.group(1) == 'run' and m.group(2) == '|':
                i += 1; run = []
                while i < len(lines) and (lines[i].startswith('          ') or not lines[i].strip()):
                    run.append(lines[i][10:]); i += 1
                step['run'] = '\n'.join(run) + '\n'; continue
            if m: step[m.group(1)] = m.group(2)
            elif lines[i] == '        env:':
                i += 1
                while i < len(lines) and lines[i].startswith('          '):
                    k, v = lines[i].strip().split(': ', 1); step['env'][k] = v; i += 1
                continue
            elif re.match(r'^          ref: ', lines[i]): step['ref'] = lines[i].split(': ', 1)[1]
            i += 1
        out.append(step)
    return out

def expand(text, values):
    def one(m):
        if m.group(1) not in values: raise KeyError(f'expression not modelled: {m.group(1)}')
        return values[m.group(1)]
    return re.sub(r'\$\{\{\s*([^}]+?)\s*\}\}', one, text)

def run_steps(steps, values, origin, workspace, env_extra):
    """Runs steps as the runner would: a checkout clones the origin at its ref, other actions are skipped, and a
    run step runs in bash -eo pipefail with its env. Stops at the first failing step. Returns (ok, log, outputs)."""
    workspace.mkdir(parents=True); log, outputs = [], {}
    out_file, env_file = workspace.parent / (workspace.name + '.out'), workspace.parent / (workspace.name + '.env')
    for step in steps:
        if 'if' in step and 'run' in step: raise KeyError(f'a run step with a condition is not modelled: {step}')
        uses = step.get('uses', '')
        if uses.startswith('actions/checkout@'):
            ref = expand(step.get('ref', ''), values) or values['github.sha']
            subprocess.run(['git', 'clone', '-q', str(origin), str(workspace / 'src')], check=True)
            subprocess.run(['git', '-C', str(workspace / 'src'), 'checkout', '-q', ref], check=True)
            log.append(f'checkout {ref}'); continue
        if 'run' not in step: continue
        env = dict(env0, GITHUB_OUTPUT=str(out_file), GITHUB_ENV=str(env_file), **env_extra)
        env.update({k: expand(v, values) for k, v in step['env'].items()})
        cwd = workspace / 'src' if (workspace / 'src').exists() else workspace
        r = subprocess.run(['bash', '--noprofile', '--norc', '-eo', 'pipefail', '-c', expand(step['run'], values)],
                           cwd=cwd, env=env, capture_output=True, text=True, timeout=60)
        log.append(f"{step.get('name', 'run')}: exit {r.returncode} {(r.stdout + r.stderr).strip()}")
        if out_file.exists():
            outputs.update(l.split('=', 1) for l in out_file.read_text().splitlines() if '=' in l)
        if r.returncode != 0: return False, log, outputs
    return True, log, outputs

# A repository like the project's: an older revision whose signing script has the old interface (ignores the commit
# argument, would sign with whatever secrets it is given), the tag on a revision with this script, and a later one.
OLD_SCRIPT = '''#!/usr/bin/env bash
echo "older script ran: $*"; [ -n "${LUBELINK_RELEASE_KEYSTORE_B64:-}" ] && echo "older script saw the key"
touch "$SIGN_MARKER"; exit 0
'''
work = Path(tempfile.mkdtemp(prefix='release-workflow-check.'))
try:
    origin = work / 'origin'; (origin / 'scripts').mkdir(parents=True)
    subprocess.run(['git', 'init', '-q', '-b', 'main', str(origin)], check=True, env=env0)
    def commit(script_text, message):
        (origin / 'scripts/build-signed-release.sh').write_text(script_text); (origin / 'scripts/build-signed-release.sh').chmod(0o755)
        (origin / 'note.txt').write_text(message + '\n')
        git(origin, 'add', '-A'); subprocess.run(['git', '-C', str(origin), 'commit', '-qm', message], check=True, env=env0)
        return git(origin, 'rev-parse', 'HEAD')
    older = commit(OLD_SCRIPT, 'older')
    tag = commit(SCRIPT.read_text(), 'tagged')
    later = commit(OLD_SCRIPT, 'later')
    git(origin, 'tag', 'release-tag', tag); git(origin, 'branch', 'old', older); git(origin, 'branch', 'moving', tag)
    build_steps = steps_of(jobs['build-and-unit'])
    build_steps = build_steps[:next(i for i, st in enumerate(build_steps) if st.get('id') == 'revision') + 1]
    release_steps = steps_of(jobs['release'])
    secrets = {f'secrets.{k}': 'synthetic-value' for k in SECRETS}

    def tag_run(name, revision, move_branch=False, steps=None, with_secrets=True):
        marker = work / f'{name}.signed'
        values = {'inputs.revision': revision, 'github.sha': tag}
        ok, blog, out = run_steps(build_steps, values, origin, work / f'{name}-build', {})
        if move_branch: git(origin, 'branch', '-f', 'moving', later)
        values.update({'needs.build-and-unit.outputs.commit': out.get('commit', '')},
                      **(secrets if with_secrets else {f'secrets.{k}': '' for k in SECRETS}))
        ok, rlog, _ = run_steps(steps or release_steps, values, origin, work / f'{name}-release',
                                {'SIGN_MARKER': str(marker), 'ANDROID_HOME': str(work / 'no-sdk')})
        if move_branch: git(origin, 'branch', '-f', 'moving', tag)
        return ok, out.get('commit'), '\n'.join(rlog), marker.exists()

    for name, revision, move in [('no revision', '', False), ('the tag commit', tag, False),
                                 ('a branch that moves after the build job', 'moving', True)]:
        ok, resolved, log, signed_old = tag_run(name.replace(' ', '-'), revision, move, with_secrets=False)
        check(f'workflow run, {name}: the build job resolves the tag commit', resolved == tag, f'resolved {resolved}')
        check(f'workflow run, {name}: this script runs on the tag commit',
              ok and f'checkout {tag}' in log and 'No release signing key secret' in log, log)
        check(f'workflow run, {name}: the later revision never runs', not signed_old, log)
    for name, revision in [('the older commit', older), ('a branch at the older commit', 'old')]:
        ok, resolved, log, signed_old = tag_run(name.replace(' ', '-'), revision)
        check(f'workflow run, {name}: the job stops at its first step', not ok and 'nothing is signed' in log
              and 'checkout' not in log, log)
        check(f'workflow run, {name}: the older script never runs or sees the key',
              not signed_old and 'older script' not in log, log)
    ok, _, log, signed_old = tag_run('without-the-first-step', older, steps=release_steps[1:])
    check('workflow run, the older commit without the first step: its script runs (the check tells them apart)',
          signed_old and 'older script saw the key' in log, log)
finally:
    shutil.rmtree(work, ignore_errors=True)

# The workflow (text): the build job resolves the commit; every other checkout uses it; the release's first step
# compares it with the tag's commit; the script is given the tag's commit too.
build = jobs.get('build-and-unit', '')
check('workflow: the build job resolves the commit',
      'commit: ${{ steps.revision.outputs.commit }}' in build and 'id: revision' in build
      and 'echo "commit=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"' in build, 'no commit output')
for job, text in jobs.items():
    refs = re.findall(r'uses: actions/checkout@\S+\n\s+with:\n(?:\s+\S.*\n)*?\s+ref: (.*)', text)
    want = '${{ inputs.revision }}' if job == 'build-and-unit' else '${{ needs.build-and-unit.outputs.commit }}'
    check(f'workflow: {job} checks out the resolved commit', refs == [want], f'checkout refs {refs}')
    if job != 'build-and-unit':
        check(f'workflow: {job} waits for the build job',
              re.search(r'(?m)^    needs: (build-and-unit|\[build-and-unit(, [a-z-]+)*\])$', text) is not None,
              'no needs: build-and-unit')
release = jobs.get('release', '')
first = steps_of(release)[0] if release else {}
check('workflow: the release job first compares the tested commit with the tag commit, before checkout',
      first.get('env') == {'RESOLVED_COMMIT': '${{ needs.build-and-unit.outputs.commit }}', 'TAG_COMMIT': '${{ github.sha }}'}
      and '[ "$RESOLVED_COMMIT" != "$TAG_COMMIT" ]' in first.get('run', '') and 'exit 1' in first.get('run', ''),
      f'first step {first}')
check('workflow: the release passes the tag commit',
      'TAG_COMMIT: ${{ github.sha }}' in release and './scripts/build-signed-release.sh release-out "$TAG_COMMIT"' in release,
      'the script is not given the tag commit')

# The release signs only when every test job passed (LL-03): it needs all of them, and its condition, evaluated for
# every combination of their results on a tag, is true only when all three succeeded.
GATES = ['build-and-unit', 'harness-and-real-server', 'instrumented']
needs_line = re.search(r'(?m)^    needs: (.*)$', release)
needs = [n.strip() for n in needs_line.group(1).strip('[]').split(',')] if needs_line else []
check('workflow: the release needs every test job', sorted(needs) == sorted(GATES), f'needs {needs}')
cond = re.search(r'(?m)^    if: \$\{\{ (.*) \}\}$', release)
def release_runs(results, ref='refs/tags/v1'):
    expr = cond.group(1)
    expr = expr.replace('!cancelled()', 'True').replace("startsWith(github.ref, 'refs/tags/')", repr(ref.startswith('refs/tags/')))
    expr = re.sub(r'needs\.([a-z-]+)\.result', lambda m: repr(results[m.group(1)]), expr)
    if re.search(r'[a-z-]+\.|\(', expr.replace('True', '').replace('False', '')):
        raise KeyError(f'release condition not modelled: {cond.group(1)}')
    return eval(expr.replace('&&', ' and ').replace('||', ' or ').replace('!', ' not '), {})
if cond:
    import itertools
    for combo in itertools.product(['success', 'failure', 'skipped', 'cancelled'], repeat=3):
        results = dict(zip(GATES, combo))
        try: runs = release_runs(results)
        except (KeyError, SyntaxError, NameError) as e: check('workflow: the release condition can be evaluated', False, str(e)); break
        check(f'workflow: release with {results}', runs == all(r == 'success' for r in combo),
              f'signs={runs}')
    check('workflow: no release on a branch', not release_runs(dict.fromkeys(GATES, 'success'), 'refs/heads/main'), 'signs on a branch')
else:
    check('workflow: the release has a condition', False, 'no if: ${{ ... }} line')

if failures:
    print('\n'.join(f'FAIL {f}' for f in failures))
    print(f'Release signing check: {len(failures)} failed, {passed} passed')
    sys.exit(1)
print(f'Passed release signing check: {passed} checks (source, all 16 secret combinations, the workflow run, workflow text)')
