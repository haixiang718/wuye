"""Exercise the legacy notification task with fake transport and cache only."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local


def main():
    java, cp, logging, output = compile_audit()
    repo = Path.home() / '.m2/repository'
    cp = os.pathsep.join([cp, str(repo / 'junit/junit/4.13.1/junit-4.13.1.jar'),
        str(repo / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources = [local.BACK / name for name in [
        'java110-bean/src/main/java/com/java110/dto/fee/BillOweFeeDto.java',
        'service-job/src/main/java/com/java110/job/task/wechat/PublicWeChatPushMessageTemplate.java',
        'service-job/src/test/java/com/java110/job/task/wechat/PublicWeChatPushMessageTemplateTest.java']]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', cp,
        '-d', str(output)] + list(map(str, sources)), check=True)
    return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', cp,
        'org.junit.runner.JUnitCore', 'com.java110.job.task.wechat.PublicWeChatPushMessageTemplateTest']).returncode


if __name__ == '__main__':
    raise SystemExit(main())
