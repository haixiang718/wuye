"""Read-only expected community IDs from the isolated loopback MySQL databases."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


def main():
    # local.mysql_run fixes its destination to 127.0.0.1:13306. Never accept SQL,
    # database credentials, host overrides, or mutation arguments from callers.
    if sys.argv[1:] == ['--account-table-status']:
        count = local.mysql_run("""
            SELECT COUNT(*) FROM information_schema.tables
            WHERE LOWER(table_schema)='tt' AND LOWER(table_name)='account'
        """, capture=True)
        if count not in ('0', '1'):
            raise RuntimeError('Unexpected account table reference result')
        print(json.dumps({'database': 'TT', 'table': 'account', 'accountTablePresent': count == '1'}))
        return
    if sys.argv[1:]:
        raise RuntimeError('Unsupported reference query')
    sql = """
        SELECT t.community_id
        FROM hc_community.s_community t
        JOIN hc_community.city_area ca ON t.city_code=ca.area_code
        WHERE t.status_cd='0' AND ca.status_cd='0'
        UNION ALL
        SELECT t.community_id
        FROM TT.s_community t
        JOIN TT.city_area ca ON t.city_code=ca.area_code
        WHERE t.status_cd='0' AND ca.status_cd='0'
    """
    expected = sorted(set(local.mysql_run(sql, capture=True).split()))
    if not expected or not all(value.isdecimal() for value in expected):
        raise RuntimeError('Local community fixture query is empty or invalid')
    print(json.dumps(expected))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Read-only local community reference query failed.', file=sys.stderr)
        sys.exit(1)
