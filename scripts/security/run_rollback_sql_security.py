"""Isolated rollback SQL test; owns and removes only a new ops_restore schema."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local
import runtime_ops


def main():
    java = Path(local.java())
    schema = 'ops_restore_' + uuid.uuid4().hex[:12]
    runtime_ops.validate_restore_target(schema)
    created = False
    with tempfile.TemporaryDirectory(prefix='wuye-rollback-sql-') as directory:
        output = Path(directory)
        libraries = output / 'lib'
        libraries.mkdir()
        with zipfile.ZipFile(local.BACK / 'service-job/target/service-job.jar') as archive:
            for name in archive.namelist():
                filename = Path(name).name
                if name.startswith('BOOT-INF/lib/') and name.endswith('.jar') and not filename.startswith(('java110-', 'log4j-slf4j-impl-')):
                    (libraries / filename).write_bytes(archive.read(name))
        modules = ['java110-bean', 'java110-utils', 'java110-interface', 'java110-core', 'java110-db', 'java110-service', 'service-order']
        cp = os.pathsep.join(map(str, [output] + [local.BACK / module / 'target/classes' for module in modules] + list(libraries.glob('*.jar'))))
        sources = [Path(__file__).with_name('RollbackSqlSecurityTest.java'),
                   local.BACK / 'java110-db/src/main/java/com/java110/db/Java110MybatisInterceptor.java',
                   local.BACK / 'service-order/src/main/java/com/java110/order/smo/impl/OIdServiceSMOImpl.java']
        helper = local.BACK / 'java110-utils/src/main/java/com/java110/utils/util/RollbackSqlLiteral.java'
        if helper.exists():
            sources.append(helper)
        subprocess.run([str(java.with_name('javac.exe' if os.name == 'nt' else 'javac')), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                        '-cp', cp, '-d', str(output)] + list(map(str, sources)), check=True)
        logging = output / 'logback.xml'
        logging.write_text('<configuration><root level="OFF"/></configuration>', encoding='utf-8')
        try:
            runtime_ops.mysql_query('CREATE DATABASE `' + schema + '` CHARACTER SET utf8mb4')
            created = True
            return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-Dschema.audit=' + schema,
                                   '-Dfee.config.mapper=' + str(local.BACK / 'java110-db/src/main/resources/mapper/fee/FeeConfigServiceDaoImplMapper.xml'),
                                   '-cp', cp, 'RollbackSqlSecurityTest'], cwd=ROOT).returncode
        finally:
            if created:
                runtime_ops.validate_restore_target(schema)
                runtime_ops.mysql_query('DROP DATABASE `' + schema + '`')


if __name__ == '__main__':
    raise SystemExit(main())
