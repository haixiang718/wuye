import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('wuye_local', Path(__file__).parents[1] / 'local.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


class LocalConfigTests(unittest.TestCase):
    def test_all_services_use_isolated_middleware(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'RUN', Path(directory)), patch.object(local, 'settings', return_value={'db_password': 'test_only'}):
            local.configure()
            for name, port in local.SERVICES.items():
                text = (Path(directory) / f'config/{name}.properties').read_text()
                self.assertIn(f'server.port={port}', text)
                self.assertIn('server.address=127.0.0.1', text)
                self.assertIn('spring.redis.port=16379', text)
                self.assertIn('kafka.producer.servers=127.0.0.1:19092', text)
                self.assertIn('java110.code.zookeeper.port=12181', text)
                self.assertIn('logging.config=file:', text)
                self.assertNotIn('dev.java110.com', text)
                source = local.BACK / f'service-{name}/src/main/resources/dataSource.yml'
                if source.exists():
                    datasource = (Path(directory) / f'config/{name}-datasource.yml').read_text()
                    active = '\n'.join(line for line in datasource.splitlines() if not line.lstrip().startswith('#'))
                    self.assertNotIn('dev.db.java110.com', active)
                    self.assertNotIn('password: TT@', active)
                    self.assertIn('127.0.0.1:13306/TT', active)
                    self.assertIn('shardingRule:', active)
                    self.assertIn('java110.datasource.config=file:', text)
            logback = (Path(directory) / 'config/logback-local.xml').read_text()
            self.assertNotIn('level="DEBUG"', logback)
            self.assertNotIn('resource=', logback)
            self.assertIn('<root level="WARN">', logback)

    def test_existing_import_never_reimports_business_data(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'RUN', Path(directory)), patch.object(local, 'mysql_run') as mysql:
            (Path(directory) / 'database-imported.json').write_text('{}')
            local.import_db()
            for call in mysql.call_args_list:
                self.assertNotIn('DROP', call.args[0])
                self.assertNotIn('CREATE DATABASE', call.args[0])

    def test_launch_overrides_packaged_dev_profile_without_command_line_secrets(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'RUN', Path(directory)), patch.object(local, 'BACK', Path(directory) / 'backend'), patch.object(local, 'settings', return_value={'db_password': 'test_only'}), patch.object(local, 'java', return_value='java.exe'), patch.object(local, 'spawn') as spawn:
            jar = local.BACK / 'service-eureka/target/service-eureka.jar'
            jar.parent.mkdir(parents=True)
            jar.touch()
            local.start(['eureka'])
            import json
            values = json.loads(spawn.call_args.kwargs['env']['SPRING_APPLICATION_JSON'])
            self.assertEqual(values['spring.redis.port'], '16379')
            self.assertEqual(values['spring.datasource.password'], 'test_only')
            self.assertNotIn('test_only', str(spawn.call_args.args))


class RuntimeProfileTests(unittest.TestCase):
    def test_property_profile_preserves_business_services(self):
        with patch.dict(os.environ, {}, clear=True):
            selected = local.selected_services(['all'], 'property')
        self.assertNotIn('goods', selected)
        self.assertEqual(set(selected), set(local.SERVICES) - {'goods'} | {'web'})
        self.assertIn('job', selected)
        self.assertIn('acct', selected)
        self.assertIn('report', selected)

    def test_full_and_explicit_goods_are_available(self):
        self.assertEqual(local.selected_services(['all'], 'full'), list(local.SERVICES) + ['web'])
        self.assertEqual(local.selected_services(['goods'], 'property'), ['goods'])

    def test_scheduled_jobs_restore_goods_dependency(self):
        with patch.dict(os.environ, {'WUYE_ENABLE_SCHEDULED_TASKS': '1'}):
            self.assertIn('goods', local.selected_services(['all'], 'property'))
            self.assertEqual(local.selected_services(['job'], 'property'), ['goods', 'job'])
            full = local.selected_services(['all'], 'full')
            self.assertLess(full.index('goods'), full.index('job'))
            with patch.object(local, 'stop') as stop:
                local.trim('property')
                stop.assert_not_called()

    def test_trim_only_stops_excluded_project_services(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(local, 'processes', return_value={}), patch.object(local, 'stop') as stop:
            local.trim('property')
            stop.assert_called_once_with(['goods'])
        with patch.object(local, 'stop') as stop:
            local.trim('full')
            stop.assert_not_called()

    def test_trim_preserves_dependency_of_existing_scheduled_job(self):
        recorded = {'job': {'command': ['java.exe', '-Djava110.local.disableScheduledTasks=false']}}
        with patch.dict(os.environ, {}, clear=True), patch.object(local, 'processes', return_value=recorded), patch.object(local, 'listening', return_value=True), patch.object(local, 'stop') as stop:
            local.trim('property')
            stop.assert_not_called()
        with patch.dict(os.environ, {}, clear=True), patch.object(local, 'processes', return_value=recorded), patch.object(local, 'listening', return_value=False), patch.object(local, 'stop') as stop:
            local.trim('property')
            stop.assert_called_once_with(['goods'])

    def test_profile_configs_keep_data_and_bound_idle_resources(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(local, 'RUN', Path(directory)), patch.object(local, 'settings', return_value={'db_password': 'test_only'}):
            local.configure('property')
            config = (Path(directory) / 'config/api.properties').read_text()
            self.assertIn('java110.runtime.api-docs-enabled=false', config)
            self.assertIn('spring.jmx.enabled=false', config)
            self.assertIn('jedis.pool.config.maxIdle=2', config)
            for service in local.SERVICES:
                file = Path(directory) / f'config/{service}-datasource.yml'
                if file.exists():
                    active = '\n'.join(line for line in file.read_text().splitlines() if not line.lstrip().startswith('#'))
                    self.assertIn('initialSize: 0', active)
                    self.assertIn('minIdle: 0', active)
                    self.assertIn('poolPreparedStatements: false', active)
                    self.assertIn('shardingRule:', active)
            local.configure('full')
            config = (Path(directory) / 'config/api.properties').read_text()
            self.assertIn('java110.runtime.api-docs-enabled=true', config)
            self.assertNotIn('spring.jmx.enabled=false', config)
            datasource = (Path(directory) / 'config/fee-datasource.yml').read_text()
            self.assertIn('initialSize: 1', datasource)
            self.assertIn('poolPreparedStatements: true', datasource)

    def test_cli_defaults_to_property_and_full_is_explicit(self):
        with patch.dict(os.environ, {}, clear=True), patch('sys.argv', ['local.py', 'start', 'all']), patch.object(local, 'trim') as trim, patch.object(local, 'start') as start:
            local.main()
            trim.assert_called_once_with('property')
            self.assertNotIn('goods', start.call_args.args[0])
            self.assertEqual(start.call_args.kwargs['profile'], 'property')
        with patch('sys.argv', ['local.py', 'start', 'all', '--profile', 'full']), patch.object(local, 'trim') as trim, patch.object(local, 'start') as start:
            local.main()
            trim.assert_called_once_with('full')
            self.assertIn('goods', start.call_args.args[0])
            self.assertEqual(start.call_args.kwargs['profile'], 'full')

    def test_restart_applies_profile_without_stopping_middleware_or_owner_sessions(self):
        with patch.dict(os.environ, {}, clear=True), patch('sys.argv', ['local.py', 'restart', 'all']), patch.object(local, 'stop') as stop, patch.object(local, 'start') as start:
            local.main()
            self.assertEqual(stop.call_args.args[0], list(local.SERVICES) + ['web'])
            for name in ('mysql', 'redis', 'kafka', 'zookeeper', 'owner-h5', 'wechat-gateway'):
                self.assertNotIn(name, stop.call_args.args[0])
            self.assertNotIn('goods', start.call_args.args[0])
            self.assertEqual(start.call_args.kwargs['profile'], 'property')


if __name__ == '__main__':
    unittest.main()
