"""Reviewed V2 retirement on project MySQL 127.0.0.1:13306; defaults to plan only.

Source writes require stopped writers, no pending payments, code dependency checks,
verified full and selected-table backups, and the exact bounded retirement manifest.
Isolated test writes are limited to schemas created by this Python process.
"""
import argparse
from contextlib import contextmanager
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import uuid

import runtime_ops as ops
import migrate_business_schema as locking

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path(__file__).with_name('v2-retired-tables.json')
SOURCE = ROOT/'wuye-b/MicroCommunity-v0.8'
OWNED_CLONES = {}
# A JSON edit can narrow retirement, never expand it to property/financial/shared tables.
APPROVED = {
    'TT': frozenset('''application_key appraise business_application_key business_complaint
business_inspection_plan business_inspection_plan_staff business_inspection_point
business_inspection_route business_inspection_route_point_rel business_inspection_task
business_inspection_task_detail business_junk_requirement business_machine_record
business_repair_pool business_repair_setting business_repair_type_user business_repair_user
business_shop business_shop_attr business_shop_attr_param business_shop_catalog business_shop_desc
business_shop_photo business_shop_preferential business_visit_info complaint inspection_plan
inspection_plan_staff inspection_point inspection_route inspection_route_point_rel inspection_task
inspection_task_detail junk_requirement machine_record r_repair_pool r_repair_setting
r_repair_type_user r_repair_user renting_appointment renting_config renting_pool renting_pool_attr
renting_pool_flow s_buy_shop s_buy_shop_attr s_shop s_shop_attr s_shop_attr_param s_shop_catalog
s_shop_desc s_shop_photo s_shop_preferential s_visit_info'''.split()),
    'hc_community': frozenset('''group_buy group_buy_batch group_buy_product group_buy_product_spec
group_buy_setting product product_attr product_category product_detail product_label product_spec
product_spec_detail product_spec_value store_cart store_order store_order_address store_order_cart
store_order_cart_event store_order_cart_return store_order_cart_return_event'''.split())}
PARKING = frozenset(('machine.uploadownerphoto', 'machinetranslate.machinecmdresult',
    'machinetranslate.machinegetfreeparkingspace', 'machinetranslate.machineheartbeart',
    'machinetranslate.machineroadgateopen', 'machinetranslate.machineuploadcarlog',
    'machinetranslate.machinegettaskinfo'))
RETIRED_BEANS = frozenset(('translateOwnerPhotoToMachine', 'reTranslateOwnerPhotoMachineTemplate',
    'translateOwnerPhotoToMachineTemplate', 'translateVisitPhotoToMachineTemplate',
    'applicationKeyToMachineTemplate', 'translateStaffPhotoToMachineTemplate',
    'trainingRepairTemplate', 'generatorInspectionTaskTemplate', 'returnVisitRepairTemplate',
    'generatorGroupBuyBatchTemplate'))
RETIRED_DATABUS = {'databus_id': '122020120728820001',
                  'business_type_cd': '200200030001', 'bean_name': 'machineTransactionIotAdapt'}


def ident(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', value):
        raise ValueError('Invalid database identifier')
    return '`'+value+'`'


def literal(value):
    return "CONVERT(0x"+str(value).encode('utf-8').hex()+" USING utf8mb4)" if str(value) else "''"


def rows(database, sql):
    return [line.split('\t') for line in ops.mysql_query(sql, database).splitlines() if line]


def validate(database, role=None, writing=False):
    if database in ops.DATABASES:
        if role not in (None, database):
            raise ValueError('Live schema role mismatch')
        return database
    ops.validate_restore_target(database)
    if role not in ops.DATABASES:
        raise ValueError('Isolated schema requires an explicit source role')
    if writing and OWNED_CLONES.get(database) != role:
        raise ValueError('Isolated writes require an owned newly created clone')
    return role


@contextmanager
def isolated_schema(role):
    ops.validate_source(role)
    database = 'ops_restore_'+uuid.uuid4().hex[:12]
    ops.mysql_query('CREATE DATABASE '+ident(database)+' CHARACTER SET utf8mb4')
    OWNED_CLONES[database] = role
    try:
        yield database
    finally:
        if OWNED_CLONES.pop(database, None) == role:
            ops.validate_restore_target(database)
            ops.mysql_query('DROP DATABASE '+ident(database))


def manifest():
    return json.loads(MANIFEST.read_text(encoding='utf-8'))


def approved_manifest():
    value = manifest()
    for role, tables in value.get('schemas', {}).items():
        if role not in APPROVED or not isinstance(tables, list) or len(tables) != len(set(tables)) or not set(tables) <= APPROVED[role]:
            raise ValueError('Retirement allowlist contains an unapproved schema or table')
    if set(value.get('schemas', {})) != set(APPROVED):
        raise ValueError('Retirement allowlist must specify both source roles')
    for code in value['config']['serviceCodes']:
        normalized = code.replace('/', '.').lower()
        if normalized in PARKING or not re.fullmatch(r'(?:repair|ownerrepair|ownersrepair|complaint|visit|inspection\w*|applicationkey|machine\w*|junk\w*|renting\w*|shop\w*|product\w*|groupbuy\w*|storeorder\w*|storecart\w*)\.[a-z0-9_]+', normalized):
            raise ValueError('Retirement allowlist includes a shared or unknown service')
    if not set(value['config']['taskBeans']) <= RETIRED_BEANS:
        raise ValueError('Retirement allowlist includes a shared task')
    if value['config'].get('databus', []) not in ([], [RETIRED_DATABUS]):
        raise ValueError('Retirement allowlist includes an unreviewed databus binding')
    return value


def table_names(database):
    return [name for (name,) in rows(database, "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME")]


def source_references(tables, source=SOURCE):
    if not tables:
        return []
    names = '|'.join(re.escape(table) for table in sorted(tables, key=len, reverse=True))
    pattern = re.compile(r'(?i)\b(?:from|join|into|update|table)\s+(?:`?(?:tt|hc_community)`?\s*\.\s*)?`?(?:'+names+r')`?(?![A-Za-z0-9_])')
    command = ['rg', '--files', str(source), '-g', '*.java', '-g', '*.xml', '-g', '*.sql', '-g', '!**/target/**']
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', check=True)
    found = []
    for filename in result.stdout.splitlines():
        path = Path(filename)
        if '/src/main/' in path.as_posix() and pattern.search(path.read_text(encoding='utf-8', errors='replace')):
            found.append(path.relative_to(source).as_posix())
    return sorted(found)


def dependency_check(database, tables, names):
    if tables:
        values = ','.join(literal(table) for table in tables)
        foreign = rows(database, "SELECT TABLE_NAME,REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_NAME IS NOT NULL AND ((TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ("+values+")) OR (REFERENCED_TABLE_SCHEMA=DATABASE() AND REFERENCED_TABLE_NAME IN ("+values+")))")
        if foreign:
            raise ValueError('A foreign key references a retirement table')
    objects = rows(database, "SELECT 'view' FROM information_schema.VIEWS WHERE TABLE_SCHEMA=DATABASE() UNION ALL SELECT 'trigger' FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() UNION ALL SELECT 'routine' FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() UNION ALL SELECT 'event' FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE()")
    if objects:
        raise ValueError('Manual dependency review required for '+objects[0][0])
    if 'c_service_sql' in names:
        for table in tables:
            pattern = '(^|[^a-z0-9_])'+table+'([^a-z0-9_]|$)'
            count = int(ops.mysql_query("SELECT COUNT(*) FROM c_service_sql WHERE status_cd='0' AND LOWER(CONCAT_WS(' ',`sql`,proc,java_script,template)) REGEXP "+literal(pattern), database))
            if count:
                raise ValueError('An enabled dynamic SQL definition references '+table)


def config_plan(database, names, config):
    changes = []
    def select(table, key, guard, predicate, extra=None):
        if table not in names:
            return []
        columns = ','.join(ident(column) for column in [key, guard, 'status_cd'])
        all_matching = rows(database, 'SELECT '+columns+' FROM '+ident(table)+' WHERE '+predicate+' ORDER BY '+ident(key))
        counts = Counter((row[0],row[1]) for row in all_matching)
        found = [row for row in all_matching if row[2]=='0']
        for row_id, code, before in found:
            if counts[(row_id,code)] != 1:
                raise ValueError('Configuration key is not unique: '+table)
            changes.append({'table': table, 'key': key, 'id': row_id, 'guard': guard,
                            'value': code, 'before': before, 'after': '1', 'extraGuards': extra or {}})
        return found
    codes = config['serviceCodes']
    if codes and 'c_service' in names:
        predicate = 'service_code IN ('+','.join(literal(code) for code in codes)+')'
        # Routes must also be disabled if the corresponding service was disabled earlier.
        services = rows(database, 'SELECT service_id,business_type_cd FROM c_service WHERE '+predicate)
        select('c_service', 'id', 'service_code', predicate)
        if services:
            ids = ','.join(literal(item[0]) for item in services)
            select('c_route', 'id', 'service_id', 'service_id IN ('+ids+')')
            if 'c_service_business' in names:
                types = sorted({item[1] for item in services if item[1] != 'NULL'})
                for kind in types:
                    retained = int(ops.mysql_query('SELECT COUNT(*) FROM c_service WHERE business_type_cd='+literal(kind)+' AND NOT ('+predicate+')', database))
                    if not retained:
                        select('c_service_business', 'service_business_id', 'business_type_cd', 'business_type_cd='+literal(kind))
    beans = config['taskBeans']
    if beans and 'task_template' in names:
        predicate = 'class_bean IN ('+','.join(literal(bean) for bean in beans)+')'
        templates = rows(database, 'SELECT template_id FROM task_template WHERE '+predicate)
        select('task_template', 'template_id', 'class_bean', predicate)
        if templates:
            select('task', 'task_id', 'template_id', 'template_id IN ('+','.join(literal(item[0]) for item in templates)+')')
    for item in config.get('databus', []):
        predicate = ' AND '.join(ident(key)+'='+literal(value) for key,value in item.items())
        select('c_business_databus', 'databus_id', 'bean_name', predicate,
               {'business_type_cd': item['business_type_cd']})
    return changes


def plan(database='TT', role=None):
    role = validate(database, role)
    config = approved_manifest()
    names = table_names(database)
    tables = sorted(set(names) & set(config['schemas'][role]))
    dependency_check(database, tables, names)
    changes = config_plan(database, names, config['config']) if role == 'TT' else []
    return {'database': database, 'role': role, 'tables': tables, 'configChanges': changes,
            'sourceReferences': source_references(tables) if database in ops.DATABASES else [],
            'sql': ['DROP TABLE '+ident(table)+';' for table in tables]}


def snapshot(database, tables=None):
    selected = table_names(database) if tables is None else sorted(tables)
    result = {}
    for offset in range(0, len(selected), 40):
        group = selected[offset:offset+40]
        counts = rows(database, ' UNION ALL '.join('SELECT '+literal(table)+',COUNT(*) FROM '+ident(table) for table in group))
        for table, count in counts:
            result[table] = {'rows': int(count)}
        for full_name, check in rows(database, 'CHECKSUM TABLE '+','.join(ident(table) for table in group)+' EXTENDED'):
            table = full_name.split('.', 1)[1]
            if check == 'NULL':
                raise ValueError('Table content checksum unavailable: '+table)
            result[table]['checksum'] = check
    return result


def assert_writers_stopped(database):
    if database not in ops.DATABASES:
        return
    # Include retired legacy ports even if V2 removes them from the launcher map.
    ports = set([port for name, port in ops.local.SERVICES.items() if name != 'eureka']+
                [3000, 8000, 8080, 8001, 8002, 8003, 8005, 8006, 8007, 8008, 8009, 8010, 8012, 8013, 8014, 8015])
    if any(ops.local.listening(port) for port in ports):
        raise ValueError('Stop business writers and HTTP entrypoints before source migration')
    # A client with DB=NULL (or another default schema) may still write TT.table.
    # This dedicated instance must have no other non-system client sessions.
    connections = int(ops.mysql_query("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND USER NOT IN ('system user','event_scheduler')"))
    if connections:
        raise ValueError('Database still has connected writers; close application connections')


def assert_no_pending_payment(database):
    if 'payment_order' in table_names(database):
        if int(ops.mysql_query("SELECT COUNT(*) FROM payment_order WHERE state='CREATED'", database)):
            raise ValueError('Pending payment orders must be resolved before migration')


def backup(database, folder):
    if database in ops.DATABASES:
        return ops.create_backup(database, folder/'full')
    return {'database': database, 'isolated': True}


def selected_backup(database, tables, folder, before):
    artifacts = {}
    for table in tables:
        path = folder/(table+'.restore.sql')
        with ops.client_command('mysqldump') as (command, env), path.open('wb') as output:
            command += ['--single-transaction', '--skip-lock-tables', '--hex-blob', '--no-tablespaces',
                '--set-gtid-purged=OFF', '--column-statistics=0', '--skip-extended-insert',
                '--skip-add-locks', '--skip-disable-keys', '--skip-add-drop-table', '--no-autocommit', database, table]
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, env=env, timeout=180)
        if result.returncode:
            raise ValueError('Selected table backup failed: '+table)
        data = path.read_bytes()
        inventory = ops.dump_inventory(data)
        if inventory['tableRows'] != {table: before[table]['rows']} or not inventory['restoreDrillSupported'] or not re.search(rb'-- Dump completed on [^\r\n]+\s*\Z', data[-4096:]):
            raise ValueError('Selected backup verification failed: '+table)
        if re.search(rb'(?im)^\s*DROP\s', data):
            raise ValueError('Inverse backup must not overwrite an existing table')
        artifacts[table] = {'file': path.name, 'sha256': ops.digest(path), 'bytes': path.stat().st_size}
    return artifacts


def config_where(change, state):
    parts = [ident(change['key'])+'='+literal(change['id']), ident(change['guard'])+'='+literal(change['value']), 'status_cd='+literal(state)]
    parts += [ident(key)+'='+literal(value) for key,value in change.get('extraGuards',{}).items()]
    return ' AND '.join(parts)


def config_sql(changes, reverse=False):
    parts = ['START TRANSACTION;']
    for item in changes:
        old, new = (item['after'], item['before']) if reverse else (item['before'], item['after'])
        parts.append('UPDATE '+ident(item['table'])+' SET status_cd='+literal(new)+' WHERE '+config_where(item, old)+';')
    return '\n'.join(parts+['COMMIT;'])+'\n'


def config_matches(database, changes, state_key):
    for offset in range(0,len(changes),60):
        statements = ['SELECT COUNT(*) FROM '+ident(item['table'])+' WHERE '+config_where(item,item[state_key]) for item in changes[offset:offset+60]]
        if any(count != '1' for (count,) in rows(database,' UNION ALL '.join(statements))):
            return False
    return True


def config_content(database, changes, normalize=False):
    """Digest every configuration value, normalizing ONLY reviewed status cells.

    This verifies exact configuration differences without temporary source writes or
    publishing SQL bodies, credentials, or row contents in the report.
    """
    result = {}
    for table in sorted({item['table'] for item in changes}):
        columns = [column for (column,) in rows(database, "SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+literal(table)+' ORDER BY ORDINAL_POSITION')]
        expressions = []
        for column in columns:
            value = ident(column)
            if column == 'status_cd' and normalize:
                cases = ['WHEN '+config_where(item, item['after'])+' THEN '+literal(item['before']) for item in changes if item['table'] == table]
                value = 'CASE '+' '.join(cases)+' ELSE status_cd END'
            expressions.append('IFNULL(HEX('+value+"),'~')")
        digests = ops.mysql_query("SELECT SHA2(CONCAT_WS('|',"+','.join(expressions)+'),256) digest FROM '+ident(table)+' ORDER BY digest', database).splitlines()
        result[table] = hashlib.sha256('\n'.join(digests).encode('ascii')).hexdigest()
    return result


def write_inverse(folder, report):
    parts = []
    for table in report['removedTables']:
        artifact = report['restoreArtifacts'][table]
        path = folder/artifact['file']
        if ops.digest(path) != artifact['sha256']:
            raise ValueError('Inverse backup checksum mismatch')
        parts.append(path.read_text(encoding='utf-8'))
    if report['configCommitted']:
        parts.append(config_sql(report['plan']['configChanges'], reverse=True))
    temporary = folder/'rollback.sql.partial'
    temporary.write_text('\n'.join(parts), encoding='utf-8')
    temporary.replace(folder/'rollback.sql')
    report['rollbackSha256'] = ops.digest(folder/'rollback.sql')
    ops.write_json(folder/'result.json', report)


def apply(database='TT', role=None, backup_root=None):
    role = validate(database, role, writing=True)
    with locking.migration_lock():
        assert_writers_stopped(database)
        assert_no_pending_payment(database)
        proposed = plan(database, role)
        if proposed['sourceReferences']:
            raise ValueError('Retired tables still have main-source SQL dependencies; inspect plan sourceReferences')
        if not proposed['tables'] and not proposed['configChanges']:
            return {'passed': True, 'database': database, 'removedCount': 0, 'configCount': 0}
        base = Path(backup_root) if backup_root else ops.local.RUN/'backups/v2-pruning'
        folder = base/(role+'-'+uuid.uuid4().hex[:12])
        folder.mkdir(parents=True, exist_ok=False)
        report = {'version': 1, 'database': database, 'role': role, 'directory': str(folder.resolve()),
            'endpoint': ops.ENDPOINT, 'startedAtUtc': ops.now(), 'passed': False, 'plan': proposed,
            'removedTables': [], 'removedCount': 0, 'configCommitted': False, 'configCount': 0,
            'inProgress': None, 'needsSchemaInspection': False}
        report['backup'] = backup(database, folder)
        before = snapshot(database)
        report['before'] = before
        report['configContentBefore'] = config_content(database, proposed['configChanges'])
        report['restoreArtifacts'] = selected_backup(database, proposed['tables'], folder, before)
        # Writers must still be stopped after the possibly lengthy backup operation.
        assert_writers_stopped(database)
        assert_no_pending_payment(database)
        if before != snapshot(database) or proposed != plan(database, role):
            raise ValueError('Database changed during backup; nothing was retired')
        write_inverse(folder, report)
        try:
            changes = proposed['configChanges']
            if changes:
                report['inProgress'] = 'config'
                ops.write_json(folder/'result.json', report)
                try:
                    ops.mysql_query(config_sql(changes), database)
                    if not config_matches(database, changes, 'after'):
                        raise ValueError('Configuration transition did not match the reviewed plan')
                    report['configCommitted'] = True
                except Exception:
                    report['configCommitted'] = config_matches(database, changes, 'after')
                    report['needsSchemaInspection'] = not report['configCommitted']
                    if report['configCommitted']:
                        report['inProgress'] = None
                    write_inverse(folder, report)
                    raise
                report['configCount'] = len(changes)
                report['inProgress'] = None
                write_inverse(folder, report)
            for table in proposed['tables']:
                report['inProgress'] = table
                ops.write_json(folder/'result.json', report)
                try:
                    ops.mysql_query('SET SESSION lock_wait_timeout=5; DROP TABLE '+ident(table), database)
                    if table in table_names(database):
                        raise ValueError('Retired table is unexpectedly still present')
                except Exception:
                    try:
                        absent = table not in table_names(database)
                    except Exception:
                        absent = False
                    if absent:
                        report['removedTables'].append(table)
                        report['inProgress'] = None
                    else:
                        report['needsSchemaInspection'] = True
                    report['removedCount'] = len(report['removedTables'])
                    write_inverse(folder, report)
                    raise
                report['removedTables'].append(table)
                report['removedCount'] = len(report['removedTables'])
                report['inProgress'] = None
                write_inverse(folder, report)
            after = snapshot(database)
            config_tables = {item['table'] for item in changes}
            preserved = set(before)-set(proposed['tables'])-config_tables
            if any(before[table] != after.get(table) for table in preserved):
                raise ValueError('Retained business table content changed')
            if changes:
                if not config_matches(database, changes, 'after'):
                    raise ValueError('Configuration no longer matches the intended status')
                for table in config_tables:
                    if before[table]['rows'] != after[table]['rows']:
                        raise ValueError('Unexpected configuration row count change')
                if config_content(database, changes, normalize=True) != report['configContentBefore']:
                    raise ValueError('Unexpected configuration content change outside reviewed status cells')
            report.update(after=after, preservedTables=len(preserved), preservedDataEqual=True,
                passed=True, finishedAtUtc=ops.now())
            write_inverse(folder, report)
            return report
        except Exception:
            report['finishedAtUtc'] = ops.now()
            write_inverse(folder, report)
            raise


def rollback(database, directory, role=None):
    role = validate(database, role, writing=True)
    folder = Path(directory)
    with locking.migration_lock():
        assert_writers_stopped(database)
        report = json.loads((folder/'result.json').read_text(encoding='utf-8'))
        if report.get('version') != 1 or report.get('database') != database or report.get('role') != role or report.get('endpoint') != ops.ENDPOINT:
            raise ValueError('Rollback does not belong to this schema')
        if report['needsSchemaInspection'] or report['inProgress'] is not None:
            raise ValueError('Manual schema/server DDL inspection is required before rollback')
        names = set(table_names(database))
        if names & set(report['removedTables']):
            raise ValueError('A retired table already exists; rollback never overwrites it')
        if names != set(report['before'])-set(report['removedTables']):
            raise ValueError('Table inventory changed after retirement; inspect before rollback')
        if ops.digest(folder/'rollback.sql') != report['rollbackSha256']:
            raise ValueError('Rollback file checksum mismatch')
        config_tables = {item['table'] for item in report['plan']['configChanges']}
        preserved = set(report['before'])-set(report['removedTables'])-config_tables
        current = snapshot(database, preserved)
        if any(current[table] != report['before'][table] for table in preserved):
            raise ValueError('Retained data changed after migration; refuse automatic rollback')
        if report['configCommitted'] and not config_matches(database, report['plan']['configChanges'], 'after'):
            raise ValueError('Configuration changed after retirement; refuse automatic rollback')
        if report['configCommitted'] and config_content(database, report['plan']['configChanges'], normalize=True) != report['configContentBefore']:
            raise ValueError('Configuration content changed after retirement; refuse automatic rollback')
        # Every CREATE has no preceding DROP: even a concurrent table creation fails closed.
        for table in report['removedTables']:
            artifact = report['restoreArtifacts'][table]
            path = folder/artifact['file']
            if path.name != table+'.restore.sql' or ops.digest(path) != artifact['sha256']:
                raise ValueError('Selected restore file checksum mismatch')
            with ops.client_command() as (command, env), path.open('rb') as source:
                result = subprocess.run(command+[database], stdin=source, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=env, timeout=180)
            if result.returncode:
                raise ValueError('Selected table restore failed; inspect partially restored table')
            if snapshot(database, [table])[table] != report['before'][table]:
                raise ValueError('Restored table content differs from migration baseline')
        if report['configCommitted']:
            ops.mysql_query(config_sql(report['plan']['configChanges'], reverse=True), database)
        if snapshot(database) != report['before']:
            raise ValueError('Rollback content differs from the migration baseline')
        report['rollbackPassed'] = True
        report['rolledBackAtUtc'] = ops.now()
        ops.write_json(folder/'result.json', report)
        return {'passed': True, 'restoredTables': len(report['removedTables'])}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', choices=ops.DATABASES, default='TT')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--rollback', type=Path)
    args = parser.parse_args(argv)
    if args.apply and args.rollback:
        parser.error('Choose apply or rollback, not both')
    if args.apply or args.rollback:
        with ops.local.launcher_lock():
            result = rollback(args.database, args.rollback) if args.rollback else apply(args.database)
    else:
        result = plan(args.database)
    # No table contents, configuration bodies or credentials are exposed.
    if args.apply:
        result = {key: result.get(key) for key in ['passed','database','directory','removedCount','configCount','preservedTables','preservedDataEqual']}
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, ops.OpsError) as error:
        raise SystemExit(str(error))
