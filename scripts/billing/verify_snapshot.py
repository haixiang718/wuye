"""Real Spring/MyBatis/MySQL billing regression; writes only random audit tables on 13306."""
from pathlib import Path
import os
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from verify import compile_audit
import local


def main():
    java, classpath, logging, output = compile_audit()
    repository = Path.home() / '.m2/repository'
    test_jars = [repository / 'junit/junit/4.13.1/junit-4.13.1.jar',
                 repository / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar']
    cp = os.pathsep.join([classpath] + list(map(str, test_jars)))
    sources = [Path(__file__).with_name('BillSnapshotDatabaseTest.java')]
    sources += [local.BACK / path for path in [
        'service-fee/src/main/java/com/java110/fee/dao/IFeeServiceDao.java',
        'service-fee/src/main/java/com/java110/fee/dao/impl/FeeServiceDaoImpl.java',
        'service-fee/src/main/java/com/java110/fee/smo/impl/BillSnapshotService.java']]
    subprocess.run([str(java.with_name('javac.exe' if os.name == 'nt' else 'javac')), '-encoding', 'UTF-8',
                    '-source', '8', '-target', '8', '-cp', cp, '-d', str(output)] + list(map(str, sources)), check=True)
    mapper = local.BACK / 'java110-db/src/main/resources/mapper/fee/FeeServiceDaoImplMapper.xml'
    return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging),
        '-Dbilling.mapper=' + str(mapper), '-cp', cp, 'org.junit.runner.JUnitCore', 'BillSnapshotDatabaseTest']).returncode


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        raise SystemExit('Billing database test command failed (exit ' + str(error.returncode) + ')')
