"""Back up isolated TT, then add bill publication columns without changing existing row values."""
import argparse
import hashlib
import json
from pathlib import Path
import migrate_business_schema as migration

SOURCE = Path(__file__).with_name('migrations') / '2026-11-02-bill-review.sql'


def snapshot(database, columns):
    fields = ','.join('`'+column+'`' for column in columns)
    raw = migration.query(database, 'SELECT '+fields+' FROM bill ORDER BY bill_id')
    return {'rows':int(migration.query(database,'SELECT count(*) FROM bill')),
            'sha256':hashlib.sha256(raw.encode('utf-8')).hexdigest()}


def apply(database='TT'):
    previous = migration.SOURCE
    migration.SOURCE = SOURCE
    try:
        columns = list(migration.inspect(database,'bill')[0])
        before = snapshot(database,columns)
        result = migration.apply(database,backup_root=migration.local.RUN/'backups/bill-review')
        after = snapshot(database,columns)
        if before != after:
            raise migration.MigrationError('Existing bill data changed during additive migration')
        return {'applied':result['applied'],'existingBillDataPreserved':True,'backup':result['backup']}
    finally:
        migration.SOURCE = previous


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    args = parser.parse_args()
    if args.apply:
        print(json.dumps(apply(),indent=2))
    else:
        migration.SOURCE = SOURCE
        print(';\n'.join(migration.plan())+';')
