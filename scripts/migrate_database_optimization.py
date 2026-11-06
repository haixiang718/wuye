"""Reviewed index/primary-key maintenance on the project MySQL; never deletes rows/tables."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid
import runtime_ops as ops
import migrate_business_schema as locking

ROOT=Path(__file__).resolve().parents[1]
CORE_TABLES=set('building_owner building_owner_room_rel building_room building_unit f_floor owner_app_user owner_car p_parking_space pay_fee pay_fee_config business_pay_fee business_pay_fee_attrs business_pay_fee_config business_pay_fee_detail m_menu m_menu_group p_privilege p_privilege_group p_privilege_rel p_privilege_user s_community s_community_photo business_community_attr s_store s_store_photo s_store_cerdentials c_business_type c_orders'.split())
IDENTITIES={'bill':('bill_id','idx_billing_identity',('bill_id','community_id')),
            'bill_owe_fee':('owe_id','idx_billing_owe_identity',('owe_id','community_id','bill_id'))}
SORT_INDEX=('bill','idx_billing_current_date',('community_id','cur_bill','create_time'))


def ident(value):
    if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*',value):
        raise ValueError('Invalid schema identifier')
    return '`'+value+'`'


def validate(database,role=None):
    if database in ops.DATABASES:
        if role not in (None,database):raise ValueError('Live schema role mismatch')
        return database
    ops.validate_restore_target(database)
    if role not in ops.DATABASES:raise ValueError('Isolated test schema needs an explicit role')
    return role


def rows(database,sql):
    return [line.split('\t') for line in ops.mysql_query(sql,database).splitlines()]


def inspect(database):
    tables={}
    for table,engine in rows(database,"SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'"):
        tables[table]={'engine':engine,'indexes':{},'nullable':{},'clustered':set(),'foreign':False}
    for table,column,nullable in rows(database,"SELECT TABLE_NAME,COLUMN_NAME,IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()"):
        if table in tables:tables[table]['nullable'][column]=nullable=='YES'
    sql="SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,INDEX_TYPE,IS_VISIBLE,COLUMN_NAME,COALESCE(SUB_PART,0),COALESCE(COLLATION,'NONE'),COALESCE(EXPRESSION,'NONE') FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"
    for table,name,nonunique,kind,visible,column,prefix,direction,expression in rows(database,sql):
        item=tables[table]['indexes'].setdefault(name,{'name':name,'unique':nonunique=='0','kind':kind,'visible':visible,'parts':[]})
        item['parts'].append((column,int(prefix),direction,expression))
    sql="SELECT SUBSTRING(t.NAME,LOCATE('/',t.NAME)+1),i.NAME FROM information_schema.INNODB_INDEXES i JOIN information_schema.INNODB_TABLES t ON t.TABLE_ID=i.TABLE_ID WHERE LOWER(SUBSTRING_INDEX(t.NAME,'/',1))=LOWER(DATABASE()) AND (i.TYPE & 1)=1"
    for table,name in rows(database,sql):
        if table in tables:tables[table]['clustered'].add(name)
    sql="SELECT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL UNION SELECT REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IS NOT NULL"
    for (table,) in rows(database,sql):
        if table in tables:tables[table]['foreign']=True
    return tables


def columns(index):return tuple(part[0] for part in index['parts'])


def regular(index):
    return index['kind']=='BTREE' and index['visible']=='YES' and all(p[1]==0 and p[2]=='A' and p[3]=='NONE' for p in index['parts'])


def add_index(index):
    if index['name']=='PRIMARY':return 'ADD PRIMARY KEY ('+','.join(ident(c) for c in columns(index))+')'
    return 'ADD '+('UNIQUE ' if index['unique'] else '')+'INDEX '+ident(index['name'])+' ('+','.join(ident(c) for c in columns(index))+')'


def index_hints(database):
    # Refuse hinted tables rather than guessing whether a named index is optional.
    sql="SELECT CONCAT_WS(' ',`sql`,proc,java_script,template) FROM c_service_sql WHERE status_cd='0'"
    configured=ops.mysql_query(sql,'TT')
    command=['rg','--files',str(ROOT/'wuye-b/MicroCommunity-v0.8'),'-g','*.java','-g','*.xml','-g','!**/target/**']
    paths=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',check=True).stdout.splitlines()
    sources=[configured]+[Path(p).read_text(encoding='utf-8',errors='replace') for p in paths if '/src/main/' in Path(p).as_posix()]
    return '\n'.join(match for source in sources for match in re.findall(r'(?is)\b(?:FORCE|USE|IGNORE)\s+(?:INDEX|KEY)\b.{0,300}',source))


def plan(database='TT',role=None,hints=None):
    role=validate(database,role);tables=inspect(database)
    if hints is None:hints=index_hints(database)
    changes={}
    def change(table,forward,reverse,reason):
        item=changes.setdefault(table,{'table':table,'forward':[],'reverse':[],'reasons':[]})
        item['forward'].append(forward);item['reverse'].insert(0,reverse);item['reasons'].append(reason)
    for table,definition in sorted(tables.items()):
        if table not in CORE_TABLES or definition['engine']!='InnoDB' or definition['foreign']:continue
        groups=defaultdict(list)
        for index in definition['indexes'].values():
            if regular(index):groups[tuple(index['parts'])].append(index)
        for group in groups.values():
            if len(group)<2:continue
            ordered=sorted(group,key=lambda i:(i['name']!='PRIMARY',i['name'] not in definition['clustered'],not i['unique'],len(i['name']),i['name']))
            keep=ordered[0]
            for index in ordered[1:]:
                if index['name'] in definition['clustered'] or index['name']=='PRIMARY':continue
                if index['unique'] and not keep['unique']:continue
                if re.search(r'(?i)(?<![\w])'+re.escape(index['name'])+r'(?![\w])',hints):
                    raise ValueError('Explicit index hint prevents removing '+table+'.'+index['name'])
                change(table,'DROP INDEX '+ident(index['name']),add_index(index),'Exact duplicate; retain '+keep['name'])
    if role=='TT':
        for table,(key,old,old_columns) in IDENTITIES.items():
            definition=tables.get(table)
            if not definition or definition['engine']!='InnoDB' or key not in definition['nullable'] or definition['nullable'][key] or definition['foreign']:
                raise ValueError('Incompatible billing identity table: '+table)
            indexes=definition['indexes'];primary=indexes.get('PRIMARY')
            if primary and (columns(primary)!=(key,) or not regular(primary)):raise ValueError('Unexpected billing primary key')
            invalid=int(ops.mysql_query('SELECT COUNT(*) FROM '+ident(table)+' WHERE '+ident(key)+" IS NULL OR TRIM("+ident(key)+")=''",database))
            duplicates=int(ops.mysql_query('SELECT COUNT(*) FROM (SELECT '+ident(key)+' FROM '+ident(table)+' GROUP BY '+ident(key)+' HAVING COUNT(*)>1) duplicate_ids',database))
            if invalid or duplicates:raise ValueError('Billing identifiers are blank or duplicated: '+table)
            if not primary:change(table,'ADD PRIMARY KEY ('+ident(key)+')','DROP PRIMARY KEY','Enforce unique non-null bill identity')
            if old in indexes:
                previous=indexes[old]
                if columns(previous)!=old_columns or previous['unique'] or not regular(previous):raise ValueError('Unexpected previous identity index')
                if re.search(r'(?i)\b'+re.escape(old)+r'\b',hints):raise ValueError('Identity index has an explicit hint')
                change(table,'DROP INDEX '+ident(old),add_index(previous),'Primary key replaces old identity lookup')
        table,name,fields=SORT_INDEX;indexes=tables[table]['indexes']
        if name in indexes and (columns(indexes[name])!=fields or not regular(indexes[name]) or indexes[name]['unique']):
            raise ValueError('Unexpected bill ordering index')
        if not any(regular(i) and columns(i)[:len(fields)]==fields for i in indexes.values()):
            change(table,'ADD INDEX '+ident(name)+' ('+','.join(ident(f) for f in fields)+')','DROP INDEX '+ident(name),'Current bill list filter and date ordering')
    result=[]
    for table,item in sorted(changes.items()):
        forward='ALTER TABLE '+ident(table)+' '+', '.join(item['forward'])+', ALGORITHM=INPLACE, LOCK=NONE'
        reverse='ALTER TABLE '+ident(table)+' '+', '.join(item['reverse'])
        result.append({'table':table,'sql':forward,'rollbackSql':reverse,'reasons':item['reasons']})
    return result


def snapshot(database,tables):
    validate(database,'TT' if database.startswith('ops_restore_') else None)
    return {table:{'rows':int(ops.mysql_query('SELECT COUNT(*) FROM '+ident(table),database)),
                   'checksum':ops.mysql_query('CHECKSUM TABLE '+ident(table)+' EXTENDED',database).split('\t')[-1]}
            for table in tables}


def backup(database,backup_root):
    if database in ops.DATABASES:return ops.create_backup(database,backup_root)
    ops.validate_restore_target(database)
    folder=Path(backup_root)/('optimization-test-'+uuid.uuid4().hex);folder.mkdir(parents=True)
    file=folder/'database.sql'
    with ops.client_command('mysqldump') as (command,env),file.open('wb') as output:
        command+=['--single-transaction','--skip-lock-tables','--hex-blob','--no-tablespaces','--set-gtid-purged=OFF','--column-statistics=0',database]
        result=subprocess.run(command,stdout=output,stderr=subprocess.PIPE,env=env,timeout=60)
    if result.returncode or b'Dump completed' not in file.read_bytes()[-2048:]:raise ValueError('Synthetic schema backup failed')
    return {'directory':str(folder),'bytes':file.stat().st_size,'sha256':ops.digest(file)}


def write_atomic(path,text):
    temporary=path.with_name(path.name+'.tmp')
    temporary.write_text(text,encoding='utf-8')
    temporary.replace(path)


def apply(database='TT',role=None,backup_root=None):
    role=validate(database,role)
    with locking.migration_lock():
        proposed=plan(database,role);before=snapshot(database,[i['table'] for i in proposed])
        if not proposed:return {'database':database,'applied':0,'passed':True}
        saved=backup(database,backup_root or ops.local.RUN/'backups/database-optimization')
        folder=Path(saved['directory'])
        (folder/'forward.sql').write_text(';\n'.join(i['sql'] for i in proposed)+';\n',encoding='utf-8')
        (folder/'rollback-plan.sql').write_text(';\n'.join(i['rollbackSql'] for i in reversed(proposed))+';\n',encoding='utf-8')
        report={'database':database,'backup':saved,'planned':proposed,'applied':0,'before':before,'passed':False}
        def journal():
            committed=proposed[:report['applied']]
            write_atomic(folder/'rollback.sql',';\n'.join(i['rollbackSql'] for i in reversed(committed))+';\n' if committed else '-- No committed changes yet.\n')
            write_atomic(folder/'result.json',json.dumps(report,indent=2))
        journal()
        try:
            for change in proposed:
                report['inProgress']=change['table'];journal()
                try:
                    ops.mysql_query('SET SESSION lock_wait_timeout=10; '+change['sql'],database)
                except Exception:
                    # MySQL 8 commits each ALTER atomically; a lost reply is not proof of failure.
                    report['failedTable']=change['table']
                    report['needsSchemaInspection']=True
                    try:
                        pending={item['table']:item for item in plan(database,role)}
                        if change['table'] not in pending:
                            report['applied']+=1;report['needsSchemaInspection']=False
                        # Seeing the old plan does not prove a server-side ALTER has
                        # stopped after a client timeout. Keep the ambiguous table marked.
                        if not report['needsSchemaInspection']:report['inProgress']=None
                    except Exception:pass
                    journal()
                    raise
                report['applied']+=1
                report['inProgress']=None;journal()
            for table in before:ops.mysql_query('ANALYZE TABLE '+ident(table),database)
            report['after']=snapshot(database,list(before))
            if before!=report['after']:raise ValueError('Data changed during maintenance; inspect backup and concurrent activity')
            if plan(database,role):raise ValueError('Index plan still has pending changes')
            report['passed']=True
        finally:
            journal()
        return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--database',choices=ops.DATABASES,default='TT');parser.add_argument('--apply',action='store_true')
    args=parser.parse_args();print(json.dumps(apply(args.database) if args.apply else plan(args.database),indent=2))
