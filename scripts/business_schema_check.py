"""Read-only prerequisite check on the isolated local database, never a migration.

Lists columns referenced by retained production mappers; does not infer types or
create financial tables. Exit 1 means schema prerequisites remain unsatisfied.
"""
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local

REQUIRED = {
    'account': {'amount', 'acct_type', 'obj_id', 'acct_id', 'status_cd', 'acct_name', 'b_id', 'obj_type', 'create_time'},
    'business_account': {'amount', 'operate', 'acct_type', 'obj_id', 'acct_id', 'acct_name', 'b_id', 'obj_type'},
    'account_detail': {'detail_type', 'amount', 'order_id', 'obj_id', 'detail_id', 'acct_id', 'rel_acct_id', 'remark', 'status_cd', 'b_id', 'obj_type', 'create_time'},
    'business_account_detail': {'detail_type', 'amount', 'operate', 'order_id', 'obj_id', 'detail_id', 'acct_id', 'rel_acct_id', 'remark', 'b_id', 'obj_type'},
    'contract': {'contract_parent_id', 'obj_name', 'obj_person_name', 'obj_person_id', 'start_user_id', 'b_id'},
    'contract_room': {'cr_id','b_id','contract_id','store_id','room_id','room_name','owner_id','owner_name','status_cd','create_time'},
}


def main():
    names = ','.join("'" + name + "'" for name in REQUIRED)
    raw = local.mysql_run("SELECT table_name,column_name FROM information_schema.columns "
                          "WHERE table_schema='TT' AND table_name IN (" + names + ")",
                          database='TT', capture=True)
    actual = {}
    for line in raw.splitlines():
        if not line.strip():
            continue
        table, column = line.split('\t')
        actual.setdefault(table, set()).add(column)
    result = {'endpoint': '127.0.0.1:13306', 'database': 'TT', 'readOnly': True,
              'missingTables': sorted(set(REQUIRED) - set(actual)),
              'missingColumns': {table: sorted(columns - actual[table]) for table, columns in REQUIRED.items()
                                 if table in actual and columns - actual[table]}}
    result['passed'] = not result['missingTables'] and not result['missingColumns']
    print(json.dumps(result, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError):
        raise SystemExit('Schema check could not reach the isolated database')
