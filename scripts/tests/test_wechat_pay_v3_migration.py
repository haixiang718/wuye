"""V3 schema guards plus a backed-up upgrade of a random, synthetic-only schema."""
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_wechat_pay_v3 as migration


class MigrationPlanTest(unittest.TestCase):
    def schemas(self, provider=None, primary=None):
        orders = {'order_id': ('varchar(32)', False, None, '')}
        if provider is not None:
            orders['provider_version'] = provider
        return {'payment_order': (orders, {'PRIMARY': ['order_id']}),
                'payment_order_item': ({'owe_id': ('varchar(30)', False, None, ''),
                    'order_id': ('varchar(32)', False, None, '')}, {'PRIMARY': primary or ['owe_id']}),
                'payment_order_reservation': ({'fee_id': ('varchar(30)', False, None, '')},
                    {'PRIMARY': ['community_id', 'fee_id']})}

    def plan(self, schemas):
        with patch.object(migration.base, 'inspect', side_effect=lambda db, table: schemas[table]), \
             patch.object(migration.base, 'query', return_value='InnoDB'):
            return migration.plan('audit_schema_012345abcdef')

    def test_old_schema_requires_both_steps(self):
        self.assertEqual(2, len(self.plan(self.schemas())))

    def test_upgraded_schema_is_idempotent(self):
        self.assertEqual([], self.plan(self.schemas(('varchar(4)', False, 'V2', ''), ['owe_id', 'order_id'])))

    def test_partial_upgrade_requires_only_primary_key(self):
        self.assertEqual(1, len(self.plan(self.schemas(('varchar(4)', False, 'V2', '')))))

    def test_incompatible_provider_column_fails_closed(self):
        with self.assertRaises(migration.base.MigrationError):
            self.plan(self.schemas(('varchar(4)', True, 'V2', '')))

    def test_unexpected_primary_key_fails_closed(self):
        with self.assertRaises(migration.base.MigrationError):
            self.plan(self.schemas(primary=['order_id']))


class IsolatedMigrationTest(unittest.TestCase):
    def test_old_order_history_survives_backed_up_idempotent_upgrade(self):
        local = migration.base.local
        database = 'audit_schema_' + uuid.uuid4().hex[:12]
        local.mysql_run('CREATE DATABASE `' + database + '` CHARACTER SET utf8mb4')
        try:
            for statement in migration.base.statements(migration.payment.SOURCE):
                if not statement.startswith('CREATE TABLE'):
                    continue
                statement = statement.replace("  provider_version varchar(4) NOT NULL DEFAULT 'V2',", '')
                statement = statement.replace('PRIMARY KEY (owe_id,order_id)', 'PRIMARY KEY (owe_id)')
                local.mysql_run(statement, database=database)
            local.mysql_run("INSERT INTO payment_order(order_id,community_id,user_id,app_id,mch_id,order_type,total_fee,payload) "
                            "VALUES('synthetic-order','community','user','app','merchant','SINGLE',100,'[]')", database=database)
            local.mysql_run("INSERT INTO payment_order_item VALUES('synthetic-owe','synthetic-order')", database=database)
            local.mysql_run("INSERT INTO payment_order_reservation VALUES('community','fee','synthetic-order')", database=database)
            self.assertEqual(2, len(migration.plan(database)))
            with tempfile.TemporaryDirectory() as folder:
                report = migration.apply(database, Path(folder))
                self.assertEqual(2, report['applied'])
                self.assertTrue(report['existingDataPreserved'])
                self.assertTrue(Path(report['backup']['file']).is_file())
                self.assertEqual(0, migration.apply(database, Path(folder))['applied'])
            self.assertEqual('V2', migration.base.query(database, 'SELECT provider_version FROM payment_order'))
            local.mysql_run("INSERT INTO payment_order_item VALUES('synthetic-owe','retry-order')", database=database)
            self.assertEqual('2', migration.base.query(database, 'SELECT count(*) FROM payment_order_item'))
            self.assertEqual('synthetic-order', migration.base.query(database, 'SELECT order_id FROM payment_order_reservation'))
        finally:
            if not re.fullmatch(r'audit_schema_[a-f0-9]{12}', database):
                raise RuntimeError('Unsafe audit cleanup')
            local.mysql_run('DROP DATABASE `' + database + '`')


if __name__ == '__main__':
    unittest.main()
