"""Real MySQL reconciliation queries on schema-only clones and synthetic rows.

No business rows are copied. Cleanup can only drop the exact randomly created schema.
"""
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import local
import payment_reconciliation as recon

DAY = '2026-11-06'
APP = 'wxSynthetic'
MCH = 'syntheticMerchant'
TABLES = ['payment_order', 'payment_order_item', 'payment_order_reservation', 'bill', 'pay_fee',
          'bill_owe_fee', 'pay_fee_detail', 'fee_receipt', 'fee_receipt_detail']


class ReconciliationDatabaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = 'audit_recon_' + uuid.uuid4().hex[:12]
        cls.created = False
        local.mysql_run('CREATE DATABASE `' + cls.schema + '` CHARACTER SET utf8mb4')
        cls.created = True
        try:
            for table in TABLES:
                local.mysql_run('CREATE TABLE `' + table + '` LIKE TT.`' + table + '`', database=cls.schema)
        except Exception:
            cls.tearDownClass()
            raise

    @classmethod
    def tearDownClass(cls):
        if cls.created:
            if not re.fullmatch(r'audit_recon_[a-f0-9]{12}', cls.schema):
                raise RuntimeError('Unexpected reconciliation audit cleanup target')
            local.mysql_run('DROP DATABASE `' + cls.schema + '`')
            cls.created = False

    def execute(self, sql):
        return local.mysql_run("SET time_zone='+08:00';" + sql, database=self.schema, capture=True)

    def insert(self, table, **fields):
        assert table in TABLES and all(re.fullmatch(r'[a-z_]+', key) for key in fields)
        values = ','.join('NULL' if value is None else recon._literal(str(value)) for value in fields.values())
        return self.execute('INSERT INTO `' + table + '` (' + ','.join('`' + key + '`' for key in fields) + ') VALUES (' + values + ')')

    def setUp(self):
        self.execute(';'.join('DELETE FROM `' + table + '`' for table in TABLES))
        self.insert('payment_order', order_id='order1', community_id='community', user_id='synthetic-user',
                    app_id=APP, mch_id=MCH, order_type='SINGLE', provider_version='V3', total_fee=1,
                    payload='[]', state='POSTED', transaction_id='transaction1',
                    create_time='2026-11-01 10:00:00', posted_time=DAY + ' 10:00:00')
        self.insert('bill', bill_id='bill1', community_id='community', config_id='config1',
                    review_state='PUBLISHED', cur_bill='T', receivable='0.01', receipts='0.01',
                    cur_receivable='0.01', bill_time=DAY + ' 00:00:00')
        self.add_item('fee1', 'owe1', 'detail1', '0.01')
        self.insert('fee_receipt', receipt_id='receipt1', community_id='community', obj_type='3333',
                    obj_id='room1', obj_name='Synthetic room', amount='0.01', status_cd='0',
                    remark='微信账单支付 order1')

    def add_item(self, fee, owe, detail, amount):
        self.insert('pay_fee', fee_id=fee, community_id='community', config_id='config1', status_cd='0',
                    payer_obj_id='room1', payer_obj_type='3333', end_time=DAY + ' 00:00:00',
                    state='2008001', fee_flag='1003006')
        self.insert('bill_owe_fee', owe_id=owe, bill_id='bill1', fee_id=fee, community_id='community',
                    state='2000', owner_id='synthetic-owner', owner_name='Synthetic', owner_tel='19900000000',
                    payer_obj_name='Synthetic room', payer_obj_type='3333', fee_end_time='2026-10-01 00:00:00',
                    deadline_time=DAY + ' 00:00:00', bill_amount_owed=amount, amount_owed=amount)
        self.insert('payment_order_item', owe_id=owe, order_id='order1')
        self.insert('pay_fee_detail', detail_id=detail, fee_id=fee, community_id='community', b_id='-1',
                    receivable_amount=amount, received_amount=amount, cycles='1', prime_rate='6',
                    status_cd='0', state='1400', start_time='2026-10-01 00:00:00',
                    end_time=DAY + ' 00:00:00', remark='微信账单支付 order1')
        self.insert('fee_receipt_detail', detail_id=detail, fee_id=fee, receipt_id='receipt1',
                    community_id='community', fee_name='Synthetic fee', amount=amount,
                    start_time='2026-10-01 00:00:00', end_time=DAY + ' 00:00:00', cycle='1', status_cd='0')

    def rows(self, ids=()):
        return [json.loads(line) for line in self.execute(recon.audit_sql(DAY, APP, MCH, ids)).splitlines() if line]

    def codes(self):
        return {row['code'] for row in recon.local_audit(self.rows())['differences']}

    def test_current_posting_matches(self):
        self.assertTrue(recon.local_audit(self.rows())['consistentWithinScope'])

    def test_legacy_bare_order_reference_matches_without_substring_join(self):
        self.execute("UPDATE pay_fee_detail SET remark='order1'; UPDATE fee_receipt SET remark='order1'")
        self.assertTrue(recon.local_audit(self.rows())['consistentWithinScope'])
        self.execute("UPDATE pay_fee_detail SET remark='other-order1'")
        self.assertIn('DETAIL_COUNT_MISMATCH', self.codes())

    def test_multi_item_payments_do_not_multiply_receipt_amounts(self):
        self.add_item('fee2', 'owe2', 'detail2', '0.02')
        self.execute('UPDATE payment_order SET total_fee=3; UPDATE fee_receipt SET amount=0.03; UPDATE bill SET receipts=0.03,bill_time=bill_time')
        rows = self.rows()
        self.assertEqual(rows[0]['receiptFen'], 3)
        self.assertEqual(rows[0]['receiptDetailCount'], 2)
        self.assertTrue(recon.local_audit(rows)['consistentWithinScope'])

    def test_same_total_wrong_fee_or_period_is_detected(self):
        self.execute("UPDATE pay_fee_detail SET fee_id='wrong'; UPDATE fee_receipt_detail SET fee_id='wrong'")
        self.assertIn('SNAPSHOT_POSTING_MISMATCH', self.codes())

    def test_deleted_detail_cannot_be_counted_as_valid_payment(self):
        self.execute("UPDATE pay_fee_detail SET status_cd='1'")
        self.assertIn('DETAIL_STATE_INVALID', self.codes())
        self.assertIn('SNAPSHOT_POSTING_MISMATCH', self.codes())

    def test_missing_detail_and_orphan_receipt_detail_are_detected(self):
        self.execute('DELETE FROM pay_fee_detail')
        self.assertIn('DETAIL_AMOUNT_MISMATCH', self.codes())
        self.assertIn('RECEIPT_DETAIL_LINK_MISMATCH', self.codes())

    def test_missing_snapshot_and_wrong_community_are_detected(self):
        self.execute("UPDATE bill_owe_fee SET community_id='another-community',fee_end_time=fee_end_time")
        self.assertIn('SNAPSHOT_MISSING', self.codes())

    def test_pending_reservation_on_posted_order_is_detected(self):
        self.insert('payment_order_reservation', order_id='order1', community_id='community', fee_id='fee1')
        self.assertIn('POSTED_RESERVATION_REMAINS', self.codes())

    def test_fee_period_must_be_advanced_but_later_payment_is_allowed(self):
        self.execute("UPDATE pay_fee SET end_time='2026-10-01 00:00:00'")
        self.assertIn('FEE_PERIOD_NOT_ADVANCED', self.codes())
        self.execute("UPDATE pay_fee SET end_time='2027-01-01 00:00:00'")
        self.assertTrue(recon.local_audit(self.rows())['consistentWithinScope'])

    def test_bill_receipts_must_include_known_payments_but_can_include_other_channels(self):
        self.execute('UPDATE bill SET receipts=0,bill_time=bill_time')
        self.assertIn('BILL_RECEIPTS_BELOW_POSTED', self.codes())
        self.execute('UPDATE bill SET receipts=0.05,bill_time=bill_time')
        self.assertTrue(recon.local_audit(self.rows())['consistentWithinScope'])

    def test_midnight_beijing_date_boundary(self):
        self.execute("UPDATE payment_order SET posted_time='2026-11-06 00:00:00'")
        self.assertEqual(len(self.rows()), 1)
        self.execute("UPDATE payment_order SET posted_time='2026-11-07 00:00:00'")
        self.assertEqual(self.rows(), [])
        self.assertEqual(len(self.rows(['order1'])), 1)

    def test_provider_ids_include_old_or_unposted_orders(self):
        self.execute("UPDATE payment_order SET state='CREATED',posted_time=NULL")
        self.assertEqual(self.rows(), [])
        self.assertEqual(self.rows(['order1'])[0]['state'], 'CREATED')

    def test_other_merchant_is_not_included_in_daily_scan(self):
        self.execute("UPDATE payment_order SET mch_id='another-merchant'")
        self.assertEqual(self.rows(), [])
        self.assertEqual(self.rows(['order1'])[0]['mchId'], 'another-merchant')

    def test_read_only_transaction_refuses_payment_mutation(self):
        with recon.runtime_ops.client_command() as (command, env):
            result = subprocess.run(command + [self.schema], input=(
                "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;"
                "UPDATE payment_order SET total_fee=999;COMMIT;").encode('utf-8'),
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=15)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.rows()[0]['totalFen'], 1)


if __name__ == '__main__':
    unittest.main()
