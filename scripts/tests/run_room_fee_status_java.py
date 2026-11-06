"""Check room payment indicators using deterministic read-only service doubles."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local

def main():
    java, cp, logging, output = compile_audit()
    repo = Path.home() / '.m2/repository'
    cp = os.pathsep.join([cp, str(local.BACK / 'service-api/target/classes'),
        str(repo / 'junit/junit/4.13.1/junit-4.13.1.jar'),
        str(repo / 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources = [local.BACK / p for p in [
        'java110-bean/src/main/java/com/java110/vo/api/ApiRoomDataVo.java',
        'java110-bean/src/main/java/com/java110/dto/fee/BillOweFeeDto.java',
        'service-api/src/main/java/com/java110/api/bmo/fee/impl/RoomFeeStatusService.java',
        'service-api/src/main/java/com/java110/api/listener/fee/ListRoomsWhereFeeSetListener.java']]
    sources.append(Path(__file__).with_name('RoomFeeStatusTest.java'))
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', cp,
        '-d', str(output)] + list(map(str, sources)), check=True)
    return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging),
        '-cp', cp, 'org.junit.runner.JUnitCore', 'RoomFeeStatusTest']).returncode

if __name__ == '__main__':
    raise SystemExit(main())
