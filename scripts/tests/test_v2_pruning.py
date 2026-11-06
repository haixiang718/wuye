"""Retirement safety against newly created synthetic MySQL schemas only."""
import json
from contextlib import contextmanager, redirect_stdout
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_v2_pruning as m


class RetirementTests(unittest.TestCase):
    def setUp(self):
        self.context = m.isolated_schema('TT')
        self.db = self.context.__enter__()
        self.addCleanup(self.context.__exit__, None, None, None)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.query('CREATE TABLE application_key(id int PRIMARY KEY, payload varchar(200)) ENGINE=InnoDB')
        self.query("INSERT INTO application_key VALUES(1,'synthetic door credential')")
        self.query('CREATE TABLE bill(id int PRIMARY KEY, amount decimal(10,2)) ENGINE=InnoDB')
        self.query('INSERT INTO bill VALUES(1,0.01)')
        self.query('CREATE TABLE machine(id int PRIMARY KEY) ENGINE=InnoDB')
        self.query('INSERT INTO machine VALUES(1)')
        self.query('CREATE TABLE c_service(id int PRIMARY KEY,service_id varchar(30),service_code varchar(80),business_type_cd varchar(30),status_cd varchar(12)) ENGINE=InnoDB')
        self.query("INSERT INTO c_service VALUES(1,'door','applicationKey.listApplicationKeys','retired','0'),(2,'parking','machineTranslate.machineHeartbeart','shared','0'),(3,'fee','fee.listFees','fee','0')")
        self.query('CREATE TABLE c_route(id int PRIMARY KEY,service_id varchar(30),status_cd varchar(12)) ENGINE=InnoDB')
        self.query("INSERT INTO c_route VALUES(1,'door','0'),(2,'parking','0')")
        self.query('CREATE TABLE task_template(template_id varchar(30),class_bean varchar(100),status_cd varchar(12)) ENGINE=InnoDB')
        self.query("INSERT INTO task_template VALUES('retired','generatorInspectionTaskTemplate','0'),('fee','generateBillProTemplate','0')")
        self.query('CREATE TABLE task(task_id varchar(30),template_id varchar(30),status_cd varchar(12)) ENGINE=InnoDB')
        self.query("INSERT INTO task VALUES('retired-task','retired','0'),('fee-task','fee','0')")

    def query(self, sql):
        return m.ops.mysql_query(sql, self.db)

    def apply(self):
        return m.apply(self.db, 'TT', Path(self.temp.name))

    def test_plan_does_not_change_data_and_parking_stays_outside_retirement(self):
        before = m.snapshot(self.db)
        proposed = m.plan(self.db, 'TT')
        self.assertEqual(['application_key'], proposed['tables'])
        self.assertEqual(before, m.snapshot(self.db))
        self.assertEqual(4, len(proposed['configChanges']))
        self.assertNotIn('machine', proposed['tables'])

    def test_drop_exact_allowlist_and_restore_original_data_and_configuration(self):
        before = m.snapshot(self.db)
        result = self.apply()
        self.assertTrue(result['passed'])
        self.assertNotIn('application_key', m.table_names(self.db))
        self.assertEqual('0', self.query("SELECT status_cd FROM c_service WHERE service_id='parking'"))
        self.assertEqual('0.01', self.query('SELECT amount FROM bill'))
        self.assertEqual('1', self.query("SELECT status_cd FROM task WHERE task_id='retired-task'"))
        self.assertEqual([], m.plan(self.db, 'TT')['tables'])
        self.assertEqual([], m.plan(self.db, 'TT')['configChanges'])
        self.assertEqual(0, self.apply()['removedCount'])
        m.rollback(self.db, result['directory'], 'TT')
        self.assertEqual(before, m.snapshot(self.db))

    def test_backup_failure_keeps_tables_and_config_intact(self):
        before = m.snapshot(self.db)
        with patch.object(m, 'backup', side_effect=RuntimeError('synthetic backup failure')):
            with self.assertRaisesRegex(RuntimeError, 'backup'):
                self.apply()
        self.assertEqual(before, m.snapshot(self.db))

    def test_unknown_schema_role_and_tampered_allowlist_are_rejected(self):
        for db, role in [('production', None), ('TT', 'hc_community'), ('ops_restore_123456789abc', None)]:
            with self.assertRaises((ValueError, m.ops.OpsError)):
                m.plan(db, role)
        with patch.object(m, 'manifest', return_value={'schemas': {'TT': ['bill']}, 'config': {}}):
            with self.assertRaisesRegex(ValueError, 'allowlist'):
                m.plan(self.db, 'TT')

    def test_foreign_key_and_executable_objects_block_before_backup(self):
        self.query('CREATE TABLE child(id int PRIMARY KEY, ref int, FOREIGN KEY(ref) REFERENCES application_key(id)) ENGINE=InnoDB')
        with patch.object(m, 'backup') as backup:
            with self.assertRaisesRegex(ValueError, 'foreign'):
                self.apply()
            backup.assert_not_called()
        self.query('DROP TABLE child')
        self.query('CREATE VIEW retired_view AS SELECT * FROM application_key')
        with self.assertRaisesRegex(ValueError, 'view'):
            self.apply()

    def test_new_unreviewed_dynamic_sql_reference_blocks(self):
        self.query('CREATE TABLE c_service_sql(id int PRIMARY KEY,service_code varchar(60),status_cd varchar(2),`sql` text,proc text,java_script text,template text) ENGINE=InnoDB')
        self.query("INSERT INTO c_service_sql VALUES(1,'owner.query','0','SELECT * FROM application_key','','','')")
        with self.assertRaisesRegex(ValueError, 'dynamic SQL'):
            self.apply()
        self.assertIn('application_key', m.table_names(self.db))

    def test_partial_failure_journal_and_inverse_only_include_observed_changes(self):
        self.query('CREATE TABLE complaint(id int PRIMARY KEY) ENGINE=InnoDB')
        self.query('INSERT INTO complaint VALUES(1)')
        before = m.snapshot(self.db)
        real = m.ops.mysql_query
        def fail_second(sql, database=None):
            if sql.startswith('SET SESSION lock_wait_timeout') and '`complaint`' in sql:
                raise m.ops.OpsError('synthetic second-table failure')
            return real(sql, database)
        with patch.object(m.ops, 'mysql_query', side_effect=fail_second):
            with self.assertRaisesRegex(m.ops.OpsError, 'second-table'):
                self.apply()
        folder = next(Path(self.temp.name).iterdir())
        report = json.loads((folder/'result.json').read_text())
        self.assertEqual(['application_key'], report['removedTables'])
        self.assertTrue(report['needsSchemaInspection'])
        self.assertEqual('complaint', report['inProgress'])
        self.assertNotIn('CREATE TABLE `complaint`', (folder/'rollback.sql').read_text())
        with self.assertRaisesRegex(ValueError, 'inspection'):
            m.rollback(self.db, folder, 'TT')
        # Explicit observation after the synthetic client is finished; this is not automatic timeout handling.
        report['needsSchemaInspection'] = False
        report['inProgress'] = None
        m.ops.write_json(folder/'result.json', report)
        m.rollback(self.db, folder, 'TT')
        self.assertEqual(before, m.snapshot(self.db))

    def test_lost_drop_reply_is_detected_and_restore_does_not_overwrite_existing_table(self):
        real = m.ops.mysql_query
        def lost_reply(sql, database=None):
            result = real(sql, database)
            if sql.startswith('SET SESSION lock_wait_timeout'):
                raise m.ops.OpsError('synthetic lost reply')
            return result
        with patch.object(m.ops, 'mysql_query', side_effect=lost_reply):
            with self.assertRaisesRegex(m.ops.OpsError, 'lost reply'):
                self.apply()
        folder = next(Path(self.temp.name).iterdir())
        report = json.loads((folder/'result.json').read_text())
        self.assertEqual(['application_key'], report['removedTables'])
        self.assertFalse(report['needsSchemaInspection'])
        self.query('CREATE TABLE application_key(id int PRIMARY KEY) ENGINE=InnoDB')
        self.query('INSERT INTO application_key VALUES(9)')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            m.rollback(self.db, folder, 'TT')
        self.assertEqual('9', self.query('SELECT id FROM application_key'))

    def test_wrong_clone_ownership_and_pending_payment_refuse(self):
        with patch.object(m, 'OWNED_CLONES', {}):
            with self.assertRaisesRegex(ValueError, 'owned'):
                self.apply()
        self.query('CREATE TABLE payment_order(state varchar(20)) ENGINE=InnoDB')
        self.query("INSERT INTO payment_order VALUES('CREATED')")
        with self.assertRaisesRegex(ValueError, 'payment'):
            self.apply()

    def test_source_guard_refuses_running_writers_and_source_sql_dependencies(self):
        with patch.object(m.ops.local, 'listening', return_value=True):
            with self.assertRaisesRegex(ValueError, 'writers'):
                m.assert_writers_stopped('TT')
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)/'service-fee/src/main/resources/mapper.xml'
            path.parent.mkdir(parents=True)
            path.write_text('<select>SELECT * FROM application_key</select>')
            self.assertEqual(['service-fee/src/main/resources/mapper.xml'], m.source_references(['application_key'], Path(root)))

    def test_unplanned_configuration_change_is_detected_and_blocks_automatic_rollback(self):
        real = m.ops.mysql_query
        def modify_other_row(sql, database=None):
            result = real(sql, database)
            if sql.startswith('SET SESSION lock_wait_timeout'):
                real("UPDATE c_service SET status_cd='1' WHERE service_id='fee'", self.db)
            return result
        with patch.object(m.ops, 'mysql_query', side_effect=modify_other_row):
            with self.assertRaisesRegex(ValueError, 'configuration content'):
                self.apply()
        folder = next(Path(self.temp.name).iterdir())
        with self.assertRaisesRegex(ValueError, 'Configuration content'):
            m.rollback(self.db, folder, 'TT')

    def test_exact_orphan_databus_binding_is_disabled_and_restored(self):
        self.query('CREATE TABLE c_business_databus(databus_id varchar(30) PRIMARY KEY,business_type_cd varchar(30),bean_name varchar(100),status_cd varchar(2)) ENGINE=InnoDB')
        self.query("INSERT INTO c_business_databus VALUES('122020120728820001','200200030001','machineTransactionIotAdapt','0'),('other','fee-type','machineTransactionIotAdapt','0')")
        before = m.snapshot(self.db)
        proposed = m.plan(self.db, 'TT')
        retired = [x for x in proposed['configChanges'] if x['table']=='c_business_databus']
        self.assertEqual(1, len(retired))
        result = self.apply()
        self.assertEqual('1', self.query("SELECT status_cd FROM c_business_databus WHERE databus_id='122020120728820001'"))
        self.assertEqual('0', self.query("SELECT status_cd FROM c_business_databus WHERE databus_id='other'"))
        m.rollback(self.db, result['directory'], 'TT')
        self.assertEqual(before, m.snapshot(self.db))

    def test_live_guard_rejects_sessions_without_default_database_or_in_another_database(self):
        # Exercise the actual PROCESSLIST predicate with owned client sessions only.
        # Other live application connections are excluded so they cannot mask a gap.
        real = m.ops.mysql_query
        for selected_database in (None, self.db):
            with self.subTest(database=selected_database), m.ops.client_command() as (command, env):
                args = command+['--batch','--skip-column-names','--unbuffered','-e',
                                'SELECT CONNECTION_ID(); SELECT SLEEP(20);']
                if selected_database:
                    args.append(selected_database)
                helper = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
                try:
                    connection_id = int(helper.stdout.readline().strip())
                    def only_owned_session(sql, database=None):
                        if 'information_schema.PROCESSLIST' in sql:
                            sql += ' AND ID='+str(connection_id)
                        return real(sql, database)
                    with patch.object(m.ops.local, 'listening', return_value=False), patch.object(m.ops, 'mysql_query', side_effect=only_owned_session):
                        with self.assertRaisesRegex(ValueError, 'writers'):
                            m.assert_writers_stopped('TT')
                finally:
                    helper.terminate()
                    helper.communicate(timeout=5)


class CommandLockTests(unittest.TestCase):
    def test_mutating_cli_owns_launcher_lock_before_apply_or_rollback(self):
        for action in ('--apply','--rollback'):
            events = []
            @contextmanager
            def lock():
                events.append('enter')
                try:
                    yield
                finally:
                    events.append('exit')
            def mutation(*args, **kwargs):
                self.assertEqual(['enter'], events)
                events.append('mutation')
                return {'passed': True}
            method = 'apply' if action=='--apply' else 'rollback'
            argv = ['--database','TT',action]+([] if action=='--apply' else ['private-journal'])
            with self.subTest(action=action), patch.object(m.ops.local, 'launcher_lock', side_effect=lock), patch.object(m, method, side_effect=mutation), redirect_stdout(io.StringIO()):
                m.main(argv)
            self.assertEqual(['enter','mutation','exit'], events)


if __name__ == '__main__':
    unittest.main()
