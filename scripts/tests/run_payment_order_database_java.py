"""Run production payment SQL/transactions against synthetic rows in an exact random schema.

Reads TT table definitions only. Never copies rows or modifies TT. All temporary DDL and
fixtures live in audit_schema_<random>, which is dropped in finally.
"""
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local


def main():
    java, classpath, logging, output = compile_audit()
    repository = Path.home() / '.m2/repository'
    cp = os.pathsep.join([classpath, str(local.BACK / 'service-fee/target/classes'),
        str(repository / 'junit/junit/4.13.1/junit-4.13.1.jar'),
        str(repository / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources = [Path(__file__).with_name('PaymentOrderDatabaseTest.java'),
        local.BACK / 'java110-interface/src/main/java/com/java110/intf/fee/IFeePaymentOrderInnerServiceSMO.java',
        local.BACK / 'service-fee/src/main/java/com/java110/fee/dao/PaymentOrderRepository.java',
        local.BACK / 'service-fee/src/main/java/com/java110/fee/dao/impl/FeeDetailServiceDaoImpl.java',
        local.BACK / 'service-fee/src/main/java/com/java110/fee/dao/impl/FeeServiceDaoImpl.java',
        local.BACK / 'service-fee/src/main/java/com/java110/fee/smo/impl/FeePaymentOrderInnerServiceSMOImpl.java']
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-cp', cp, '-d', str(output)] + list(map(str, sources)), check=True)
    database = 'audit_schema_' + uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `' + database + '` CHARACTER SET utf8mb4')
    try:
        for table in ['bill', 'bill_owe_fee', 'pay_fee', 'pay_fee_config', 'owner_app_user',
                      'building_owner', 'small_wechat', 'pay_fee_detail', 'business_pay_fee_detail', 'fee_receipt', 'fee_receipt_detail']:
            local.mysql_run('CREATE TABLE `' + table + '` LIKE TT.`' + table + '`', database=database)
        # The production review migration is owned by the main repair task. Add only to this clone.
        if not local.mysql_run("SHOW COLUMNS FROM bill LIKE 'review_state'", database=database, capture=True):
            local.mysql_run("ALTER TABLE bill ADD COLUMN review_state varchar(12) NOT NULL DEFAULT 'DRAFT'", database=database)
        migration = Path(__file__).resolve().parents[1] / 'migrations/2026-11-02-payment-order.sql'
        text = re.sub(r'--[^\n]*', '', migration.read_text(encoding='utf-8'))
        for statement in text.split(';'):
            if not statement.strip():
                continue
            alter = re.search(r'ALTER TABLE (\w+) ADD COLUMN (\w+)', statement, re.I)
            if alter and local.mysql_run("SHOW COLUMNS FROM `" + alter[1] + "` LIKE '" + alter[2] + "'",
                                         database=database, capture=True):
                continue
            local.mysql_run(statement, database=database)
        mapper = local.BACK / 'java110-db/src/main/resources/mapper'
        return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging),
            '-Dschema.audit=' + database, '-Dschema.mapper.root=' + str(mapper), '-cp', cp,
            'org.junit.runner.JUnitCore', 'PaymentOrderDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}', database):
            raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE `' + database + '`')


if __name__ == '__main__':
    raise SystemExit(main())
