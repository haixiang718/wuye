"""Offline production orchestration audit; no runtime payment credentials or network."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/billing'))
sys.path.insert(0,str(ROOT/'scripts/tests'))
from verify import compile_audit,local
from verify_wechat_v3 import dependency_jars


def main():
    java,cp,logging,_=compile_audit()
    base=local.BACK/'service-front/src/main/java/com/java110/front'
    with tempfile.TemporaryDirectory(prefix='wuye-v3-flow-') as output:
        cp=os.pathsep.join([output,cp,str(local.BACK/'service-front/target/classes')]+list(map(str,dependency_jars())))
        sources=list((base/'smo/payment/v3').glob('*.java'))
        sources.extend([Path(__file__).with_name('WechatPayV3FlowAudit.java'),
            local.BACK/'java110-interface/src/main/java/com/java110/intf/fee/IFeePaymentOrderInnerServiceSMO.java',
            base/'smo/payment/impl/PublishedBillToPaySMO.java',base/'smo/payment/impl/ToPaySMOImpl.java',
            base/'controller/app/WechatPayV3Controller.java',base/'configuration/PaymentRecoveryConfiguration.java'])
        subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-source','8','-target','8',
                        '-cp',cp,'-d',output]+list(map(str,sources)),check=True)
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,'WechatPayV3FlowAudit']).returncode


if __name__=='__main__':
    raise SystemExit(main())
