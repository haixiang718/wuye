"""Real MyBatis review/query checks in a random disposable database on loopback 13306."""
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit,local
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import migrate_bill_review


def main():
    java,cp,logging,output=compile_audit()
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([cp,str(local.BACK/'service-business/target/classes'),str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources=[str(Path(__file__).with_name('BillReviewDatabaseTest.java')),str(local.BACK/'service-fee/src/main/java/com/java110/fee/bmo/impl/PayOweFeeImpl.java')]
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+sources,check=True)
    database='audit_schema_'+uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4')
    try:
        for table in ['bill','bill_owe_fee','pay_fee','pay_fee_config','t_dict','contract']:
            local.mysql_run('CREATE TABLE `'+table+'` LIKE TT.`'+table+'`',database=database)
        migrate_bill_review.apply(database)
        mapper=local.BACK/'java110-db/src/main/resources/mapper/fee/FeeServiceDaoImplMapper.xml'
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Dschema.audit='+database,'-Dschema.mapper='+str(mapper),'-cp',cp,'org.junit.runner.JUnitCore','BillReviewDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}',database):raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE `'+database+'`')


if __name__=='__main__':raise SystemExit(main())
