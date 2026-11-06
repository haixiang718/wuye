"""Backup, repeatability, compatibility and preservation checks on isolated temporary schemas."""
from pathlib import Path
import re
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import local
import migrate_meter_import as migration

class MeterImportMigrationTest(unittest.TestCase):
    def setUp(self):
        self.database='audit_schema_'+uuid.uuid4().hex[:12]
        local.mysql_run('CREATE DATABASE `'+self.database+'` CHARACTER SET utf8mb4')
        self.folder=tempfile.TemporaryDirectory(dir=local.RUN)
        self.addCleanup(self.folder.cleanup)
        self.addCleanup(self.drop)
        self.query("CREATE TABLE contract(contract_id varchar(30) PRIMARY KEY,amount decimal(18,2)); INSERT INTO contract VALUES('legacy',123.45)")
    def query(self,sql):return local.mysql_run(sql,database=self.database,capture=True)
    def drop(self):
        assert re.fullmatch(r'audit_schema_[a-f0-9]{12}',self.database)
        local.mysql_run('DROP DATABASE `'+self.database+'`')
    def apply(self):return migration.apply(self.database,backup_root=Path(self.folder.name))
    def test_plan_is_read_only(self):
        self.assertEqual(1,len(migration.plan(self.database)))
        self.assertEqual('1',self.query('SELECT count(*) FROM information_schema.tables WHERE table_schema=DATABASE()'))
    def test_backup_preservation_and_repeatability(self):
        before=self.query('SELECT * FROM contract');result=self.apply()
        self.assertEqual(1,result['applied']);self.assertTrue(Path(result['backup']['file']).is_file())
        self.assertEqual(before,self.query('SELECT * FROM contract'))
        self.assertEqual('0',self.query('SELECT count(*) FROM contract_room'))
        self.assertEqual(0,self.apply()['applied'])
    def test_backup_failure_prevents_table_creation(self):
        with patch.object(migration.base,'create_backup',side_effect=RuntimeError('Injected failure')):
            with self.assertRaises(RuntimeError):self.apply()
        self.assertEqual(1,len(migration.plan(self.database)))
    def test_incompatible_existing_table_is_rejected(self):
        self.query('CREATE TABLE contract_room(cr_id varchar(8) PRIMARY KEY)')
        with self.assertRaises(migration.base.MigrationError):self.apply()
        self.assertEqual([],list(Path(self.folder.name).iterdir()))
    def test_wrong_status_default_is_rejected(self):
        self.apply();self.query("ALTER TABLE contract_room ALTER status_cd SET DEFAULT '1'")
        with self.assertRaises(migration.base.MigrationError):migration.plan(self.database)
    def test_other_databases_are_rejected(self):
        for database in ['mysql','production','hc_community','TT; DROP DATABASE TT']:
            with self.assertRaises(migration.base.MigrationError):migration.plan(database)

if __name__=='__main__':unittest.main()
