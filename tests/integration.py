#!/usr/bin/env python3
"""Black-box checks against actual HDFS and Hadoop MapReduce (no mocks)."""
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
OUT = ROOT / 'results'
OUT.mkdir(exist_ok=True)
CHECKS = []
COMMAND_LOG = OUT / 'commands.log'

def run(args, ok=True):
    p = subprocess.run([str(x) for x in args], text=True, stdout=subprocess.PIPE,
                       stderr=subprocess.PIPE, timeout=180)
    with COMMAND_LOG.open('a') as f:
        f.write('$ ' + ' '.join(str(x) for x in args) + '\n')
        f.write(p.stdout + p.stderr + f'\nEXIT={p.returncode}\n')
    if ok and p.returncode:
        raise AssertionError(f'Failed: {args}\n{p.stdout}\n{p.stderr}')
    if not ok and p.returncode == 0:
        raise AssertionError(f'Unexpected success: {args}')
    return p.stdout

def check(name, condition=True):
    if not condition: raise AssertionError(name)
    CHECKS.append(name)
    print('PASS', name, flush=True)

def dfs(*a): return run(['hdfs', 'dfs', *a])

def test_hdfs(backend):
    prefix = ['hadoop', 'jar', 'build/lab1.jar', 'lab.HdfsCli'] if backend == 'java' else ['bash', 'scripts/hdfs_ops.sh']
    def op(*a, ok=True): return run(prefix + list(a), ok=ok)
    base = '/user/hadoop/lab1/' + backend
    local = OUT / backend
    local.mkdir()
    a, b, z = [local / n for n in ('alpha.txt', 'beta.txt', 'prefix.txt')]
    a.write_text('alpha\n'); b.write_text('beta\n'); z.write_text('zero\n')
    dest = base + '/sample.txt'
    op('upload', a, dest, 'overwrite')
    op('upload', b, dest, 'append')
    check(backend + ': upload existing append', dfs('-cat', dest) == 'alpha\nbeta\n')
    op('upload', a, dest, 'overwrite')
    check(backend + ': upload existing overwrite', dfs('-cat', dest) == 'alpha\n')
    op('add', dest, b, 'end')
    op('add', dest, z, 'begin')
    expected = 'zero\nalpha\nbeta\n'
    check(backend + ': prepend and append', op('cat', dest) == expected)
    download = local / 'downloads'; download.mkdir()
    (download / 'sample.txt').write_text('keep me\n')
    op('download', dest, download); op('download', dest, download)
    check(backend + ': download collision keeps original', (download / 'sample.txt').read_text() == 'keep me\n')
    check(backend + ': download chooses _1 and _2', all((download / f'sample_{n}.txt').read_text() == expected for n in (1, 2)))
    nested = base + '/nested/deep/new.txt'
    op('create', nested)
    check(backend + ': recursive file create', dfs('-stat', '%b', nested).strip() == '0')
    op('create', nested, ok=False)
    check(backend + ': create refuses existing file', dfs('-stat', '%b', nested).strip() == '0')
    stat = op('stat', nested)
    (local / 'metadata.txt').write_text(stat)
    check(backend + ': metadata path permission size and time', 'new.txt' in stat and ('created=' in stat or 'user.lab.createdAt=' in stat))
    listing = op('list', base)
    (local / 'recursive-list.txt').write_text(listing)
    check(backend + ': recursive listing', 'sample.txt' in listing and 'new.txt' in listing)
    op('mkdir', base + '/empty/a/b')
    op('rmdir', base + '/empty/a/b')
    check(backend + ': recursive mkdir and empty rmdir')
    op('rmdir', base + '/nested/deep', ok=False)
    check(backend + ': nonempty rmdir refusal preserves file', dfs('-stat', '%b', nested).strip() == '0')
    op('delete', base + '/nested', ok=False)
    check(backend + ': delete refuses directories')
    op('add', dest, local / 'missing.txt', 'begin', ok=False)
    check(backend + ': failed prepend preserves data', op('cat', dest) == expected)
    moved = base + '/moved/deep/final.txt'
    op('move', dest, moved)
    check(backend + ': move creates parents', op('cat', moved) == expected)
    op('move', moved, nested, ok=False)
    check(backend + ': move refuses existing target', op('cat', moved) == expected)
    op('delete', nested); op('rmdir', base + '/nested/deep')
    op('delete', moved)
    check(backend + ': delete file', 'final.txt' not in dfs('-ls', base + '/moved/deep'))

def job(name, local_input, remote_name=None):
    remote = '/user/hadoop/lab1/' + (remote_name or name)
    dfs('-mkdir', '-p', remote + '/input')
    dfs('-put', *sorted(local_input.iterdir()), remote + '/input')
    run(['hadoop', 'jar', 'build/lab1.jar', 'lab.LabJobs', name, remote + '/input', remote + '/output'])
    result = dfs('-cat', remote + '/output/part-r-00000')
    (OUT / ((remote_name or name) + '.tsv')).write_text(result)
    dfs('-test', '-e', remote + '/output/_SUCCESS')
    return result

def test_jobs():
    result = job('dedup', ROOT / 'data/dedup')
    expected = ['20170101\tx', '20170101\ty', '20170102\ty', '20170103\tx',
                '20170104\ty', '20170104\tz', '20170105\ty', '20170105\tz', '20170106\tx']
    check('MapReduce: 11 input rows merge to 9 unique rows', result.splitlines() == expected)
    result = job('sort', ROOT / 'data/sort')
    numbers = [1, 4, 5, 12, 16, 25, 33, 37, 39, 40, 45]
    check('MapReduce: all 11 sorted positions match', result.splitlines() == [f'{i}\t{x}' for i, x in enumerate(numbers, 1)])
    result = job('family', ROOT / 'data/family')
    rows = result.splitlines()
    expected = {(c, g) for c in ('Steven', 'Jone') for g in ('Alice', 'Jesse', 'Mary', 'Frank')}
    expected |= {(c, g) for c in ('Philip', 'Mark') for g in ('Alice', 'Jesse')}
    check('MapReduce: header and 12 grandparent relationships', rows[0] == 'grandchild\tgrandparent' and len(rows) == 13 and {tuple(r.split()) for r in rows[1:]} == expected)
    edge = OUT / 'sort-edge-input'; edge.mkdir()
    (edge / 'numbers.txt').write_text('-2147483648\n-2\n0\n-2\n2147483647\n0\n')
    result = job('sort', edge, 'sort-edge')
    numbers = [-2147483648, -2, -2, 0, 0, 2147483647]
    check('MapReduce: negative repeated and boundary integers', result.splitlines() == [f'{i}\t{x}' for i, x in enumerate(numbers, 1)])

started = time.time()
try:
    test_hdfs('java')
    test_hdfs('shell')
    test_jobs()
finally:
    (OUT / 'verification.json').write_text(json.dumps({'passed_checks': len(CHECKS), 'checks': CHECKS,
        'elapsed_seconds': round(time.time() - started, 2)}, indent=2))
print(f'ALL {len(CHECKS)} CHECKS PASSED', flush=True)
