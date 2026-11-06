"""Read-only project database inventory: metadata, counts, code references and EXPLAIN.

No business records, SQL configuration bodies, credentials, or maintenance SQL are
written to the report. Empty tables are never marked safe to delete.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import runtime_ops

SCHEMAS = ('TT', 'hc_community')
SOURCE_ROOT = ROOT / 'wuye-b/MicroCommunity-v0.8'
HISTORICAL = {'m_menu_20200316', 'm_menu_org', 'p_privilege_0731', 'c_service_business_31'}
IDENTIFIER = re.compile(r'^[A-Za-z0-9_$]+$')
PLANS = {
    'bill_list': "SELECT t.bill_id,c.fee_name FROM bill t LEFT JOIN pay_fee_config c ON t.config_id=c.config_id AND c.community_id=t.community_id AND c.status_cd='0' WHERE t.community_id='audit-community' AND t.cur_bill='T' ORDER BY t.create_time DESC LIMIT 0,10",
    'bill_owe_list': "SELECT t.owe_id,t.bill_id,t.fee_id,t.bill_amount_owed,pf.payer_obj_id FROM bill_owe_fee t INNER JOIN bill b ON t.bill_id=b.bill_id AND b.community_id=t.community_id LEFT JOIN pay_fee pf ON t.fee_id=pf.fee_id AND t.community_id=pf.community_id AND pf.status_cd='0' LEFT JOIN pay_fee_config pfc ON pf.config_id=pfc.config_id AND pfc.community_id=t.community_id AND pfc.status_cd='0' LEFT JOIN t_dict td ON pf.fee_type_cd=td.status_cd AND td.table_name='pay_fee_config' AND td.table_columns='fee_type_cd' WHERE t.community_id='audit-community' AND t.owner_id='audit-owner' AND b.cur_bill='T' AND b.review_state='PUBLISHED' AND t.state IN ('1000','3000') AND t.bill_amount_owed>0 ORDER BY t.create_time DESC LIMIT 0,10",
    'payment_snapshots': "SELECT bof.owe_id,bof.bill_id,bof.fee_id,bof.bill_amount_owed,pf.payer_obj_id,pfc.fee_name FROM bill_owe_fee bof INNER JOIN bill b ON b.bill_id=bof.bill_id AND b.community_id=bof.community_id INNER JOIN pay_fee pf ON pf.fee_id=bof.fee_id AND pf.community_id=bof.community_id AND pf.status_cd='0' INNER JOIN pay_fee_config pfc ON pfc.config_id=pf.config_id AND pfc.community_id=pf.community_id AND pfc.status_cd='0' WHERE bof.community_id='audit-community' AND b.review_state='PUBLISHED' AND b.cur_bill='T' AND bof.state IN ('1000','3000') AND bof.bill_amount_owed>0 AND pf.end_time=bof.fee_end_time AND EXISTS (SELECT 1 FROM owner_app_user oau INNER JOIN building_owner bo ON bo.member_id=oau.member_id AND bo.community_id=oau.community_id AND bo.status_cd='0' WHERE oau.user_id='audit-user' AND oau.community_id=bof.community_id AND oau.state='12000' AND oau.status_cd='0' AND bo.owner_id=bof.owner_id) ORDER BY bof.owe_id",
    'snapshot_by_id': "SELECT owe_id FROM bill_owe_fee WHERE owe_id='audit-owe' AND community_id='audit-community' AND bill_id='audit-bill'",
    'room_fees': "SELECT fee_id FROM pay_fee WHERE payer_obj_id='audit-room' AND community_id='audit-community' AND status_cd='0'",
    'dictionary_lookup': "SELECT name FROM t_dict WHERE table_name='pay_fee_config' AND table_columns='fee_type_cd' AND status_cd='audit-type'",
}


def query(sql, schema=None):
    if sql.lstrip().split(None, 1)[0].upper() not in {'SELECT', 'SHOW', 'EXPLAIN'}:
        raise ValueError('Inventory accepts only SELECT, SHOW, or EXPLAIN')
    return [line.split('\t') for line in runtime_ops.mysql_query(sql, schema).splitlines() if line]


def identifier(name):
    if not IDENTIFIER.fullmatch(name):
        raise ValueError('Unexpected metadata identifier')
    return '`' + name + '`'


def source_references(table_names):
    command = ['rg', '--files', str(SOURCE_ROOT), '-g', '*.java', '-g', '*.xml',
               '-g', '*.yaml', '-g', '*.yml', '-g', '*.properties', '-g', '!**/target/**']
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', check=True)
    refs = defaultdict(list)
    for filename in result.stdout.splitlines():
        path = Path(filename)
        if '/src/main/' not in path.as_posix():
            continue
        words = set(re.findall(r'\b[a-z_][a-z_0-9]*\b', path.read_text(encoding='utf-8', errors='replace').lower()))
        for table in table_names & words:
            refs[table].append(path.relative_to(ROOT).as_posix())
    return refs


def dynamic_references(names):
    refs = {}
    for offset in range(0, len(names), 40):
        statements = []
        for name in names[offset:offset + 40]:
            identifier(name)
            pattern = '(^|[^a-z0-9_])' + name + '([^a-z0-9_]|$)'
            statements.append("SELECT '" + name + "',COUNT(*) FROM c_service_sql WHERE status_cd='0' "
                              "AND LOWER(CONCAT_WS(' ',`sql`,proc,java_script,template)) REGEXP '" + pattern + "'")
        for name, count in query(' UNION ALL '.join(statements), 'TT'):
            refs[name] = int(count)
    return refs


def classify(table, references, dynamic):
    name = table['table']
    if name.startswith(('act_', 'qrtz_')):
        return 'in_use', 'Framework-managed Activiti/Quartz table; absence of source literals is expected.'
    if references or dynamic:
        return 'in_use', 'Referenced by current main source or enabled SQL configuration; runtime traffic not established.'
    if name in HISTORICAL:
        return 'historical_retain', 'Historical/backup-style name; retain pending provenance and retention review.'
    if table['exact_rows'] == 0:
        return 'suspected_unused', 'Empty and no direct source or enabled SQL-template reference found; not deletion approval.'
    return 'needs_confirmation', 'Contains data but no direct source or enabled SQL-template reference found.'


def inventory():
    tables = []
    for schema, name, engine, estimated, data, index in query(
            "SELECT TABLE_SCHEMA,TABLE_NAME,ENGINE,COALESCE(TABLE_ROWS,0),DATA_LENGTH,INDEX_LENGTH "
            "FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('TT','hc_community') ORDER BY TABLE_SCHEMA,TABLE_NAME"):
        canonical = 'TT' if schema.lower() == 'tt' else schema
        tables.append({'schema': canonical, 'table': name, 'engine': engine,
                       'estimated_rows': int(estimated), 'data_bytes': int(data), 'index_bytes': int(index)})
    for schema in SCHEMAS:
        selected = [table for table in tables if table['schema'] == schema]
        sql = ' UNION ALL '.join("SELECT '" + table['table'] + "',COUNT(*) FROM " + identifier(table['table']) for table in selected)
        counts = {name: int(count) for name, count in query(sql, schema)}
        for table in selected:
            table['exact_rows'] = counts[table['table']]
    names = {table['table'] for table in tables}
    refs = source_references(names)
    dynamic = dynamic_references(sorted(names))
    indexes = defaultdict(list)
    for schema, name, index, unique, columns in query(
            "SELECT TABLE_SCHEMA,TABLE_NAME,INDEX_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
            "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA IN ('TT','hc_community') "
            "GROUP BY TABLE_SCHEMA,TABLE_NAME,INDEX_NAME,NON_UNIQUE ORDER BY TABLE_SCHEMA,TABLE_NAME,INDEX_NAME"):
        schema = 'TT' if schema.lower() == 'tt' else schema
        indexes[(schema, name)].append({'name': index, 'unique': unique == '0', 'columns': columns.split(',')})
    for table in tables:
        name = table['table']
        table['source_reference_files'] = sorted(refs[name])
        table['enabled_sql_config_reference_count'] = dynamic.get(name, 0)
        table['classification'], table['reason'] = classify(table, refs[name], dynamic.get(name, 0))
        table['indexes'] = indexes[(table['schema'], name)]
    foreign_keys = query(
        "SELECT DISTINCT TABLE_SCHEMA,TABLE_NAME,REFERENCED_TABLE_SCHEMA,REFERENCED_TABLE_NAME,CONSTRAINT_NAME "
        "FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA IN ('TT','hc_community') "
        "AND REFERENCED_TABLE_NAME IS NOT NULL ORDER BY TABLE_SCHEMA,TABLE_NAME,CONSTRAINT_NAME")
    objects = query(
        "SELECT 'TRIGGER',TRIGGER_SCHEMA,TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA IN ('TT','hc_community') "
        "UNION ALL SELECT 'VIEW',TABLE_SCHEMA,TABLE_NAME FROM information_schema.VIEWS WHERE TABLE_SCHEMA IN ('TT','hc_community') "
        "UNION ALL SELECT 'ROUTINE',ROUTINE_SCHEMA,ROUTINE_NAME FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA IN ('TT','hc_community')")
    integrity = query(
        "SELECT 'duplicate_bill_id',COUNT(*) FROM (SELECT bill_id FROM bill GROUP BY bill_id HAVING COUNT(*)>1) x "
        "UNION ALL SELECT 'duplicate_owe_id',COUNT(*) FROM (SELECT owe_id FROM bill_owe_fee GROUP BY owe_id HAVING COUNT(*)>1) y "
        "UNION ALL SELECT 'orphan_bill_owe',COUNT(*) FROM bill_owe_fee x LEFT JOIN bill b ON x.bill_id=b.bill_id "
        "AND x.community_id=b.community_id WHERE b.bill_id IS NULL", 'TT')
    explain_columns = ['id', 'select_type', 'table', 'partitions', 'access_type', 'possible_keys', 'key',
                       'key_length', 'ref', 'estimated_rows', 'filtered', 'extra']
    plans = {}
    for name, sql in PLANS.items():
        rows = query('EXPLAIN ' + sql, 'TT')
        if any(len(row) != len(explain_columns) for row in rows):
            raise RuntimeError('Unexpected EXPLAIN column layout')
        plans[name] = {'sql': sql, 'plan': [dict(zip(explain_columns, row)) for row in rows]}
    summary = {}
    for schema in SCHEMAS:
        selected = [table for table in tables if table['schema'] == schema]
        summary[schema] = {'tables': len(selected), 'exact_rows': sum(table['exact_rows'] for table in selected),
                           'empty_tables': sum(table['exact_rows'] == 0 for table in selected),
                           'allocated_bytes': sum(table['data_bytes'] + table['index_bytes'] for table in selected),
                           'classification_counts': dict(Counter(table['classification'] for table in selected))}
    return {'generated_at_utc': datetime.now(timezone.utc).isoformat(timespec='seconds'),
            'endpoint': runtime_ops.ENDPOINT, 'summary': summary, 'tables': tables,
            'foreign_keys': [dict(zip(['schema', 'table', 'referenced_schema', 'referenced_table', 'constraint'], row)) for row in foreign_keys],
            'other_database_objects': objects, 'billing_integrity_counts': {name: int(count) for name, count in integrity},
            'explain': plans,
            'limitations': [
                'in_use means a code/config/framework dependency exists, not proof of recent traffic.',
                'Exact counts were read without a cross-schema snapshot and can change during collection.',
                'InnoDB TABLE_ROWS and EXPLAIN rows are estimates; sizes are allocated data/index bytes.',
                'Source matching is token-based across src/main; comments and inactive modules may match.',
                'Enabled c_service_sql templates are checked; external jobs/tools and runtime-assembled table names are not exhaustively known.',
                'EXPLAIN uses synthetic identifiers and does not execute business queries; current tables are too small for realistic load estimates.',
                'No classification authorizes dropping tables or deleting records.'
            ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = inventory()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(args.output), 'summary': report['summary']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
