"""Run production legacy callback diagnostics entirely in-process; never invokes live callbacks."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local


def main():
    java, cp, logging, output = compile_audit()
    cp = os.pathsep.join([cp, str(local.BACK / 'service-front/target/classes')])
    base = local.BACK / 'service-front/src/main/java/com/java110/front/smo/payment/adapt/wechatPay'
    sources = [Path(__file__).with_name('PaymentCallbackAudit.java'), base / 'WechatPayNotifyAdapt.java', base / 'WechatOweFeeToNotifyAdapt.java']
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', cp, '-d', str(output)] + list(map(str, sources)), check=True)
    report = local.RUN / 'payment-callback-simulation.json'
    subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', cp, 'PaymentCallbackAudit', str(report)], check=True)


if __name__ == '__main__':
    main()
