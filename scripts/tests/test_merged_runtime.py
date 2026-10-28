import importlib.util
import json
import re
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('merged_local', Path(__file__).parents[1] / 'local.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)
DOMAINS = ('user', 'community', 'fee', 'acct', 'store', 'common', 'report')


class MergedRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        runtime = patch.object(local, 'RUN', Path(self.directory.name))
        runtime.start()
        self.addCleanup(runtime.stop)
        ports = patch.object(local, 'listening', return_value=False)
        ports.start()
        self.addCleanup(ports.stop)

    def test_launcher_prevents_concurrent_cli_mutations(self):
        with local.launcher_lock():
            with self.assertRaisesRegex(RuntimeError, 'Another local launcher'):
                with local.launcher_lock():
                    self.fail('Second launcher acquired the same lock')
        with local.launcher_lock():
            pass

    def test_merged_selects_one_business_process_and_preserves_original_modes(self):
        self.assertEqual(set(local.selected_services(['all'], 'merged')),
                         {'eureka', 'order', 'api', 'front', 'job', 'business', 'web'})
        for profile in ('property', 'full'):
            selected = local.selected_services(['all'], profile)
            self.assertNotIn('business', selected)
            self.assertTrue(set(DOMAINS).issubset(selected))

    def test_merged_configuration_redirects_every_old_business_name(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'RUN', Path(directory)), \
                patch.object(local, 'settings', return_value={'db_password': 'test_only'}):
            local.configure('merged')
            for caller in ('business', 'api', 'front', 'order', 'job'):
                values = dict(line.split('=', 1) for line in
                              (local.RUN / f'config/{caller}.properties').read_text().splitlines())
                for domain in DOMAINS:
                    self.assertEqual(values[f'{domain}-service.ribbon.listOfServers'],
                                     '127.0.0.1:' + str(local.SERVICES['business']))
                    self.assertEqual(values[f'{domain}-service.ribbon.NIWSServerListClassName'],
                                     'com.netflix.loadbalancer.ConfigurationBasedServerList')
                self.assertEqual(values['java110.runtime.api-docs-enabled'], 'false')
            datasource = (local.RUN / 'config/business-datasource.yml').read_text()
            self.assertIn('127.0.0.1:13306/TT', datasource)
            self.assertIn('defaultDataSourceName: ds1', datasource)
            community = (local.RUN / 'config/business-community-datasource.yml').read_text()
            self.assertIn('s_community_member:', community)
            self.assertIn('business_community_photo:', community)
            self.assertIn('ds${Long.parseLong(community_id) % 2}', community)
            report = (local.RUN / 'config/business-report-datasource.yml').read_text()
            self.assertIn('defaultDataSourceName: ds0', report)
            self.assertNotIn('/hc_community?', report)
            business = (local.RUN / 'config/business.properties').read_text()
            for domain in DOMAINS:
                if domain == 'report':
                    continue
                original = (local.BACK / f'service-{domain}/src/main/resources/application-dev.yml').read_text(encoding='utf-8')
                group = re.search(r'group:\s*\n\s*id:\s*(\S+)', original)[1]
                self.assertIn(f'java110.kafka.{domain}.group-id={group}', business)
            local.configure('property')
            self.assertNotIn('ribbon.listOfServers', (local.RUN / 'config/api.properties').read_text())

    def test_mode_switch_checks_artifact_before_stopping_anything(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'BACK', Path(directory)), \
                patch.object(local, 'stop') as stop, patch.object(local, 'start') as start:
            with self.assertRaisesRegex(RuntimeError, 'missing'):
                local.restart(['all'], 'merged')
            stop.assert_not_called()
            start.assert_not_called()

    def test_mode_switch_keeps_middleware_and_h5(self):
        with patch.object(local, 'preflight'), patch.object(local, 'stop') as stop, \
                patch.object(local, 'start') as start:
            local.restart(['all'], 'merged')
            stopped = stop.call_args.args[0]
            self.assertTrue(set(DOMAINS).issubset(stopped))
            for name in ('mysql', 'redis', 'kafka', 'zookeeper', 'owner-h5', 'wechat-gateway'):
                self.assertNotIn(name, stopped)
            self.assertEqual(set(start.call_args.args[0]),
                             {'eureka', 'order', 'api', 'front', 'job', 'business', 'web'})

    def test_explicit_mixed_business_processes_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'business'):
            local.selected_services(['business', 'user'], 'merged')

    def test_start_rejects_running_conflicting_mode(self):
        with patch.object(local, 'listening', side_effect=lambda port: port == local.SERVICES['user']), \
                patch.object(local, 'spawn'), patch.object(local, 'configure') as configure:
            with self.assertRaisesRegex(RuntimeError, 'restart'):
                local.start(['business'], 'merged')
            configure.assert_not_called()

    def test_partial_restart_checks_layout_before_stopping(self):
        with patch.object(local, 'preflight'), \
                patch.object(local, 'listening', side_effect=lambda port: port == local.SERVICES['user']), \
                patch.object(local, 'stop') as stop:
            with self.assertRaisesRegex(RuntimeError, 'restart all'):
                local.restart(['front'], 'merged')
            stop.assert_not_called()

    def test_retained_callers_cannot_keep_routes_from_other_layout(self):
        for previous, target in [('property', 'merged'), ('merged', 'property')]:
            recorded = {'api': {'pid': 123, 'port': 8008, 'profile': previous,
                                'command': ['java', 'service-api.jar']}}
            with patch.object(local, 'processes', return_value=recorded), \
                    patch.object(local, 'listening', side_effect=lambda port: port == 8008), \
                    patch.object(local, 'spawn'), patch.object(local, 'configure') as configure:
                with self.assertRaisesRegex(RuntimeError, 'restart all'):
                    local.start(['business'] if target == 'merged' else ['user'], target)
                configure.assert_not_called()

    def test_pending_opposing_jvm_also_blocks_layout_switch(self):
        recorded = {'user': {'pid': 123, 'port': 8002,
                            'command': ['java', 'service-user.jar']}}
        result = type('Result', (), {'returncode': 0, 'stdout':
                      '[{"ProcessId":123,"CommandLine":"java service-user.jar"}]'})()
        with patch.object(local, 'processes', return_value=recorded), \
                patch.object(local, 'listening', return_value=False), \
                patch.object(local.subprocess, 'run', return_value=result), \
                patch.object(local, 'spawn'), patch.object(local, 'configure') as configure:
            with self.assertRaisesRegex(RuntimeError, 'restart all'):
                local.start(['business'], 'merged')
            configure.assert_not_called()

    def test_launch_keeps_sufficient_heap_without_exposing_credentials(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'RUN', Path(directory) / 'run'), \
                patch.object(local, 'BACK', Path(directory) / 'backend'), \
                patch.object(local, 'settings', return_value={'db_password': 'test_only'}), \
                patch.object(local, 'java', return_value='java.exe'), \
                patch.object(local, 'listening', return_value=False), patch.object(local, 'spawn') as spawn:
            jar = local.BACK / 'service-business/target/service-business.jar'
            jar.parent.mkdir(parents=True)
            jar.touch()
            local.start(['business'], 'merged')
            args = spawn.call_args.args[1]
            self.assertIn('-Xmx768m', args)
            self.assertNotIn('test_only', str(args))
            values = json.loads(spawn.call_args.kwargs['env']['SPRING_APPLICATION_JSON'])
            self.assertEqual(values['spring.datasource.password'], 'test_only')

    def test_stop_waits_for_port_release_before_replacement(self):
        recorded = {'user': {'pid': 123, 'port': 8002, 'command': ['java', 'service-user.jar']}}
        command_result = type('Result', (), {'stdout': '{"CommandLine":"java service-user.jar"}'})()
        killed = type('Result', (), {'returncode': 0})()
        exited = type('Result', (), {'stdout': ''})()
        with patch.object(local, 'processes', return_value=recorded), \
                patch.object(local.subprocess, 'run', side_effect=[command_result, killed, exited, exited]), \
                patch.object(local, 'listening', side_effect=[True, False]) as listening, \
                patch.object(local.time, 'sleep') as sleep, patch.object(local, 'save'):
            local.stop(['user'])
            self.assertEqual(listening.call_count, 2)
            sleep.assert_called_once()

    def test_failed_stop_keeps_unbound_jvm_tracked(self):
        recorded = {'user': {'pid': 123, 'port': 8002, 'command': ['java', 'service-user.jar']}}
        matched = type('Result', (), {'stdout': '{"CommandLine":"java service-user.jar"}'})()
        failed = type('Result', (), {'returncode': 1})()
        with patch.object(local, 'processes', return_value=recorded), \
                patch.object(local.subprocess, 'run', side_effect=[matched, failed]), \
                patch.object(local, 'save') as save:
            with self.assertRaisesRegex(RuntimeError, 'preserved process records'):
                local.stop(['user'])
            save.assert_not_called()


if __name__ == '__main__':
    unittest.main()
