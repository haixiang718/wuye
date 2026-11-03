"""Opt-in, fixture-scoped local WeChat binding verification; no real OAuth or messages.

The external WeChat identity is synthetic. The binding, authorization and bill reads
use the running real local front/API/business services. All private state is ignored.
Run prepare, generate, publish, exercise, cleanup in that order after deployment.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/billing'))
import local
from verify import compile_audit

RUN = local.RUN / 'wechat-direct-flow'
RECORD = RUN / 'fixture.json'
LABEL = 'LOCAL WECHAT DIRECT FLOW ONLY - DO NOT PAY'
APP = '992020061452450002'
TABLES = {'f_floor':'floor_id','building_unit':'unit_id','building_room':'room_id',
          'building_owner':'member_id','building_owner_room_rel':'rel_id',
          'owner_app_user':'app_user_id','u_user':'user_id','u_user_attr':'attr_id',
          'pay_fee':'fee_id','pay_fee_config':'config_id','bill':'bill_id',
          'bill_owe_fee':'owe_id','pay_fee_detail':'detail_id','account':'acct_id',
          'account_detail':'detail_id','contract':'contract_id'}
SUFFIXES = {'floor':'F','unit':'U','room':'R','room2':'R2','outsideRoom':'R3',
            'owner':'O','member':'M','outsideOwner':'O2','outsideMember':'M2',
            'rel':'REL','rel2':'REL2','outsideRel':'REL3','config':'C',
            'fee':'FEE','business':'B'}


def query(sql):
    return local.mysql_run(sql, database='TT', capture=True)


def q(value):
    return 'CONVERT(0x' + str(value).encode('utf-8').hex() + ' USING utf8mb4)'


def save(record):
    RECORD.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')


def load():
    record = json.loads(RECORD.read_text(encoding='utf-8'))
    prefix = record.get('prefix', '')
    if record.get('label') != LABEL or not re.fullmatch(r'WXD[a-f0-9]{12}', prefix):
        raise RuntimeError('Unrecognized fixture provenance')
    if record.get('ids') != {key: prefix + suffix for key, suffix in SUFFIXES.items()}:
        raise RuntimeError('Fixture identifiers changed; preserving records')
    if record.get('openId') != 'synthetic-' + prefix:
        raise RuntimeError('Unexpected external identity; preserving records')
    expected_user = 'wx' + hashlib.sha256((record['wechatAppId'] + ':' + record['openId']).encode()).hexdigest()[:28]
    if record.get('expectedUserId') != expected_user:
        raise RuntimeError('Unexpected technical identity; preserving records')
    return record


def snapshot():
    return {table: {'rows': int(query('SELECT count(*) FROM `' + table + '`')),
                    'sha256': hashlib.sha256(query('SELECT * FROM `' + table + '` ORDER BY `' + key + '`').encode()).hexdigest()}
            for table, key in TABLES.items()}


def ensure_local(require_services=True):
    if query("SELECT value FROM c_mapping WHERE `key`='SMS_SEND_SWITCH'") != 'OFF':
        raise RuntimeError('SMS must remain disabled')
    if '-Djava110.local.disableScheduledTasks=true' not in local.processes().get('job', {}).get('command', []):
        raise RuntimeError('Scheduled tasks must remain disabled')
    if require_services and not all(local.listening(port) for port in (13306, 16379, 8012, 8015)):
        raise RuntimeError('Start merged local services before this verification')


def id_card(sequence):
    body = '11010119900101' + str(sequence).zfill(3)
    weights = [7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]
    return body + '10X98765432'[sum(int(digit)*weight for digit, weight in zip(body, weights)) % 11]


def insert_sql(table, key, values):
    existing = query('SELECT b_id FROM `' + table + '` WHERE `' + key + '`=' + q(values[key]))
    if existing:
        raise RuntimeError('Fixture ID collision; no existing record may be adopted')
    return 'INSERT INTO `' + table + '` (' + ','.join('`' + key + '`' for key in values) + ') VALUES (' + ','.join(q(v) for v in values.values()) + ')'


def prepare():
    ensure_local()
    RUN.mkdir(parents=True, exist_ok=True)
    if RECORD.exists():
        previous = load()
        if not previous.get('cleanupVerified'):
            raise RuntimeError('Previous run must be cleaned before preparing another fixture')
        archive = RUN / previous['prefix']
        archive.mkdir(exist_ok=True)
        for path in list(RUN.glob('*.json')):
            path.rename(archive / path.name)
    config = json.loads((local.RUN / 'wechat-domain.json').read_text(encoding='utf-8-sig'))
    community = config['communityId']
    details = query('SELECT name,city_code FROM s_community WHERE community_id=' + q(community) + " AND status_cd='0' AND state='1100'").split('\t')
    if len(details) != 2:
        raise RuntimeError('Expected one configured official-account community')
    prefix = 'WXD' + uuid.uuid4().hex[:12]
    record = {'prefix': prefix, 'label': LABEL, 'communityId': community,
              'communityName': details[0], 'areaCode': details[1], 'wechatAppId': config['appId'],
              'openId': 'synthetic-' + prefix, 'ownerName': prefix,
              'phone': '199' + str(secrets.randbelow(100000000)).zfill(8),
              'idCard': id_card(secrets.randbelow(998) + 1),
              'ids': {key: prefix + suffix for key, suffix in SUFFIXES.items()}}
    record['expectedUserId'] = 'wx' + hashlib.sha256((record['wechatAppId'] + ':' + record['openId']).encode()).hexdigest()[:28]
    if query('SELECT count(*) FROM u_user WHERE user_id=' + q(record['expectedUserId']) + ' OR tel=' + q(record['phone'])) != '0':
        raise RuntimeError('Synthetic technical identity collision')
    before = snapshot()
    (RUN / 'before.json').write_text(json.dumps(before, indent=2), encoding='utf-8')
    save(record)  # Durable cleanup provenance precedes every write.
    ids = record['ids']
    admin = query("SELECT user_id FROM u_user WHERE status_cd='0' ORDER BY id LIMIT 1")
    store = query("SELECT store_id FROM s_store WHERE status_cd='0' ORDER BY store_id LIMIT 1")
    if not admin or not store:
        raise RuntimeError('An existing admin and store are required')
    common = {'b_id': ids['business'], 'user_id': admin, 'remark': LABEL, 'status_cd': '0'}
    rows = [
        ('f_floor','floor_id',{**common,'floor_id':ids['floor'],'floor_num':'WD','name':'SYNTHETIC DIRECT LOGIN','community_id':community,'floor_area':'90'}),
        ('building_unit','unit_id',{**common,'unit_id':ids['unit'],'unit_num':'1','floor_id':ids['floor'],'layer_count':'1','lift':'1010','unit_area':'90'}),
        ('building_owner','member_id',{**common,'member_id':ids['member'],'owner_id':ids['owner'],'name':record['ownerName'],'sex':'1','age':'30','link':record['phone'],'community_id':community,'id_card':record['idCard'],'owner_type_cd':'1001'}),
        ('building_owner','member_id',{**common,'member_id':ids['outsideMember'],'owner_id':ids['outsideOwner'],'name':prefix+' OTHER','sex':'1','age':'30','link':'19900009999','community_id':community,'id_card':'-1','owner_type_cd':'1001'}),
        ('pay_fee_config','config_id',{'config_id':ids['config'],'b_id':ids['business'],'community_id':community,'fee_type_cd':'888800010001','square_price':'0','additional_amount':'0.01','start_time':time.strftime('%Y-%m-01'),'end_time':'2035-01-01','fee_flag':'1003006','fee_name':LABEL,'computing_formula':'2002','computing_formula_text':LABEL,'bill_type':'002','payment_cycle':'1','payment_cd':'1200','status_cd':'0'}),
        ('pay_fee','fee_id',{'fee_id':ids['fee'],'b_id':ids['business'],'fee_type_cd':'888800010001','community_id':community,'payer_obj_id':ids['room'],'income_obj_id':store,'start_time':time.strftime('%Y-%m-01'),'end_time':time.strftime('%Y-%m-01'),'amount':'0.01','user_id':admin,'fee_flag':'1003006','state':'2008001','config_id':ids['config'],'payer_obj_type':'3333','status_cd':'0'})]
    for room_key, rel_key, owner_key, number in [('room','rel','owner','WD101'), ('room2','rel2','owner','WD102'), ('outsideRoom','outsideRel','outsideOwner','WD999')]:
        rows.extend([
            ('building_room','room_id',{**common,'room_id':ids[room_key],'room_num':number,'unit_id':ids['unit'],'layer':'1','apartment':'10101','built_up_area':'90','state':'2001','community_id':community,'room_type':'1010301'}),
            ('building_owner_room_rel','rel_id',{**common,'rel_id':ids[rel_key],'owner_id':ids[owner_key],'room_id':ids[room_key],'state':'2001'})])
    statements = [insert_sql(table, key, values) for table, key, values in rows]
    query('START TRANSACTION;' + ';'.join(statements) + ';COMMIT;')
    record['prepared'] = True
    save(record)
    print('Prepared marked local owner with two rooms plus a separate outsider room. No user was registered; no external API called.')


def generate():
    ensure_local()
    record = load()
    ids = record['ids']
    fixture = {'communityId':record['communityId'],'roomId':ids['room'],'ownerId':ids['owner'],
               'configId':ids['config'],'feeId':ids['fee'],'merged':True}
    path = RUN / 'generation-input.json'
    path.write_text(json.dumps(fixture), encoding='utf-8')
    java, cp, logging, output = compile_audit()
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', cp, '-d', str(output), str(ROOT/'scripts/billing/LocalMonthlyBillingAudit.java')], check=True)
    with (RUN/'generation.log').open('wb') as log:
        subprocess.run([str(java), '-Dlogback.configurationFile='+str(logging), '-cp', cp, 'LocalMonthlyBillingAudit', str(path), str(RUN/'generation.json')], stdout=log, stderr=subprocess.STDOUT, check=True)
    pair = query('SELECT bill_id,owe_id FROM bill_owe_fee WHERE fee_id='+q(ids['fee'])+' AND community_id='+q(record['communityId'])).split('\t')
    if len(pair) != 2:
        raise RuntimeError('Expected one generated bill and detail')
    record['billId'], record['oweId'] = pair
    save(record)
    print('Actual local monthly generation created one 0.01 draft bill; no notification or payment operation.')


def publish():
    ensure_local()
    load()
    subprocess.run(['node', str(Path(__file__).with_name('wechat_direct_publish.cjs'))], check=True)


def redis_command(*parts):
    encoded = [str(part).encode('utf-8') for part in parts]
    wire = ('*'+str(len(encoded))+'\r\n').encode() + b''.join(('$'+str(len(part))+'\r\n').encode()+part+b'\r\n' for part in encoded)
    with socket.create_connection(('127.0.0.1',16379), timeout=5) as connection:
        connection.sendall(wire)
        response = connection.makefile('rb')
        line = response.readline()
        if line.startswith(b'-'):
            raise RuntimeError('Local cache command rejected')
        if line.startswith(b'$'):
            length=int(line[1:-2]);return None if length<0 else response.read(length)
        return line[1:-2].decode('utf-8')


def identity(record):
    session = str(uuid.uuid4())
    record.setdefault('syntheticSessions', []).append(session)
    save(record)
    body = {'wechatAppId':record['wechatAppId'],'openId':record['openId'],
            'expiresAt':int(time.time()*1000)+1800000,'communityId':record['communityId']}
    if redis_command('SET', 'owner-wechat-identity:'+session, json.dumps(body), 'EX', 1800) != 'OK':
        raise RuntimeError('Unable to prepare synthetic local identity')
    return session


def guest_ticket(record, session):
    ticket=str(uuid.uuid4())
    record.setdefault('syntheticAuthTickets',[]).append(ticket);save(record)
    body={'appId':APP,'wechatSession':session}
    if redis_command('SET','owner-wechat-login:'+ticket,json.dumps(body),'EX',300)!='OK':
        raise RuntimeError('Unable to prepare single-use synthetic OAuth ticket')
    return ticket


def request(path, body=None, token=None, params=None):
    headers={'app-id':APP, 'transaction-id':str(uuid.uuid4()), 'req-time':time.strftime('%Y%m%d%H%M%S'), 'sign':'1234567','X-Requested-With':'XMLHttpRequest'}
    if token:
        headers['Cookie']='_java110_token_='+token
    data=None
    if body is not None:
        data=json.dumps(body).encode('utf-8');headers['Content-Type']='application/json'
    url='http://127.0.0.1:8012'+path
    if params:
        url+='?'+urllib.parse.urlencode(params)
    req=urllib.request.Request(url,data=data,headers=headers,method='POST' if body is not None else 'GET')
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            status=response.status; raw=response.read()
    except urllib.error.HTTPError as error:
        status=error.code;raw=error.read()
    try: result=json.loads(raw)
    except (ValueError, UnicodeDecodeError): result={}
    return status,result


def exercise():
    ensure_local()
    record=load();ids=record['ids'];checks=[]
    def check(name, passed):
        checks.append({'name':name,'passed':bool(passed)})
        (RUN/'results.json').write_text(json.dumps({'mode':'actual-local-binding-and-authorization-with-synthetic-wechat-identity','realOAuthVerified':False,'messagesSent':0,'payments':0,'checks':checks,'passed':all(c['passed'] for c in checks)},indent=2),encoding='utf-8')
        if not passed:raise RuntimeError('Integration assertion failed: '+name)
    check('fresh_identity_has_no_technical_user',query('SELECT count(*) FROM u_user WHERE user_id='+q(record['expectedUserId']))=='0')
    base={'name':record['ownerName'],'phone':record['phone'],'idCard':record['idCard']}
    session=identity(record);ticket=guest_ticket(record,session)
    status,guest=request('/app/loginOwnerByKey',{'authTicket':ticket})
    check('synthetic_verified_guest_ticket_exchanges_without_property_permission',status==200 and guest.get('code')==0 and guest.get('hasBinding') is False and guest.get('owner') is None and not guest.get('token') and guest.get('wechatSession')==session)
    status,_=request('/app/loginOwnerByKey',{'authTicket':ticket})
    check('guest_ticket_cannot_be_replayed',status==401)
    status,_=request('/app/room.queryRoomsByOwner',params={'communityId':record['communityId'],'ownerId':ids['owner'],'page':1,'row':10})
    check('unbound_identity_cannot_read_room_data',status in (401,403))
    for field,replacement in [('name',record['ownerName']+' WRONG'),('phone','18800000000'),('idCard',record['idCard'][:-1]+('0' if record['idCard'][-1]!='0' else '1'))]:
        session=identity(record)
        status,data=request('/app/bindOwnerWechat',{**base,field:replacement,'wechatSession':session})
        check('wrong_'+field+'_cannot_bind',status in (400,403,422) or (status==200 and data.get('code') not in (None,0)))
        check('wrong_'+field+'_creates_no_user',query('SELECT count(*) FROM u_user WHERE user_id='+q(record['expectedUserId']))=='0')
        check('wrong_'+field+'_creates_no_binding',query('SELECT count(*) FROM owner_app_user WHERE member_id='+q(ids['member']))=='0')
    session=identity(record)
    status,data=request('/app/bindOwnerWechat',{**base,'wechatSession':session})
    (RUN/'binding-private.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
    check('exact_triple_binds_without_registration_or_review',status==200 and data.get('code')==0 and data.get('hasBinding') is True and bool(data.get('owner')) and bool(data.get('token')))
    check('browser_receives_complete_owner_descriptors', isinstance(data.get('owners'),list) and bool(data['owners'])
          and all(isinstance(owner,dict) and owner.get('ownerId') and owner.get('memberId') and owner.get('state')=='12000'
                  and '$ref' not in owner for owner in data['owners']))
    token=data['token'];user=data['owner']['userId'];record['createdUserId']=user;save(record)
    check('only_expected_technical_identity_created',user==record['expectedUserId'])
    binding=query('SELECT state FROM owner_app_user WHERE user_id='+q(user)+' AND member_id='+q(ids['member'])+' AND open_id='+q(record['openId']))
    check('new_binding_immediately_approved',binding=='12000')
    status,repeated=request('/app/bindOwnerWechat',{**base,'wechatSession':session})
    check('repeat_binding_is_idempotent',status==200 and repeated.get('code')==0 and query('SELECT count(*) FROM owner_app_user WHERE user_id='+q(user)+' AND member_id='+q(ids['member']))=='1')
    status,rooms=request('/app/room.queryRoomsByOwner',token=token,params={'communityId':record['communityId'],'ownerId':ids['owner'],'page':1,'row':50})
    room_ids={room.get('roomId') for room in rooms.get('rooms',[])}
    check('both_own_rooms_visible',status==200 and {ids['room'],ids['room2']}.issubset(room_ids) and ids['outsideRoom'] not in room_ids)
    status,_=request('/app/fee.listFee',token=token,params={'communityId':record['communityId'],'payerObjId':ids['outsideRoom'],'state':'2008001','page':1,'row':10})
    check('other_owner_room_denied',status==403)
    status,fees=request('/app/fee.listFee',token=token,params={'communityId':record['communityId'],'payerObjId':ids['room'],'state':'2008001','page':1,'row':50})
    selected=[fee for fee in fees.get('fees',[]) if fee.get('feeId')==ids['fee']]
    check('published_bill_visible_after_binding',status==200 and len(selected)==1 and selected[0].get('billSnapshot') is True and float(selected[0].get('feePrice',-1))==0.01)
    state=query('SELECT state,bill_amount_owed FROM bill_owe_fee WHERE fee_id='+q(ids['fee'])).split('\t')
    check('bill_remains_unpaid',len(state)==2 and state[0]=='1000' and float(state[1])==0.01 and query('SELECT count(*) FROM pay_fee_detail WHERE fee_id='+q(ids['fee']))=='0')
    record['exercised']=True;save(record)
    print('Passed '+str(len(checks))+' actual local checks. WeChat identity was synthetic; no real OAuth, messages or payments.')


def cleanup():
    ensure_local(require_services=False)
    record=load();ids=record['ids']
    if record.get('cleanupVerified'):
        print('Previously cleaned fixture remains recorded.');return
    user=record['expectedUserId']
    exists=query('SELECT count(*) FROM u_user WHERE user_id='+q(user))
    if exists!='0':
        provenance=query('SELECT count(*) FROM owner_app_user WHERE user_id='+q(user)+' AND member_id='+q(ids['member'])+' AND open_id='+q(record['openId']))
        if provenance!='1' or query('SELECT count(*) FROM owner_app_user WHERE user_id='+q(user)+' AND member_id<>'+q(ids['member']))!='0':
            raise RuntimeError('Technical account has unexpected binding provenance; preserving records')
    if query('SELECT count(*) FROM owner_app_user WHERE (member_id='+q(ids['member'])+' AND (open_id IS NULL OR open_id<>'+q(record['openId'])+')) OR member_id='+q(ids['outsideMember']))!='0':
        raise RuntimeError('Fixture acquired unrelated bindings; preserving records')
    for table,key,name in [('f_floor','floor_id','floor'),('building_unit','unit_id','unit'),('building_room','room_id','room'),('building_room','room_id','room2'),('building_room','room_id','outsideRoom'),('building_owner','member_id','member'),('building_owner','member_id','outsideMember'),('pay_fee_config','config_id','config'),('pay_fee','fee_id','fee')]:
        owner=query('SELECT b_id FROM '+table+' WHERE '+key+'='+q(ids[name]))
        if owner and owner!=ids['business']:
            raise RuntimeError('Fixture row ownership changed; preserving records')
    if query('SELECT count(*) FROM pay_fee_detail WHERE fee_id='+q(ids['fee']))!='0':
        raise RuntimeError('Fixture acquired payment data; preserving records')
    statements=[]
    if exists!='0':
        for table in ('u_user_attr','business_user_attr','business_user'):
            statements.append('DELETE FROM '+table+' WHERE user_id='+q(user))
        statements.append('DELETE FROM u_user WHERE user_id='+q(user))
    for table in ('owner_app_user','business_owner_app_user'):
        statements.append('DELETE FROM '+table+' WHERE member_id='+q(ids['member'])+' AND open_id='+q(record['openId']))
    statements.extend(['DELETE FROM bill_owe_fee WHERE fee_id='+q(ids['fee'])+' AND community_id='+q(record['communityId']),
                       'DELETE FROM bill WHERE config_id='+q(ids['config'])+' AND community_id='+q(record['communityId'])])
    for table,key,names in [('pay_fee','fee_id',['fee']),('pay_fee_config','config_id',['config']),('building_owner_room_rel','rel_id',['rel','rel2','outsideRel']),('building_owner','member_id',['member','outsideMember']),('building_room','room_id',['room','room2','outsideRoom']),('building_unit','unit_id',['unit']),('f_floor','floor_id',['floor'])]:
        for name in names:statements.append('DELETE FROM '+table+' WHERE '+key+'='+q(ids[name])+' AND b_id='+q(ids['business']))
    query('START TRANSACTION;'+';'.join(statements)+';COMMIT;')
    for session in record.get('syntheticSessions',[]):
        if not re.fullmatch(r'[a-f0-9-]{36}',session):raise RuntimeError('Unexpected local cache identity')
        redis_command('DEL','owner-wechat-identity:'+session)
    for ticket in record.get('syntheticAuthTickets',[]):
        if not re.fullmatch(r'[a-f0-9-]{36}',ticket):raise RuntimeError('Unexpected local OAuth ticket')
        redis_command('DEL','owner-wechat-login:'+ticket)
    before=json.loads((RUN/'before.json').read_text(encoding='utf-8'));after=snapshot()
    changed=[table for table in TABLES if before[table]!=after[table]]
    result={'checkedTables':len(TABLES),'changedTables':changed,'unchanged':not changed,'orderAuditRetained':True,'before':before,'after':after}
    (RUN/'preservation.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    record['cleanupVerified']=not changed;save(record)
    if changed:raise RuntimeError('Post-cleanup business snapshot differs; preserve evidence')
    print('Removed only owned random fixture and technical identity; all '+str(len(TABLES))+' business tables match original counts and hashes. Order audit retained.')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','generate','publish','exercise','cleanup'])
    args=parser.parse_args()
    try:globals()[args.action]()
    except Exception as error:
        print('Local direct-login verification stopped: '+type(error).__name__+'. Private state retained; inspect ignored runtime files and use scoped cleanup.',file=sys.stderr)
        return 2
    return 0


if __name__=='__main__':raise SystemExit(main())
