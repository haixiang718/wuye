"""Backed-up, additive billing lookup indexes on the project's local MySQL only."""
import argparse
import json
from pathlib import Path
import migrate_business_schema as base

INDEXES = (
    ('bill','idx_billing_identity',('bill_id','community_id')),
    ('bill_owe_fee','idx_billing_owe_identity',('owe_id','community_id','bill_id')),
    ('bill_owe_fee','idx_billing_owe_bill',('community_id','bill_id','state','create_time')),
    ('bill_owe_fee','idx_billing_owe_owner',('community_id','owner_id','state','create_time')),
    ('owner_app_user','idx_billing_owner_identity',('user_id','community_id','state','status_cd','member_id')),
    ('pay_fee','idx_billing_payer',('payer_obj_id','community_id','status_cd')),
    ('t_dict','idx_billing_dictionary',('table_name','table_columns','status_cd')),
)
TABLES=tuple(dict.fromkeys(item[0] for item in INDEXES))


def plan(database='TT'):
    base.validate_database(database)
    pending=[]
    for table,name,fields in INDEXES:
        columns,indexes=base.inspect(database,table)
        if any(field not in columns for field in fields):
            raise base.MigrationError('Required billing table/columns missing: '+table)
        if name in indexes and indexes[name]!=list(fields):
            raise base.MigrationError('Incompatible billing index: '+table+'.'+name)
        # Avoid duplicating an existing usable leftmost index prefix.
        if any(value[:len(fields)]==list(fields) for value in indexes.values()):continue
        pending.append('ALTER TABLE `'+table+'` ADD INDEX `'+name+'` ('+','.join('`'+field+'`' for field in fields)+')')
    return pending


def snapshot(database):
    base.validate_database(database)
    return {table:{'rows':int(base.query(database,'SELECT COUNT(*) FROM `'+table+'`')),
                   'checksum':base.query(database,'CHECKSUM TABLE `'+table+'` EXTENDED').split('\t')[-1]}
            for table in TABLES}


def apply(database='TT',backup_root=None):
    base.validate_database(database)
    with base.migration_lock():
        pending=plan(database);before=snapshot(database);backup=None
        if pending:
            backup=base.create_backup(database,backup_root or base.local.RUN/'backups/billing-indexes')
            folder=Path(backup['file']).parent
            (folder/'applied.sql').write_text(';\n'.join(pending)+';\n',encoding='utf-8')
            for statement in pending:base.query(database,statement)
        if plan(database):raise base.MigrationError('Billing index migration incomplete; inspect then rerun')
        after=snapshot(database)
        if before!=after:raise base.MigrationError('Data changed during index migration; inspect backup and concurrent activity')
        report={'database':database,'applied':len(pending),'backup':backup,'before':before,'after':after,'passed':True}
        if backup:(Path(backup['file']).parent/'migration.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    print(json.dumps(apply(),indent=2) if args.apply else ';\n'.join(plan()) or '-- No changes required.')
