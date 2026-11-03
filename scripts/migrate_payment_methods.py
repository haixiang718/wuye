"""Backed-up, additive payment-method dictionary repair on local MySQL 127.0.0.1:13306."""
import argparse
import hashlib
import json
from pathlib import Path

import migrate_business_schema as base


# Codes 5/6 are reserved by PayFeePreListener for actual online payments.
# The admin collection forms exclude those codes and leave the choice unselected.
METHODS = (
    ('1', '现金', '人工登记已收到的现金'),
    ('2', '微信（线下已收款）', '人工登记已核实的线下微信收款，不调用在线支付接口'),
    ('3', '支付宝（线下已收款）', '人工登记已核实的线下支付宝收款，不调用在线支付接口'),
    ('4', '银行卡/POS（线下已收款）', '人工登记已核实的银行转账或POS收款'),
    ('5', '微信小程序支付', '保留给实际小程序支付回调登记，后台不可手动选择'),
    ('6', '微信公众号支付', '保留给实际公众号支付回调登记，后台不可手动选择'),
)
SCOPE = "table_name='pay_fee_detail' AND table_columns='prime_rate'"


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def plan(database='TT'):
    base.validate_database(database)
    columns, _ = base.inspect(database, 't_dict')
    required = {'id', 'status_cd', 'name', 'description', 'create_time', 'table_name', 'table_columns'}
    if not required.issubset(columns):
        raise base.MigrationError('t_dict does not have the required legacy dictionary columns')
    engine = base.query(database, "SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='t_dict'")
    if engine.upper() != 'INNODB':
        raise base.MigrationError('t_dict must use InnoDB for an atomic dictionary repair')
    existing = {}
    for line in base.query(database, 'SELECT status_cd,count(*) FROM t_dict WHERE ' + SCOPE + ' GROUP BY status_cd').splitlines():
        code, count = line.split('\t')
        if int(count) != 1:
            raise base.MigrationError('Duplicate payment method code: ' + code)
        existing[code] = True
    pending = []
    for code, name, description in METHODS:
        if code in existing:
            continue
        pending.append('INSERT INTO t_dict(status_cd,name,description,table_name,table_columns) SELECT ' +
                       ','.join(literal(value) for value in (code, name, description, 'pay_fee_detail', 'prime_rate')) +
                       ' WHERE NOT EXISTS (SELECT 1 FROM t_dict WHERE ' + SCOPE + ' AND status_cd=' + literal(code) + ')')
    return pending


def apply(database='TT', backup_root=None):
    base.validate_database(database)
    with base.migration_lock():
        pending = plan(database)
        before = base.query(database, 'SELECT * FROM t_dict ORDER BY id')
        backup = None
        if pending:
            backup = base.create_backup(database, backup_root or base.local.RUN / 'backups/payment-methods')
            sql = 'START TRANSACTION;\n' + ';\n'.join(pending) + ';\nCOMMIT;\n'
            (Path(backup['file']).parent / 'applied.sql').write_text(sql, encoding='utf-8')
            base.query(database, sql)
        if plan(database):
            raise base.MigrationError('Payment methods remain incomplete')
        after = base.query(database, 'SELECT * FROM t_dict ORDER BY id')
        if not set(before.splitlines()).issubset(set(after.splitlines())):
            raise base.MigrationError('Existing dictionary rows changed; inspect the backup')
        report = {'database': database, 'endpoint': '127.0.0.1:13306', 'applied': len(pending), 'backup': backup,
                  'existingDictionaryRowsPreserved': True,
                  'dictionaryBeforeSha256': hashlib.sha256(before.encode('utf-8')).hexdigest(), 'passed': True}
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
        pending = plan()
        print(';\n'.join(pending) + (';' if pending else '-- No changes required.'))
