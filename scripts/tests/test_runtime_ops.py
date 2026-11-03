import importlib.util
from contextlib import contextmanager
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('runtime_ops', Path(__file__).parents[1] / 'runtime_ops.py')
ops = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ops)

DUMP = (b'-- MySQL dump\nCREATE TABLE `example` (\n `id` int\n);\n'
        b"INSERT INTO `example` VALUES (1);\nINSERT INTO `example` VALUES (2);\n"
        b'-- Dump completed on 2026-11-03 12:00:00\n')


class RuntimeOpsTests(unittest.TestCase):
    def test_private_options_support_current_mysql_and_ignore_login_path(self):
        with patch.object(ops, 'mysql_binary', return_value=Path('mysql.exe')):
            with ops.client_command() as (command, env):
                self.assertNotIn('--no-login-paths', command)
                self.assertEqual(env['MYSQL_TEST_LOGIN_FILE'], os.devnull)

    def make_backup(self, directory):
        def dump(database, target):
            target.write_bytes(DUMP)
        with patch.object(ops, 'run_dump', side_effect=dump):
            return ops.create_backup('TT', Path(directory))

    def test_backup_requires_success_and_footer_before_manifest(self):
        for content in [b'', DUMP.split(b'-- Dump completed')[0]]:
            with self.subTest(content=content), tempfile.TemporaryDirectory() as d:
                with patch.object(ops, 'run_dump', side_effect=lambda database, target: target.write_bytes(content)):
                    with self.assertRaises(ops.OpsError):
                        ops.create_backup('TT', Path(d))
                self.assertFalse(list(Path(d).rglob('manifest.json')))

    def test_failed_dump_cannot_leave_completed_backup(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(ops, 'run_dump', side_effect=ops.OpsError('dump failed')):
                with self.assertRaises(ops.OpsError):
                    ops.create_backup('TT', Path(d))
            self.assertFalse(list(Path(d).rglob('manifest.json')))

    def test_completed_backup_is_verifiable_and_counts_snapshot_rows(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.make_backup(d)
            manifest = ops.verify_backup(Path(result['directory']))
            self.assertTrue(manifest['completed'])
            self.assertEqual(manifest['tableRows'], {'example': 2})
            self.assertNotIn('password', json.dumps(result).lower())

    def test_corrupted_backup_and_changed_inventory_fail(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.make_backup(d)
            folder = Path(result['directory'])
            (folder / 'database.sql').write_bytes(DUMP + b'corruption')
            with self.assertRaises(ops.OpsError):
                ops.verify_backup(folder)
        with tempfile.TemporaryDirectory() as d:
            result = self.make_backup(d)
            folder = Path(result['directory'])
            manifest = json.loads((folder / 'manifest.json').read_text())
            manifest['tableRows']['example'] = 99
            (folder / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaises(ops.OpsError):
                ops.verify_backup(folder)

    def test_restore_names_reject_live_schemas_and_near_matches(self):
        for target in ['TT', 'hc_community', 'ops_restore_abc', 'ops_restore_' + 'a'*12 + 'x', '../ops_restore_' + 'a'*12, 'ops_restore_' + 'A'*12]:
            with self.subTest(target=target), self.assertRaises(ops.OpsError):
                ops.validate_restore_target(target)
        ops.validate_restore_target('ops_restore_0123456789ab')

    def test_restore_refuses_source_switches_and_explicit_live_target(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.make_backup(d)
            with patch.object(ops, 'mysql_query') as query:
                with self.assertRaises(ops.OpsError):
                    ops.restore_drill(Path(result['directory']), target='TT')
                query.assert_not_called()
        for statement in [b'USE `TT`;\n', b'CREATE DATABASE `TT`;\n', b'/*!50003 CREATE*/ /*!50017 DEFINER=`root`@`localhost`*/ /*!50003 TRIGGER t BEFORE INSERT ON x FOR EACH ROW SET @x=1 */;\n']:
            with self.subTest(statement=statement):
                inventory = ops.dump_inventory(DUMP + statement)
                self.assertFalse(inventory['restoreDrillSupported'])

    def test_manifest_path_cannot_escape_backup_folder(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.make_backup(d)
            folder = Path(result['directory'])
            manifest = json.loads((folder / 'manifest.json').read_text())
            manifest['file'] = '../database.sql'
            (folder / 'manifest.json').write_text(json.dumps(manifest))
            with self.assertRaises(ops.OpsError):
                ops.verify_backup(folder)

    def test_restore_checks_rows_and_only_drops_its_created_schema(self):
        @contextmanager
        def client():
            yield ['mysql'], {}
        for count in ['2', '1']:
            with self.subTest(count=count), tempfile.TemporaryDirectory() as d:
                result = self.make_backup(d)
                target = 'ops_restore_0123456789ab'
                def query(sql, database=None):
                    if sql == 'SHOW TABLES':
                        return 'example'
                    if sql.startswith('SELECT COUNT'):
                        return count
                    return ''
                with patch.object(ops, 'mysql_query', side_effect=query) as sql, patch.object(ops, 'client_command', client), patch.object(ops.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)):
                    if count == '2':
                        report = ops.restore_drill(Path(result['directory']), target)
                        self.assertTrue(report['passed'])
                        self.assertTrue(report['targetRemoved'])
                    else:
                        with self.assertRaises(ops.OpsError):
                            ops.restore_drill(Path(result['directory']), target)
                    self.assertIn(unittest.mock.call('DROP DATABASE `' + target + '`'), sql.call_args_list)
                    self.assertFalse(any('DROP DATABASE `TT`' in str(call) for call in sql.call_args_list))

    def test_restore_existing_target_is_never_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.make_backup(d)
            with patch.object(ops, 'mysql_query', side_effect=ops.OpsError('already exists')) as query:
                with self.assertRaises(ops.OpsError):
                    ops.restore_drill(Path(result['directory']), 'ops_restore_0123456789ab')
                self.assertEqual(query.call_count, 1)
                self.assertTrue(query.call_args.args[0].startswith('CREATE DATABASE'))

    def test_nontransactional_source_cannot_claim_consistent_backup(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(ops, 'mysql_query', return_value='1'), patch.object(ops.subprocess, 'run') as run:
                with self.assertRaises(ops.OpsError):
                    ops.run_dump('TT', Path(d) / 'dump')
                run.assert_not_called()

    def test_dump_groups_restored_inserts_in_transactions(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(ops, 'mysql_query', return_value='0'), patch.object(ops, 'mysql_binary', return_value=Path('mysqldump.exe')), patch.object(ops.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
                ops.run_dump('TT', Path(d) / 'dump')
            self.assertIn('--no-autocommit', run.call_args.args[0])

    def test_health_failures_return_nonzero_without_exception_secrets(self):
        with patch.object(ops, 'health_report', return_value={'healthy': False, 'checks': [{'name': 'mysql', 'passed': False}]}):
            with patch('builtins.print') as output:
                self.assertEqual(ops.main(['health']), 1)
            self.assertNotIn('password', str(output.call_args))
        with patch.object(ops, 'health_report', side_effect=RuntimeError('secret-password=DO-NOT-PRINT')):
            with patch('builtins.print') as output:
                self.assertEqual(ops.main(['health']), 2)
            self.assertNotIn('DO-NOT-PRINT', str(output.call_args))

    def test_dump_binary_error_is_sanitized_and_password_not_command_argument(self):
        with tempfile.TemporaryDirectory() as d:
            captured = []
            def run(command, **kwargs):
                captured.append(command)
                self.assertFalse(any('--password=' in item for item in command))
                self.assertNotIn('MYSQL_PWD', kwargs['env'])
                return subprocess.CompletedProcess(command, 1, stderr=b'secret-password=DO-NOT-PRINT')
            with patch.object(ops, 'mysql_binary', return_value=Path('mysqldump.exe')), patch.object(ops.subprocess, 'run', side_effect=run):
                with self.assertRaises(ops.OpsError) as error:
                    ops.run_dump('TT', Path(d) / 'partial')
            self.assertNotIn('DO-NOT-PRINT', str(error.exception))
            self.assertTrue(captured)


if __name__ == '__main__':
    unittest.main()
