"""Staff and community authorization for manual bill generation; no HTTP or database."""
from pathlib import Path
import os,subprocess,sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit,local

java,cp,logging,base=compile_audit()
output=base/'manual-access';output.mkdir(exist_ok=True)
repo=Path.home()/'.m2/repository'
cp=os.pathsep.join([str(output),cp,str(local.BACK/'service-api/target/classes'),str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
sources=[local.BACK/'java110-interface/src/main/java/com/java110/intf/job/IBillGenerateInnerServiceSMO.java',
         local.BACK/'service-api/src/main/java/com/java110/api/bmo/fee/impl/BillGenerationService.java',
         local.BACK/'service-api/src/main/java/com/java110/api/listener/fee/GenerateBillListener.java',
         local.ROOT/'scripts/tests/ManualBillAccessTest.java']
subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
raise SystemExit(subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,'org.junit.runner.JUnitCore','ManualBillAccessTest']).returncode)
