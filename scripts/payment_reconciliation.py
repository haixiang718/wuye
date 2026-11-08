"""Read-only WeChat daily bill and local posting checks. Never repairs or refunds money."""
import argparse
from collections import Counter
import csv
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
from html import escape
import io
import json
import os
from pathlib import Path
import re
import sys
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runtime_ops

REPORTS = runtime_ops.local.RUN / 'payments/reconciliation'
MAX_BYTES = 32 * 1024 * 1024
MAX_ROWS = 5000
IDENTIFIER = re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}\Z')
DIFFERENCES = {
    'PROVIDER_ONLY': ('微信有记录，本地未找到订单', '核对是否为其他系统的交易；若属于本系统，交由管理员查单，禁止直接补写收费记录。'),
    'PROVIDER_PAID_NOT_POSTED': ('微信已收款，本地未入账', '核查签名查单及回调、补偿任务；使用现有受控入账流程，不要求业主重复付款。'),
    'LOCAL_ONLY_CHECK_TRADE_DATE': ('本地已入账，日账单未找到', '核对微信实际支付日期及相邻日期账单。入账时间与支付时间可能跨日，不能直接判为少账。'),
    'LOCAL_IDENTITY_MISMATCH': ('商户或公众号不一致', '核对账单归属和商户配置，暂停相关订单的人工修正。'),
    'AMOUNT_MISMATCH': ('微信金额与订单不一致', '核对原订单金额、优惠和交易来源，保留原记录，交财务处理。'),
    'TRANSACTION_MISMATCH': ('微信交易号不一致', '从商户平台核对商户订单号和微信交易号，不覆盖原交易号。'),
    'PROVIDER_REFUND_REVIEW': ('存在退款记录，需核查', '核对退款单及原交易，确认退款入账。此工具不发起退款或冲正。'),
    'PROVIDER_STATE_REVIEW': ('微信交易状态需核查', '在商户平台核实交易最终状态，保留账单原件。'),
    'POSTED_TRANSACTION_MISSING': ('已入账订单缺少微信交易号', '核对原交易及入账日志，不凭本地状态补造交易号。'),
    'POSTED_AMOUNT_INVALID': ('已入账订单金额无效', '核对订单原金额及数据来源，提交财务和技术人员检查。'),
    'ORDER_ITEMS_MISSING': ('订单缺少账单明细', '核对原始账单快照及订单记录，不删除付款订单。'),
    'SNAPSHOT_AMOUNT_MISMATCH': ('账单明细合计不一致', '核对订单包含的项目和冻结金额，确认是否发生未授权更改。'),
    'DETAIL_COUNT_MISMATCH': ('缴费流水数量不一致', '逐项核对原账单与流水，排查缺失或重复，禁止再次收费。'),
    'DETAIL_AMOUNT_MISMATCH': ('流水金额不一致', '核对各收费项目的实收和订单总额，保留流水待财务处理。'),
    'RECEIPT_COUNT_MISMATCH': ('收据数量不一致', '每笔订单应有一份有效收据，核查缺失或重复收据。'),
    'RECEIPT_AMOUNT_MISMATCH': ('收据金额不一致', '核对收据总额与原支付订单。'),
    'RECEIPT_DETAIL_COUNT_MISMATCH': ('收据明细数量不一致', '逐项核对收据明细与订单项目。'),
    'RECEIPT_DETAIL_AMOUNT_MISMATCH': ('收据明细金额不一致', '核对各项目收据明细与订单总金额。'),
    'RECEIPT_DETAIL_LINK_MISMATCH': ('收据与流水关联不一致', '核对明细编号、费用、金额和起止日期，不能只看合计相等。'),
    'SNAPSHOT_MISSING': ('账单快照缺失或小区不一致', '检查账单和小区归属，禁止伪造账单补齐。'),
    'SNAPSHOT_NOT_SETTLED': ('账单状态与入账不一致', '检查已缴状态及审核状态，使用受控流程处理。'),
    'POSTED_RESERVATION_REMAINS': ('已入账订单仍占用费用', '检查入账事务及费用占用，不直接删除支付订单。'),
    'DETAIL_STATE_INVALID': ('缴费流水状态异常', '核查流水是否被作废或退款，保留原始记录。'),
    'SNAPSHOT_POSTING_MISMATCH': ('费用或账期与流水不一致', '逐项核对费用编号、冻结金额、起止时间及对应收据。'),
    'FEE_PERIOD_NOT_ADVANCED': ('费用缴费截止时间未推进', '核对收费日期与已支付账期，避免再次生成同账期费用；不要要求业主重复付款。'),
    'BILL_RECEIPTS_BELOW_POSTED': ('账单实收小于已入账支付', '核对账单实收统计及已支付项目，交财务调查，不直接改写原账单。'),
}


class ReconciliationError(ValueError):
    """Messages are fixed diagnostics, never raw source rows or query output."""


def validate_scope(bill_date, app_id, mch_id):
    try:
        parsed = date.fromisoformat(bill_date)
        if parsed.isoformat() != bill_date:
            raise ValueError()
    except (TypeError, ValueError):
        raise ReconciliationError('Use an actual calendar date in YYYY-MM-DD format') from None
    if not all(isinstance(value, str) and IDENTIFIER.fullmatch(value) for value in (app_id, mch_id)):
        raise ReconciliationError('Invalid application or merchant identifier')
    return parsed


def money_fen(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{1,12}(?:\.\d{1,2})?', value):
        raise ReconciliationError('Invalid money value; require nonnegative yuan with at most two decimals')
    return int(Decimal(value) * 100)


def _cells(row, width=0):
    cells = [value.strip().removeprefix('`').strip() for value in row]
    while len(cells) > width and not cells[-1]:
        cells.pop()
    return cells


def parse_bill(content, bill_date, app_id, mch_id):
    validate_scope(bill_date, app_id, mch_id)
    if not content or len(content) > MAX_BYTES:
        raise ReconciliationError('Bill is empty or exceeds the 32 MiB input limit')
    try:
        text = content.decode('utf-8-sig')
    except UnicodeDecodeError:
        try:
            text = content.decode('gb18030')
        except UnicodeDecodeError:
            raise ReconciliationError('Bill encoding is not UTF-8 or GB18030') from None
    try:
        rows = [row for row in csv.reader(io.StringIO(text, newline=''), strict=True) if any(cell.strip() for cell in row)]
    except csv.Error:
        raise ReconciliationError('Bill CSV is malformed') from None
    if len(rows) < 3 or len(rows) > MAX_ROWS + 3:
        raise ReconciliationError('Bill is incomplete or exceeds the 5000-row limit')
    header = _cells(rows[0])
    if len(header) != len(set(header)):
        raise ReconciliationError('Duplicate bill column names')
    required = ['交易时间', '公众账号ID', '商户号', '微信订单号', '商户订单号', '交易状态', '货币种类',
                '微信退款单号', '商户退款单号', '退款金额', '退款状态']
    amount_column = next((name for name in ('订单金额', '总金额') if name in header), None)
    if not amount_column or any(name not in header for name in required):
        raise ReconciliationError('Original ALL bill with refund columns and original order amount is required')
    if '退款申请时间' in header or '退款成功时间' in header:
        raise ReconciliationError('REFUND-only exports are not complete ALL daily bills')
    # A summary/footer is required: a truncated file must never pass as a complete day.
    summary_header = _cells(rows[-2])
    summary_values = _cells(rows[-1])
    if summary_header[0] != '总交易单数' or len(summary_values) != len(summary_header) or not summary_values[0].isdigit():
        raise ReconciliationError('Bill summary footer is missing or malformed')
    data = rows[1:-2]
    if int(summary_values[0]) != len(data):
        raise ReconciliationError('Bill summary transaction count does not match its rows')
    summary_amount = next((name for name in ('订单总金额', '总交易额') if name in summary_header), None)
    if not summary_amount:
        raise ReconciliationError('Bill summary original order total is missing')
    summary_fen = money_fen(summary_values[summary_header.index(summary_amount)])
    records, excluded, successful_orders, successful_transactions, gross_fen = [], 0, set(), set(), 0
    for source_values in data:
        values = _cells(source_values, width=len(header))
        if len(values) != len(header):
            raise ReconciliationError('Bill data row does not match its header')
        row = dict(zip(header, values))
        try:
            timestamp = datetime.strptime(row['交易时间'], '%Y-%m-%d %H:%M:%S')
        except ValueError:
            raise ReconciliationError('Invalid bill transaction time') from None
        if timestamp.date().isoformat() != bill_date:
            raise ReconciliationError('Bill contains a transaction outside the selected Beijing date')
        if row['商户号'] != mch_id or row['货币种类'] != 'CNY':
            raise ReconciliationError('Bill merchant or currency does not match the selected scope')
        for key in ('公众账号ID', '微信订单号', '商户订单号'):
            if not IDENTIFIER.fullmatch(row[key]):
                raise ReconciliationError('Missing or invalid bill identity')
        total = money_fen(row[amount_column])
        gross_fen += total
        refund = max(money_fen(row.get('申请退款金额', '0') or '0'), money_fen(row['退款金额'] or '0'))
        has_refund = refund > 0 or any(row[field] not in ('', '0') for field in ('微信退款单号', '商户退款单号', '退款状态'))
        if row['公众账号ID'] != app_id:
            excluded += 1
            continue
        state = row['交易状态']
        if not re.fullmatch(r'[A-Z_]{1,32}', state):
            raise ReconciliationError('Unrecognized bill transaction state')
        if state == 'SUCCESS':
            if total == 0 or row['商户订单号'] in successful_orders or row['微信订单号'] in successful_transactions:
                raise ReconciliationError('Duplicate or zero-value successful transaction')
            successful_orders.add(row['商户订单号'])
            successful_transactions.add(row['微信订单号'])
        records.append(dict(orderId=row['商户订单号'], transactionId=row['微信订单号'],
                            totalFen=total, refundFen=refund, state=state, hasRefund=has_refund))
    if gross_fen != summary_fen:
        raise ReconciliationError('Bill summary original order total does not match its rows')
    return dict(date=bill_date, appId=app_id, mchId=mch_id, records=records,
                excludedAppRows=excluded, inputRows=len(data), grossTotalFen=gross_fen,
                sourceSha256=hashlib.sha256(content).hexdigest())


def _difference(differences, code, order_id, **values):
    differences.append(dict(code=code, orderId=order_id, **values))


def _local_differences(orders):
    differences, seen = [], set()
    for order in orders:
        oid = order['orderId']
        if oid in seen:
            raise ReconciliationError('Duplicate local order rows')
        seen.add(oid)
        if order['state'] != 'POSTED':
            continue
        if not isinstance(order['totalFen'], int) or isinstance(order['totalFen'], bool) or order['totalFen'] <= 0:
            _difference(differences, 'POSTED_AMOUNT_INVALID', oid)
        if not order.get('transactionId'):
            _difference(differences, 'POSTED_TRANSACTION_MISSING', oid)
        if order['itemCount'] <= 0:
            _difference(differences, 'ORDER_ITEMS_MISSING', oid)
        checks = [('itemFen', 'totalFen', 'SNAPSHOT_AMOUNT_MISMATCH'),
                  ('detailCount', 'itemCount', 'DETAIL_COUNT_MISMATCH'),
                  ('detailFen', 'totalFen', 'DETAIL_AMOUNT_MISMATCH'),
                  ('receiptFen', 'totalFen', 'RECEIPT_AMOUNT_MISMATCH'),
                  ('receiptDetailCount', 'itemCount', 'RECEIPT_DETAIL_COUNT_MISMATCH'),
                  ('receiptDetailFen', 'totalFen', 'RECEIPT_DETAIL_AMOUNT_MISMATCH'),
                  ('linkedDetailCount', 'itemCount', 'RECEIPT_DETAIL_LINK_MISMATCH')]
        for left, right, code in checks:
            if order[left] != order[right]:
                _difference(differences, code, oid, expected=order[right], actual=order[left])
        if order['receiptCount'] != 1:
            _difference(differences, 'RECEIPT_COUNT_MISMATCH', oid, expected=1, actual=order['receiptCount'])
        for field, code in [('missingItems', 'SNAPSHOT_MISSING'), ('unsettledItems', 'SNAPSHOT_NOT_SETTLED'),
                            ('reservationCount', 'POSTED_RESERVATION_REMAINS'), ('invalidDetails', 'DETAIL_STATE_INVALID'),
                            ('snapshotPostingMismatches', 'SNAPSHOT_POSTING_MISMATCH'),
                            ('feeAdvanceMismatches', 'FEE_PERIOD_NOT_ADVANCED'),
                            ('billReceiptsBelowPosted', 'BILL_RECEIPTS_BELOW_POSTED')]:
            if order[field] != 0:
                _difference(differences, code, oid, actual=order[field])
    return differences


def _report(scope, orders, differences):
    return dict(format=1, generatedAtUtc=datetime.now(timezone.utc).isoformat(timespec='seconds'),
                scope=scope, consistentWithinScope=not differences, providerReconciled=False,
                officialSourceAuthenticated=False, localOrderCount=len(orders),
                postedOrderCount=sum(order['state'] == 'POSTED' for order in orders),
                differences=differences, differenceCounts=dict(Counter(row['code'] for row in differences)),
                actionsTriggered=[])


def local_audit(orders):
    return _report('LOCAL_POSTING_ONLY', orders, _local_differences(orders))


def reconcile(provider, orders):
    differences = _local_differences(orders)
    indexed = {order['orderId']: order for order in orders}
    provider_ids, matched = set(), 0
    for row in provider['records']:
        oid = row['orderId']
        provider_ids.add(oid)
        if row['state'] == 'REFUND' or row['refundFen'] > 0 or row['hasRefund']:
            _difference(differences, 'PROVIDER_REFUND_REVIEW', oid)
            continue
        if row['state'] != 'SUCCESS':
            _difference(differences, 'PROVIDER_STATE_REVIEW', oid)
            continue
        local = indexed.get(oid)
        if local is None:
            _difference(differences, 'PROVIDER_ONLY', oid)
            continue
        before = len(differences)
        if local['appId'] != provider['appId'] or local['mchId'] != provider['mchId']:
            _difference(differences, 'LOCAL_IDENTITY_MISMATCH', oid)
        if local['state'] != 'POSTED':
            _difference(differences, 'PROVIDER_PAID_NOT_POSTED', oid)
        if local['totalFen'] != row['totalFen']:
            _difference(differences, 'AMOUNT_MISMATCH', oid, expected=row['totalFen'], actual=local['totalFen'])
        if local['transactionId'] != row['transactionId']:
            _difference(differences, 'TRANSACTION_MISMATCH', oid)
        if before == len(differences) and not any(item['orderId'] == oid for item in differences):
            matched += 1
    for order in orders:
        if order['state'] == 'POSTED' and order['orderId'] not in provider_ids:
            _difference(differences, 'LOCAL_ONLY_CHECK_TRADE_DATE', order['orderId'])
    report = _report('IMPORTED_WECHAT_DAILY_BILL', orders, differences)
    report.update(providerReconciled=not differences, matchedPayments=matched,
                  **{key: provider[key] for key in ('date', 'appId', 'mchId', 'sourceSha256', 'excludedAppRows', 'inputRows', 'grossTotalFen')})
    report['limitations'] = ['Imported CSV authenticity must be confirmed by finance; SHA-256 is not a WeChat signature.',
                             'Footer row count and gross order total are checked; fee, net settlement and refund summary totals are not reconciled.',
                             'Local posted time is not provider success time. Local-only rows need adjacent-day review.',
                             'Provider-only rows may belong to another application using the same merchant identity.',
                             'Refunds and reversals require separate authorized investigation; no repair is performed.']
    return report


def _literal(value):
    return 'CONVERT(0x' + value.encode('utf-8').hex() + ' USING utf8mb4)'


def audit_sql(bill_date, app_id, mch_id, order_ids):
    start = validate_scope(bill_date, app_id, mch_id)
    if len(order_ids) > MAX_ROWS or any(not isinstance(oid, str) or not IDENTIFIER.fullmatch(oid) for oid in order_ids):
        raise ReconciliationError('Invalid or excessive order identifiers')
    scope = (f"(po.app_id={_literal(app_id)} AND po.mch_id={_literal(mch_id)} "
             f"AND po.posted_time >= {_literal(start.isoformat())} "
             f"AND po.posted_time < {_literal((start + timedelta(days=1)).isoformat())})")
    if order_ids:
        scope += ' OR po.order_id IN (' + ','.join(_literal(oid) for oid in sorted(set(order_ids))) + ')'
    # Never interpolate raw values or accept a caller-provided table/schema/SQL fragment.
    # One connection and one consistent snapshot cover orders and every posting aggregate.
    return "SET time_zone = '+08:00';\nSTART TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY;\n" + f"""
WITH selected AS (
 SELECT po.order_id,po.community_id,po.app_id,po.mch_id,po.state,po.total_fee,po.transaction_id,po.posted_time
 FROM payment_order po WHERE {scope} ORDER BY po.order_id LIMIT 10001
), details AS (
 SELECT po.order_id,COUNT(*) n,CAST(SUM(d.received_amount*100) AS SIGNED) fen,
 SUM(d.status_cd<>'0' OR COALESCE(d.state,'')<>'1400') invalid
 FROM selected po JOIN pay_fee_detail d ON d.community_id=po.community_id
 AND d.remark IN (po.order_id,CONCAT('微信账单支付 ',po.order_id)) GROUP BY po.order_id
), receipts AS (
 SELECT po.order_id,COUNT(*) n,CAST(SUM(r.amount*100) AS SIGNED) fen
 FROM selected po JOIN fee_receipt r ON r.community_id=po.community_id AND r.status_cd='0'
 AND r.remark IN (po.order_id,CONCAT('微信账单支付 ',po.order_id)) GROUP BY po.order_id
), receipt_details AS (
 SELECT po.order_id,COUNT(*) n,CAST(SUM(rd.amount*100) AS SIGNED) fen,
 SUM(d.detail_id IS NOT NULL AND d.received_amount=rd.amount AND d.start_time=rd.start_time
     AND d.end_time=rd.end_time AND d.status_cd='0' AND d.state='1400'
     AND d.remark IN (po.order_id,CONCAT('微信账单支付 ',po.order_id))) linked
 FROM selected po JOIN fee_receipt r ON r.community_id=po.community_id AND r.status_cd='0'
 AND r.remark IN (po.order_id,CONCAT('微信账单支付 ',po.order_id))
 JOIN fee_receipt_detail rd ON rd.receipt_id=r.receipt_id AND rd.community_id=r.community_id AND rd.status_cd='0'
 LEFT JOIN pay_fee_detail d ON d.detail_id=rd.detail_id AND d.fee_id=rd.fee_id AND d.community_id=rd.community_id
 GROUP BY po.order_id
), relevant_bills AS (
 SELECT DISTINCT bof.bill_id,bof.community_id FROM selected po
 JOIN payment_order_item i ON i.order_id=po.order_id
 JOIN bill_owe_fee bof ON bof.owe_id=i.owe_id AND bof.community_id=po.community_id
), bill_postings AS (
 SELECT bof.bill_id,bof.community_id,SUM(bof.bill_amount_owed*100) fen
 FROM relevant_bills rb JOIN bill_owe_fee bof ON bof.bill_id=rb.bill_id AND bof.community_id=rb.community_id
 JOIN payment_order_item i ON i.owe_id=bof.owe_id
 JOIN payment_order po ON po.order_id=i.order_id AND po.community_id=bof.community_id AND po.state='POSTED'
 GROUP BY bof.bill_id,bof.community_id
), items AS (
 SELECT po.order_id,COUNT(*) n,CAST(SUM(bof.bill_amount_owed*100) AS SIGNED) fen,
 SUM(bof.owe_id IS NULL OR b.bill_id IS NULL) missing,
 SUM(COALESCE(bof.state,'')<>'2000' OR COALESCE(b.review_state,'')<>'PUBLISHED') unsettled,
 SUM(pf.fee_id IS NULL OR pf.end_time IS NULL OR pf.end_time<bof.deadline_time) fee_advance_mismatches,
 SUM(COALESCE(b.receipts,0)*100<COALESCE(bp.fen,0)) receipts_below_posted,
 SUM((SELECT COUNT(*) FROM pay_fee_detail d
      JOIN fee_receipt_detail rd ON rd.detail_id=d.detail_id AND rd.fee_id=d.fee_id
           AND rd.community_id=d.community_id AND rd.status_cd='0'
      JOIN fee_receipt r ON r.receipt_id=rd.receipt_id AND r.community_id=rd.community_id AND r.status_cd='0'
      WHERE d.community_id=po.community_id AND d.fee_id=bof.fee_id AND d.status_cd='0' AND d.state='1400'
      AND d.remark IN (po.order_id,CONCAT('微信账单支付 ',po.order_id))
      AND r.remark IN (po.order_id,CONCAT('微信账单支付 ',po.order_id))
      AND d.received_amount=bof.bill_amount_owed AND rd.amount=bof.bill_amount_owed
      AND d.start_time=bof.fee_end_time AND rd.start_time=bof.fee_end_time
      AND d.end_time=bof.deadline_time AND rd.end_time=bof.deadline_time) <> 1) posting_mismatches
 FROM selected po JOIN payment_order_item i ON i.order_id=po.order_id
 LEFT JOIN bill_owe_fee bof ON bof.owe_id=i.owe_id AND bof.community_id=po.community_id
 LEFT JOIN bill b ON b.bill_id=bof.bill_id AND b.community_id=bof.community_id
 LEFT JOIN pay_fee pf ON pf.fee_id=bof.fee_id AND pf.community_id=bof.community_id
 LEFT JOIN bill_postings bp ON bp.bill_id=bof.bill_id AND bp.community_id=bof.community_id
 GROUP BY po.order_id
), reservations AS (
 SELECT po.order_id,COUNT(*) n FROM selected po JOIN payment_order_reservation r ON r.order_id=po.order_id
 GROUP BY po.order_id
)
SELECT JSON_OBJECT('orderId',po.order_id,'communityId',po.community_id,'appId',po.app_id,'mchId',po.mch_id,
 'state',po.state,'totalFen',po.total_fee,'transactionId',po.transaction_id,
 'postedDate',DATE_FORMAT(po.posted_time,'%Y-%m-%d'),
 'itemCount',COALESCE(i.n,0),'itemFen',COALESCE(i.fen,0),'missingItems',COALESCE(i.missing,0),
 'unsettledItems',COALESCE(i.unsettled,0),'snapshotPostingMismatches',COALESCE(i.posting_mismatches,0),
 'feeAdvanceMismatches',COALESCE(i.fee_advance_mismatches,0),'billReceiptsBelowPosted',COALESCE(i.receipts_below_posted,0),
 'detailCount',COALESCE(d.n,0),'detailFen',COALESCE(d.fen,0),
 'invalidDetails',COALESCE(d.invalid,0),'receiptCount',COALESCE(r.n,0),'receiptFen',COALESCE(r.fen,0),
 'receiptDetailCount',COALESCE(rd.n,0),'receiptDetailFen',COALESCE(rd.fen,0),
 'linkedDetailCount',COALESCE(rd.linked,0),'reservationCount',COALESCE(v.n,0))
FROM selected po LEFT JOIN details d ON d.order_id=po.order_id LEFT JOIN receipts r ON r.order_id=po.order_id
LEFT JOIN receipt_details rd ON rd.order_id=po.order_id LEFT JOIN items i ON i.order_id=po.order_id
LEFT JOIN reservations v ON v.order_id=po.order_id ORDER BY po.order_id;
COMMIT;
"""


def read_local_orders(bill_date, app_id, mch_id, order_ids=()):
    output = runtime_ops.mysql_query(audit_sql(bill_date, app_id, mch_id, order_ids), 'TT')
    rows = [json.loads(line) for line in output.splitlines() if line]
    if len(rows) > 10000:
        raise ReconciliationError('More than 10000 local orders; refuse a partial reconciliation')
    return rows


def readable_report(report):
    title = '本地入账一致性检查' if report['scope'] == 'LOCAL_POSTING_ONLY' else '导入微信日账单核对'
    note = ('尚未核对微信官方日账单。此结果只说明所选日期的本地订单、账单、流水及收据是否一致。'
            if report['scope'] == 'LOCAL_POSTING_ONLY' else
            '请由财务确认 CSV 来自商户平台的 ALL 日交易账单。文件摘要不等于微信签名；退款、手续费及净结算额需单独核对。')
    result = '当前检查范围内一致' if report['consistentWithinScope'] else '存在待核查差异'
    stamp = datetime.fromisoformat(report['generatedAtUtc']).astimezone(timezone(timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S')
    rows = []
    for row in report['differences']:
        label, advice = DIFFERENCES.get(row['code'], (row['code'], '请交由财务核查原始记录。'))
        cells = [label, row['orderId'], row.get('expected', '—'), row.get('actual', '—'), advice]
        rows.append('<tr>' + ''.join('<td>' + escape(str(cell)) + '</td>' for cell in cells) + '</tr>')
    table = ''.join(rows) or '<tr><td colspan="5">当前检查范围没有发现差异。</td></tr>'
    return f'''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">
<title>{title}</title><style>
body{{margin:0;background:#f4f6fa;color:#243148;font:15px/1.7 system-ui,"Microsoft YaHei",sans-serif}}
main{{max-width:1160px;margin:36px auto;padding:0 24px}}h1{{font-size:26px;margin-bottom:4px}}
.muted{{color:#66748b}}.panel{{background:white;border:1px solid #dfe5ee;border-radius:12px;padding:22px;margin:20px 0}}
.status{{font-size:20px;font-weight:600;color:{'#157347' if report['consistentWithinScope'] else '#a46008'}}}
.cards{{display:flex;flex-wrap:wrap;gap:24px}}.cards div{{min-width:150px}}strong{{display:block;font-size:28px}}
.scroll{{overflow:auto}}table{{border-collapse:collapse;width:100%;min-width:760px}}th,td{{text-align:left;padding:12px;border-bottom:1px solid #e7ebf2;vertical-align:top}}
th{{background:#f7f9fc;font-weight:600}}td:nth-child(2){{font-family:monospace;overflow-wrap:anywhere;max-width:250px}}
a{{color:#245edb}}.note{{background:#fff8e6;border-left:4px solid #dca62c;padding:16px}}
</style></head><body><main><h1>{title}</h1><div class="muted">账单日：{escape(report.get('date', '未指定'))} · 生成时间：{stamp}（北京时间）</div>
<section class="panel"><div class="status">{result}</div><p class="note">{note}</p>
<div class="cards"><div>本地订单<strong>{report['localOrderCount']}</strong></div><div>已入账订单<strong>{report['postedOrderCount']}</strong></div>
<div>差异项目<strong>{len(report['differences'])}</strong></div></div></section>
<section class="panel"><h2>核查明细</h2><p>金额均以分为单位。一个订单可能出现多项差异；请勿依据本报告直接补账、删账或再次向业主收费。</p>
<div class="scroll"><table><thead><tr><th>差异</th><th>商户订单号</th><th>预期值</th><th>实际值</th><th>建议核查步骤</th></tr></thead><tbody>{table}</tbody></table></div>
<p><a href="differences.csv">打开差异 CSV</a> · <a href="report.json">查看完整核对数据</a></p></section>
<p class="muted">报告仅供有权人员使用，包含内部订单编号。检查未改动账款、发起支付或发送通知。</p></main></body></html>'''


def save_report(report, root=REPORTS):
    folder = Path(root) / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:12])
    folder.mkdir(parents=True, exist_ok=False, mode=0o700)
    files = [folder / 'report.json', folder / 'differences.csv', folder / 'report.html']
    files[0].write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    with files[1].open('w', encoding='utf-8-sig', newline='') as output:
        writer = csv.writer(output)
        writer.writerow(['差异类型', '本地商户订单号', '预期值（金额为分）', '实际值（金额为分）', '差异说明', '核查步骤'])
        for row in report['differences']:
            cells = [row['code'], row['orderId'], row.get('expected', ''), row.get('actual', '')]
            cells.extend(DIFFERENCES.get(row['code'], (row['code'], '请交由财务核查。')))
            writer.writerow(["'" + value if isinstance(value, str) and value.lstrip().startswith(('=', '+', '-', '@'))
                             else value for value in cells])
    files[2].write_text(readable_report(report), encoding='utf-8')
    if os.name != 'nt':
        for file in files:
            file.chmod(0o600)
    return folder


def console_summary(report, folder):
    return {key: report[key] for key in ('scope', 'consistentWithinScope', 'providerReconciled',
                                        'localOrderCount', 'postedOrderCount', 'differenceCounts', 'actionsTriggered')} | {'reportDirectory': str(folder)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    for action in ('local', 'bill'):
        command = commands.add_parser(action)
        command.add_argument('--date', required=True)
        command.add_argument('--app-id', required=True)
        command.add_argument('--mch-id', required=True)
        if action == 'bill':
            command.add_argument('--file', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        validate_scope(args.date, args.app_id, args.mch_id)
        provider = None
        if args.action == 'bill':
            with args.file.open('rb') as stream:
                provider = parse_bill(stream.read(MAX_BYTES + 1), args.date, args.app_id, args.mch_id)
        ids = [row['orderId'] for row in provider['records']] if provider else []
        orders = read_local_orders(args.date, args.app_id, args.mch_id, ids)
        report = reconcile(provider, orders) if provider else local_audit(orders)
        report.update(date=args.date)
        folder = save_report(report)
        print(json.dumps(console_summary(report, folder), ensure_ascii=False, indent=2))
        return 0 if report['consistentWithinScope'] else 1
    except ReconciliationError as error:
        print(json.dumps(dict(passed=False, error=str(error), actionsTriggered=[])))
        return 2
    except Exception:
        print(json.dumps(dict(passed=False, error='Reconciliation could not complete; no source data or credentials are printed.', actionsTriggered=[])))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
