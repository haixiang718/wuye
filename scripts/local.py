"""Isolated Windows development services. Run: python scripts/local.py --help."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BACK = ROOT / 'wuye-b/MicroCommunity-v0.8'
WEB = ROOT / 'wuye-f/MicroCommunityWeb-v0.8'
RUN = ROOT / '.runtime'
TOOLS = ROOT / '.tools'
SERVICES = {'eureka': 8761, 'order': 8001, 'user': 8002, 'acct': 8003,
            'common': 8005, 'store': 8006, 'report': 8007, 'api': 8008,
            'community': 8009, 'fee': 8010, 'front': 8012, 'job': 8013, 'goods': 8014}
PROFILES = ('property', 'full')


def selected_services(names=None, profile='property'):
    if profile not in PROFILES:
        raise ValueError('Unknown runtime profile: ' + profile)
    if not names or 'all' in names:
        selected = [name for name in SERVICES if profile == 'full' or name != 'goods'] + ['web']
    else:
        selected = list(dict.fromkeys(names))
    # Existing scheduled group-buy jobs call goods; do not leave a missing dependency.
    if 'job' in selected and os.environ.get('WUYE_ENABLE_SCHEDULED_TASKS') == '1':
        if 'goods' in selected:
            selected.remove('goods')
        selected.insert(selected.index('job'), 'goods')
    return selected


def trim(profile='property'):
    excluded = [name for name in SERVICES if name not in selected_services(['all'], profile)]
    job = processes().get('job', {})
    if ('goods' in excluded and
            '-Djava110.local.disableScheduledTasks=false' in job.get('command', []) and
            listening(SERVICES['job'])):
        excluded.remove('goods')
        print('Keeping goods: running job has scheduled tasks enabled; use restart all to apply current settings.', flush=True)
    if excluded:
        stop(excluded)
    print('Runtime profile: ' + profile + '; excluded services: ' + (', '.join(excluded) or 'none'), flush=True)


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2), encoding='utf-8')


def settings():
    path = RUN / 'local-settings.json'
    if not path.exists():
        mysql = os.environ.get('WUYE_MYSQL_BIN', r'D:\MySQL\MySQL Server 8.0\bin')
        save(path, {'mysql_bin': mysql, 'db_password': secrets.token_hex(16)})
    return json.loads(path.read_text(encoding='utf-8'))


def java():
    candidates = list(TOOLS.glob('*jdk8*win_x64/bin/java.exe'))
    if not candidates:
        raise RuntimeError('Project Java 8 missing; see docs/LOCAL-DEVELOPMENT.md')
    return str(candidates[0])


def listening(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=1):
            return True
    except OSError:
        return False


def wait_port(port, process, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'Process exited: {process.returncode}; see .runtime/logs')
        if listening(port):
            return
        time.sleep(1)
    raise RuntimeError(f'Port {port} did not become ready; see .runtime/logs')


def processes():
    path = RUN / 'processes.json'
    return json.loads(path.read_text()) if path.exists() else {}


def spawn(name, command, port, cwd=ROOT, env=None):
    recorded = processes()
    if listening(port):
        if name in recorded:
            print(f'{name}: port {port} already listening', flush=True)
            return
        raise RuntimeError(f'Port {port} in use by an untracked process; stop it or change configuration')
    (RUN / 'logs').mkdir(parents=True, exist_ok=True)
    with (RUN / f'logs/{name}.log').open('ab') as log:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=log,
                                   creationflags=subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS)
    recorded[name] = {'pid': process.pid, 'port': port, 'command': command}
    save(RUN / 'processes.json', recorded)
    wait_port(port, process)
    print(f'{name}: 127.0.0.1:{port} (PID {process.pid})', flush=True)


def properties(path, values):
    # Forward slashes keep Windows paths safe in java.util.Properties.
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text('\n'.join(f'{key}={str(value).replace(chr(92), "/")}' for key, value in values.items()) + '\n', encoding='utf-8')


def configure(profile='property'):
    if profile not in PROFILES:
        raise ValueError('Unknown runtime profile: ' + profile)
    compact = profile == 'property'
    cfg = settings()
    (RUN / 'config').mkdir(parents=True, exist_ok=True)
    # The bundled XML sets child API/Druid loggers to DEBUG, overriding parent
    # package levels and recording request bodies/SQL parameters with secrets.
    logback = RUN / 'config/logback-local.xml'
    logback.write_text('''<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level %logger{60} - %msg%n</pattern></encoder>
  </appender>
  <logger name="com.java110" level="INFO"/>
  <root level="WARN"><appender-ref ref="CONSOLE"/></root>
</configuration>
''', encoding='utf-8')
    for name, port in SERVICES.items():
        values = {
            'server.address': '127.0.0.1', 'server.port': port,
            'eureka.client.serviceUrl.defaultZone': 'http://127.0.0.1:8761/eureka/',
            'eureka.instance.hostname': 'localhost', 'eureka.instance.ip-address': '127.0.0.1',
            'eureka.instance.preferIpAddress': 'true',
            'eureka.instance.instanceId': f'127.0.0.1:{port}',
            'spring.redis.host': '127.0.0.1', 'spring.redis.port': 16379,
            'spring.redis.password': '', 'jedis.pool.host': '127.0.0.1',
            'jedis.pool.port': 16379, 'jedis.pool.password': '',
            'jedis.pool.config.maxTotal': 20, 'jedis.pool.config.maxIdle': 2 if compact else 5,
            'kafka.consumer.servers': '127.0.0.1:19092',
            'kafka.producer.servers': '127.0.0.1:19092', 'kafka.consumer.concurrency': 1,
            'kafka.consumer.zookeeper.connect': '127.0.0.1:12181',
            'kafka.producer.zookeeper.connect': '127.0.0.1:12181',
            'java110.code.zookeeper.url': '127.0.0.1', 'java110.code.zookeeper.port': 12181,
            'server.tomcat.max-threads': 30, 'server.tomcat.min-spare-threads': 2,
            'logging.file': str(RUN / f'logs/{name}-spring.log'),
            'logging.config': logback.as_uri(),
            'logging.level.root': 'WARN', 'logging.level.com.java110': 'INFO',
            'logging.level.org.springframework.web.client': 'INFO',
            'spring.datasource.url': jdbc('TT'), 'spring.datasource.username': 'wuye_local',
            'spring.datasource.password': cfg['db_password'],
            'java110.runtime.api-docs-enabled': 'false' if compact else 'true',
        }
        if compact:
            values['spring.jmx.enabled'] = 'false'
        yaml = BACK / f'service-{name}/src/main/resources/dataSource.yml'
        if yaml.exists():
            text = yaml.read_text(encoding='utf-8')
            # Preserve the original sharding rules, replace only connection/pool settings.
            text = re.sub(r'jdbc:mysql://[^\s]+/(hc_community|TT)\?[^\s]+', lambda m: jdbc(m[1]), text)
            text = re.sub(r'(?m)^(\s*)username:.*$', r'\1username: wuye_local', text)
            text = re.sub(r'(?m)^(\s*)password:.*$', lambda m: m[1] + 'password: ' + cfg['db_password'], text)
            idle = 0 if compact else 1
            text = re.sub(r'(?m)^(\s*)(minIdle|initialSize):.*$', lambda m: m[1] + m[2] + ': ' + str(idle), text)
            if compact:
                text = re.sub(r'(?m)^(\s*)poolPreparedStatements:.*$', r'\1poolPreparedStatements: false', text)
            output = RUN / f'config/{name}-datasource.yml'
            output.write_text(text, encoding='utf-8')
            values['java110.datasource.config'] = output.as_uri()
        properties(RUN / f'config/{name}.properties', values)
    properties(RUN / 'config/zookeeper.properties', {
        'tickTime': 2000, 'dataDir': RUN / 'zookeeper', 'clientPort': 12181,
        'clientPortAddress': '127.0.0.1', 'admin.enableServer': 'false', 'maxClientCnxns': 100})
    properties(RUN / 'config/kafka.properties', {
        'broker.id': 0, 'listeners': 'PLAINTEXT://127.0.0.1:19092',
        'advertised.listeners': 'PLAINTEXT://127.0.0.1:19092',
        'log.dirs': RUN / 'kafka', 'zookeeper.connect': '127.0.0.1:12181',
        'offsets.topic.replication.factor': 1, 'transaction.state.log.replication.factor': 1,
        'transaction.state.log.min.isr': 1, 'num.partitions': 1,
        'num.network.threads': 2, 'num.io.threads': 2, 'log.retention.hours': 24,
        'group.initial.rebalance.delay.ms': 0})
    if os.name == 'nt':
        # Kafka 2.8's cleaner renames mapped index files, which Windows rejects.
        # Preserve offsets/data and keep the local broker running; Linux deployments
        # should retain compaction. Monitor local log size when running long term.
        with (RUN / 'config/kafka.properties').open('a', encoding='utf-8') as output:
            output.write('log.cleaner.enable=false\n')
    print('Local configuration generated (.runtime/config)', flush=True)


def jdbc(database):
    return f'jdbc:mysql://127.0.0.1:13306/{database}?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'


def infra(profile='property'):
    configure(profile)
    mysql = Path(settings()['mysql_bin']) / 'mysqld.exe'
    data = RUN / 'mysql/data'
    if not (data / 'mysql').exists():
        data.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(mysql), '--no-defaults', '--initialize-insecure', f'--basedir={mysql.parent.parent}', f'--datadir={data}', '--console'], check=True)
    spawn('mysql', [str(mysql), '--no-defaults', f'--basedir={mysql.parent.parent}',
                   f'--datadir={data}', '--port=13306', '--bind-address=127.0.0.1',
                   '--mysqlx=OFF', '--sql-mode=NO_ENGINE_SUBSTITUTION',
                   '--character-set-server=utf8mb4', '--collation-server=utf8mb4_general_ci',
                   '--innodb-buffer-pool-size=256M', '--console'], 13306)
    spawn('redis', [str(TOOLS / 'redis/tools/redis-server.exe'), '--bind', '127.0.0.1',
                   '--port', '16379', '--dir', str(RUN), '--appendonly', 'no'], 16379)
    libs = str(TOOLS / 'kafka_2.13-2.8.2/libs/*')
    spawn('zookeeper', [java(), '-Xms32m', '-Xmx128m', '-cp', libs,
                       'org.apache.zookeeper.server.quorum.QuorumPeerMain', str(RUN / 'config/zookeeper.properties')], 12181)
    spawn('kafka', [java(), '-Xms64m', '-Xmx256m', '-cp', libs,
                   'kafka.Kafka', str(RUN / 'config/kafka.properties')], 19092)


def mysql_run(sql=None, database=None, source=None, capture=False):
    command = [str(Path(settings()['mysql_bin']) / 'mysql.exe'), '--no-defaults',
               '--host=127.0.0.1', '--port=13306', '--user=root', '--default-character-set=utf8mb4']
    if database:
        command.append(database)
    if source:
        with source.open('rb') as data:
            subprocess.run(command, stdin=data, check=True, stdout=subprocess.DEVNULL)
    else:
        if capture:
            command.extend(['--batch', '--skip-column-names'])
        result = subprocess.run(command, input=sql.encode('utf-8'), check=True, stdout=subprocess.PIPE if capture else None)
        return result.stdout.decode('utf-8').strip() if capture else None


def import_db():
    if (RUN / 'database-imported.json').exists():
        print('Database already imported; preserving local business data.')
        local_database_settings()
        return
    pwd = settings()['db_password']
    mysql_run(f"CREATE DATABASE IF NOT EXISTS hc_community CHARACTER SET utf8; CREATE DATABASE IF NOT EXISTS TT CHARACTER SET utf8; CREATE USER IF NOT EXISTS 'wuye_local'@'127.0.0.1' IDENTIFIED BY '{pwd}'; GRANT ALL ON hc_community.* TO 'wuye_local'@'127.0.0.1'; GRANT ALL ON TT.* TO 'wuye_local'@'127.0.0.1';")
    for database, filename in [('hc_community', 'hc_community0.6_商业版.sql'), ('TT', 'tt0.6_商业版.sql')]:
        source = BACK / 'docs/db' / filename
        print(f'Importing {database}...', flush=True)
        mysql_run(database=database, source=source)
    save(RUN / 'database-imported.json', {'completed': time.strftime('%Y-%m-%d %H:%M:%S')})
    local_database_settings()
    print('Both databases imported.', flush=True)


def local_database_settings():
    # Only the dedicated database on 13306 is reachable through mysql_run.
    mysql_run("UPDATE TT.c_mapping SET value='http://127.0.0.1:3000' WHERE `key`='SYS_API_URL'; "
              "UPDATE TT.c_mapping SET value='http://127.0.0.1:8080' WHERE `key`='OWNER_WECHAT_URL'; "
              "UPDATE TT.c_mapping SET value='http://127.0.0.1:8080/#/pages/roomOweFee/roomOweFee?roomId=' WHERE domain='WECHAT' AND `key`='OWE_FEE_PAGE';")
    migrate()


def migrate():
    changes = json.loads((ROOT / 'scripts/local-schema.json').read_text(encoding='utf-8'))
    for change in changes:
        database, table, column = change['database'], change['table'], change['column']
        found = mysql_run(f"SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='{database}' AND table_name='{table}' AND column_name='{column}'", capture=True)
        if found == '0':
            mysql_run(f"ALTER TABLE `{database}`.`{table}` ADD COLUMN `{column}` {change['definition']}")
            print(f'Local schema: added {database}.{table}.{column}', flush=True)
    routes = json.loads((ROOT / 'scripts/local-routes.json').read_text(encoding='utf-8'))
    for route in routes:
        code, service_id = route['code'], route['id']
        instance = route.get('instance', 'T')
        mysql_run(f"INSERT INTO TT.c_service(service_id,service_code,name,business_type_cd,messageQueueName,is_instance,url,method) SELECT '{service_id}','{code}','Local compatibility route','API','','{instance}','{route['url']}','{route['method']}' WHERE NOT EXISTS (SELECT 1 FROM TT.c_service WHERE service_code='{code}');")
        mysql_run(f"UPDATE TT.c_service SET business_type_cd='API',messageQueueName='' WHERE service_id='{service_id}';")
        for app in route['apps']:
            mysql_run(f"INSERT INTO TT.c_route(app_id,service_id,order_type_cd,invoke_limit_times,invoke_model,status_cd) SELECT '{app}',s.service_id,'Q',1000,'S','0' FROM TT.c_service s WHERE s.service_code='{code}' AND NOT EXISTS(SELECT 1 FROM TT.c_route r WHERE r.app_id='{app}' AND r.service_id=s.service_id AND r.status_cd='0');")
    query = json.loads((ROOT / 'scripts/local-payments-query.json').read_text(encoding='utf-8'))
    # Hex literals avoid SQL string-escaping differences in the legacy XML/JSON templates.
    literal = lambda value: "CONVERT(0x" + value.encode('utf-8').hex() + " USING utf8mb4)"
    mysql_run("INSERT INTO TT.c_service_sql(service_code,name,params,query_model,`sql`,template) SELECT "
              + ','.join([literal(query['code']), literal(query['name']), "'1'", "'1'", literal(json.dumps(query['queries'], ensure_ascii=False)), literal(json.dumps(query['template']))])
              + " WHERE NOT EXISTS(SELECT 1 FROM TT.c_service_sql WHERE service_code='api.getListPayFee');")


def build():
    running = [name for name, port in SERVICES.items() if listening(port)]
    if running:
        raise RuntimeError('Stop Java services before building (Windows locks running jars): ' + ', '.join(running))
    env = os.environ.copy()
    env['JAVA_HOME'] = str(Path(java()).parents[1])
    subprocess.run([str(TOOLS / 'apache-maven-3.9.9/bin/mvn.cmd'), '-B', '-ntp', '-DskipTests', '-Dmaven.javadoc.skip=true', 'package'], cwd=BACK, env=env, check=True)


def start(names, profile='property'):
    configure(profile)
    for name in names:
        if name == 'web':
            env = os.environ.copy()
            env['PORT'] = '3000'
            spawn('web', [shutil.which('node'), 'bin/www'], 3000, cwd=WEB, env=env)
            continue
        jar = BACK / f'service-{name}/target/service-{name}.jar'
        if not jar.exists():
            raise RuntimeError(f'{jar} missing: run build first')
        env = os.environ.copy()
        # Boot 2.0 profile files can override an unprofiled additional config file.
        # JSON has higher precedence and keeps passwords out of command-line arguments.
        config = RUN / f'config/{name}.properties'
        env['SPRING_APPLICATION_JSON'] = json.dumps(dict(line.split('=', 1) for line in config.read_text(encoding='utf-8').splitlines() if line))
        spawn(name, [java(), '-Xms48m', '-Xmx256m', '-XX:ParallelGCThreads=2',
                     '-Dlogback.configurationFile=' + str(RUN / 'config/logback-local.xml'),
                     '-Djava110.local.disableScheduledTasks=' + ('false' if os.environ.get('WUYE_ENABLE_SCHEDULED_TASKS') == '1' else 'true'), '-Dfile.encoding=UTF-8',
                     '-jar', str(jar), '--spring.profiles.active=dev',
                     '--spring.config.additional-location=' + config.as_uri()], SERVICES[name], env=env)


def stop(names=None):
    # Match recorded PID AND original command, so recycled PIDs cannot kill unrelated software.
    for name, value in reversed(list(processes().items())):
        if names and name not in names:
            continue
        command = "$p=Get-CimInstance Win32_Process -Filter 'ProcessId=" + str(value['pid']) + "'; if ($p) { $p | Select-Object CommandLine | ConvertTo-Json -Compress }"
        result = subprocess.run(['powershell', '-NoProfile', '-Command', command], capture_output=True, text=True)
        if not result.stdout.strip():
            continue
        actual = json.loads(result.stdout)['CommandLine']
        token = value['command'][0] if name in ('mysql', 'redis') else (value['command'][1] if name == 'web' else next((item for item in value['command'] if item.endswith('.jar')), value['command'][-1]))
        if token.lower() not in actual.lower():
            print(f'{name}: PID command changed; skipped')
            continue
        subprocess.run(['taskkill', '/PID', str(value['pid']), '/T', '/F'], capture_output=True)
        print(f'{name}: stopped')
    save(RUN / 'processes.json', {key: value for key, value in processes().items() if names and key not in names})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['configure', 'infra', 'import-db', 'migrate', 'build', 'start', 'restart', 'stop', 'status', 'trim'])
    parser.add_argument('services', nargs='*', choices=[*SERVICES, 'web', 'all'])
    parser.add_argument('--profile', choices=PROFILES, default='property',
                        help='property omits unused goods and disables API docs; full restores the previous runtime settings')
    args = parser.parse_args()
    if args.action == 'start':
        if not args.services or 'all' in args.services:
            trim(args.profile)
        start(selected_services(args.services, args.profile), profile=args.profile)
    elif args.action == 'restart':
        selected = selected_services(args.services, args.profile)
        # Preserve Redis notification receipts/sessions and all middleware/data.
        stop(list(SERVICES) + ['web'] if not args.services or 'all' in args.services else selected)
        start(selected, profile=args.profile)
    elif args.action == 'stop':
        stop(None if not args.services or 'all' in args.services else args.services)
    elif args.action == 'status':
        for name, value in processes().items():
            print(f'{name:12} {value["port"]:5} {"LISTENING" if listening(value["port"]) else "STOPPED"}')
    elif args.action in ('configure', 'infra', 'trim'):
        globals()[args.action](args.profile)
    else:
        globals()[args.action.replace('-', '_')]()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
