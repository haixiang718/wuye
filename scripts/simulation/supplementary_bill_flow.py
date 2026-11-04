"""Isolated real-backend supplementary-billing check; never sends notifications or pays."""
import argparse,json,subprocess,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import wechat_direct_flow as f
f.RUN=f.local.RUN/'supplementary-bill-flow';f.RECORD=f.RUN/'fixture.json'
f.TABLES={k:v for k,v in f.TABLES.items() if k!='u_user_attr'}

def extra_ids(r):return [r['prefix']+'FEE2',r['prefix']+'FEE3']

def prepare():
    f.prepare();r=f.load();q=f.q;ids=r['ids']
    f.query("START TRANSACTION;UPDATE pay_fee_config SET bill_type='005',fee_flag='2006012',additional_amount=50 WHERE config_id="+q(ids['config'])+' AND b_id='+q(ids['business'])+";UPDATE pay_fee SET fee_flag='2006012',amount=-1 WHERE fee_id="+q(ids['fee'])+' AND b_id='+q(ids['business'])+';COMMIT;')
    print('Prepared isolated one-time 50-yuan charge item.')

def generate(step,existing,supplemental):
    subprocess.run(['node',str(Path(__file__).with_name('supplementary_bill_generate.cjs')),step,str(int(existing)),str(int(supplemental))],check=True)
    return json.loads((f.RUN/(step+'.json')).read_text(encoding='utf-8'))['billId']

def add_fee(r,fee):
    if fee not in extra_ids(r):raise RuntimeError('Unexpected generated fee ID')
    q=f.q;ids=r['ids']
    if f.query('SELECT count(*) FROM pay_fee WHERE fee_id='+q(fee))!='0':raise RuntimeError('ID collision')
    f.query('INSERT INTO pay_fee(fee_id,b_id,fee_type_cd,community_id,payer_obj_id,income_obj_id,start_time,end_time,amount,user_id,status_cd,fee_flag,state,config_id,payer_obj_type) SELECT '+q(fee)+',b_id,fee_type_cd,community_id,payer_obj_id,income_obj_id,start_time,end_time,amount,user_id,status_cd,fee_flag,state,config_id,payer_obj_type FROM pay_fee WHERE fee_id='+q(ids['fee'])+' AND b_id='+q(ids['business']))

def exercise():
    f.ensure_local();r=f.load();ids=r['ids'];q=f.q;checks=[]
    def check(name,ok):
        checks.append({'name':name,'passed':bool(ok)})
        (f.RUN/'results.json').write_text(json.dumps({'checks':checks,'passed':all(x['passed'] for x in checks),'messagesSent':0,'payments':0,'wechatIdentity':'synthetic','reviewTransition':'fixture-scoped SQL; no notification queue'},indent=2),encoding='utf-8')
        if not ok:raise RuntimeError('Integration check failed: '+name)
    base=generate('base',False,False)
    check('initial_ui_repeat_is_idempotent',generate('base-repeat',True,False)==base)
    # Only random fixture data changes state; this intentionally bypasses notification dispatch.
    f.query("UPDATE bill SET review_state='PUBLISHED',bill_time=bill_time WHERE bill_id="+q(base)+' AND config_id='+q(ids['config'])+' AND community_id='+q(r['communityId']))
    original=f.query('SELECT * FROM bill WHERE bill_id='+q(base))+'\n'+f.query('SELECT * FROM bill_owe_fee WHERE bill_id='+q(base))
    more=extra_ids(r);add_fee(r,more[0]);second=generate('supplement',False,True)
    check('published_base_gets_separate_supplement',base!=second)
    check('supplement_ui_repeat_is_idempotent',generate('supplement-repeat',True,False)==second)
    add_fee(r,more[1]);third=generate('supplement-2',False,True)
    check('new_fee_after_draft_gets_another_supplement',third not in (base,second))
    check('original_published_header_and_detail_unchanged',original==f.query('SELECT * FROM bill WHERE bill_id='+q(base))+'\n'+f.query('SELECT * FROM bill_owe_fee WHERE bill_id='+q(base)))
    check('all_three_headers_remain_current',f.query("SELECT count(*) FROM bill WHERE config_id="+q(ids['config'])+" AND cur_bill='T'")=='3')
    counts=f.query('SELECT fee_id,count(*) FROM bill_owe_fee WHERE bill_id IN ('+','.join(map(q,[base,second,third]))+') GROUP BY fee_id ORDER BY fee_id').splitlines()
    check('exactly_one_detail_per_fee',len(counts)==3 and all(x.endswith('\t1') for x in counts))
    check('each_header_has_50_yuan',f.query('SELECT count(*) FROM bill WHERE config_id='+q(ids['config'])+' AND receivable=50')=='3')
    session=f.identity(r)
    status,binding=f.request('/app/bindOwnerWechat',{'name':r['ownerName'],'phone':r['phone'],'idCard':r['idCard'],'wechatSession':session})
    check('synthetic_wechat_identity_binds',status==200 and binding.get('code')==0 and bool(binding.get('token')))
    def visible():
        status,reply=f.request('/app/fee.listFee',token=binding['token'],params={'communityId':r['communityId'],'payerObjId':ids['room'],'state':'2008001','page':1,'row':50})
        check('h5_fee_query_succeeds',status==200 and reply.get('code',0)==0)
        return {x.get('feeId') for x in reply.get('fees',[]) if x.get('billSnapshot') is True}
    check('h5_keeps_base_visible_and_hides_both_drafts',visible()=={ids['fee']})
    f.query("UPDATE bill SET review_state='PUBLISHED',bill_time=bill_time WHERE bill_id="+q(second)+' AND config_id='+q(ids['config'])+' AND community_id='+q(r['communityId']))
    check('h5_shows_original_and_published_supplement_once',visible()=={ids['fee'],more[0]})
    check('no_payment_records',f.query('SELECT count(*) FROM pay_fee_detail WHERE fee_id IN ('+','.join(map(q,[ids['fee']]+more))+')')=='0')
    check('last_repeat_does_not_create_fourth_header',generate('final-repeat',True,False)==third and f.query('SELECT count(*) FROM bill WHERE config_id='+q(ids['config']))=='3')
    print('Passed '+str(len(checks))+' scoped SQL/H5 checks plus actual browser generation; no notifications or payments.')

def cleanup():
    r=f.load();q=f.q;ids=r['ids'];more=extra_ids(r)
    if f.query('SELECT count(*) FROM pay_fee_detail WHERE fee_id IN ('+','.join(map(q,more))+')')!='0':raise RuntimeError('Unexpected payments; preserve fixture')
    for fee in more:
        owner=f.query('SELECT b_id FROM pay_fee WHERE fee_id='+q(fee))
        if owner and owner!=ids['business']:raise RuntimeError('Unexpected ownership')
    f.query('START TRANSACTION;DELETE FROM bill_owe_fee WHERE fee_id IN ('+','.join(map(q,more))+') AND community_id='+q(r['communityId'])+';DELETE FROM pay_fee WHERE fee_id IN ('+','.join(map(q,more))+') AND b_id='+q(ids['business'])+';COMMIT;')
    f.cleanup()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['prepare','exercise','cleanup'])
    globals()[parser.parse_args().action]()
