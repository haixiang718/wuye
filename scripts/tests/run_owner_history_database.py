"""Read TT schemas only; test production owner-history SQL in a disposable database."""
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit,local

def main():
    java,cp,logging,output=compile_audit()
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([cp,str(local.BACK/'service-fee/target/classes'),str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources=[Path(__file__).with_name('OwnerPaymentHistoryDatabaseTest.java'),local.BACK/'service-fee/src/main/java/com/java110/fee/dao/OwnerPaymentHistoryRepository.java']
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
    database='audit_schema_'+uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4')
    try:
        for table in ['pay_fee_detail','pay_fee','pay_fee_attrs','pay_fee_config','owner_app_user','building_owner','building_owner_room_rel','building_room','building_unit','f_floor','owner_car']:
            local.mysql_run('CREATE TABLE `'+table+'` LIKE TT.`'+table+'`',database=database)
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Dschema.audit='+database,
            '-Dschema.mapper.root='+str(local.BACK/'java110-db/src/main/resources/mapper'),'-cp',cp,'org.junit.runner.JUnitCore','OwnerPaymentHistoryDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}',database):raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE `'+database+'`')
if __name__=='__main__':raise SystemExit(main())
