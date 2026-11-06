"""Exercise actual transactional fee triggers and queue leases in an isolated schema."""
import os, re, subprocess, sys, uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit,local

def main():
    java,cp,logging,output=compile_audit()
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([cp,str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources=[local.BACK/'service-job/src/main/java/com/java110/job/billing/AutomaticBillQueue.java',
        local.BACK/'service-job/src/main/java/com/java110/job/billing/AutomaticBillWorker.java',
        local.BACK/'service-job/src/main/java/com/java110/job/smo/impl/BillGenerateInnerServiceSMOImpl.java',
        local.BACK/'java110-interface/src/main/java/com/java110/intf/job/IBillGenerateInnerServiceSMO.java',
        Path(__file__).with_name('AutomaticBillQueueTest.java'),Path(__file__).with_name('AutomaticBillWorkerTest.java')]
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
    db='audit_queue_'+uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `'+db+'` CHARACTER SET utf8mb4')
    try:
        local.mysql_run('CREATE TABLE pay_fee (fee_id varchar(30) PRIMARY KEY,community_id varchar(30),config_id varchar(30),state varchar(20),status_cd varchar(2),start_time datetime,end_time datetime) ENGINE=InnoDB',database=db)
        local.mysql_run(database=db,source=local.ROOT/'scripts/migrations/2026-11-06-automatic-bill-review.sql')
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Dqueue.audit='+db,'-cp',cp,'org.junit.runner.JUnitCore','AutomaticBillQueueTest','AutomaticBillWorkerTest']).returncode
    finally:
        if not re.fullmatch(r'audit_queue_[a-f0-9]{12}',db):raise RuntimeError('Unsafe schema')
        local.mysql_run('DROP DATABASE `'+db+'`')

if __name__=='__main__':raise SystemExit(main())
