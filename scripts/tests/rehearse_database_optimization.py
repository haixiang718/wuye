"""Rehearse reviewed index DDL on private copies of affected tables; never alter source tables."""
import json
from pathlib import Path
import sys
import tempfile
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import migrate_database_optimization as m


def run():
    reports=[]
    for source in m.ops.DATABASES:
        proposed=m.plan(source)
        tables=[item['table'] for item in proposed]
        if not tables:reports.append({'source':source,'pendingTables':0});continue
        clone='ops_restore_'+uuid.uuid4().hex[:12];created=False
        try:
            m.ops.validate_restore_target(clone)
            m.ops.mysql_query('CREATE DATABASE '+m.ident(clone)+' CHARACTER SET utf8mb4');created=True
            # Only copy tables explicitly listed by the reviewed plan into this new schema.
            for table in tables:
                original=m.ident(source)+'.'+m.ident(table)
                m.ops.mysql_query('CREATE TABLE '+m.ident(table)+' LIKE '+original,clone)
                m.ops.mysql_query('INSERT INTO '+m.ident(table)+' SELECT * FROM '+original,clone)
            before=m.snapshot(clone,tables);indexes=m.inspect(clone)
            with tempfile.TemporaryDirectory(dir=m.ops.local.RUN,prefix='db-rehearsal-') as folder:
                result=m.apply(clone,source,Path(folder))
                if not result['passed'] or before!=m.snapshot(clone,tables):raise AssertionError('Clone data changed')
                rollback=Path(result['backup']['directory'])/'rollback.sql'
                m.ops.mysql_query(rollback.read_text(encoding='utf-8'),clone)
                if before!=m.snapshot(clone,tables):raise AssertionError('Clone rollback changed data')
                restored=m.inspect(clone)
                for table in tables:
                    if indexes[table]['indexes']!=restored[table]['indexes']:raise AssertionError('Clone rollback changed original indexes')
            reports.append({'source':source,'copiedTables':len(tables),'copiedRows':sum(t['rows'] for t in before.values()),'forwardPassed':True,'rollbackPassed':True,'sourceModified':False})
        finally:
            if created:
                m.ops.validate_restore_target(clone)
                m.ops.mysql_query('DROP DATABASE '+m.ident(clone))
    output=m.ops.local.RUN/'database-optimization/rehearsal.json';output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(reports,indent=2),encoding='utf-8')
    print(json.dumps(reports))


if __name__=='__main__':run()
