"""Exercise merged MVC and Kafka registrations without a database, broker or WeChat."""
from pathlib import Path
import os
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


def main():
    output = local.RUN / 'business-tests'
    libs = output / 'lib'
    libs.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(local.BACK / 'service-business/target/service-business.jar') as archive:
        # Preserve the original file storage/size settings without duplicating credentials.
        expected = (local.BACK / 'service-common/src/main/resources/java110.properties').read_bytes()
        assert archive.read('BOOT-INF/classes/java110.properties') == expected, 'Common storage configuration changed'
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(archive.read(name))
    repository = Path.home() / '.m2/repository'
    jars = [p for p in libs.glob('*.jar') if not p.name.startswith('log4j-slf4j-impl-')]
    jars += [repository / 'junit/junit/4.13.1/junit-4.13.1.jar',
             repository / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar']
    cp = os.pathsep.join(map(str, [output, local.BACK / 'service-business/target/classes'] + jars))
    java = Path(local.java())
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(output), str(ROOT / 'scripts/tests/BusinessContainerTest.java')], check=True)
    logging = output / 'logback-test.xml'
    logging.write_text('<configuration><root level="OFF"/></configuration>', encoding='utf-8')
    subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', cp,
                    'org.junit.runner.JUnitCore', 'com.java110.business.BusinessContainerTest'], check=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit('Business container tests failed (exit ' + str(error.returncode) + ')')
