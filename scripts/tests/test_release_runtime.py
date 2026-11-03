import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

root = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release_runtime', root / 'deploy/linux/bin/start_service.py')
runtime = importlib.util.module_from_spec(spec)
if Path(spec.origin).exists():
    spec.loader.exec_module(runtime)


class RuntimeTests(unittest.TestCase):
    def test_overrides_cannot_enable_payments_jobs_or_nonloopback_listeners(self):
        config = {'java110.payment.enabled': True, 'server.address': '0.0.0.0',
                  'java110.local.disableScheduledTasks': False, 'spring.datasource.password': 'provided-private-value'}
        result = runtime.safe_config('front', config)
        self.assertEqual(result['server.address'], '127.0.0.1')
        self.assertEqual(result['server.port'], 8012)
        self.assertEqual(result['java110.payment.enabled'], 'false')
        self.assertEqual(result['java110.local.disableScheduledTasks'], 'true')
        self.assertNotIn('provided-private-value', json.dumps(runtime.public_status('front', result)))

    def test_all_seven_domains_are_routed_to_merged_business(self):
        result = runtime.safe_config('api', {})
        for name in ('user', 'community', 'fee', 'acct', 'store', 'common', 'report'):
            self.assertEqual(result[name + '-service.ribbon.listOfServers'], '127.0.0.1:8015')
        self.assertEqual(result['java110.runtime.merged-enabled'], 'true')

    def test_sample_config_cannot_start(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            (directory / 'READY_FOR_NONPAYMENT').write_text('reviewed')
            (directory / 'front.json').write_text('{"password":"REPLACE_IN_PRODUCTION"}')
            with self.assertRaisesRegex(ValueError, 'placeholder'):
                runtime.read_config(directory, 'front')

    def test_unreviewed_config_and_unknown_service_cannot_start(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            (directory / 'front.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'READY_FOR_NONPAYMENT'):
                runtime.read_config(directory, 'front')
        with self.assertRaisesRegex(ValueError, 'service'):
            runtime.safe_config('goods', {})

    def test_real_sharding_expressions_are_not_mistaken_for_missing_secrets(self):
        with tempfile.TemporaryDirectory() as d:
            directory = Path(d)
            (directory / 'READY_FOR_NONPAYMENT').write_text('reviewed')
            (directory / 'order.json').write_text('{}')
            (directory / 'order-datasource.yml').write_text('algorithmExpression: ds${Long.parseLong(community_id) % 2}\n')
            self.assertEqual(runtime.read_config(directory, 'order')['server.port'], 8001)

    def test_command_forces_boot_logging_config_over_bundled_or_external_debug_config(self):
        release = Path('/opt/wuye/releases/test').resolve()
        jar = release / 'java/service-front.jar'
        command = runtime.java_command('front', '/usr/bin/java', release, jar)
        self.assertIn('--logging.config=' + (release / 'linux/config/logback.xml').as_uri(), command)
        self.assertIn('--java110.payment.enabled=false', command)
        self.assertIn('-Djava110.local.disableScheduledTasks=true', command)


if __name__ == '__main__':
    unittest.main()
