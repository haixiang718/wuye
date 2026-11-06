"""Backed-up additive migration for the isolated local instance, not a remote deploy tool."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

import local

SOURCE = Path(__file__).with_name('migrations') / '2026-11-02-account-contract.sql'


class MigrationError(RuntimeError):
    pass


def validate_database(database):
    if database != 'TT' and not re.fullmatch(r'audit_schema_[a-f0-9]{12}', database):
        raise MigrationError('Only TT or a randomly named audit schema on 127.0.0.1:13306 is allowed')


def query(database, sql):
    validate_database(database)
    return local.mysql_run(sql, database=database, capture=True)


def statements(source=None):
    text = re.sub(r'^\s*--[^\n]*', '', (source or SOURCE).read_text(encoding='utf-8'), flags=re.M)
    return [part.strip() for part in text.split(';') if part.strip()]


def column_definition(definition):
    match = re.match(r'(varchar\(\d+\)|decimal\(\d+,\d+\)|timestamp)(?=\s|,|$)', definition, re.I)
    if not match:
        raise MigrationError('Unsupported migration column definition')
    default = re.search(r"\bDEFAULT\s+('([^']*)'|CURRENT_TIMESTAMP(?:\(\))?|NULL|[\d.]+)", definition, re.I)
    value = default.group(2) if default and default.group(2) is not None else (default.group(1) if default else 'NULL')
    return match.group(1).lower(), 'NOT NULL' not in definition.upper(), normalize_default(value), ''


def normalize_default(value):
    if value.upper() == 'NULL':
        return None
    if value.lower().startswith('current_timestamp'):
        return 'current_timestamp'
    return value


def inspect(database, table):
    raw = query(database, "SELECT column_name,column_type,is_nullable,IFNULL(column_default,'NULL'),extra FROM information_schema.columns "
                f"WHERE table_schema=DATABASE() AND table_name='{table}' ORDER BY ordinal_position")
    columns = {}
    for line in raw.splitlines():
        # mysql_run strips trailing whitespace; preserve an empty final EXTRA field.
        fields = line.split('\t')
        name, kind, nullable, default = fields[:4]
        extra = fields[4] if len(fields) > 4 else ''
        extra = extra.lower().replace('default_generated', '').strip()
        columns[name] = (kind.lower(), nullable == 'YES', normalize_default(default), extra)
    raw = query(database, "SELECT index_name,column_name FROM information_schema.statistics "
                f"WHERE table_schema=DATABASE() AND table_name='{table}' ORDER BY index_name,seq_in_index")
    indexes = {}
    for line in raw.splitlines():
        name, column = line.split('\t')
        indexes.setdefault(name, []).append(column)
    return columns, indexes


def check_column(table, column, actual, expected):
    if actual != expected:
        raise MigrationError(f'Incompatible {table}.{column}: {actual}, expected {expected}; no automatic conversion')


def plan(database='TT', source=None):
    validate_database(database)
    pending = []
    schemas = {}
    def schema(table):
        if table not in schemas:
            schemas[table] = inspect(database, table)
        return schemas[table]
    for statement in statements(source):
        create = re.match(r'CREATE TABLE IF NOT EXISTS `(\w+)`', statement, re.I)
        alter = re.match(r'ALTER TABLE `(\w+)` ADD COLUMN `(\w+)` (.+)', statement, re.I)
        add_index = re.match(r'ALTER TABLE `(\w+)` ADD INDEX `(\w+)` \((.+)\)', statement, re.I)
        if create:
            table = create.group(1)
            columns, indexes = schema(table)
            if not columns:
                pending.append(statement)
                continue
            engine = query(database, f"SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='{table}'")
            if engine.upper() != 'INNODB':
                raise MigrationError('Incompatible ' + table + ' engine: transaction support requires InnoDB')
            definitions = dict(re.findall(r'^\s*`(\w+)` (.+?)(?:,)?$', statement, re.M))
            for column, actual in columns.items():
                if column in definitions:
                    continue
                _, nullable, default, extra = actual
                if not nullable and default is None and not any(value in extra for value in ('auto_increment', 'generated')):
                    raise MigrationError(f'Incompatible {table}.{column}: extra mandatory column has no default')
            for column, definition in definitions.items():
                if column not in columns:
                    raise MigrationError(f'Incompatible {table}: missing {column}')
                check_column(table, column, columns[column], column_definition(definition))
            primary = re.search(r'PRIMARY KEY \(([^)]+)\)', statement).group(1)
            if indexes.get('PRIMARY') != re.findall(r'`(\w+)`', primary):
                raise MigrationError('Incompatible ' + table + ' PRIMARY key')
            for name, fields in re.findall(r'KEY `(\w+)` \(([^)]+)\)', statement):
                expected = re.findall(r'`(\w+)`', fields)
                if name in indexes and indexes[name] != expected:
                    raise MigrationError('Incompatible ' + table + ' index ' + name)
                if name not in indexes:
                    pending.append(f'ALTER TABLE `{table}` ADD INDEX `{name}` ({fields})')
        elif alter:
            table, column, definition = alter.groups()
            columns, _ = schema(table)
            if not columns:
                raise MigrationError('Required legacy table is absent: ' + table)
            if column in columns:
                check_column(table, column, columns[column], column_definition(definition))
            else:
                pending.append(statement)
        elif add_index:
            table, name, fields = add_index.groups()
            _, indexes = schema(table)
            expected = re.findall(r'`(\w+)`', fields)
            if name in indexes and indexes[name] != expected:
                raise MigrationError('Incompatible ' + table + ' index ' + name)
            if name not in indexes:
                pending.append(statement)
        else:
            raise MigrationError('Unsupported SQL in the reviewed migration file')
    return pending


def contract_snapshot(database, columns=None):
    if columns is None:
        columns = list(inspect(database, 'contract')[0])
    fields = ','.join('`' + column + '`' for column in columns)
    raw = query(database, 'SELECT ' + fields + ' FROM contract ORDER BY contract_id')
    return {'columns': columns, 'rowCount': int(query(database, 'SELECT count(*) FROM contract')),
            'sha256': hashlib.sha256(raw.encode('utf-8')).hexdigest()}


def create_backup(database, backup_root):
    validate_database(database)
    folder = backup_root / (database + '-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True)
    partial = folder / 'before.sql.partial'
    binary = Path(local.settings()['mysql_bin']) / 'mysqldump.exe'
    command = [str(binary), '--no-defaults', '--host=127.0.0.1', '--port=13306', '--user=root',
               '--default-character-set=utf8mb4', '--single-transaction', '--skip-lock-tables', '--hex-blob',
               '--no-tablespaces', '--set-gtid-purged=OFF', '--column-statistics=0', database]
    with partial.open('wb') as output, (folder / 'dump.stderr.log').open('wb') as errors:
        result = subprocess.run(command, stdout=output, stderr=errors)
    if result.returncode or not partial.stat().st_size:
        raise MigrationError('Database backup failed; no migration DDL has been executed')
    with partial.open('rb') as data:
        data.seek(max(0, partial.stat().st_size - 2048))
        if b'Dump completed' not in data.read():
            raise MigrationError('Database backup is incomplete; no migration DDL has been executed')
    complete = folder / 'before.sql'
    partial.rename(complete)
    digest = hashlib.sha256()
    with complete.open('rb') as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b''):
            digest.update(chunk)
    metadata = {'database': database, 'file': str(complete.resolve()), 'bytes': complete.stat().st_size,
                'sha256': digest.hexdigest(), 'endpoint': '127.0.0.1:13306'}
    (folder / 'backup.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    return metadata


@contextmanager
def migration_lock():
    local.RUN.mkdir(exist_ok=True)
    lock = local.RUN / 'account-contract-migration.lock'
    try:
        descriptor = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        raise MigrationError('Another local migration owns the lock; inspect it before retrying')
    try:
        with os.fdopen(descriptor, 'w') as output:
            output.write(str(os.getpid()))
        yield
    finally:
        lock.unlink()


def apply(database='TT', backup_root=None, source=None):
    validate_database(database)
    with migration_lock():
        pending = plan(database, source)  # Check all incompatible structures before any mutation or backup.
        before = contract_snapshot(database)
        backup = None
        if pending:
            backup = create_backup(database, backup_root or local.RUN / 'backups/business-schema')
            folder = Path(backup['file']).parent
            (folder / 'applied.sql').write_text(';\n'.join(pending) + ';\n', encoding='utf-8')
            for statement in pending:
                query(database, statement)
        if plan(database, source):
            raise MigrationError('Schema migration did not complete; rerun the additive migration')
        after = contract_snapshot(database, before['columns'])
        if before != after:
            raise MigrationError('Existing contract data changed; inspect the backup before proceeding')
        report = {'database': database, 'applied': len(pending), 'backup': backup,
                  'contractBefore': before, 'contractAfter': after, 'passed': True}
        if backup:
            (Path(backup['file']).parent / 'migration.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Back up TT and apply reviewed missing DDL; default only prints the plan')
    args = parser.parse_args()
    if args.apply:
        print(json.dumps(apply(), indent=2))
    else:
        pending = plan()
        print('-- Read-only plan for TT on 127.0.0.1:13306; use --apply to back up and execute.')
        print(';\n'.join(pending) + (';' if pending else '\n-- No changes required.'))


if __name__ == '__main__':
    try:
        main()
    except (MigrationError, subprocess.CalledProcessError, OSError) as error:
        raise SystemExit('Migration stopped: ' + str(error))
