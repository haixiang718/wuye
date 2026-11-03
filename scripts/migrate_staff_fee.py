"""Replace only the local staff-fee query, with a full backup before changing it."""
import argparse
import json
from pathlib import Path
import urllib.request

import migrate_business_schema as migration

SOURCE = Path(__file__).with_name('staff-fee-query.json')
CODE = 'api.getStaffFee'


def snapshot(database, other=False):
    predicate = "service_code%s'%s'" % ('<>' if other else '=', CODE)
    return migration.query(database, 'SELECT HEX(CONCAT_WS(CHAR(9),id,service_code,name,params,query_model,'
        "IFNULL(`sql`,''),IFNULL(template,''),status_cd)) FROM c_service_sql WHERE " + predicate + ' ORDER BY id')


def plan(database='TT'):
    migration.validate_database(database)
    if migration.query(database, "SELECT COUNT(*) FROM c_service_sql WHERE service_code='"+CODE+"'") != '1':
        raise migration.MigrationError('Expected exactly one existing staff-fee query')
    artifact = json.loads(SOURCE.read_text(encoding='utf-8'))
    raw = migration.query(database, "SELECT HEX(`sql`),HEX(template) FROM c_service_sql WHERE service_code='"+CODE+"'")
    # Legacy SQL JSON contains literal newlines, accepted by the running Fastjson parser.
    current = [json.loads(bytes.fromhex(value).decode('utf-8'), strict=False) for value in raw.split('\t')]
    return artifact, current != [artifact['queries'], artifact['template']]


def apply(database='TT'):
    with migration.migration_lock():
        artifact, pending = plan(database)
        others = snapshot(database, True)
        backup = None
        if pending:
            backup = migration.create_backup(database, migration.local.RUN/'backups/staff-fee')
            values = ["CONVERT(0x"+json.dumps(artifact[key],ensure_ascii=False).encode('utf-8').hex()+" USING utf8mb4)"
                      for key in ('queries','template')]
            statement = "UPDATE c_service_sql SET `sql`="+values[0]+",template="+values[1]+" WHERE service_code='"+CODE+"'"
            migration.query(database, 'START TRANSACTION; '+statement+'; COMMIT;')
        if plan(database)[1] or snapshot(database, True) != others:
            raise migration.MigrationError('Query verification failed; inspect the backup before retrying')
        if database == 'TT':
            request = urllib.request.Request('http://127.0.0.1:8001/cacheApi/flush',headers={'cache':'SERVICE_SQL'})
            with urllib.request.urlopen(request,timeout=30) as response:
                text = response.read().decode('utf-8')
                if response.status != 200 or '刷新缓存成功' not in text:
                    raise migration.MigrationError('Query saved but cache refresh failed')
        return {'applied':pending,'backup':backup,'otherQueriesPreserved':True}


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply',action='store_true')
    args=parser.parse_args()
    print(json.dumps(apply() if args.apply else {'pending':plan()[1]},indent=2))
