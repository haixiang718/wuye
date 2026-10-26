"""Run production mapper statements on temporary tables in isolated MySQL.

Temporary tables copy the two real schemas for this one connection. No real
bill, payment or owner row is changed. Exit 1 means a reproduced isolation gap.
"""
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


def statement(mapper, name, params):
    node = next(item for item in mapper if item.get('id') == name)
    sql = node.text or ''
    for child in node:
        if child.tag != 'if':
            raise RuntimeError('Audit mapper includes an unsupported SQL element')
        test = child.get('test', '')
        include = ('oweId' in test and params.get('oweId')) or ('3000' in test and params.get('state') == '3000')
        if include:
            sql += child.text or ''
        sql += child.tail or ''
    for key, value in params.items():
        if not str(value).replace('-', '').replace('_', '').isalnum():
            raise ValueError('Audit uses only fixed simple literals')
        sql = sql.replace('#{' + key + '}', "'" + str(value) + "'")
    if '#{' in sql:
        raise RuntimeError('Unresolved audit parameter')
    return sql


def main():
    mapper = ET.parse(local.BACK / 'java110-db/src/main/resources/mapper/fee/FeeServiceDaoImplMapper.xml').getroot()
    current_update = statement(mapper, 'updateBill', {'configId': 'config-shared', 'communityId': 'community-a'})
    notify_update = statement(mapper, 'updateBillOweFees', dict(state='3000', feeId='fee', communityId='community-a', billId='bill', oweId='selected'))
    sql = [
        'CREATE TEMPORARY TABLE audit_bill LIKE TT.bill',
        'CREATE TEMPORARY TABLE audit_bill_owe_fee LIKE TT.bill_owe_fee',
        "INSERT INTO bill(bill_id,bill_name,receivable,receipts,bill_time,community_id,config_id,remark,cur_bill) VALUES ('bill-a','audit',1,0,NOW(),'community-a','config-shared','audit','T'),('bill-b','audit',1,0,NOW(),'community-b','config-shared','audit','T')",
        current_update,
        "SELECT 'current_bill_community_isolation',cur_bill FROM bill WHERE community_id='community-b'",
        "INSERT INTO bill_owe_fee(owe_id,bill_id,fee_id,bill_amount_owed,amount_owed,fee_end_time,owner_id,owner_name,owner_tel,payer_obj_name,payer_obj_type,community_id,state) VALUES ('selected','bill','fee',1,1,NOW(),'owner','Synthetic','19900000999','room','3333','community-a','2000'),('selected','bill','fee',1,1,NOW(),'owner','Synthetic','19900000999','room','3333','community-a','1000'),('other','bill','fee',1,1,NOW(),'owner','Synthetic','19900000999','room','3333','community-a','1000')",
        notify_update,
        "SELECT 'notify_preserves_paid_state',count(*) FROM bill_owe_fee WHERE state='2000'",
        "SELECT 'notify_targets_selected_unpaid_detail',count(*) FROM bill_owe_fee WHERE owe_id='selected' AND state='3000'",
        "SELECT 'notify_preserves_other_detail',count(*) FROM bill_owe_fee WHERE owe_id='other' AND state='1000'",
        "SELECT 'detail_has_unique_key',count(*) FROM information_schema.statistics WHERE table_schema='TT' AND table_name='bill_owe_fee' AND non_unique=0",
        "SELECT 'header_has_unique_key',count(*) FROM information_schema.statistics WHERE table_schema='TT' AND table_name='bill' AND non_unique=0",
    ]
    # Keep schema sources and metadata names intact; redirect only operations.
    for index in range(2, len(sql)-2):
        sql[index] = re.sub(r'\bbill_owe_fee\b', 'audit_bill_owe_fee', sql[index])
        sql[index] = re.sub(r'\bbill\b', 'audit_bill', sql[index])
    rows = local.mysql_run(';'.join(sql)+';', database='TT', capture=True).splitlines()
    observed = dict(row.split('\t', 1) for row in rows)
    expected = dict(current_bill_community_isolation='T', notify_preserves_paid_state='1',
                    notify_targets_selected_unpaid_detail='1', notify_preserves_other_detail='1')
    checks = [dict(id=name, status='PASS' if observed[name] == value else 'ISSUE', observed=observed[name], expected=value)
              for name, value in expected.items()]
    # Missing constraints are recorded as context; the concurrency business test
    # separately demonstrates the effect. Do not double count them as extra bugs.
    report = dict(mode='isolated-mysql-temporary-tables', checks=checks,
                  uniqueKeys=dict(header=int(observed['header_has_unique_key']), detail=int(observed['detail_has_unique_key'])),
                  productionRowsChanged=0)
    destination = ROOT / '.runtime/billing-audit/sql-results.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, indent=2), encoding='utf-8')
    for check in checks:
        print(check['status']+' '+check['id']+': observed='+check['observed']+', expected='+check['expected'])
    print('Local bill unique keys: header='+observed['header_has_unique_key']+', detail='+observed['detail_has_unique_key'])
    return 1 if any(check['status'] != 'PASS' for check in checks) else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError, ET.ParseError) as error:
        print('SQL billing audit infrastructure failed: '+type(error).__name__, file=sys.stderr)
        raise SystemExit(2)
