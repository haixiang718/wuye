"""Audit existing bill generation without sending messages or accessing a database.

Exit 0: checks passed; 1: reproduced business issues; 2: infrastructure/tool failure.
"""
import os
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


def compile_audit():
    output = ROOT / '.runtime/billing-audit'
    libraries = output / 'lib'
    libraries.mkdir(parents=True, exist_ok=True)
    jar = local.BACK / 'service-job/target/service-job.jar'
    if not jar.exists():
        raise RuntimeError('Package service-job before running the billing audit')
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                destination = libraries / Path(name).name
                destination.write_bytes(archive.read(name))
    modules = ['java110-bean', 'java110-utils', 'java110-interface', 'java110-core', 'java110-service', 'service-job']
    classpath = os.pathsep.join(map(str, [output] + [local.BACK / module / 'target/classes' for module in modules]
        + [path for path in libraries.glob('*.jar') if not path.name.startswith(('java110-', 'log4j-slf4j-impl-'))]))
    java = Path(local.java())
    javac = java.with_name('javac.exe' if os.name == 'nt' else 'javac')
    sources = [ROOT / 'scripts/billing/BillingWorkflowAudit.java',
        local.BACK / 'java110-bean/src/main/java/com/java110/dto/fee/BillDto.java',
        local.BACK / 'java110-bean/src/main/java/com/java110/dto/fee/BillSnapshotDto.java',
        local.BACK / 'java110-bean/src/main/java/com/java110/dto/owner/OwnerRoomRelDto.java',
        local.BACK / 'java110-utils/src/main/java/com/java110/utils/util/BillCycleWindow.java',
        local.BACK / 'java110-interface/src/main/java/com/java110/intf/fee/IFeeInnerServiceSMO.java',
        local.BACK / 'service-job/src/main/java/com/java110/job/task/fee/GenerateBillProTemplate.java',
        local.BACK / 'java110-core/src/main/java/com/java110/core/smo/impl/ComputeFeeSMOImpl.java']
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', classpath, '-d', str(output)] + list(map(str, sources)), check=True)
    logging = output / 'logback-audit.xml'
    logging.write_text('<configuration><root level="OFF"/></configuration>', encoding='utf-8')
    return java, classpath, logging, output


def main():
    java, classpath, logging, output = compile_audit()
    return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging), '-cp', classpath,
        'BillingWorkflowAudit', str(output / 'business-results.json')], cwd=ROOT).returncode


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('Billing audit infrastructure failed: ' + type(error).__name__, file=sys.stderr)
        raise SystemExit(2)
