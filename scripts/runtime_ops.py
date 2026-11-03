"""Read-only local health checks, verified private backups and isolated restore drills.

This tool is pinned to this project's dedicated MySQL at 127.0.0.1:13306.
It has no restore-over-live operation and never starts jobs, payments or messages.
"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import local

DATABASES = ('TT', 'hc_community')
ENDPOINT = '127.0.0.1:13306'
BACKUPS = local.RUN / 'backups/operations'
PORTS = {'mysql': 13306, 'redis': 16379, 'zookeeper': 12181, 'kafka': 19092,
         'eureka': 8761, 'order': 8001, 'api': 8008, 'front': 8012,
         'job': 8013, 'business': 8015, 'management': 3000,
         'ownerH5': 8000, 'domainGateway': 8080}


class OpsError(RuntimeError):
    pass


def now():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def validate_source(database):
    if database not in DATABASES:
        raise OpsError('Only the project TT and hc_community schemas may be backed up')


def validate_restore_target(target):
    if not isinstance(target, str) or not re.fullmatch(r'ops_restore_[a-f0-9]{12}', target):
        raise OpsError('Restore drill requires a newly created ops_restore_<12 lowercase hex> schema')


def mysql_binary(name='mysql'):
    binary = Path(local.settings()['mysql_bin']) / (name + ('.exe' if os.name == 'nt' else ''))
    if not binary.is_file():
        raise OpsError('Project MySQL client executable is missing')
    return binary


@contextmanager
def client_command(name='mysql'):
    # The isolated development root account has an empty password; the application
    # password belongs to wuye_local and must not be used as root's password.
    private = local.RUN / 'ops-private'
    private.mkdir(parents=True, exist_ok=True)
    descriptor, filename = tempfile.mkstemp(prefix='client-', suffix='.cnf', dir=private)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8', newline='\n') as output:
            output.write('[client]\nhost=127.0.0.1\nport=13306\nuser=root\npassword=\nprotocol=TCP\ndefault-character-set=utf8mb4\n')
        if os.name != 'nt':
            os.chmod(filename, 0o600)
        env = {key: value for key, value in os.environ.items() if key not in ('MYSQL_PWD', 'MYSQL_TEST_LOGIN_FILE')}
        # MySQL 8.0 on this machine has no --no-login-paths option. Point its
        # supported login-file override at the null device instead.
        env['MYSQL_TEST_LOGIN_FILE'] = os.devnull
        yield [str(mysql_binary(name)), '--defaults-file=' + filename], env
    finally:
        Path(filename).unlink(missing_ok=True)


def mysql_query(sql, database=None):
    if database is not None:
        if database not in DATABASES:
            validate_restore_target(database)
    with client_command() as (command, env):
        command += ['--batch', '--skip-column-names']
        if database:
            command.append(database)
        result = subprocess.run(command, input=sql.encode('utf-8'), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, env=env, timeout=60)
    if result.returncode:
        raise OpsError('Local database query failed; no query text or credentials are included in this report')
    return result.stdout.decode('utf-8').strip()


def run_dump(database, target):
    validate_source(database)
    nontransactional = mysql_query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='"
                                   + database + "' AND TABLE_TYPE='BASE TABLE' AND ENGINE<>'InnoDB'")
    if nontransactional != '0':
        raise OpsError('Single-transaction backup requires InnoDB base tables; use a planned locked backup for other engines')
    with client_command('mysqldump') as (command, env):
        command += ['--single-transaction', '--skip-lock-tables', '--hex-blob',
                    '--no-tablespaces', '--set-gtid-purged=OFF', '--column-statistics=0',
                    '--skip-extended-insert', '--skip-add-locks', '--skip-disable-keys',
                    '--no-autocommit', '--routines', '--events', '--triggers', database]
        with target.open('wb') as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, env=env, timeout=1800)
    if result.returncode:
        raise OpsError('Database dump failed; the partial file is retained but is not a completed backup')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as data:
        for chunk in iter(lambda: data.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def dump_inventory(content):
    # One INSERT statement per row makes this inventory describe the transaction
    # inside the dump, without comparing it to a later, possibly changed live DB.
    tables = {name.decode('utf-8'): 0 for name in re.findall(rb'^CREATE TABLE `([^`]+)`', content, re.M)}
    for raw_name in re.findall(rb'^INSERT INTO `([^`]+)` VALUES ', content, re.M):
        name = raw_name.decode('utf-8')
        if name not in tables:
            raise OpsError('Dump inserts into a table absent from its schema inventory')
        tables[name] += 1
    unsupported = re.search(rb'(?im)^\s*(?:USE\s|CREATE\s+DATABASE\b|DELIMITER\b|\\[.!]|.*\bDEFINER\s*=|.*\bCREATE\b.*\b(?:VIEW|TRIGGER|PROCEDURE|FUNCTION|EVENT)\b)', content)
    return {'tableRows': tables, 'restoreDrillSupported': unsupported is None}


def write_json(path, value):
    temporary = path.with_name(path.name + '.partial')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def create_backup(database, backup_root=BACKUPS):
    validate_source(database)
    folder = Path(backup_root) / (database + '-' + datetime.now().strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    folder.mkdir(parents=True, exist_ok=False)
    partial = folder / 'database.sql.partial'
    run_dump(database, partial)
    content = partial.read_bytes()
    if not content or not re.search(rb'-- Dump completed on [^\r\n]+\s*\Z', content[-4096:]):
        raise OpsError('Dump is empty or has no completion footer; no completion manifest was written')
    inventory = dump_inventory(content)
    if not inventory['tableRows']:
        raise OpsError('Dump has no base tables; no completion manifest was written')
    complete = folder / 'database.sql'
    partial.replace(complete)
    metadata = {'version': 1, 'completed': True, 'createdAtUtc': now(), 'database': database,
                'endpoint': ENDPOINT, 'file': 'database.sql', 'bytes': complete.stat().st_size,
                'sha256': digest(complete), **inventory,
                'consistency': 'single transaction for transactional tables; schemas backed up separately'}
    write_json(folder / 'manifest.json', metadata)
    verify_backup(folder)
    return {'directory': str(folder.resolve()), 'database': database,
            'bytes': metadata['bytes'], 'sha256': metadata['sha256'],
            'tables': len(metadata['tableRows']), 'restoreDrillSupported': inventory['restoreDrillSupported']}


def verify_backup(folder):
    try:
        folder = Path(folder)
        metadata = json.loads((folder / 'manifest.json').read_text(encoding='utf-8'))
        if metadata.get('version') != 1 or metadata.get('completed') is not True or metadata.get('endpoint') != ENDPOINT:
            raise OpsError('Backup manifest is not a completed local operations backup')
        validate_source(metadata.get('database'))
        if metadata.get('file') != 'database.sql':
            raise OpsError('Unexpected backup filename')
        path = folder / 'database.sql'
        if path.is_symlink() or not path.is_file() or path.stat().st_size != metadata.get('bytes') or digest(path) != metadata.get('sha256'):
            raise OpsError('Backup content checksum or size does not match its manifest')
        content = path.read_bytes()
        if not re.search(rb'-- Dump completed on [^\r\n]+\s*\Z', content[-4096:]):
            raise OpsError('Backup completion footer is missing')
        inventory = dump_inventory(content)
        if not inventory['tableRows'] or inventory['tableRows'] != metadata.get('tableRows') or inventory['restoreDrillSupported'] != metadata.get('restoreDrillSupported'):
            raise OpsError('Backup inventory does not match its manifest')
        return metadata
    except OpsError:
        raise
    except (OSError, ValueError, TypeError):
        raise OpsError('Backup manifest or dump cannot be read') from None


def restore_drill(folder, target=None):
    target = target or 'ops_restore_' + uuid.uuid4().hex[:12]
    validate_restore_target(target)
    metadata = verify_backup(folder)
    if not metadata['restoreDrillSupported']:
        raise OpsError('Dump contains database directives or executable objects; this restricted drill will not import it')
    # CREATE without IF NOT EXISTS refuses an existing target. Drop only after
    # successful creation by this exact call; never destroy a pre-existing schema.
    created = False
    report = {'database': metadata['database'], 'target': target, 'startedAtUtc': now(),
              'passed': False, 'targetRemoved': False, 'sourceModified': False}
    try:
        mysql_query('CREATE DATABASE `' + target + '` CHARACTER SET utf8mb4')
        created = True
        with client_command() as (command, env), (Path(folder) / 'database.sql').open('rb') as data:
            result = subprocess.run(command + [target], stdin=data, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.PIPE, env=env, timeout=1800)
        if result.returncode:
            raise OpsError('Isolated restore import failed')
        actual_tables = mysql_query('SHOW TABLES', target).splitlines()
        expected = metadata['tableRows']
        if set(actual_tables) != set(expected):
            raise OpsError('Restored table inventory differs from the completed backup')
        rows = {}
        for table in actual_tables:
            if not re.fullmatch(r'[A-Za-z0-9_]+', table):
                raise OpsError('Unexpected table identifier in restricted drill')
            rows[table] = int(mysql_query('SELECT COUNT(*) FROM `' + table + '`', target))
        if rows != expected:
            raise OpsError('Restored row counts differ from the transaction captured in the dump')
        report.update(passed=True, verifiedTables=len(rows), verifiedRows=sum(rows.values()))
    finally:
        if created:
            validate_restore_target(target)
            mysql_query('DROP DATABASE `' + target + '`')
            report['targetRemoved'] = True
        report['finishedAtUtc'] = now()
        write_json(Path(folder) / ('restore-drill-' + target + '.json'), report)
    return report


def http_status(port, path='/'):
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=4)
    try:
        connection.request('GET', path)
        response = connection.getresponse()
        response.read(1024)
        return response.status
    finally:
        connection.close()


def health_report():
    checks = []
    for name, port in PORTS.items():
        checks.append({'name': name, 'kind': 'tcp', 'port': port, 'passed': local.listening(port)})
    for name, port, path in [('managementHtml', 3000, '/admin.html'), ('ownerHtml', 8000, '/'),
                              ('domainGate', 8080, '/'), ('serviceRegistry', 8761, '/eureka/apps')]:
        try:
            status = http_status(port, path)
            checks.append({'name': name, 'kind': 'http', 'passed': status == 200, 'status': status})
        except Exception:
            checks.append({'name': name, 'kind': 'http', 'passed': False})
    try:
        passed = mysql_query('SELECT 1') == '1'
    except Exception:
        passed = False
    checks.append({'name': 'databaseQuery', 'kind': 'sql', 'passed': passed})
    try:
        with socket.create_connection(('127.0.0.1', 16379), timeout=3) as client:
            client.settimeout(3)
            client.sendall(b'*1\r\n$4\r\nPING\r\n')
            passed = client.recv(64).startswith(b'+PONG\r\n')
    except Exception:
        passed = False
    checks.append({'name': 'redisPing', 'kind': 'redis', 'passed': passed})
    try:
        import server_tunnel
        state = json.loads(server_tunnel.STATE.read_text(encoding='utf-8'))
        passed = server_tunnel.alive(state)
    except Exception:
        passed = False
    checks.append({'name': 'tunnelSupervisor', 'kind': 'local-process', 'passed': passed})
    try:
        passed = '-Djava110.local.disableScheduledTasks=true' in local.processes().get('job', {}).get('command', [])
    except Exception:
        passed = False
    checks.append({'name': 'recordedJobScheduleDisabled', 'kind': 'recorded-config', 'passed': passed})
    return {'checkedAtUtc': now(), 'healthy': all(item['passed'] for item in checks), 'checks': checks,
            'scope': 'Local runtime readiness only. TCP listeners do not prove business, public tunnel, OAuth, notification or payment acceptance.',
            'actionsTriggered': []}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    commands.add_parser('health')
    commands.add_parser('backup')
    for name in ('verify', 'restore-drill'):
        command = commands.add_parser(name)
        command.add_argument('directory', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.action == 'health':
            report = health_report()
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0 if report['healthy'] else 1
        if args.action == 'backup':
            report = {'createdAtUtc': now(), 'backups': [create_backup(database) for database in DATABASES]}
        elif args.action == 'verify':
            metadata = verify_backup(args.directory)
            report = {'verified': True, 'database': metadata['database'], 'tables': len(metadata['tableRows']), 'sha256': metadata['sha256']}
        else:
            report = restore_drill(args.directory)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except Exception:
        # Database/subprocess exceptions may contain credentials or business data.
        # Diagnostics deliberately expose only an action name and safe failure.
        print(json.dumps({'action': args.action, 'passed': False,
                          'error': 'Operation failed; no credentials or database contents are printed. Incomplete backups have no completion manifest.'}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
