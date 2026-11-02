"""Run real callback/posting code using process-local network and transaction substitutes."""
import os
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local


def main():
    java, cp, logging, output = compile_audit()
    cp = os.pathsep.join([cp, str(local.BACK / 'service-front/target/classes'), str(local.BACK / 'service-fee/target/classes')])
    base = local.BACK / 'service-front/src/main/java/com/java110/front/smo/payment/adapt/wechatPay'
    placement = local.BACK / 'service-front/src/main/java/com/java110/front/smo/payment/impl'
    sources = [Path(__file__).with_name('PaymentCallbackAudit.java'),
        base / 'WechatFeeCallbackSupport.java', base / 'WechatPayNotifyAdapt.java', base / 'WechatOweFeeToNotifyAdapt.java',
        placement / 'PublishedBillToPaySMO.java', placement / 'ToPaySMOImpl.java', placement / 'ToPayOweFeeSMOImpl.java', placement / 'ToQrPayOweFeeSMOImpl.java',
        local.BACK / 'java110-interface/src/main/java/com/java110/intf/fee/IFeePaymentOrderInnerServiceSMO.java',
        local.BACK / 'service-fee/src/main/java/com/java110/fee/dao/PaymentOrderRepository.java',
        local.BACK / 'service-fee/src/main/java/com/java110/fee/smo/impl/FeePaymentOrderInnerServiceSMOImpl.java']
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', cp, '-d', str(output)] + list(map(str, sources)), check=True)
    report = local.RUN / 'payment-callback-simulation.json'
    subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', cp, 'PaymentCallbackAudit', str(report)], check=True)


if __name__ == '__main__':
    main()
