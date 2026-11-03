"""Compile source and run public-front ownership regressions with synthetic internal responses."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local

def main():
    java, cp, logging, output = compile_audit()
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([cp,str(local.BACK/'service-front/target/classes'),str(local.BACK/'service-api/target/classes'),
        str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    base=local.BACK/'service-front/src/main/java/com/java110/front/smo/api/impl'
    sources=[Path(__file__).with_name('OwnerDataAccessTest.java'),Path(__file__).with_name('OwnerBindingApprovalTest.java'),
        Path(__file__).with_name('OwnerVehicleSqlTest.java'),Path(__file__).with_name('OwnerRegistrationSmsTest.java'),
        local.BACK/'service-api/src/main/java/com/java110/api/listener/owner/OwnerRegisterListener.java',
        local.BACK/'service-api/src/main/java/com/java110/api/bmo/owner/impl/OwnerBMOImpl.java',
        local.BACK/'service-api/src/main/java/com/java110/api/listener/owner/AppUserBindingOwnerListener.java',base/'ApiSMOImpl.java']
    if (base/'OwnerDataAccessService.java').exists():sources.append(base/'OwnerDataAccessService.java')
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
    mapper=local.BACK/'java110-db/src/main/resources/mapper/user/OwnerCarServiceDaoImplMapper.xml'
    return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Downer.car.mapper='+str(mapper),'-cp',cp,
        'org.junit.runner.JUnitCore','OwnerDataAccessTest','OwnerBindingApprovalTest','OwnerVehicleSqlTest','OwnerRegistrationSmsTest']).returncode

if __name__=='__main__':
    try:raise SystemExit(main())
    except subprocess.CalledProcessError as error:raise SystemExit('Owner access test compile failed (exit '+str(error.returncode)+')')
