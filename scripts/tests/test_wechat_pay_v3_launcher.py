import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('payment_local',Path(__file__).parents[1]/'local.py')
local=importlib.util.module_from_spec(spec);spec.loader.exec_module(local)


class PaymentLauncherTest(unittest.TestCase):
    def config(self,folder):
        local.configure('merged')
        return dict(line.split('=',1) for line in (folder/'config/front.properties').read_text().splitlines() if '=' in line)

    def test_missing_payment_profile_closes_new_collection(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(local,'RUN',Path(tmp)),patch.object(local,'settings',return_value={'db_password':'synthetic-only'}):
            cfg=self.config(Path(tmp))
            self.assertEqual(cfg['java110.payment.enabled'],'false')
            self.assertNotIn('java110.payment.v3.config',cfg)

    def test_disabled_profile_retains_recovery_without_copying_keys(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(local,'RUN',Path(tmp)),patch.object(local,'settings',return_value={'db_password':'synthetic-only'}):
            p=Path(tmp)/'wechat-pay.json';p.write_text(json.dumps({'enabled':False,'apiV3Key':'NEVER_COPY_TO_PROPERTIES'}))
            cfg=self.config(Path(tmp))
            self.assertEqual(cfg['java110.payment.enabled'],'false')
            self.assertEqual(cfg['java110.payment.v3.recovery.enabled'],'true')
            self.assertEqual(cfg['java110.payment.v3.config'],p.as_posix())
            self.assertNotIn('NEVER_COPY_TO_PROPERTIES',str(cfg))
            p.write_text(json.dumps({'enabled':True}))
            self.assertEqual(self.config(Path(tmp))['java110.payment.enabled'],'true')

    def test_corrupt_profile_refuses_launch(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(local,'RUN',Path(tmp)),patch.object(local,'settings',return_value={'db_password':'synthetic-only'}):
            (Path(tmp)/'wechat-pay.json').write_text('invalid')
            with self.assertRaises(RuntimeError):self.config(Path(tmp))


if __name__=='__main__':unittest.main()
