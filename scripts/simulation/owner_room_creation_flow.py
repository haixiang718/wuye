"""Scoped backend owner/two-room browser verification. Never sends messages or collects payments."""
import argparse,json,re,secrets,sys,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
sys.path.insert(0,str(Path(__file__).resolve().parent))
import local
from wechat_direct_flow import snapshot as full_snapshot
def snapshot():
    # Browser requests can rotate the existing user's temporary session key.
    # That technical login metadata must not be restored over a live session.
    return {k:v for k,v in full_snapshot().items() if k!='u_user_attr'}
RUN=local.RUN/'owner-room-creation-flow'
RECORD=RUN/'fixture.json'
LABEL='LOCAL OWNER ROOM CREATION ONLY - DO NOT PAY'
def query(sql):return local.mysql_run(sql,database='TT',capture=True)
def q(value):return 'CONVERT(0x'+str(value).encode().hex()+' USING utf8mb4)'
def load():
    r=json.loads(RECORD.read_text(encoding='utf-8'))
    if r.get('label')!=LABEL or not re.fullmatch(r'ORC[a-f0-9]{12}',r.get('prefix','')):
        raise RuntimeError('Unexpected fixture provenance')
    if any(r['ids'][k]!=r['prefix']+s for k,s in [('floor','F'),('unit','U'),('room','R1'),('room2','R2'),('spare','R3')]):
        raise RuntimeError('Unexpected asset identifiers')
    return r
def save(r):RECORD.write_text(json.dumps(r,ensure_ascii=False,indent=2),encoding='utf-8')
def prepare():
    RUN.mkdir(parents=True,exist_ok=True)
    if RECORD.exists() and not load().get('cleanupVerified'):
        raise RuntimeError('Clean up previous fixture first')
    prefix='ORC'+uuid.uuid4().hex[:12]
    cfg=json.loads((local.RUN/'wechat-domain.json').read_text(encoding='utf-8-sig'))
    before=snapshot()
    (RUN/'before.json').write_text(json.dumps(before),encoding='utf-8')
    def card():
        base='11010119900101'+str(secrets.randbelow(999)+1).zfill(3)
        weights=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]
        return base+'10X98765432'[sum(int(a)*b for a,b in zip(base,weights))%11]
    r={'prefix':prefix,'label':LABEL,'communityId':cfg['communityId'],
       'floorNum':'T'+prefix[3:9],'name':'录入联测'+prefix[-3:],
       'phone':'199'+str(secrets.randbelow(100000000)).zfill(8),'idCard':card(),
       'ids':{k:prefix+s for k,s in [('floor','F'),('unit','U'),('room','R1'),('room2','R2'),('spare','R3')]}}
    if query('SELECT COUNT(*) FROM building_owner WHERE id_card='+q(r['idCard']))!='0':
        raise RuntimeError('Synthetic identity collision; retry')
    save(r);ids=r['ids']
    statements=['START TRANSACTION',
      "INSERT INTO f_floor(floor_id,b_id,floor_num,name,user_id,remark,community_id,floor_area) VALUES("+','.join(map(q,[ids['floor'],prefix,r['floorNum'],'Synthetic owner entry','-1',LABEL,r['communityId']]))+",0)",
      "INSERT INTO building_unit(unit_id,b_id,unit_num,floor_id,layer_count,lift,user_id,remark,unit_area) VALUES("+','.join(map(q,[ids['unit'],prefix,'1',ids['floor']]))+",10,'1010','-1',"+q(LABEL)+",0)"]
    for index,k in enumerate(['room','room2','spare']):
        statements.append("INSERT INTO building_room(room_id,b_id,room_num,unit_id,layer,section,apartment,built_up_area,fee_coefficient,user_id,remark,state,community_id,room_type) VALUES("+
            ','.join(map(q,[ids[k],prefix,'T10'+str(index+1),ids['unit']]))+
            ",1,1,'10101',80,1,'-1',"+q(LABEL)+",'2002',"+q(r['communityId'])+",'2020602')")
    query(';'.join(statements+['COMMIT']))
    print('Prepared three isolated, unbound test rooms.')
def verify():
    r=load();ids=r['ids']
    owners=query('SELECT member_id,owner_id FROM building_owner WHERE remark='+q(LABEL)+' AND id_card='+q(r['idCard'])+' AND community_id='+q(r['communityId'])).split('\t')
    if len(owners)!=2 or owners[0]!=owners[1]:raise RuntimeError('Expected one newly created owner')
    r['createdOwnerId']=owners[0];save(r)
    relations=query('SELECT room_id FROM building_owner_room_rel WHERE owner_id='+q(owners[0])+" AND status_cd='0' ORDER BY room_id").splitlines()
    expected=sorted([ids['room'],ids['room2']])
    if sorted(relations)!=expected:raise RuntimeError('Expected exactly the two selected relations')
    count=query('SELECT COUNT(*) FROM building_owner_room_rel WHERE owner_id='+q(owners[0])+" AND start_time IS NOT NULL AND end_time>start_time AND status_cd='0'")
    if count!='2':raise RuntimeError('Missing relation validity dates')
    state=query('SELECT GROUP_CONCAT(state ORDER BY room_id) FROM building_room WHERE room_id IN ('+','.join(map(q,expected))+')')
    if state!='2001,2001':raise RuntimeError('Room state not synchronized')
    if query('SELECT COUNT(*) FROM building_owner_room_rel WHERE room_id='+q(ids['spare']))!='0':raise RuntimeError('Unselected room was bound')
    if query('SELECT COUNT(*) FROM building_owner WHERE remark='+q(LABEL)+' AND id_card<>'+q(r['idCard']))!='0':raise RuntimeError('Failed request created a partial owner')
    checks={'passed':True,'ownerCount':1,'boundRooms':2,'validityDatesPresent':True,'roomStatesUpdated':True,
      'unselectedRoomUnbound':True,'invalidRequestsCreatedNoOwner':True,'messagesSent':0,'payments':0}
    (RUN/'database-verification.json').write_text(json.dumps(checks,indent=2),encoding='utf-8')
    print(json.dumps(checks))
def cleanup():
    r=load();ids=r['ids']
    owners=query('SELECT member_id FROM building_owner WHERE remark='+q(LABEL)+' AND community_id='+q(r['communityId'])).splitlines()
    for owner in owners:
        if not re.fullmatch('[0-9]{1,30}',owner):raise RuntimeError('Unexpected generated owner identifier')
    statements=['START TRANSACTION']
    for owner in owners:
        bound=query('SELECT room_id FROM building_owner_room_rel WHERE owner_id='+q(owner)).splitlines()
        if any(room not in ids.values() for room in bound):raise RuntimeError('Unexpected fixture relationship; preserve for review')
        statements.extend(['DELETE FROM building_owner_attr WHERE member_id='+q(owner),
            'DELETE FROM building_owner_room_rel WHERE owner_id='+q(owner),
            'DELETE FROM building_owner WHERE member_id='+q(owner)+' AND remark='+q(LABEL)])
    for table,key,names in [('building_room','room_id',['room','room2','spare']),('building_unit','unit_id',['unit']),('f_floor','floor_id',['floor'])]:
        statements.append('DELETE FROM '+table+' WHERE '+key+' IN ('+','.join(q(ids[k]) for k in names)+') AND remark='+q(LABEL))
    query(';'.join(statements+['COMMIT']))
    before=json.loads((RUN/'before.json').read_text(encoding='utf-8'));before.pop('u_user_attr',None);after=snapshot()
    matched=before==after
    (RUN/'cleanup.json').write_text(json.dumps({'passed':matched,'tablesCompared':len(before),'originalBusinessDataUnchanged':matched},indent=2),encoding='utf-8')
    if not matched:raise RuntimeError('Business data changed; inspect private snapshots')
    r['cleanupVerified']=True;save(r)
    print('Cleaned isolated test rows; original business data hashes match across '+str(len(before))+' tables. Order audit records retained.')
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','verify','cleanup'])
    globals()[p.parse_args().action]()
