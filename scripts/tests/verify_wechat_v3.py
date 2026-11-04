"""Compile and run offline V3 tests, without reading payment runtime settings."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
BACK = ROOT / 'wuye-b/MicroCommunity-v0.8'
SDK = ROOT / '.tools/wechat-v3-sdk'
JAVA = next((ROOT / '.tools').glob('*jdk8*win_x64/bin/java.exe'))
M2 = Path.home() / '.m2/repository'


def dependency_jars():
    jars = list((SDK / 'lib').glob('*.jar'))
    if not jars:
        raise RuntimeError('Resolve wechatpay-java:0.2.17 runtime dependencies into .tools/wechat-v3-sdk/lib')
    jars += [M2 / 'junit/junit/4.11/junit-4.11.jar',
             M2 / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar',
             M2 / 'com/alibaba/fastjson/1.2.69/fastjson-1.2.69.jar']
    return jars


def main():
    jars = dependency_jars()
    with tempfile.TemporaryDirectory(prefix='wuye-wechat-v3-') as temporary:
        cp = os.pathsep.join(map(str, [temporary] + jars))
        provider = BACK / 'service-front/src/main/java/com/java110/front/smo/payment/v3'
        sources = [provider / (name + '.java') for name in
                   ('WechatPayV3Settings', 'WechatPayV3Provider', 'WechatPayV3Client')]
        sources += list((BACK / 'service-front/src/test/java/com/java110/front/smo/payment/v3').glob('*.java'))
        subprocess.run([str(JAVA.with_name('javac.exe')), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                        '-cp', cp, '-d', temporary] + list(map(str, sources)), check=True)
        return subprocess.run([str(JAVA), '-cp', cp, 'org.junit.runner.JUnitCore',
                               'com.java110.front.smo.payment.v3.WechatPayV3ClientTest']).returncode


if __name__ == '__main__':
    raise SystemExit(main())
