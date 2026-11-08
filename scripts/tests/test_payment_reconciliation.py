import copy
import csv
from decimal import Decimal
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import payment_reconciliation as recon

DAY = '2026-11-06'
APP = 'wxTest123'
MCH = '1234567890'
HEADER = ['交易时间', '公众账号ID', '商户号', '微信订单号', '商户订单号', '交易状态',
          '货币种类', '应结订单金额', '订单金额', '申请退款金额', '商品名称']


def bill(rows=None, summary_count=None, encoding='utf-8-sig'):
    rows = rows if rows is not None else [[DAY + ' 10:01:00', APP, MCH, 'wxtransaction',
                                         'order1', 'SUCCESS', 'CNY', '0.00', '0.01', '0.00', 'Fee']]
    stream = io.StringIO(newline='')
    writer = csv.writer(stream)
    writer.writerow(HEADER + ['微信退款单号', '商户退款单号', '退款金额', '退款状态', ''])
    for row in rows:
        writer.writerow(['`' + str(value) for value in row] + ['`0', '`0', '`0.00', '`', ''])
    writer.writerow(['总交易单数', '订单总金额'])
    writer.writerow(['`' + str(len(rows) if summary_count is None else summary_count),
                     '`' + format(sum((Decimal(str(row[8])) for row in rows), Decimal(0)), 'f')])
    return stream.getvalue().encode(encoding)


def order(**updates):
    value = dict(orderId='order1', appId=APP, mchId=MCH, communityId='community1',
                 state='POSTED', totalFen=1, transactionId='wxtransaction', postedDate=DAY,
                 itemCount=1, itemFen=1, missingItems=0, unsettledItems=0,
                 detailCount=1, detailFen=1, invalidDetails=0,
                 receiptCount=1, receiptFen=1, receiptDetailCount=1, receiptDetailFen=1,
                 reservationCount=0, linkedDetailCount=1, snapshotPostingMismatches=0,
                 feeAdvanceMismatches=0, billReceiptsBelowPosted=0)
    value.update(updates)
    return value


class BillParsingTests(unittest.TestCase):
    def parse(self, content=None):
        return recon.parse_bill(content if content is not None else bill(), DAY, APP, MCH)

    def test_backticks_bom_trailing_comma_and_gross_not_net(self):
        parsed = self.parse()
        self.assertEqual(parsed['records'][0]['totalFen'], 1)
        self.assertEqual(parsed['excludedAppRows'], 0)
        self.assertEqual(len(parsed['sourceSha256']), 64)

    def test_money_is_exact_and_rejects_fractional_fen_and_non_numbers(self):
        self.assertEqual(recon.money_fen('900719925.01'), 90071992501)
        for value in ['NaN', 'Infinity', '1e2', '0.001', '-0.01', '1,000', '', ' 1.00x']:
            with self.subTest(value=value), self.assertRaises(recon.ReconciliationError):
                recon.money_fen(value)

    def test_gb18030_and_quoted_product_commas(self):
        row = [DAY + ' 10:00:00', APP, MCH, 'tx', 'order1', 'SUCCESS', 'CNY', '1.00', '1.00', '0', '物业费,水费']
        self.assertEqual(self.parse(bill([row], encoding='gb18030'))['records'][0]['totalFen'], 100)

    def test_wrong_merchant_date_currency_and_missing_identity_fail(self):
        base = [DAY + ' 10:00:00', APP, MCH, 'tx', 'order1', 'SUCCESS', 'CNY', '1', '1', '0', 'Fee']
        for column, value in [(2, 'otherMerchant'), (0, '2026-11-07 00:00:00'), (6, 'USD'), (3, ''), (4, '')]:
            row = base.copy()
            row[column] = value
            with self.subTest(column=column), self.assertRaises(recon.ReconciliationError):
                self.parse(bill([row]))

    def test_wrong_summary_count_truncation_unknown_headers_are_rejected(self):
        for content in [bill(summary_count=2), bill().split('总交易单数'.encode())[0], b'order,amount\na,1\n']:
            with self.subTest(content=content[:15]), self.assertRaises(recon.ReconciliationError):
                self.parse(content)

    def test_duplicate_success_order_or_transaction_rejected(self):
        row = [DAY + ' 10:00:00', APP, MCH, 'tx', 'order1', 'SUCCESS', 'CNY', '1', '1', '0', 'Fee']
        for column in [3, 4]:
            another = row.copy()
            another[column] = 'different'
            with self.subTest(column=column), self.assertRaises(recon.ReconciliationError):
                self.parse(bill([row, another]))

    def test_same_merchant_other_app_is_excluded_but_counted(self):
        row = [DAY + ' 10:00:00', 'wxOther', MCH, 'tx', 'order1', 'SUCCESS', 'CNY', '1', '1', '0', 'Fee']
        parsed = self.parse(bill([row]))
        self.assertEqual(parsed['records'], [])
        self.assertEqual(parsed['excludedAppRows'], 1)

    def test_zero_bill_has_summary_and_no_rows(self):
        self.assertEqual(self.parse(bill([]))['records'], [])

    def test_optional_final_data_cell_can_be_empty(self):
        row = [DAY + ' 10:00:00', APP, MCH, 'tx', 'order1', 'SUCCESS', 'CNY', '1', '1', '0', '']
        self.assertEqual(self.parse(bill([row]))['records'][0]['totalFen'], 100)

    def test_success_only_export_cannot_masquerade_as_all_daily_bill(self):
        stream = io.StringIO(newline='')
        writer = csv.writer(stream)
        writer.writerow(HEADER)
        writer.writerow([DAY + ' 10:00:00', APP, MCH, 'tx', 'order1', 'SUCCESS', 'CNY', '1', '1', '0', 'Fee'])
        writer.writerow(['总交易单数', '订单总金额'])
        writer.writerow(['1', '1.00'])
        with self.assertRaises(recon.ReconciliationError):
            self.parse(stream.getvalue().encode('utf-8'))

    def test_refund_only_export_is_not_an_all_daily_bill(self):
        source = list(csv.reader(io.StringIO(bill().decode('utf-8-sig'))))
        source[0][-1:] = ['退款申请时间', '退款成功时间', '']
        source[1][-1:] = [DAY + ' 10:00:00', DAY + ' 10:00:01', '']
        stream = io.StringIO(newline='')
        csv.writer(stream).writerows(source)
        with self.assertRaises(recon.ReconciliationError):
            self.parse(stream.getvalue().encode('utf-8'))

    def test_gross_summary_total_includes_other_app_rows_and_must_match(self):
        content = bill().replace(b'`1,`0.01\r\n', b'`1,`0.02\r\n')
        self.assertNotEqual(content, bill())
        with self.assertRaises(recon.ReconciliationError):
            self.parse(content)

    def test_no_personal_fields_survive_parsing(self):
        payload = bill().replace(b'`Fee', b'`PERSONAL-DO-NOT-PRINT')
        self.assertNotIn('PERSONAL-DO-NOT-PRINT', json.dumps(self.parse(payload)))


class ReconciliationTests(unittest.TestCase):
    def provider(self, content=None):
        return recon.parse_bill(content if content is not None else bill(), DAY, APP, MCH)

    def codes(self, report):
        return {row['code'] for row in report['differences']}

    def test_successful_order_has_no_differences(self):
        report = recon.reconcile(self.provider(), [order()])
        self.assertTrue(report['consistentWithinScope'])
        self.assertEqual(report['matchedPayments'], 1)
        self.assertFalse(report['officialSourceAuthenticated'])
        self.assertEqual(report['actionsTriggered'], [])

    def test_official_zero_refund_identifiers_are_not_refund_activity(self):
        parsed = self.provider()
        self.assertFalse(parsed['records'][0]['hasRefund'])
        self.assertTrue(recon.reconcile(parsed, [order()])['consistentWithinScope'])

    def test_old_created_order_and_late_posting_still_match(self):
        self.assertTrue(recon.reconcile(self.provider(), [order(postedDate='2026-11-08')])['consistentWithinScope'])

    def test_provider_paid_missing_or_unposted_is_actionable(self):
        self.assertIn('PROVIDER_ONLY', self.codes(recon.reconcile(self.provider(), [])))
        for state in ['CREATED', 'CLOSED']:
            report = recon.reconcile(self.provider(), [order(state=state)])
            self.assertIn('PROVIDER_PAID_NOT_POSTED', self.codes(report))
            self.assertFalse(report['consistentWithinScope'])

    def test_amount_transaction_and_local_merchant_mismatch(self):
        for changes, expected in [({'totalFen': 2}, 'AMOUNT_MISMATCH'),
                                  ({'transactionId': 'other'}, 'TRANSACTION_MISMATCH'),
                                  ({'mchId': 'other'}, 'LOCAL_IDENTITY_MISMATCH')]:
            with self.subTest(changes=changes):
                self.assertIn(expected, self.codes(recon.reconcile(self.provider(), [order(**changes)])))

    def test_local_posted_missing_provider_row_explains_date_boundary(self):
        report = recon.reconcile(self.provider(bill([])), [order()])
        self.assertIn('LOCAL_ONLY_CHECK_TRADE_DATE', self.codes(report))
        self.assertFalse(report['consistentWithinScope'])

    def test_refunds_are_review_not_success_and_not_automatically_reversed(self):
        row = [DAY + ' 10:00:00', APP, MCH, 'wxtransaction', 'order1', 'REFUND', 'CNY', '0', '0.01', '0.01', 'Fee']
        report = recon.reconcile(self.provider(bill([row])), [order()])
        self.assertIn('PROVIDER_REFUND_REVIEW', self.codes(report))
        self.assertEqual(report['matchedPayments'], 0)
        self.assertEqual(report['actionsTriggered'], [])

    def test_success_with_refund_amount_requires_review(self):
        row = [DAY + ' 10:00:00', APP, MCH, 'wxtransaction', 'order1', 'SUCCESS', 'CNY', '0', '0.01', '0.01', 'Fee']
        report = recon.reconcile(self.provider(bill([row])), [order()])
        self.assertIn('PROVIDER_REFUND_REVIEW', self.codes(report))
        self.assertFalse(report['consistentWithinScope'])

    def test_refund_columns_cannot_be_hidden_by_zero_application_amount(self):
        source = list(csv.reader(io.StringIO(bill().decode('utf-8-sig'))))
        for field, value in [('退款金额', '`0.01'), ('微信退款单号', '`refund123'), ('退款状态', '`PROCESSING')]:
            with self.subTest(field=field):
                changed = copy.deepcopy(source)
                changed[1][changed[0].index(field)] = value
                stream = io.StringIO(newline='')
                csv.writer(stream).writerows(changed)
                report = recon.reconcile(self.provider(stream.getvalue().encode('utf-8')), [order()])
                self.assertIn('PROVIDER_REFUND_REVIEW', self.codes(report))

    def test_duplicate_local_rows_fail_closed(self):
        with self.assertRaises(recon.ReconciliationError):
            recon.reconcile(self.provider(), [order(), order()])

    def test_multi_item_order_validates_counts_and_fen_without_float(self):
        value = order(totalFen=3, itemCount=2, itemFen=3, detailCount=2, detailFen=3,
                      receiptFen=3, receiptDetailCount=2, receiptDetailFen=3, linkedDetailCount=2)
        self.assertTrue(recon.local_audit([value])['consistentWithinScope'])

    def test_local_posting_errors_are_separate_from_provider_match(self):
        cases = [({'detailFen': 2}, 'DETAIL_AMOUNT_MISMATCH'),
                 ({'detailCount': 2}, 'DETAIL_COUNT_MISMATCH'),
                 ({'receiptCount': 2}, 'RECEIPT_COUNT_MISMATCH'),
                 ({'receiptFen': 2}, 'RECEIPT_AMOUNT_MISMATCH'),
                 ({'receiptDetailFen': 2}, 'RECEIPT_DETAIL_AMOUNT_MISMATCH'),
                 ({'linkedDetailCount': 0}, 'RECEIPT_DETAIL_LINK_MISMATCH'),
                 ({'missingItems': 1}, 'SNAPSHOT_MISSING'),
                 ({'unsettledItems': 1}, 'SNAPSHOT_NOT_SETTLED'),
                 ({'reservationCount': 1}, 'POSTED_RESERVATION_REMAINS'),
                 ({'transactionId': None}, 'POSTED_TRANSACTION_MISSING'),
                 ({'itemCount': 0}, 'ORDER_ITEMS_MISSING'),
                 ({'invalidDetails': 1}, 'DETAIL_STATE_INVALID')]
        for changes, expected in cases:
            with self.subTest(changes=changes):
                report = recon.reconcile(self.provider(), [order(**changes)])
                self.assertIn(expected, self.codes(report))
                self.assertFalse(report['consistentWithinScope'])

    def test_equal_totals_cannot_hide_wrong_fee_or_period_allocations(self):
        report = recon.local_audit([order(snapshotPostingMismatches=1)])
        self.assertIn('SNAPSHOT_POSTING_MISMATCH', self.codes(report))

    def test_paid_money_must_advance_fee_and_reach_bill_receipts(self):
        for changes, expected in [({'feeAdvanceMismatches': 1}, 'FEE_PERIOD_NOT_ADVANCED'),
                                  ({'billReceiptsBelowPosted': 1}, 'BILL_RECEIPTS_BELOW_POSTED')]:
            with self.subTest(changes=changes):
                self.assertIn(expected, self.codes(recon.local_audit([order(**changes)])))

    def test_posted_order_requires_a_positive_integer_amount(self):
        self.assertIn('POSTED_AMOUNT_INVALID', self.codes(recon.local_audit([order(totalFen=0)])))

    def test_pending_order_not_in_bill_is_not_invented_as_lost_payment(self):
        report = recon.reconcile(self.provider(bill([])), [order(state='CREATED')])
        self.assertEqual(report['differences'], [])

    def test_local_audit_is_not_provider_reconciliation(self):
        report = recon.local_audit([order()])
        self.assertEqual(report['scope'], 'LOCAL_POSTING_ONLY')
        self.assertFalse(report['providerReconciled'])


class BoundaryTests(unittest.TestCase):
    def test_query_uses_read_only_snapshot_and_hex_values(self):
        query = recon.audit_sql(DAY, APP, MCH, ['order1'])
        self.assertIn('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY', query)
        self.assertIn("SET time_zone = '+08:00'", query)
        self.assertIn('posted_time >=', query)
        self.assertNotIn('wxTest123', query)
        self.assertNotIn('order1', query)
        self.assertNotIn('user_id', query)
        self.assertNotIn('payload', query)
        self.assertNotIn('po.*', query)
        self.assertIn('d.remark IN (po.order_id,', query)
        self.assertNotIn(' LIKE ', query)

    def test_invalid_date_and_identity_are_rejected_before_query(self):
        for date, app, mch in [('2026-11-31', APP, MCH), (DAY, APP + "';DROP", MCH),
                                (DAY, APP, '1 OR 1=1')]:
            with self.subTest(date=date, app=app), self.assertRaises(recon.ReconciliationError):
                recon.audit_sql(date, app, mch, [])

    def test_private_reports_are_unique_and_stdout_has_no_transaction_ids(self):
        report = recon.local_audit([order(detailFen=2)])
        with tempfile.TemporaryDirectory() as tmp:
            one = recon.save_report(report, Path(tmp))
            two = recon.save_report(report, Path(tmp))
            self.assertNotEqual(one, two)
            self.assertTrue((one / 'report.json').is_file())
            self.assertTrue((one / 'differences.csv').is_file())
            self.assertTrue((one / 'report.html').is_file())
            summary = recon.console_summary(report, one)
            self.assertNotIn('wxtransaction', json.dumps(summary))
            self.assertNotIn('order1', json.dumps(summary))

    def test_cli_error_does_not_print_exception_or_input_contents(self):
        with patch.object(recon, 'read_local_orders', side_effect=RuntimeError('SECRET-DO-NOT-PRINT')):
            with patch('builtins.print') as output:
                self.assertEqual(recon.main(['local', '--date', DAY, '--app-id', APP, '--mch-id', MCH]), 2)
            self.assertNotIn('SECRET-DO-NOT-PRINT', str(output.call_args))

    def test_csv_cells_cannot_inject_excel_formulas_from_legacy_data(self):
        report = recon.local_audit([order(orderId='=EXTERNAL()', detailFen=2)])
        with tempfile.TemporaryDirectory() as tmp:
            folder = recon.save_report(report, Path(tmp))
            with (folder / 'differences.csv').open(encoding='utf-8-sig', newline='') as source:
                rows = list(csv.reader(source))
            self.assertEqual(rows[1][1], "'=EXTERNAL()")

    def test_readable_report_escapes_legacy_ids_and_distinguishes_local_scope(self):
        report = recon.local_audit([order(orderId='<img src=x onerror=alert(1)>', detailFen=2)])
        with tempfile.TemporaryDirectory() as tmp:
            folder = recon.save_report(report, Path(tmp))
            html = (folder / 'report.html').read_text(encoding='utf-8')
            self.assertNotIn('<img src=x', html)
            self.assertIn('&lt;img src=x', html)
            self.assertIn('尚未核对微信官方日账单', html)
            self.assertIn('流水金额不一致', html)


if __name__ == '__main__':
    unittest.main()
