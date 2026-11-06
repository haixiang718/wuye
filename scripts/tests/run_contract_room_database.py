"""Real production MyBatis mapper against a backed-up, migrated temporary schema."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'billing'))
from verify import compile_audit,local
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import migrate_meter_import as migration

def main():
    java,cp,logging,output=compile_audit()
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([cp,str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output),str(Path(__file__).with_name('ContractRoomDatabaseTest.java'))],check=True)
    database='audit_schema_'+uuid.uuid4().hex[:12]
    local.mysql_run('CREATE DATABASE `'+database+'` CHARACTER SET utf8mb4')
    try:
        local.mysql_run('CREATE TABLE contract LIKE TT.contract',database=database)
        with tempfile.TemporaryDirectory(dir=local.RUN) as folder:
            migration.apply(database,backup_root=Path(folder))
        mapper=local.BACK/'java110-db/src/main/resources/mapper/store/ContractRoomServiceDaoImplMapper.xml'
        return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-Dschema.audit='+database,'-Dschema.mapper='+str(mapper),'-cp',cp,'org.junit.runner.JUnitCore','ContractRoomDatabaseTest']).returncode
    finally:
        if not re.fullmatch(r'audit_schema_[a-f0-9]{12}',database):raise RuntimeError('Unsafe cleanup')
        local.mysql_run('DROP DATABASE `'+database+'`')

if __name__=='__main__':raise SystemExit(main())
