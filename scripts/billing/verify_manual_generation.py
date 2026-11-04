"""Compile and run manual bill generation against memory service boundaries only."""
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify


def main():
    java, classpath, logging, output = verify.compile_audit()
    sources = [Path(__file__).with_name('ManualBillGenerationTest.java')]
    for relative in [
        'java110-interface/src/main/java/com/java110/intf/job/IBillGenerateInnerServiceSMO.java',
        'service-job/src/main/java/com/java110/job/smo/impl/BillGenerateInnerServiceSMOImpl.java',
    ]:
        source = verify.local.BACK / relative
        if source.exists():
            sources.append(source)
    javac = java.with_name('javac.exe' if verify.os.name == 'nt' else 'javac')
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                    '-cp', classpath, '-d', str(output)] + list(map(str, sources)), check=True)
    return subprocess.run([str(java), '-Dlogback.configurationFile=' + str(logging),
        '-cp', classpath, 'ManualBillGenerationTest'], cwd=verify.ROOT).returncode


if __name__ == '__main__':
    raise SystemExit(main())
