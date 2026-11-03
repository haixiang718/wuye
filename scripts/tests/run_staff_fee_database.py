"""Exercise the staff-fee query in an empty, disposable loopback database."""
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
    cp=os.pathsep.join([cp,str(local.BACK/'java110-db/target/classes'),str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output),str(Path(__file__).with_name('StaffFeeDetailsDatabaseTest.java'))],check=True)
    database='audit_schema_'+uuid.uuid4().hex[:12]
    empty='audit_schema_'+uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4')
    try:
        local.mysql_run('CREATE DATABASE `'+empty+'` CHARACTER SET utf8mb4')
        for table in ['pay_fee_detail','c_business','c_orders','pay_fee','pay_fee_config','u_user','s_store_user','t_dict','building_room','building_unit','f_floor','owner_car']:
            local.mysql_run('CREATE TABLE `'+table+'` LIKE TT.`'+table+'`',database=database)
        artifact=Path(__file__).resolve().parents[1]/'staff-fee-query.json'
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Dschema.audit='+database,
            '-Dschema.empty='+empty,'-Dquery.artifact='+str(artifact),'-cp',cp,'org.junit.runner.JUnitCore','StaffFeeDetailsDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}',database):raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE `'+database+'`')
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}',empty):raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE IF EXISTS `'+empty+'`')


if __name__=='__main__':raise SystemExit(main())
