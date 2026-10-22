"""Run the manual notification JUnit suite after packaging service-api (no real sends)."""
from pathlib import Path
import os
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


def main():
    project = local.BACK
    output = local.RUN / 'bill-notify-tests'
    libs = output / 'lib'
    libs.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(project / 'service-api/target/service-api.jar') as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libs / Path(name).name).write_bytes(archive.read(name))
    repository = Path.home() / '.m2/repository'
    test_jars = [repository / 'junit/junit/4.13.1/junit-4.13.1.jar',
                 repository / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar']
    if not all(path.exists() for path in test_jars):
        raise RuntimeError('Resolve JUnit 4.13.1 and hamcrest-core 1.3 with Maven first')
    # Prefer the application's Logback binding; the legacy Log4j SLF4J binding
    # conflicts with log4j-to-slf4j when a test exercises the real HTTP client.
    runtime_jars = [path for path in libs.glob('*.jar') if not path.name.startswith('log4j-slf4j-impl-')]
    cp = os.pathsep.join(map(str, [output, project / 'service-api/target/classes'] + runtime_jars + test_jars))
    java = Path(local.java())
    sources = [project / ('service-api/src/' + name) for name in [
        'main/java/com/java110/api/bmo/fee/impl/BillNotificationService.java',
        'main/java/com/java110/api/listener/fee/PushBillListener.java',
        'test/java/com/java110/api/bmo/fee/impl/BillNotificationServiceTest.java']]
    subprocess.run([str(java.with_name('javac.exe' if os.name == 'nt' else 'javac')),
                    '-encoding', 'UTF-8', '-source', '7', '-target', '7',
                    '-cp', cp, '-d', str(output)] + list(map(str, sources)), check=True)
    logging = output / 'logback-test.xml'
    logging.write_text('<configuration><root level="OFF"/></configuration>', encoding='utf-8')
    subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', cp,
                    'org.junit.runner.JUnitCore', 'com.java110.api.bmo.fee.impl.BillNotificationServiceTest'], check=True)


if __name__ == '__main__':
    main()
