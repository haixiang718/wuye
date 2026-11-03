"""Backed-up additive vehicle mapper compatibility repair on local MySQL 127.0.0.1:13306."""
import argparse
import hashlib
import json
from pathlib import Path

import migrate_business_schema as base

SOURCE = Path(__file__).with_name('migrations') / '2026-11-03-owner-car-compat.sql'
TABLES = ('owner_car', 'business_owner_car')


def plan(database='TT'):
    return base.plan(database, source=SOURCE)


def snapshot(database, previous=None):
    result = {}
    for table in TABLES:
        columns = previous[table]['columns'] if previous else list(base.inspect(database, table)[0])
        fields = ','.join('`' + column + '`' for column in columns)
        rows = sorted(base.query(database, 'SELECT ' + fields + ' FROM `' + table + '`').splitlines())
        result[table] = {'columns': columns, 'rows': len(rows),
                         'sha256': hashlib.sha256('\n'.join(rows).encode('utf-8')).hexdigest()}
    return result


def apply(database='TT', backup_root=None):
    base.validate_database(database)
    with base.migration_lock():
        pending = plan(database)
        before = snapshot(database)
        backup = None
        if pending:
            backup = base.create_backup(database, backup_root or base.local.RUN / 'backups/owner-car')
            folder = Path(backup['file']).parent
            (folder / 'applied.sql').write_text(';\n'.join(pending) + ';\n', encoding='utf-8')
            for statement in pending:
                base.query(database, statement)
        if plan(database):
            raise base.MigrationError('Vehicle compatibility migration is incomplete; rerun after inspection')
        after = snapshot(database, before)
        if before != after:
            raise base.MigrationError('Existing vehicle data changed; inspect the backup before proceeding')
        report = {'database': database, 'endpoint': '127.0.0.1:13306', 'applied': len(pending),
                  'backup': backup, 'before': before, 'after': after, 'passed': True}
        if backup:
            (Path(backup['file']).parent / 'migration.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if args.apply:
        print(json.dumps(apply(), indent=2))
    else:
        print(';\n'.join(plan()) or '-- No changes required.')
