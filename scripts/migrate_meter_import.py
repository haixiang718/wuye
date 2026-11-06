"""Backed-up contract-room compatibility migration on isolated MySQL 127.0.0.1:13306."""
import argparse
import json
from pathlib import Path
import migrate_business_schema as base

SOURCE=Path(__file__).with_name('migrations')/'2026-11-02-meter-import-contract-room.sql'

def plan(database='TT'):
    return base.plan(database, source=SOURCE)

def apply(database='TT', backup_root=None):
    return base.apply(database,backup_root=backup_root,source=SOURCE)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    if args.apply:print(json.dumps(apply(),indent=2))
    else:print(';\n'.join(plan()) or '-- No changes required.')
