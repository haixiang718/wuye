"""Offline execution of the real legacy refund validators; synthetic services only."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local


def main():
    java, classpath, logging, output = compile_audit()
    repository = Path.home() / '.m2/repository'
    classpath = os.pathsep.join([classpath, str(local.BACK / 'service-api/target/classes'),
        str(repository / 'junit/junit/4.13.1/junit-4.13.1.jar'),
        str(repository / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources = [Path(__file__).with_name('OnlinePaymentRefundGuardTest.java'),
        local.BACK / 'java110-interface/src/main/java/com/java110/intf/fee/IFeePaymentOrderInnerServiceSMO.java']
    sources.extend(local.BACK / 'service-api/src/main/java/com/java110/api/listener/returnPayFee' / name
                   for name in ['SaveReturnPayFeeListener.java', 'UpdateReturnPayFeeListener.java'])
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', classpath, '-d', str(output)] + list(map(str, sources)), check=True)
    return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', classpath,
                           'org.junit.runner.JUnitCore', 'OnlinePaymentRefundGuardTest']).returncode


if __name__ == '__main__':
    raise SystemExit(main())
