"""Index-only migration on empty disposable clones; no live financial writes."""
import sys
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import migrate_billing_indexes as migration


class BillingIndexMigrationTest(unittest.TestCase):
    def setUp(self):
        self.database='audit_schema_'+uuid.uuid4().hex[:12]
        migration.base.local.mysql_run('CREATE DATABASE `'+self.database+'` CHARACTER SET utf8mb4')
        self.addCleanup(lambda:migration.base.local.mysql_run('DROP DATABASE `'+self.database+'`'))
        for table in migration.TABLES:
            migration.base.query(self.database,'CREATE TABLE `'+table+'` LIKE TT.`'+table+'`')
        # The live schema may already be migrated; reset only these disposable clones.
        for table,name,_ in migration.INDEXES:
            if name in migration.base.inspect(self.database,table)[1]:
                migration.base.query(self.database,'ALTER TABLE `'+table+'` DROP INDEX `'+name+'`')
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)

    def test_additive_backup_repeat_and_equivalent_index(self):
        migration.base.query(self.database,'ALTER TABLE bill_owe_fee ADD INDEX existing_identity(owe_id,community_id,bill_id)')
        before=migration.snapshot(self.database)
        pending=migration.plan(self.database)
        self.assertFalse(any('idx_billing_owe_identity' in s for s in pending))
        result=migration.apply(self.database,Path(self.temp.name))
        self.assertGreater(result['applied'],0);self.assertTrue(Path(result['backup']['file']).is_file())
        self.assertEqual(before,migration.snapshot(self.database));self.assertEqual([],migration.plan(self.database))
        self.assertEqual(0,migration.apply(self.database,Path(self.temp.name))['applied'])

    def test_backup_failure_prevents_indexes(self):
        before=migration.plan(self.database)
        with patch.object(migration.base,'create_backup',side_effect=migration.base.MigrationError('synthetic failure')):
            with self.assertRaises(migration.base.MigrationError):migration.apply(self.database,Path(self.temp.name))
        self.assertEqual(before,migration.plan(self.database))

    def test_incompatible_named_index_and_unknown_database_rejected(self):
        migration.base.query(self.database,'ALTER TABLE bill_owe_fee ADD INDEX idx_billing_owe_identity(fee_id)')
        with self.assertRaises(migration.base.MigrationError):migration.plan(self.database)
        with self.assertRaises(migration.base.MigrationError):migration.plan('production')


if __name__=='__main__':unittest.main()
