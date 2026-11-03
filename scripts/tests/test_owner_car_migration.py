"""Real database compatibility and preservation checks using disposable local schemas."""
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local
import migrate_owner_car as migration


class OwnerCarMigrationTest(unittest.TestCase):
    def setUp(self):
        self.database = 'audit_schema_' + uuid.uuid4().hex[:12]
        local.mysql_run('CREATE DATABASE `' + self.database + '` CHARACTER SET utf8mb4')
        self.addCleanup(self.drop)
        self.folder = tempfile.TemporaryDirectory(dir=local.RUN)
        self.addCleanup(self.folder.cleanup)
        for table in migration.TABLES:
            self.query('CREATE TABLE `' + table + '` (car_id varchar(30),car_num varchar(12),car_type varchar(4),remark varchar(200));'
                       "INSERT INTO `" + table + "` VALUES('legacy','TEST-001','9901','keep existing data')")

    def query(self, sql):
        return local.mysql_run(sql, database=self.database, capture=True)

    def drop(self):
        assert re.fullmatch(r'audit_schema_[a-f0-9]{12}', self.database)
        local.mysql_run('DROP DATABASE `' + self.database + '`')

    def apply(self):
        return migration.apply(self.database, backup_root=Path(self.folder.name))

    def test_plan_is_read_only(self):
        before = migration.snapshot(self.database)
        self.assertEqual(4, len(migration.plan(self.database)))
        self.assertEqual(before, migration.snapshot(self.database))
        self.assertNotIn('car_type_cd', migration.base.inspect(self.database, 'owner_car')[0])

    def test_backup_legacy_data_preservation_and_repeatability(self):
        before = migration.snapshot(self.database)
        report = self.apply()
        self.assertEqual(4, report['applied'])
        self.assertTrue(Path(report['backup']['file']).is_file())
        self.assertEqual(before, migration.snapshot(self.database, before))
        self.assertEqual('9901\t1001\tNULL', self.query('SELECT car_type,car_type_cd,member_id FROM owner_car'))
        self.query("UPDATE owner_car SET car_type_cd='1002',member_id='existing-member'")
        self.assertEqual(0, self.apply()['applied'])
        self.assertEqual('1002\texisting-member', self.query('SELECT car_type_cd,member_id FROM owner_car'))

    def test_backup_failure_prevents_all_ddl(self):
        with patch.object(migration.base, 'create_backup', side_effect=RuntimeError('Injected backup failure')):
            with self.assertRaises(RuntimeError):
                self.apply()
        self.assertEqual(4, len(migration.plan(self.database)))

    def test_incompatible_existing_column_blocks_entire_migration(self):
        self.query('ALTER TABLE business_owner_car ADD member_id int')
        with self.assertRaises(migration.base.MigrationError):
            self.apply()
        self.assertNotIn('car_type_cd', migration.base.inspect(self.database, 'owner_car')[0])
        self.assertEqual([], list(Path(self.folder.name).iterdir()))

    def test_unrelated_databases_are_rejected(self):
        for database in ['mysql', 'production', 'hc_community', 'TT; DROP DATABASE TT']:
            with self.assertRaises(migration.base.MigrationError):
                migration.plan(database)


if __name__ == '__main__':
    unittest.main()
