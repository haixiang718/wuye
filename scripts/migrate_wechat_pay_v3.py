"""Reviewed, backed-up V3 order upgrade; default only inspects metadata and prints the plan.

Quiesce payment writers before --apply. MySQL DDL commits implicitly: each step is
independently validated and restartable. Existing orders default to V2; no order, fee,
receipt or ledger rows are deleted or updated. Endpoint remains 127.0.0.1:13306 only.
"""
import argparse
import json
from pathlib import Path
import migrate_payment_order as payment

base = payment.base
SOURCE = Path(__file__).with_name('migrations') / '2026-11-04-wechat-pay-v3.sql'
TABLES = ('payment_order', 'payment_order_item', 'payment_order_reservation')


def plan(database='TT'):
    base.validate_database(database)
    steps = base.statements(SOURCE)
    expected = ["ALTER TABLE payment_order ADD COLUMN provider_version varchar(4) NOT NULL DEFAULT 'V2'",
                'ALTER TABLE payment_order_item DROP PRIMARY KEY, ADD PRIMARY KEY (owe_id,order_id)']
    if steps != expected:
        raise base.MigrationError('Unexpected V3 migration SQL; review the migration source')
    schemas = {table: base.inspect(database, table) for table in TABLES}
    for table, (columns, _) in schemas.items():
        if not columns:
            raise base.MigrationError('Missing payment table; apply original payment-order migration first: ' + table)
        engine = base.query(database, "SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'")
        if engine.upper() != 'INNODB':
            raise base.MigrationError('Payment tables require InnoDB: ' + table)
    pending = []
    columns, indexes = schemas['payment_order']
    if indexes.get('PRIMARY') != ['order_id']:
        raise base.MigrationError('Unexpected payment_order primary key')
    if 'provider_version' in columns:
        base.check_column('payment_order', 'provider_version', columns['provider_version'],
                          ('varchar(4)', False, 'V2', ''))
    else:
        pending.append(steps[0])
    columns, indexes = schemas['payment_order_item']
    for column, size in [('owe_id', 30), ('order_id', 32)]:
        base.check_column('payment_order_item', column, columns.get(column),
                          ('varchar(' + str(size) + ')', False, None, ''))
    primary = indexes.get('PRIMARY')
    if primary == ['owe_id']:
        pending.append(steps[1])
    elif primary != ['owe_id', 'order_id']:
        raise base.MigrationError('Unexpected payment_order_item primary key; no automatic conversion')
    if schemas['payment_order_reservation'][1].get('PRIMARY') != ['community_id', 'fee_id']:
        raise base.MigrationError('Unexpected active fee reservation primary key')
    return pending


def apply(database='TT', backup_root=None):
    base.validate_database(database)
    with base.migration_lock():
        pending = plan(database)
        # Compare original columns so adding provider_version cannot hide existing-data changes.
        tables = TABLES + ('bill', 'bill_owe_fee', 'pay_fee', 'pay_fee_detail', 'fee_receipt', 'fee_receipt_detail')
        existing = {table: list(base.inspect(database, table)[0]) for table in tables}
        before = payment.snapshots(database, existing)
        backup = None
        if pending:
            backup = base.create_backup(database, backup_root or base.local.RUN / 'backups/wechat-pay-v3')
            folder = Path(backup['file']).parent
            (folder / 'applied.sql').write_text(';\n'.join(pending) + ';\n', encoding='utf-8')
            for statement in pending:
                base.query(database, statement)
        if plan(database):
            raise base.MigrationError('Incomplete V3 upgrade; inspect backup and rerun the checked plan')
        after = payment.snapshots(database, existing)
        if before != after:
            raise base.MigrationError('Existing payment/accounting data changed; inspect the backup')
        report = {'applied': len(pending), 'backup': backup, 'existingDataPreserved': True, 'passed': True}
        if backup:
            (Path(backup['file']).parent / 'migration.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(apply(), indent=2) if args.apply else ';\n'.join(plan()))
