"""Add durable auto-review events and audit without rewriting existing financial rows."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import runtime_ops as ops

ROOT=Path(__file__).resolve().parents[1]
FILES=[ROOT/'scripts/migrations/2026-11-06-automatic-bill-review.sql',ROOT/'scripts/migrations/2026-11-06-bill-review-audit.sql']

def statements(path):
    delimiter=';';buffer=[]
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.lstrip().startswith('--') or not line.strip():continue
        if line.upper().startswith('DELIMITER '):delimiter=line.split()[1];continue
        buffer.append(line)
        if line.rstrip().endswith(delimiter):
            yield '\n'.join(buffer).rstrip()[:-len(delimiter)].strip()
            buffer=[]
    if buffer:raise RuntimeError('Incomplete migration statement')

def normalize(body):
    return re.sub(r'\s+','',body.replace('`','')).lower()

def plan(database='TT'):
    ops.validate_source(database)
    planned=[]
    for path in FILES:
        for statement in statements(path):
            match=re.match(r'CREATE TRIGGER\s+`?(\w+)`?\s+(BEFORE|AFTER)\s+(INSERT|UPDATE|DELETE)\s+ON\s+`?(\w+)`?\s+FOR EACH ROW\s+(.*)',statement,re.S|re.I)
            if match:
                name,timing,event,table,body=match.groups()
                found=ops.mysql_query("SELECT CONCAT(ACTION_TIMING,'|',EVENT_MANIPULATION,'|',EVENT_OBJECT_TABLE,'|',HEX(ACTION_STATEMENT)) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='"+database+"' AND TRIGGER_NAME='"+name+"'")
                if found:
                    parts=found.split('|',3);found='|'.join(parts[:3]+[bytes.fromhex(parts[3]).decode('utf-8')])
                    if normalize(found)!=normalize('|'.join([timing,event,table,body])):raise RuntimeError('Existing migration trigger differs: '+name)
                    continue
            planned.append(statement)
    return planned

def seed(database):
    # Existing fee events are queued once, with amounts/owners recomputed by the trusted generator.
    ops.mysql_query("INSERT IGNORE INTO automatic_bill_queue(community_id,config_id,next_run) "
        "SELECT DISTINCT f.community_id,f.config_id,NOW(3) FROM pay_fee f "
        "INNER JOIN pay_fee_config c ON c.community_id=f.community_id AND c.config_id=f.config_id "
        "AND c.status_cd='0' WHERE f.status_cd='0' AND f.state='2008001'",database)

def apply(database='TT',backup=True):
    changes=plan(database)
    before={t:hashlib.sha256(ops.mysql_query('SELECT * FROM '+t+' ORDER BY '+key,database).encode()).hexdigest()
        for t,key in [('bill','bill_id'),('bill_owe_fee','owe_id'),('pay_fee','fee_id')]}
    saved=ops.create_backup(database) if backup else None
    for statement in changes:
        command = 'DELIMITER $$\n'+statement+'$$\nDELIMITER ;\n' if re.match(r'CREATE TRIGGER\b',statement,re.I) else statement
        ops.mysql_query(command,database)
    seed(database)
    after={t:hashlib.sha256(ops.mysql_query('SELECT * FROM '+t+' ORDER BY '+key,database).encode()).hexdigest()
        for t,key in [('bill','bill_id'),('bill_owe_fee','owe_id'),('pay_fee','fee_id')]}
    if before!=after:raise RuntimeError('Financial rows changed during additive migration; inspect before continuing')
    return {'appliedStatements':len(changes),'financialRowsPreserved':True,'backup':saved}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apply',action='store_true');a=p.parse_args()
    if a.apply:print(json.dumps(apply(),ensure_ascii=True,indent=2))
    else:print(json.dumps({'plannedStatements':len(plan()),'writesPerformed':False}))
