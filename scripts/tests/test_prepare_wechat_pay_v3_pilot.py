"""Offline guard tests: never connect to the active database."""
import importlib.util
from contextlib import nullcontext
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
SPEC = importlib.util.spec_from_file_location('pilot', Path(__file__).resolve().parents[1] / 'prepare_wechat_pay_v3_pilot.py')
pilot = importlib.util.module_from_spec(SPEC) if SPEC else None
if SPEC and SPEC.loader and Path(SPEC.origin).exists():
    SPEC.loader.exec_module(pilot)


class PilotGuards(unittest.TestCase):
    def test_implementation_exists(self):
        self.assertTrue(hasattr(pilot, 'validate_selection'), 'pilot guards are not implemented')

    def test_selection_requires_one_approved_wechat_owner_and_room(self):
        if not hasattr(pilot, 'validate_selection'):
            self.fail('pilot guards are not implemented')
        selection = {'requestedOwnerName': 'Synthetic Pilot Owner', 'approvedForPaymentPilot': True, 'communityId': 'c',
                     'bindings': [{'state': '12000', 'appType': 'WECHAT', 'userId': 'u',
                                   'ownerId': 'o', 'memberId': 'm', 'hasOpenId': 1}],
                     'rooms': [{'roomId': 'r', 'roomNumber': 'AUDIT7', 'relState': '2001'}]}
        self.assertEqual(pilot.validate_selection(selection)['ownerId'], 'o')
        for field, value in [('bindings', []), ('bindings', selection['bindings'] * 2),
                             ('rooms', selection['rooms'] * 2), ('requestedOwnerName', ''),
                             ('approvedForPaymentPilot', False), ('approvedForPaymentPilot', None)]:
            bad = {**selection, field: value}
            with self.subTest(field=field, value=value), self.assertRaises(pilot.PilotError):
                pilot.validate_selection(bad)
        for field, value in [('state', '10000'), ('appType', 'APP'), ('hasOpenId', 0)]:
            bad = {**selection, 'bindings': [{**selection['bindings'][0], field: value}]}
            with self.subTest(field=field), self.assertRaises(pilot.PilotError):
                pilot.validate_selection(bad)

    def test_identifiers_and_hex_quoting(self):
        if not hasattr(pilot, 'fixture_ids'):
            self.fail('pilot identifiers are not implemented')
        ids = pilot.fixture_ids('PV3abcdef123456')
        self.assertEqual(len(set(ids.values())), 5)
        self.assertTrue(all(len(v) <= 30 and v.startswith('PV3abcdef123456') for v in ids.values()))
        self.assertEqual(pilot.q("a'\\"), 'CONVERT(0x61275c USING utf8mb4)')
        with self.assertRaises(pilot.PilotError):
            pilot.fixture_ids('unsafe;')

    def test_schema_rejects_unknown_required_columns(self):
        if not hasattr(pilot, 'insert_sql'):
            self.fail('schema guards are not implemented')
        schema = {'fee_id': ('varchar(30)', False, None, ''),
                  'new_required': ('varchar(30)', False, None, '')}
        with self.assertRaises(pilot.PilotError):
            pilot.insert_sql('pay_fee', {'fee_id': 'x'}, schema)
        schema['new_required'] = ('varchar(30)', True, None, '')
        self.assertIn('INSERT INTO `pay_fee` (`fee_id`) VALUES', pilot.insert_sql('pay_fee', {'fee_id': 'x'}, schema))

    def test_disabled_profile_uses_application_default_when_property_is_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'config').mkdir()
            (root / 'config/front.properties').write_text('server.port=8012\n', encoding='utf-8')
            (root / 'wechat-pay.json').write_text('{"enabled": false}', encoding='utf-8')
            with patch.object(pilot, 'RUN', root):
                pilot.collection_disabled()
                (root / 'wechat-pay.json').write_text('{"enabled": true}', encoding='utf-8')
                with self.assertRaises(pilot.PilotError):
                    pilot.collection_disabled()

    def test_manual_fixture_has_exact_fixed_amount_and_scope(self):
        record = {'ids': pilot.fixture_ids('PV3abcdef123456'), 'communityId': 'c',
                  'roomId': 'real-room', 'ownerId': 'real-owner',
                  'startTime': '2026-11-01 00:00:00', 'deadlineTime': '2026-12-01 00:00:00'}
        rows = pilot.expected_rows(record, {'feeType': 'x', 'incomeObjId': 'store',
                                          'creatorId': 'staff', 'ownerName': 'owner', 'payerName': 'AUDIT7'})
        self.assertEqual(set(rows), set(pilot.KEYS))
        self.assertEqual(rows['pay_fee']['payer_obj_id'], 'real-room')
        self.assertEqual(rows['pay_fee_config']['bill_type'], '005')
        self.assertEqual(rows['pay_fee_config']['additional_amount'], '0.01')
        self.assertEqual(rows['bill']['review_state'], 'PUBLISHED')
        self.assertEqual(rows['bill']['receipts'], '0.00')
        self.assertEqual(rows['bill_owe_fee']['state'], '1000')
        self.assertEqual(rows['bill_owe_fee']['owner_tel'], '')
        self.assertEqual(rows['bill_owe_fee']['bill_amount_owed'], '0.01')

    def test_same_identity_multi_binding_is_supported_without_identity_values(self):
        if not hasattr(pilot, 'validate_identity_counts'):
            self.fail('aggregate identity guard is not implemented')
        pilot.validate_identity_counts({'bindings': 2, 'distinctIdentities': 1, 'missingIdentities': 0})
        for stats in [{'bindings': 2, 'distinctIdentities': 2, 'missingIdentities': 0},
                      {'bindings': 2, 'distinctIdentities': 1, 'missingIdentities': 1},
                      {'bindings': 0, 'distinctIdentities': 0, 'missingIdentities': None}]:
            with self.assertRaises(pilot.PilotError):
                pilot.validate_identity_counts(stats)

    def test_plan_never_backs_up_or_writes_and_apply_saves_provenance_before_transaction(self):
        selection = {'requestedOwnerName': 'Synthetic Pilot Owner', 'approvedForPaymentPilot': True, 'communityId': 'c',
                     'bindings': [{'state': '12000', 'appType': 'WECHAT', 'userId': 'u',
                                   'ownerId': 'o', 'memberId': 'm', 'hasOpenId': 1}],
                     'rooms': [{'roomId': 'r', 'roomNumber': 'AUDIT7', 'relState': '2001'}]}
        context = {'feeType': 'x', 'incomeObjId': 'store', 'creatorId': 'staff',
                   'ownerName': 'Synthetic Pilot Owner', 'payerName': 'AUDIT7'}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            selection_path, record_path = root / 'selection.json', root / 'record.json'
            selection_path.write_text(json.dumps(selection), encoding='utf-8')
            events = []
            applied = [False]

            def query(database, sql):
                self.assertEqual(database, 'TT')
                if sql.startswith('SELECT engine'):
                    return 'InnoDB'
                if sql.startswith('SELECT COUNT(*)'):
                    return '1' if applied[0] else '0'
                self.assertTrue(record_path.exists(), 'provenance must precede writes')
                self.assertEqual(events, ['backup'])
                self.assertIn('START TRANSACTION', sql)
                self.assertIn('COMMIT', sql)
                self.assertEqual(sql.count('INSERT INTO `_pv3_guard`'), 4)
                self.assertNotIn('UPDATE ', sql)
                self.assertNotIn('DELETE ', sql)
                applied[0] = True
                events.append('transaction')
                return ''

            def inspect(database, table):
                keys = pilot.expected_rows({'ids': pilot.fixture_ids('PV3abcdef123456'), **pilot.validate_selection(selection),
                                            'startTime': 's', 'deadlineTime': 'd'}, context)[table]
                return {key: ('varchar(1000)', False, None, '') for key in keys}, {}

            def backup(*args):
                events.append('backup')
                return {'database': 'TT', 'file': str(root / 'backup.sql'), 'sha256': 'fake'}

            with patch.object(pilot, 'RUN', root), patch.object(pilot, 'SELECTION', selection_path), \
                    patch.object(pilot, 'RECORD', record_path), patch.object(pilot, 'collection_disabled'), \
                    patch.object(pilot, 'selected_context', return_value=context), \
                    patch.object(pilot.base, 'inspect', side_effect=inspect), \
                    patch.object(pilot.base, 'query', side_effect=query), \
                    patch.object(pilot.base, 'create_backup', side_effect=backup), \
                    patch.object(pilot.base, 'migration_lock', side_effect=nullcontext), \
                    patch.object(pilot, 'snapshot', return_value={'existing': 'unchanged'}):
                status, _ = pilot.prepare()
                self.assertIn('read-only', status)
                self.assertFalse(record_path.exists())
                self.assertEqual(events, [])
                status, record = pilot.prepare(apply=True)
                self.assertIn('prepared', status)
                self.assertEqual(events, ['backup', 'transaction'])
                self.assertTrue(record['existingDataPreserved'])
                pilot.prepare(apply=True)
                self.assertEqual(events, ['backup', 'transaction'], 'rerun must not write again')


if __name__ == '__main__':
    unittest.main()
