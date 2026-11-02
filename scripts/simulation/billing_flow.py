"""Fixture-scoped real local billing flow. No WeChat sends or live payment callbacks."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
import uuid
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/billing'))
import local
from verify import compile_audit

RUN = local.RUN / 'billing-flow-simulation'
RECORD = RUN / 'fixture.json'
LABEL = 'LOCAL FULL FLOW SIMULATION ONLY - DO NOT PAY'
TABLES = {'f_floor':'floor_id','building_unit':'unit_id','building_room':'room_id','building_owner':'member_id',
          'building_owner_room_rel':'rel_id','owner_app_user':'app_user_id','u_user':'user_id',
          'pay_fee':'fee_id','pay_fee_config':'config_id','bill':'bill_id','bill_owe_fee':'owe_id',
          'pay_fee_detail':'detail_id','account':'acct_id','account_detail':'detail_id','contract':'contract_id'}


def query(sql):
    return local.mysql_run(sql, database='TT', capture=True)


def q(value):
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def snapshot():
    return {table:{'rows':int(query('SELECT count(*) FROM `' + table + '`')),
                   'sha256':hashlib.sha256(query('SELECT * FROM `' + table + '` ORDER BY `' + key + '`').encode('utf-8')).hexdigest()}
            for table,key in TABLES.items()}


def fixture_module(record):
    spec = importlib.util.spec_from_file_location('simulation_fixture', ROOT / 'scripts/owner-h5/fixture.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.PREFIX, module.LABEL, module.IDS, module.RECORD = record['prefix'], LABEL, record['ids'], RECORD
    return module


def load():
    record = json.loads(RECORD.read_text(encoding='utf-8'))
    if record['label'] != LABEL or not re.fullmatch(r'SIM[a-f0-9]{12}', record['prefix']):
        raise RuntimeError('Unrecognized simulation ownership record')
    expected = {name:record['prefix']+suffix for name,suffix in {'floor':'F','unit':'U','room':'R','owner':'O','member':'M','rel':'REL','config':'C','fee':'FEE','business':'B'}.items()}
    if record['ids'] != expected:
        raise RuntimeError('Simulation identifiers changed; preserve data for review')
    return record


def prepare():
    RUN.mkdir(parents=True, exist_ok=True)
    if RECORD.exists():
        old = load()
        if not old.get('cleanupVerified'):
            raise RuntimeError('Previous simulation requires cleanup before starting a new run')
        archive = RUN / old['prefix']
        archive.mkdir(exist_ok=True)
        for path in list(RUN.glob('*.json')):
            path.rename(archive / path.name)
    if query("SELECT value FROM c_mapping WHERE `key`='SMS_SEND_SWITCH'") != 'OFF':
        raise RuntimeError('SMS must already be disabled in this local environment')
    if '-Djava110.local.disableScheduledTasks=true' not in local.processes().get('job',{}).get('command',[]):
        raise RuntimeError('Automatic jobs must be disabled')
    if not all(local.listening(port) for port in (13306,8015,8012,3000)):
        raise RuntimeError('Start the existing merged local services first')
    before = snapshot()
    (RUN / 'before.json').write_text(json.dumps(before,indent=2),encoding='utf-8')
    original = json.loads((local.RUN / 'wechat-test-fixture.json').read_text(encoding='utf-8'))
    base = 'SIM' + uuid.uuid4().hex[:12]
    record = {key:original[key] for key in ('communityId','communityName','areaCode')}
    record.update(prefix=base,label=LABEL,ownerName=base,phone='199'+str(secrets.randbelow(100000000)).zfill(8),password=secrets.token_urlsafe(24),
                  ids={name:base+suffix for name,suffix in {'floor':'F','unit':'U','room':'R','owner':'O','member':'M','rel':'REL','config':'C','fee':'FEE','business':'B'}.items()})
    if query('SELECT count(*) FROM u_user WHERE tel='+q(record['phone'])) != '0':
        raise RuntimeError('Synthetic phone collision; rerun before creating any records')
    RECORD.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    fixture = fixture_module(record)
    fixture.create()
    register()


def register():
    record = load()
    payload={'communityName':record['communityName'],'areaCode':record['areaCode'],'appUserName':record['ownerName'],
             'link':record['phone'],'msgCode':'LOCAL_ONLY_NO_SMS','password':record['password'],'remark':LABEL}
    results=[]
    for endpoint in ['http://127.0.0.1:8012/app/owner.ownerRegister']:
        request=urllib.request.Request(endpoint,data=json.dumps(payload,ensure_ascii=False).encode('utf-8'),
            headers={'Content-Type':'application/json','app-id':'992020061452450002','user-id':'-1',
                     'transaction-id':record['prefix'],'req-time':'20261102000000','sign':'1234567'},method='POST')
        try:
            with urllib.request.urlopen(request,timeout=45) as response:
                status=response.status;raw=response.read().decode('utf-8')
        except urllib.error.HTTPError as error:
            status=error.code;raw=error.read().decode('utf-8',errors='replace')
        try: code=json.loads(raw).get('code')
        except ValueError: code=None
        results.append({'entry':'public-h5-front' if ':8012/' in endpoint else 'internal-api-diagnostic-continuation',
                        'http':status,'code':code,'passed':status==200 and code==0})
        (RUN/'registration.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
        if results[-1]['passed']:
            break
    if not results[-1]['passed']:
        (RUN/'registration-response-private.txt').write_text(raw,encoding='utf-8')
        raise RuntimeError('Public H5 registration failed; no internal bypass is permitted in the repaired flow')
    user=query('SELECT u.user_id FROM owner_app_user o JOIN u_user u ON u.user_id=o.user_id WHERE o.member_id='+q(record['ids']['member'])+' AND u.tel='+q(record['phone'])+' AND o.remark='+q(LABEL))
    if not user or '\n' in user:
        raise RuntimeError('Cannot prove registration fixture ownership')
    record['createdUserId']=user
    RECORD.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    record = load()
    binding = query('SELECT app_user_id,state FROM owner_app_user WHERE user_id='+q(record['createdUserId'])+' AND member_id='+q(record['ids']['member'])).split('\t')
    if len(binding)!=2 or binding[1]!='10000':
        raise RuntimeError('Expected one pending binding after real API registration')
    record['appUserId']=binding[0]
    # Only labelled fixture rows are changed into a monthly fee scenario.
    query("UPDATE building_room SET room_num='SIM101' WHERE room_id="+q(record['ids']['room'])+' AND b_id='+q(record['ids']['business']))
    start="DATE_FORMAT(CURRENT_DATE(),'%Y-%m-01 00:00:00')"
    query("UPDATE pay_fee_config SET fee_name='SIMULATION monthly fee - DO NOT PAY',fee_flag='1003006',bill_type='002',start_time="+start+',end_time=DATE_ADD('+start+",INTERVAL 12 MONTH) WHERE config_id="+q(record['ids']['config'])+' AND b_id='+q(record['ids']['business']))
    query("UPDATE pay_fee SET fee_flag='1003006',start_time="+start+',end_time='+start+' WHERE fee_id='+q(record['ids']['fee'])+' AND b_id='+q(record['ids']['business']))
    RECORD.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Prepared synthetic owner/room/monthly fee; real registration created a pending binding. Credentials remain private.')


def generate():
    record=load()
    fixture={'communityId':record['communityId'],'roomId':record['ids']['room'],'ownerId':record['ids']['owner'],
             'configId':record['ids']['config'],'feeId':record['ids']['fee'],'merged':True}
    path=RUN/'generation-input.json'
    path.write_text(json.dumps(fixture),encoding='utf-8')
    java,cp,logging,output=compile_audit()
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output),str(ROOT/'scripts/billing/LocalMonthlyBillingAudit.java')],check=True)
    with (RUN/'generation.log').open('wb') as log:
        subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,'LocalMonthlyBillingAudit',str(path),str(RUN/'generation.json')],stdout=log,stderr=subprocess.STDOUT,check=True)
    pair=query('SELECT bill_id,owe_id FROM bill_owe_fee WHERE fee_id='+q(record['ids']['fee'])+' AND community_id='+q(record['communityId'])).split('\t')
    if len(pair)!=2:
        raise RuntimeError('Expected one generated bill and detail')
    record['billId'],record['oweId']=pair
    RECORD.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Real monthly task generated one 0.01 bill; repeated generation was rejected.')


def final_state():
    """Record unpaid state without treating a synthetic callback as real posting."""
    record = load()
    if record.get('cleanupVerified'):
        raise RuntimeError('Capture final state before cleanup')
    scope = 'fee_id='+q(record['ids']['fee'])+' AND community_id='+q(record['communityId'])
    detail = query('SELECT state,bill_amount_owed,amount_owed FROM bill_owe_fee WHERE '+scope).split('\t')
    if len(detail) != 3:
        raise RuntimeError('Expected one fixture bill detail')
    before = json.loads((RUN/'before.json').read_text(encoding='utf-8'))
    current = snapshot()
    result = {'billState':detail[0], 'billAmount':detail[1], 'amountStillOwed':detail[2],
              'billReceipts':query('SELECT receipts FROM bill WHERE bill_id='+q(record['billId'])+' AND community_id='+q(record['communityId'])),
              'paymentDetails':int(query('SELECT count(*) FROM pay_fee_detail WHERE fee_id='+q(record['ids']['fee']))),
              'accountTablesUnchanged':all(current[t]==before[t] for t in ('account','account_detail')),
              'realPaymentPostingVerified':False}
    (RUN/'final-state.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(result))


def cleanup():
    record=load()
    if record.get('cleanupVerified'):
        print('Simulation already cleaned.');return
    ids=record['ids']
    query('START TRANSACTION; DELETE FROM bill_owe_fee WHERE fee_id='+q(ids['fee'])+' AND community_id='+q(record['communityId'])+
          ';DELETE FROM bill WHERE config_id='+q(ids['config'])+' AND community_id='+q(record['communityId'])+';COMMIT;')
    fixture_module(record).cleanup()
    after=snapshot()
    before=json.loads((RUN/'before.json').read_text(encoding='utf-8'))
    changed=[table for table in TABLES if before[table]!=after[table]]
    report={'checkedTables':len(TABLES),'changedTables':changed,'unchanged':not changed,'before':before,'after':after}
    (RUN/'preservation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    record['cleanupVerified']=not changed
    RECORD.write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
    if changed:
        raise RuntimeError('Post-cleanup data differs in: '+','.join(changed))
    print('All simulation business records cleaned; 15 existing business tables match their pre-run row counts and hashes. Order audit traces are retained.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','register','generate','final_state','cleanup'])
    args=parser.parse_args()
    try:
        globals()[args.action]()
    except Exception as error:
        print('Simulation stopped: '+type(error).__name__+'; inspect private runtime logs and run cleanup when ownership is confirmed.',file=sys.stderr)
        raise SystemExit(2)
