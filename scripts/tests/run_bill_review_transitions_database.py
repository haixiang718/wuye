"""Real mapper transitions/audit in a disposable database; dedicated compiler output."""
import re
import subprocess
from pathlib import Path
import uuid
from run_bill_review_transitions import compile_tests,local,ROOT
import migrate_bill_review

def main():
    java,cp,logging,output=compile_tests()
    sources=[Path(__file__).with_name('BillReviewDatabaseTest.java'),
        local.BACK/'java110-core/src/main/java/com/java110/core/smo/impl/ComputeFeeSMOImpl.java',
        local.BACK/'java110-bean/src/main/java/com/java110/dto/fee/BillOweFeeDto.java',
        local.BACK/'service-fee/src/main/java/com/java110/fee/bmo/impl/PayOweFeeImpl.java']
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
    database='audit_schema_'+uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4')
    try:
        for table in ['bill','bill_owe_fee','pay_fee','pay_fee_config','t_dict','contract']:
            local.mysql_run('CREATE TABLE `'+table+'` LIKE TT.`'+table+'`',database=database)
        local.mysql_run('''CREATE TABLE s_store(store_id varchar(30),user_id varchar(30),b_id varchar(30),name varchar(100),address varchar(100),tel varchar(30),store_type_cd varchar(30),nearby_landmarks varchar(100),map_x varchar(30),map_y varchar(30),create_time timestamp DEFAULT CURRENT_TIMESTAMP,status_cd varchar(2),state varchar(10));
            CREATE TABLE s_store_user(store_user_id varchar(30),store_id varchar(30),user_id varchar(30),status_cd varchar(2),rel_cd varchar(30));''',database=database)
        migrate_bill_review.apply(database)
        audit=ROOT/'scripts/migrations/2026-11-06-bill-review-audit.sql'
        if audit.exists():local.mysql_run(audit.read_text(encoding='utf-8'),database=database)
        mapper=local.BACK/'java110-db/src/main/resources/mapper/fee/FeeServiceDaoImplMapper.xml'
        store_mapper=local.BACK/'java110-db/src/main/resources/mapper/store/StoreServiceDaoImplMapper.xml'
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Dschema.audit='+database,'-Dschema.mapper='+str(mapper),'-Dschema.store.mapper='+str(store_mapper),'-cp',cp,'org.junit.runner.JUnitCore','BillReviewDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}',database):raise RuntimeError('Unsafe cleanup target')
        local.mysql_run('DROP DATABASE `'+database+'`')

if __name__=='__main__':raise SystemExit(main())
