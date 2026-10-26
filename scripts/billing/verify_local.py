"""Verify original monthly generation against isolated local HTTP services.

Creates two labelled fee rows, invokes existing business code twice, and removes
only the exact randomly identified audit rows in finally. No account operations,
payments, SMS, WeChat API calls, or scheduled job activation are performed.
Exit 0 means checks passed; 1 means a reproduced business issue; 2 is a tool error.
"""
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

from verify import ROOT, compile_audit, local

LABEL = 'LOCAL BILLING AUDIT ONLY - DO NOT PAY'


def query(sql):
    return local.mysql_run(sql, database='TT', capture=True)


def quote(value):
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def preflight():
    # local.mysql_run fixes the endpoint to 127.0.0.1:13306 and configures utf8mb4.
    if query("SELECT value FROM c_mapping WHERE `key`='SMS_SEND_SWITCH'") != 'OFF':
        raise RuntimeError('This audit requires the existing local SMS switch to be OFF')
    job = local.processes().get('job', {})
    if '-Djava110.local.disableScheduledTasks=true' not in job.get('command', []):
        raise RuntimeError('Use the normal local profile with automatic tasks disabled')
    for port in (13306, 8002, 8009, 8010):
        if not local.listening(port):
            raise RuntimeError('A required isolated local service is not listening')


def prepare():
    record = json.loads((ROOT / '.runtime/wechat-test-fixture.json').read_text(encoding='utf-8-sig'))
    community, room, owner = record['communityId'], record['ids']['room'], record['ids']['owner']
    for value in (community, room, owner):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,30}', value):
            raise RuntimeError('Unexpected existing fixture identifier')
    if query(f"SELECT count(*) FROM building_owner_room_rel WHERE owner_id={quote(owner)} AND room_id={quote(room)} AND status_cd='0'") != '1':
        raise RuntimeError('The existing synthetic owner/room relationship is not unique')
    base = 'BLA' + uuid.uuid4().hex[:12]
    fixture = dict(prefix=base, communityId=community, roomId=room, ownerId=owner,
                   configId=base+'C', feeId=base+'F', label=LABEL)
    input_path = ROOT / '.runtime/billing-audit/local-fixture.json'
    input_path.parent.mkdir(parents=True, exist_ok=True)
    # Persist exact cleanup identifiers BEFORE performing any writes.
    input_path.write_text(json.dumps(fixture, indent=2), encoding='utf-8')
    columns = query(f"SELECT user_id,income_obj_id FROM pay_fee WHERE fee_id={quote(record['ids']['fee'])} AND community_id={quote(community)}").split('\t')
    if len(columns) != 2:
        raise RuntimeError('Expected one existing synthetic fee to identify its local creator')
    user, income = columns
    if query(f"SELECT count(*) FROM pay_fee_config WHERE config_id={quote(fixture['configId'])}") != '0':
        raise RuntimeError('Random audit ID collision')
    # Dates are calendar-relative, but no production billing rule or schedule is changed.
    start = "DATE_FORMAT(CURRENT_DATE(),'%Y-%m-01 00:00:00')"
    end = "DATE_ADD(" + start + ", INTERVAL 12 MONTH)"
    sql = ['START TRANSACTION',
        "INSERT INTO pay_fee_config (config_id,b_id,community_id,fee_type_cd,square_price,additional_amount,start_time,end_time,fee_flag,fee_name,computing_formula,computing_formula_text,bill_type,payment_cycle,payment_cd,status_cd) VALUES ("
        + ','.join([quote(fixture['configId']), quote(base+'B'), quote(community), "'888800010001'", "'0'", "'0.01'", start, end,
                    "'1003006'", quote('AUDIT monthly fee - do not pay'), "'2002'", quote(LABEL), "'002'", "'1'", "'1200'", "'0'"]) + ')',
        "INSERT INTO pay_fee (fee_id,b_id,fee_type_cd,community_id,payer_obj_id,income_obj_id,start_time,end_time,amount,user_id,fee_flag,state,config_id,payer_obj_type,status_cd) VALUES ("
        + ','.join([quote(fixture['feeId']), quote(base+'B'), "'888800010001'", quote(community), quote(room), quote(income), start, start,
                    "'0.01'", quote(user), "'1003006'", "'2008001'", quote(fixture['configId']), "'3333'", "'0'"]) + ')', 'COMMIT']
    try:
        query(';'.join(sql)+';')
    except Exception:
        cleanup(fixture)
        raise
    return fixture, input_path


def cleanup(fixture):
    if not re.fullmatch(r'BLA[0-9a-f]{12}', fixture['prefix']) or fixture['label'] != LABEL:
        raise RuntimeError('Unrecognized audit cleanup record')
    base, community = fixture['prefix'], fixture['communityId']
    if fixture['configId'] != base+'C' or fixture['feeId'] != base+'F':
        raise RuntimeError('Audit cleanup identifiers do not match')
    if query(f"SELECT count(*) FROM pay_fee_config WHERE config_id={quote(fixture['configId'])} AND (b_id<>{quote(base+'B')} OR computing_formula_text<>{quote(LABEL)})") != '0':
        raise RuntimeError('Audit ownership changed; preserving data for review')
    query(';'.join(['START TRANSACTION',
        f"DELETE FROM bill_owe_fee WHERE fee_id={quote(fixture['feeId'])} AND community_id={quote(community)}",
        f"DELETE FROM bill WHERE config_id={quote(fixture['configId'])} AND community_id={quote(community)}",
        f"DELETE FROM pay_fee WHERE fee_id={quote(fixture['feeId'])} AND b_id={quote(base+'B')} AND community_id={quote(community)}",
        f"DELETE FROM pay_fee_config WHERE config_id={quote(fixture['configId'])} AND b_id={quote(base+'B')} AND community_id={quote(community)}",'COMMIT'])+';')
    remaining = query(f"SELECT (SELECT count(*) FROM bill_owe_fee WHERE fee_id={quote(fixture['feeId'])})+(SELECT count(*) FROM bill WHERE config_id={quote(fixture['configId'])})+(SELECT count(*) FROM pay_fee WHERE fee_id={quote(fixture['feeId'])})+(SELECT count(*) FROM pay_fee_config WHERE config_id={quote(fixture['configId'])})")
    if remaining != '0':
        raise RuntimeError('Audit cleanup incomplete; inspect the local fixture record')


def main():
    preflight()
    java, classpath, logging, output = compile_audit()
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', classpath, '-d', str(output), str(ROOT/'scripts/billing/LocalMonthlyBillingAudit.java')], check=True)
    issues = 0
    for scenario in ('normal', 'header-failure'):
        fixture, input_path = prepare()
        result_path = output / ('local-results.json' if scenario == 'normal' else 'local-header-failure-results.json')
        result = None
        try:
            process = subprocess.run([str(java), '-Dlogback.configurationFile='+str(logging), '-cp', classpath,
                'LocalMonthlyBillingAudit', str(input_path), str(result_path)]
                + ([] if scenario == 'normal' else ['header-failure']), cwd=ROOT,
                capture_output=True, text=True, encoding='utf-8', errors='replace')
            (output / ('local-'+scenario+'-run.log')).write_text(process.stdout+process.stderr, encoding='utf-8')
            # Do not echo HTTP exception URLs or local owner details.
            if process.returncode:
                raise RuntimeError('Local monthly generation failed; review fixture-scoped backend state')
            print(process.stdout.strip())
            result = json.loads(result_path.read_text(encoding='utf-8'))
            totals = query(f"SELECT count(*),sum(b.bill_id IS NULL) FROM bill_owe_fee d LEFT JOIN bill b ON d.bill_id=b.bill_id AND d.community_id=b.community_id WHERE d.fee_id={quote(fixture['feeId'])} AND d.community_id={quote(fixture['communityId'])}").split('\t')
            if len(totals) != 2:
                raise RuntimeError('Unexpected fixture detail count response')
            result['persistedDetails'] = int(totals[0])
            result['orphanDetails'] = int(totals[1])
            good = result['persistedDetails'] == 1 and result['orphanDetails'] == 0
            result['status'] = 'PASS' if good else 'ISSUE'
            if not good:
                issues += 1
            print(result['status']+' actual SQL '+scenario+': details='+totals[0]+', orphanDetails='+totals[1])
        finally:
            cleanup(fixture)
            print('PASS audit fixture cleanup: zero remaining rows; existing owner and bills preserved.')
            if result is not None:
                result['cleanupVerified'] = True
                result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    return 1 if issues else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print('Local billing audit stopped: '+type(error).__name__, file=sys.stderr)
        raise SystemExit(2)
