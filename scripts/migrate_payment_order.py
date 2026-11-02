"""Backed-up, additive payment migration for loopback TT only; default is read-only."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import migrate_business_schema as base

SOURCE = Path(__file__).with_name('migrations') / '2026-11-02-payment-order.sql'
TABLES = {'payment_order', 'payment_order_item', 'payment_order_reservation'}
LEGACY = ['bill', 'fee_receipt', 'contract', 'account', 'business_account', 'account_detail', 'business_account_detail']


def definition(text):
    match = re.match(r'(bigint|longtext)\b', text, re.I)
    if not match:
        return base.column_definition(text)
    return match[1].lower(), 'NOT NULL' not in text.upper(), None, ''


def normalized(actual):
    kind, nullable, default, extra = actual
    return re.sub(r'^bigint\(\d+\)$', 'bigint', kind), nullable, default, extra


def indexes(database, table):
    raw = base.query(database, "SELECT index_name,non_unique,column_name FROM information_schema.statistics "
        "WHERE table_schema=DATABASE() AND table_name='" + table + "' ORDER BY index_name,seq_in_index")
    result = {}
    for line in raw.splitlines():
        name, non_unique, column = line.split('\t')
        value = result.setdefault(name, {'unique': non_unique == '0', 'columns': []})
        value['columns'].append(column)
    return result


def plan(database='TT'):
    base.validate_database(database)
    pending = []
    text = re.sub(r'--[^\n]*', '', SOURCE.read_text(encoding='utf-8'))
    for statement in (part.strip() for part in text.split(';') if part.strip()):
        create = re.match(r'CREATE TABLE IF NOT EXISTS (\w+)', statement, re.I)
        alter = re.fullmatch(r'ALTER TABLE (fee_receipt) ADD COLUMN (pay_obj_id|pay_obj_name) (.+)', statement, re.I)
        if create and create[1] in TABLES:
            table = create[1]
            columns, _ = base.inspect(database, table)
            if not columns:
                pending.append(statement)
                continue
            if base.query(database, "SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='" + table + "'").upper() != 'INNODB':
                raise base.MigrationError('Payment tables require InnoDB: ' + table)
            expected = {}
            for line in statement.splitlines()[1:]:
                column = re.match(r'\s*(\w+) ((?:varchar\(|bigint\b|longtext\b|timestamp\b).+)', line, re.I)
                if column:
                    expected[column[1]] = definition(column[2].rstrip(','))
            if set(columns) != set(expected):
                raise base.MigrationError('Incompatible payment columns: ' + table)
            for name, value in expected.items():
                base.check_column(table, name, normalized(columns[name]), value)
            wanted = {}
            for line in statement.splitlines():
                key = re.match(r'\s*(PRIMARY KEY|UNIQUE KEY (\w+)|KEY (\w+)) \(([^)]+)\)', line, re.I)
                if key:
                    name = key[2] or key[3] or 'PRIMARY'
                    wanted[name] = {'unique': key[3] is None, 'columns': key[4].split(',')}
            if indexes(database, table) != wanted:
                raise base.MigrationError('Incompatible payment indexes: ' + table)
        elif alter:
            table, column, value = alter.groups()
            columns, _ = base.inspect(database, table)
            if not columns:
                raise base.MigrationError('Missing legacy table: ' + table)
            if column in columns:
                base.check_column(table, column, columns[column], definition(value))
            else:
                pending.append(statement)
        else:
            raise base.MigrationError('Unexpected payment migration SQL')
    return pending


def snapshots(database, existing=None):
    if existing is None:
        existing = {table: list(base.inspect(database, table)[0]) for table in LEGACY}
    result = {}
    for table, columns in existing.items():
        if not columns:
            continue
        fields = ','.join('`' + column + '`' for column in columns)
        primary = base.inspect(database, table)[1].get('PRIMARY') or columns
        order = ','.join('`' + column + '`' for column in primary)
        raw = base.query(database, 'SELECT ' + fields + ' FROM `' + table + '` ORDER BY ' + order)
        result[table] = {'columns': columns, 'sha256': hashlib.sha256(raw.encode('utf-8')).hexdigest(),
                         'rows': int(base.query(database, 'SELECT count(*) FROM `' + table + '`'))}
    return result


def apply(database='TT', backup_root=None):
    base.validate_database(database)
    with base.migration_lock():
        pending = plan(database)
        before = snapshots(database)
        backup = None
        if pending:
            backup = base.create_backup(database, backup_root or base.local.RUN / 'backups/payment-order')
            folder = Path(backup['file']).parent
            (folder / 'applied.sql').write_text(';\n'.join(pending) + ';\n', encoding='utf-8')
            for statement in pending:
                base.query(database, statement)
        if plan(database):
            raise base.MigrationError('Incomplete payment migration')
        after = snapshots(database, {table: value['columns'] for table, value in before.items()})
        if before != after:
            raise base.MigrationError('Legacy data changed; inspect the backup before proceeding')
        report = {'applied': len(pending), 'backup': backup, 'legacyDataPreserved': True, 'passed': True}
        if backup:
            (Path(backup['file']).parent / 'migration.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    print(json.dumps(apply(), indent=2) if args.apply else ';\n'.join(plan()))
