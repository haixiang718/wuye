"""Exercise production MyBatis SQL against a migrated random schema on isolated MySQL."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import migrate_business_schema as migration


def main():
    java, classpath, logging, output = compile_audit()
    repository = Path.home() / '.m2/repository'
    cp = os.pathsep.join([classpath, str(repository / 'junit/junit/4.13.1/junit-4.13.1.jar'),
                         str(repository / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    source = Path(__file__).with_name('AccountContractDatabaseTest.java')
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(output), str(source)], check=True)
    database = 'audit_schema_' + uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `' + database + '` CHARACTER SET utf8mb4')
    try:
        for table in ['contract', 'contract_type', 't_dict']:
            local.mysql_run('CREATE TABLE `' + table + '` LIKE TT.`' + table + '`', database=database)
        with tempfile.TemporaryDirectory(dir=local.RUN) as backup:
            migration.apply(database, backup_root=Path(backup))
        mapper = local.BACK / 'java110-db/src/main/resources/mapper'
        return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging),
                               '-Dschema.audit=' + database, '-Dschema.mapper.root=' + str(mapper),
                               '-cp', cp, 'org.junit.runner.JUnitCore', 'AccountContractDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}', database):
            raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE `' + database + '`')


if __name__ == '__main__':
    raise SystemExit(main())
