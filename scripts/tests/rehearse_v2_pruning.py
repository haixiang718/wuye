"""Restore V1 backups to owned random clones, retire exact V2 tables, then restore them.

Never changes TT or hc_community. Reports contain counts and checksums only.
"""
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_v2_pruning as m


def run():
    index = json.loads((m.ops.local.RUN/'v2-pruning/v1-baseline-backups.json').read_text(encoding='utf-8'))
    results = []
    report_path = m.ROOT/'docs/validation/2026-11-06-v2-database.json'
    report = {'version': 1, 'phase': 'isolated-rehearsal', 'startedAtUtc': m.ops.now(),
              'sourceModified': False, 'results': results}
    m.ops.write_json(report_path, report)
    for item in index['backups']:
        folder = Path(item['directory'])
        metadata = m.ops.verify_backup(folder)
        if not metadata['restoreDrillSupported']:
            raise ValueError('Backup contains unsupported executable objects')
        role = metadata['database']
        print('Restoring '+role+' backup to a newly owned isolated schema', flush=True)
        entry = {'source': role, 'sourceBackupSha256': metadata['sha256'],
                 'passed': False, 'targetRemoved': False}
        results.append(entry)
        with m.isolated_schema(role) as target:
            entry['target'] = target
            with m.ops.client_command() as (command, env), (folder/'database.sql').open('rb') as data:
                loaded = subprocess.run(command+[target], stdin=data, stdout=subprocess.DEVNULL,
                                        stderr=subprocess.PIPE, env=env, timeout=1800)
            if loaded.returncode:
                raise ValueError('Full isolated backup import failed')
            before = m.snapshot(target)
            if {name: state['rows'] for name, state in before.items()} != metadata['tableRows']:
                raise ValueError('Clone rows differ from verified source backup')
            entry['restoredTables'] = len(before)
            entry['restoredRows'] = sum(state['rows'] for state in before.values())
            print(role+': verified full backup import; rehearsing retirement', flush=True)
            changed = m.apply(target, role, m.ops.local.RUN/'backups/v2-pruning-rehearsal')
            if not changed['passed']:
                raise ValueError('Isolated retirement did not pass')
            entry['removedTables'] = changed['removedTables']
            entry['removedCount'] = changed['removedCount']
            entry['configCount'] = changed['configCount']
            entry['preservedTables'] = changed['preservedTables']
            entry['preservedDataEqual'] = changed['preservedDataEqual']
            entry['privateJournal'] = changed['directory']
            entry['forwardPassed'] = True
            if m.plan(target, role)['tables'] or m.plan(target, role)['configChanges']:
                raise ValueError('Completed retirement was not idempotent')
            print(role+': retirement verified; rehearsing exact inverse', flush=True)
            reverted = m.rollback(target, changed['directory'], role)
            entry['rollbackPassed'] = reverted['passed']
            entry['allOriginalContentEqual'] = before == m.snapshot(target)
            entry['passed'] = entry['rollbackPassed'] and entry['allOriginalContentEqual']
            if not entry['passed']:
                raise ValueError('Isolated inverse did not reproduce all original content')
        entry['targetRemoved'] = True
        m.ops.write_json(report_path, report)
        print(role+': forward and inverse passed; clone removed', flush=True)
    report['passed'] = all(entry['passed'] and entry['targetRemoved'] for entry in results)
    report['finishedAtUtc'] = m.ops.now()
    m.ops.write_json(report_path, report)
    print(json.dumps({'passed': report['passed'], 'schemas': len(results),
                      'retiredTables': sum(entry['removedCount'] for entry in results),
                      'sourceModified': False}))


if __name__ == '__main__':
    run()
