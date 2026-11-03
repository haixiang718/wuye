"""Safety regressions for the opt-in local direct-login fixture tool; no database."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('direct_flow',Path(__file__).parents[1]/'simulation/wechat_direct_flow.py')
flow=importlib.util.module_from_spec(spec);spec.loader.exec_module(flow)


class DirectFlowSafetyTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup)
        self.folder=Path(self.directory.name);self.record=self.folder/'fixture.json'
        self.data={'prefix':'WXD0123456789ab','label':flow.LABEL,'wechatAppId':'wx-synthetic','openId':'synthetic-WXD0123456789ab',
                   'ids':{key:'WXD0123456789ab'+suffix for key,suffix in flow.SUFFIXES.items()}}
        self.data['expectedUserId']='wx'+flow.hashlib.sha256((self.data['wechatAppId']+':'+self.data['openId']).encode()).hexdigest()[:28]
        self.data.update(communityId='synthetic-community',ownerName='synthetic-owner',phone='19900000000')
        self.record.write_text(json.dumps(self.data),encoding='utf-8')
        for attribute,value in [('RUN',self.folder),('RECORD',self.record)]:
            patched=patch.object(flow,attribute,value);patched.start();self.addCleanup(patched.stop)

    def test_tampered_record_never_reaches_database_cleanup(self):
        self.data['ids']['room']='real-room'
        self.record.write_text(json.dumps(self.data),encoding='utf-8')
        with patch.object(flow,'ensure_local'),patch.object(flow,'query') as query:
            with self.assertRaises(RuntimeError):flow.cleanup()
        query.assert_not_called()

    def test_technical_user_with_unrelated_binding_is_preserved(self):
        with patch.object(flow,'ensure_local'),patch.object(flow,'query',side_effect=['1','1','1']) as query:
            with self.assertRaisesRegex(RuntimeError,'provenance'):flow.cleanup()
        self.assertFalse(any('DELETE' in call.args[0] for call in query.call_args_list))

    def test_collision_is_never_adopted_into_fixture(self):
        with patch.object(flow,'query',return_value='someone-else'):
            with self.assertRaisesRegex(RuntimeError,'collision'):flow.insert_sql('building_room','room_id',{'room_id':'synthetic','b_id':'fixture'})

    def test_hex_literals_do_not_expose_sql_metacharacters(self):
        value="a'); DROP TABLE building_room; --"
        literal=flow.q(value)
        self.assertNotIn('DROP',literal);self.assertNotIn("'",literal)
        self.assertIn(value.encode().hex(),literal)

    def test_cache_identity_is_always_local_and_marked_synthetic(self):
        with patch.object(flow,'redis_command',return_value='OK') as redis:
            session=flow.identity(self.data)
        args=redis.call_args.args
        self.assertEqual(args[0],'SET');self.assertEqual(args[-2:],('EX',1800))
        self.assertEqual(args[1],'owner-wechat-identity:'+session)
        self.assertEqual(json.loads(args[2])['openId'],self.data['openId'])
        self.assertEqual(flow.load()['syntheticSessions'],[session])

    def test_valid_synthetic_id_has_18_characters_and_checksum(self):
        card=flow.id_card(123)
        self.assertEqual(len(card),18);self.assertEqual(card,'110101199001011237')


if __name__=='__main__':unittest.main()
