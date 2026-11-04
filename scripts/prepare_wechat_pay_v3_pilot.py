"""Prepare an isolated, manually authored CNY 0.01 acceptance bill; default read-only.

This does not exercise normal bill generation or review. It never creates a payment
order, requests prepay, sends messages, enables collection, or removes paid history.
Only the explicit --apply switch writes four newly identified business rows.
"""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import uuid

import migrate_business_schema as base

RUN = base.local.RUN
SELECTION = RUN / 'wechat-pay-pilot-selection.json'
RECORD = RUN / 'wechat-pay-v3-pilot.json'
LABEL = 'WECHAT V3 LIVE ACCEPTANCE CNY 0.01 - MANUAL FIXTURE'
KEYS = {'pay_fee_config': 'config_id', 'pay_fee': 'fee_id', 'bill': 'bill_id', 'bill_owe_fee': 'owe_id'}
IDS = {'pay_fee_config': 'config', 'pay_fee': 'fee', 'bill': 'bill', 'bill_owe_fee': 'owe'}
SNAPSHOT_TABLES = ('building_owner', 'building_owner_room_rel', 'building_room',
                   'owner_app_user', 'u_user', 'pay_fee_config', 'pay_fee', 'bill',
                   'bill_owe_fee', 'pay_fee_detail', 'fee_receipt', 'fee_receipt_detail',
                   'payment_order', 'payment_order_item', 'payment_order_reservation',
                   'account', 'account_detail', 'contract')


class PilotError(RuntimeError):
    pass


def q(value):
    encoded = str(value).encode('utf-8').hex()
    return 'CONVERT(0x' + encoded + ' USING utf8mb4)' if encoded else "''"


def fixture_ids(prefix):
    if not re.fullmatch(r'PV3[a-f0-9]{12}', prefix):
        raise PilotError('Unrecognized pilot prefix')
    return {name: prefix + suffix for name, suffix in
            {'config': 'C', 'fee': 'F', 'bill': 'B', 'owe': 'O', 'business': 'X'}.items()}


def validate_selection(selection):
    requested_name = selection.get('requestedOwnerName')
    if selection.get('approvedForPaymentPilot') is not True:
        raise PilotError('Private selection lacks explicit payment pilot approval')
    if not isinstance(requested_name, str) or not requested_name.strip() or len(requested_name) > 100:
        raise PilotError('Private selection must name the explicitly approved owner')
    bindings, rooms = selection.get('bindings', []), selection.get('rooms', [])
    if len(bindings) != 1 or len(rooms) != 1:
        raise PilotError('Selection must contain exactly one binding and one room')
    binding, room = bindings[0], rooms[0]
    if (binding.get('state') != '12000' or binding.get('appType') != 'WECHAT'
            or binding.get('hasOpenId') != 1 or room.get('relState') != '2001'):
        raise PilotError('Selection must be an approved WECHAT binding and active room relation')
    result = {'communityId': selection.get('communityId'),
              **{k: binding.get(k) for k in ('userId', 'ownerId', 'memberId')},
              'roomId': room.get('roomId'), 'roomNumber': room.get('roomNumber')}
    if any(not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,30}', value)
           for value in result.values()):
        raise PilotError('Selection contains an invalid identifier')
    return {**result, 'requestedOwnerName': requested_name, 'approvedForPaymentPilot': True}


def collection_disabled():
    # Access only the enabled boolean; merchant fields are never accessed or logged.
    path = RUN / 'wechat-pay.json'
    if path.exists():
        try:
            enabled = json.loads(path.read_text(encoding='utf-8-sig')).get('enabled')
        except (ValueError, OSError, AttributeError):
            raise PilotError('Cannot verify private collection enabled flag') from None
        if enabled is not False:
            raise PilotError('Collection must explicitly remain disabled')
    # The effective front profile must also be disabled, even if the file changed.
    profile = RUN / 'config/front.properties'
    if not profile.exists():
        raise PilotError('Effective front profile is unavailable')
    flags = re.findall(r'^java110\.payment\.enabled=(.*)$', profile.read_text(encoding='utf-8'), re.M)
    # Older local profiles omit this key; the application default is false.
    if flags not in ([], ['false']):
        raise PilotError('Effective front collection flag must remain false')


def read_rows(sql):
    raw = base.query('TT', sql)
    return [json.loads(line) for line in raw.splitlines() if line.strip()]


def validate_identity_counts(stats):
    if (stats.get('bindings', 0) < 1 or stats.get('distinctIdentities') != 1
            or stats.get('missingIdentities') != 0):
        raise PilotError('Approved WECHAT bindings have missing or ambiguous identities')


def selected_context(selected):
    c, u, o, m, r = (q(selected[k]) for k in ('communityId', 'userId', 'ownerId', 'memberId', 'roomId'))
    identities = read_rows("SELECT JSON_OBJECT('bindings',COUNT(*),'distinctIdentities',"
                           "COUNT(DISTINCT CASE WHEN open_id IS NOT NULL AND open_id<>'' THEN open_id END),"
                           "'missingIdentities',COALESCE(SUM(open_id IS NULL OR open_id=''),0)) "
                           'FROM owner_app_user WHERE user_id=' + u + ' AND community_id=' + c
                           + " AND status_cd='0' AND state='12000' AND app_type='WECHAT'")
    if len(identities) != 1:
        raise PilotError('Could not verify approved WECHAT identity counts')
    validate_identity_counts(identities[0])
    bindings = read_rows("SELECT JSON_OBJECT('ownerId',bo.owner_id,'memberId',bo.member_id,'ownerName',bo.name,"
                         "'hasOpenId',IF(oau.open_id IS NOT NULL AND oau.open_id<>'',1,0)) "
                         "FROM owner_app_user oau JOIN building_owner bo ON bo.member_id=oau.member_id "
                         "AND bo.community_id=oau.community_id AND bo.status_cd='0' WHERE oau.user_id=" + u
                         + ' AND oau.community_id=' + c + " AND oau.status_cd='0' AND oau.state='12000' "
                         "AND oau.app_type='WECHAT' AND bo.owner_id=" + o + ' AND bo.member_id=' + m)
    if len(bindings) != 1 or bindings[0] != {'ownerId': selected['ownerId'], 'memberId': selected['memberId'],
                                           'ownerName': selected['requestedOwnerName'], 'hasOpenId': 1}:
        raise PilotError('Current approved binding/owner no longer matches the selection')
    owners = base.query('TT', 'SELECT COUNT(*) FROM building_owner WHERE owner_id=' + o
                        + ' AND community_id=' + c + " AND status_cd='0'")
    if owners != '1':
        raise PilotError('Selected owner is ambiguous')
    rooms = read_rows("SELECT JSON_OBJECT('roomId',br.room_id,'roomNumber',br.room_num) "
                      'FROM building_owner_room_rel rel JOIN building_room br ON br.room_id=rel.room_id '
                      "AND br.status_cd='0' WHERE rel.owner_id=" + o
                      + " AND rel.status_cd='0' AND rel.state='2001' AND br.community_id=" + c)
    if rooms != [{'roomId': selected['roomId'], 'roomNumber': selected['roomNumber']}]:
        raise PilotError('Current owner room relation is missing, changed, or ambiguous')
    sources = read_rows("SELECT DISTINCT JSON_OBJECT('incomeObjId',income_obj_id,'creatorId',user_id,"
                        "'feeType',fee_type_cd) FROM pay_fee WHERE community_id=" + c
                        + ' AND payer_obj_id=' + r + " AND payer_obj_type='3333' AND status_cd='0' "
                        "AND fee_id NOT LIKE 'PV3%'")
    if len(sources) != 1 or any(not v for v in sources[0].values()):
        raise PilotError('Existing room fee accounting source is missing or ambiguous')
    return {**sources[0], 'ownerName': selected['requestedOwnerName'], 'payerName': selected['roomNumber']}


def insert_sql(table, values, columns):
    if table not in KEYS or set(values) - set(columns):
        raise PilotError('Unexpected insert table or columns')
    for column, (_, nullable, default, extra) in columns.items():
        if column not in values and not nullable and default is None and not any(
                option in extra for option in ('auto_increment', 'generated')):
            raise PilotError('Unmapped required column: ' + table + '.' + column)
    for column, value in values.items():
        width = re.match(r'varchar\((\d+)\)', columns[column][0])
        if width and len(str(value)) > int(width[1]):
            raise PilotError('Insert value exceeds column width: ' + table + '.' + column)
    return ('INSERT INTO `' + table + '` (' + ','.join('`' + k + '`' for k in values)
            + ') VALUES (' + ','.join(q(v) for v in values.values()) + ')')


def expected_rows(record, context):
    ids, c = record['ids'], record['communityId']
    start, deadline = record['startTime'], record['deadlineTime']
    return {
        'pay_fee_config': {'config_id': ids['config'], 'b_id': ids['business'], 'community_id': c,
                           'fee_type_cd': context['feeType'], 'square_price': '0.00', 'additional_amount': '0.01',
                           'start_time': start, 'end_time': deadline, 'fee_flag': '2006012', 'fee_name': LABEL,
                           'computing_formula': '2002', 'computing_formula_text': LABEL, 'bill_type': '005',
                           'payment_cycle': '1', 'payment_cd': '1200', 'status_cd': '0', 'is_default': 'F'},
        'pay_fee': {'fee_id': ids['fee'], 'b_id': ids['business'], 'community_id': c,
                    'fee_type_cd': context['feeType'], 'payer_obj_id': record['roomId'],
                    'income_obj_id': context['incomeObjId'], 'user_id': context['creatorId'],
                    'start_time': start, 'end_time': start, 'amount': '0.01', 'fee_flag': '2006012',
                    'state': '2008001', 'config_id': ids['config'], 'payer_obj_type': '3333', 'status_cd': '0'},
        'bill': {'bill_id': ids['bill'], 'bill_name': LABEL, 'receivable': '0.01', 'receipts': '0.00',
                 'bill_time': start, 'community_id': c, 'config_id': ids['config'], 'remark': LABEL,
                 'cur_bill': 'T', 'cur_receivable': '0.01', 'review_state': 'PUBLISHED',
                 'review_remark': 'Manual acceptance fixture; normal generation/review not exercised'},
        'bill_owe_fee': {'owe_id': ids['owe'], 'bill_id': ids['bill'], 'fee_id': ids['fee'],
                         'bill_amount_owed': '0.01', 'amount_owed': '0.01', 'fee_end_time': start,
                         'deadline_time': deadline, 'owner_id': record['ownerId'], 'owner_name': context['ownerName'],
                         'owner_tel': '', 'payer_obj_name': context['payerName'], 'payer_obj_type': '3333',
                         'community_id': c, 'state': '1000'}}


def row_predicate(values):
    return ' AND '.join('`' + key + '` <=> ' + q(value) for key, value in values.items())


def verify_rows(rows):
    for table, values in rows.items():
        key = KEYS[table]
        total = base.query('TT', 'SELECT COUNT(*) FROM `' + table + '` WHERE `' + key + '`=' + q(values[key]))
        valid = base.query('TT', 'SELECT COUNT(*) FROM `' + table + '` WHERE ' + row_predicate(values))
        if total != '1' or valid != '1':
            raise PilotError('Recorded pilot rows changed or were paid; preserving every row for review')


def snapshot(ids):
    result = {}
    for table in SNAPSHOT_TABLES:
        columns, _ = base.inspect('TT', table)
        if not columns:
            raise PilotError('Required preservation table is absent: ' + table)
        fields = ','.join("IFNULL(HEX(`" + col + "`),'NULL')" for col in columns)
        exclusion = (' WHERE `' + KEYS[table] + '`<>' + q(ids[IDS[table]])) if table in KEYS else ''
        raw = base.query('TT', "SELECT SHA2(CONCAT_WS('|'," + fields + '),256) FROM `' + table + '`'
                         + exclusion + ' ORDER BY 1')
        result[table] = {'rows': len(raw.splitlines()) if raw else 0,
                         'sha256': hashlib.sha256(raw.encode('ascii')).hexdigest()}
    return result


def save(record):
    RECORD.parent.mkdir(parents=True, exist_ok=True)
    temporary = RECORD.with_suffix('.json.partial')
    temporary.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(RECORD)


def prepare(apply=False):
    collection_disabled()
    selected = validate_selection(json.loads(SELECTION.read_text(encoding='utf-8-sig')))
    context = selected_context(selected)
    if RECORD.exists():
        record = json.loads(RECORD.read_text(encoding='utf-8'))
        if (record.get('label') != LABEL or record.get('ids') != fixture_ids(record.get('prefix', ''))
                or any(record.get(k) != v for k, v in selected.items())):
            raise PilotError('Existing provenance differs from selected scope')
        rows = record.get('expectedRows')
        if rows != expected_rows(record, context):
            raise PilotError('Existing provenance does not match the approved manual fixture')
        counts = [base.query('TT', 'SELECT COUNT(*) FROM `' + t + '` WHERE `' + KEYS[t] + '`=' + q(v[KEYS[t]]))
                  for t, v in rows.items()]
        if counts == ['1'] * 4:
            verify_rows(rows)
            return 'already prepared; verified', record
        if counts != ['0'] * 4 or record.get('status') != 'planned':
            raise PilotError('Incomplete or changed pilot; no rows will be overwritten')
    else:
        start = datetime.now().replace(day=1, hour=0, minute=0, second=0, microsecond=0)
        deadline = start.replace(year=start.year + (start.month == 12), month=start.month % 12 + 1)
        prefix = 'PV3' + uuid.uuid4().hex[:12]
        record = {**selected, 'label': LABEL, 'prefix': prefix, 'ids': fixture_ids(prefix), 'amount': '0.01',
                  'startTime': start.strftime('%Y-%m-%d %H:%M:%S'),
                  'deadlineTime': deadline.strftime('%Y-%m-%d %H:%M:%S'), 'status': 'planned',
                  'sameIdentityMultiBindingSupported': True,
                  'normalBillingGenerationExercised': False, 'normalBillReviewExercised': False}
        rows = expected_rows(record, context)
        record['expectedRows'] = rows
    schemas = {}
    for table in rows:
        columns, _ = base.inspect('TT', table)
        engine = base.query('TT', 'SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=' + q(table))
        if engine.upper() != 'INNODB':
            raise PilotError('Pilot inserts require InnoDB')
        schemas[table] = columns
    statements = [insert_sql(t, v, schemas[t]) for t, v in rows.items()]
    for table, values in rows.items():
        if base.query('TT', 'SELECT COUNT(*) FROM `' + table + '` WHERE `' + KEYS[table] + '`=' + q(values[KEYS[table]])) != '0':
            raise PilotError('New pilot identifier collision; existing rows will not be adopted')
    if not apply:
        return 'plan verified; read-only', record
    with base.migration_lock():
        collection_disabled()
        if selected_context(selected) != context:
            raise PilotError('Selected owner context changed before apply')
        before = snapshot(record['ids'])
        backup = base.create_backup('TT', RUN / 'backups/wechat-pay-v3-pilot')
        record.update({'backup': backup, 'existingBefore': before})
        save(record)  # Durable provenance and full backup precede the transaction.
        guards = []
        for table, values in rows.items():
            key = KEYS[table]
            condition = ('(SELECT COUNT(*) FROM `' + table + '` WHERE `' + key + '`=' + q(values[key])
                         + ')=1 AND (SELECT COUNT(*) FROM `' + table + '` WHERE ' + row_predicate(values) + ')=1')
            guards.append('INSERT INTO `_pv3_guard` VALUES (IF(' + condition + ',1,NULL))')
        # mysql exits on the first SQL error. Closing its connection rolls back all
        # four inserts; strict-mode NOT NULL guards run before COMMIT.
        sql = ["SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION'",
               'CREATE TEMPORARY TABLE `_pv3_guard` (ok INT NOT NULL) ENGINE=InnoDB',
               'START TRANSACTION', *statements, *guards, 'COMMIT']
        base.query('TT', ';\n'.join(sql) + ';')
        verify_rows(rows)
        after = snapshot(record['ids'])
        record.update({'status': 'prepared', 'existingAfter': after, 'existingDataPreserved': before == after})
        save(record)
        if before != after:
            raise PilotError('Existing data changed concurrently; inspect provenance and backup; history preserved')
        return 'prepared; existing rows preserved', record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--plan', action='store_true', help='read-only validation (the default)')
    mode.add_argument('--apply', action='store_true', help='back up TT and insert the four recorded pilot rows')
    args = parser.parse_args()
    try:
        status, _ = prepare(apply=args.apply)
        print('Pilot preparation: ' + status + '; amount CNY 0.01')
    except PilotError as error:
        print('Pilot preparation: refused; amount CNY 0.01; ' + str(error), file=sys.stderr)
        return 1
    except Exception:
        # Never echo database/private-file exceptions or credential-bearing values.
        print('Pilot preparation: failed; amount CNY 0.01; inspect local provenance and backup', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
