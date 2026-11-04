"""Fixture-scoped manual draft generation through the real local admin page; no publishing or messages."""
import argparse,json,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import wechat_direct_flow as fixture

fixture.RUN=fixture.local.RUN/'manual-bill-flow'
fixture.RECORD=fixture.RUN/'fixture.json'

def prepare():
    fixture.prepare()
    record=fixture.load();ids=record['ids'];q=fixture.q
    fixture.query("START TRANSACTION;UPDATE pay_fee_config SET bill_type='005',fee_flag='2006012',additional_amount=50 WHERE config_id="+q(ids['config'])+" AND b_id="+q(ids['business'])+";UPDATE pay_fee SET fee_flag='2006012',amount=-1 WHERE fee_id="+q(ids['fee'])+" AND b_id="+q(ids['business'])+";COMMIT;")
    print('Only marked fixture uses one-time 50-yuan generation; no existing fees modified.')

def exercise():
    fixture.ensure_local();record=fixture.load()
    subprocess.run(['node',str(Path(__file__).with_name('manual_bill_generate.cjs'))],check=True)
    ids=record['ids'];q=fixture.q
    bill=fixture.query('SELECT bill_id,review_state,receivable,cur_bill FROM bill WHERE config_id='+q(ids['config'])+' AND community_id='+q(record['communityId'])).split('\t')
    if len(bill)!=4 or bill[1]!='DRAFT' or float(bill[2])!=50 or bill[3]!='T':raise RuntimeError('Unexpected persisted manual bill')
    detail=fixture.query('SELECT bill_id,bill_amount_owed,state,owner_id FROM bill_owe_fee WHERE fee_id='+q(ids['fee'])+' AND community_id='+q(record['communityId'])).split('\t')
    if len(detail)!=4 or detail[0]!=bill[0] or float(detail[1])!=50 or detail[2]!='1000' or detail[3]!=ids['owner']:raise RuntimeError('Unexpected persisted manual detail')
    if fixture.query('SELECT count(*) FROM pay_fee_detail WHERE fee_id='+q(ids['fee']))!='0':raise RuntimeError('Unexpected payment')
    report=json.loads((fixture.RUN/'browser.json').read_text(encoding='utf-8'))
    report.update(persistedDraft=True,persistedAmount='50.00',notifications=0,payments=0)
    (fixture.RUN/'results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('Actual admin button created exactly one 50-yuan draft and unnotified detail; repeat reused it; no payments.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['prepare','exercise','cleanup']);args=parser.parse_args()
    try:
        if args.action=='cleanup':fixture.cleanup()
        else:globals()[args.action]()
    except Exception as failure:
        print('Manual generation check stopped: '+type(failure).__name__+'. Scoped runtime evidence preserved; cleanup is required.',file=sys.stderr)
        raise SystemExit(2)
