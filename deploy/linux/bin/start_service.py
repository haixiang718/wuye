#!/usr/bin/env python3
"""Guarded launcher for reviewed non-payment deployment; no installation side effects."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

PORTS = {'eureka': 8761, 'order': 8001, 'api': 8008, 'front': 8012, 'job': 8013, 'business': 8015}
DOMAINS = ('user', 'community', 'fee', 'acct', 'store', 'common', 'report')


def safe_config(service, config):
    if service not in PORTS:
        raise ValueError('Unsupported service')
    result = dict(config)
    result.update({'server.address': '127.0.0.1', 'server.port': PORTS[service],
                   'java110.payment.enabled': 'false', 'java110.local.disableScheduledTasks': 'true',
                   'java110.runtime.merged-enabled': 'true', 'java110.runtime.api-docs-enabled': 'false',
                   'spring.jmx.enabled': 'false'})
    for domain in DOMAINS:
        prefix = domain + '-service.ribbon.'
        result[prefix + 'NIWSServerListClassName'] = 'com.netflix.loadbalancer.ConfigurationBasedServerList'
        result[prefix + 'listOfServers'] = '127.0.0.1:8015'
    return result


def read_config(directory, service):
    if service not in PORTS:
        raise ValueError('Unsupported service')
    if not (directory / 'READY_FOR_NONPAYMENT').is_file():
        raise ValueError('Reviewed config marker READY_FOR_NONPAYMENT is missing')
    source = (directory / (service + '.json')).read_text(encoding='utf-8')
    if re.search(r'REPLACE_IN_PRODUCTION|PRODUCTION_HOST_REQUIRED|CHANGEME|\$\{', source):
        raise ValueError('Configuration still contains a placeholder')
    config = json.loads(source)
    if not isinstance(config, dict):
        raise ValueError('Service config must be an object')
    for suffix in ({'order': ['order'], 'job': ['job'],
                    'business': ['business', 'business-community', 'business-report']}.get(service, [])):
        file = directory / (suffix + '-datasource.yml')
        text = file.read_text(encoding='utf-8')
        if re.search(r'REPLACE_IN_PRODUCTION|PRODUCTION_HOST_REQUIRED|CHANGEME', text):
            raise ValueError('Datasource still contains a placeholder')
    return safe_config(service, config)


def public_status(service, config):
    return {'service': service, 'listen': '127.0.0.1:' + str(config['server.port']),
            'paymentEnabled': False, 'scheduledTasksEnabled': False}


def java_command(service, java, release, jar):
    if service not in PORTS:
        raise ValueError('Unsupported service')
    heap = '768m' if service == 'business' else '256m'
    logging = release / 'linux/config/logback.xml'
    # Boot 2.0 ignores the Logback JVM property during its logging reinitialization.
    # Its command-line logging.config must override bundled/profile/JSON values.
    return [java, '-Xms48m', '-Xmx' + heap, '-XX:ParallelGCThreads=2', '-Dfile.encoding=UTF-8',
            '-Djava110.local.disableScheduledTasks=true', '-Dlogback.configurationFile=' + str(logging),
            '-jar', str(jar), '--spring.profiles.active=prod', '--java110.payment.enabled=false',
            '--logging.config=' + logging.as_uri()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('service', choices=PORTS)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    directory = Path(os.environ.get('WUYE_CONFIG_DIR', '/etc/wuye')).resolve()
    config = read_config(directory, args.service)
    release = Path(__file__).resolve().parents[2]
    jar = release / 'java' / ('service-' + args.service + '.jar')
    if not jar.is_file():
        raise ValueError('Missing release JAR')
    if args.check:
        print(json.dumps(public_status(args.service, config)))
        return
    java = os.environ.get('JAVA_BIN', '/usr/bin/java')
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
    if not re.search(r'version "1\.8\.', version.stderr + version.stdout):
        raise ValueError('A separately installed compatible Java 8 runtime is required')
    env = dict(os.environ)
    env['SPRING_APPLICATION_JSON'] = json.dumps(config)
    command = java_command(args.service, java, release, jar)
    os.execve(java, command, env)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError):
        # Exceptions can include a bad operator value; keep credentials out of journald.
        raise SystemExit('Non-payment launch refused. Check reviewed config, artifact and compatible Java runtime locally.')
