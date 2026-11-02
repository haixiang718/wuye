"""Same-fixture notification simulation; no real WeChat token, identity or transport."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit, local


def main():
    java,cp,logging,output=compile_audit()
    cp=os.pathsep.join([cp,str(local.BACK/'service-api/target/classes')])
    source=Path(__file__).with_name('LinkedNotificationAudit.java')
    service=local.BACK/'service-api/src/main/java/com/java110/api/bmo/fee/impl/BillNotificationService.java'
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output),str(source),str(service)],check=True)
    folder=local.RUN/'billing-flow-simulation'
    subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,'LinkedNotificationAudit',str(folder/'fixture.json'),str(folder/'notification.json')],check=True)


if __name__=='__main__':main()
