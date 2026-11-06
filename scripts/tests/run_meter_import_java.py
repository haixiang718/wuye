"""Production meter-import parser and error-path tests; downstream calls are substituted."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit,local

def main():
    java,cp,logging,output=compile_audit()
    output=local.RUN/'meter-import-tests'
    output.mkdir(exist_ok=True)
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([str(output),cp,str(local.BACK/'service-front/target/classes'),str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources=[local.BACK/'service-front/src/main/java/com/java110/front/smo/assetImport/impl/ImportMeterWaterFeeSMOImpl.java',Path(__file__).with_name('meter-import-stubs')/'com/java110/core/factory/GenerateCodeFactory.java',Path(__file__).with_name('MeterImportRegressionTest.java')]
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output),*map(str,sources)],check=True)
    return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),*(['-Dmeter.user.file='+sys.argv[1]] if len(sys.argv)>1 else []),'-cp',cp,'org.junit.runner.JUnitCore','MeterImportRegressionTest']).returncode

if __name__=='__main__':raise SystemExit(main())
