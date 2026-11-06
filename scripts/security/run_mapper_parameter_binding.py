"""Compile/run only the isolated MyBatis SQL-binding regression; never opens a DB."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import local


def main():
    jar = Path.home() / '.m2/repository/org/mybatis/mybatis/3.4.1/mybatis-3.4.1.jar'
    if not jar.is_file():
        raise RuntimeError('Project MyBatis 3.4.1 dependency is not available locally')
    java = Path(local.java())
    with tempfile.TemporaryDirectory(prefix='wuye-mapper-binding-') as directory:
        output = Path(directory)
        subprocess.run([str(java.with_name('javac.exe' if os.name == 'nt' else 'javac')),
                        '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                        '-cp', str(jar), '-d', str(output),
                        str(Path(__file__).with_name('MapperParameterBindingTest.java'))], check=True)
        classpath = os.pathsep.join([str(output), str(jar), str(local.BACK / 'java110-bean/target/classes')])
        return subprocess.run([str(java), '-cp', classpath,
                               'MapperParameterBindingTest',
                               str(local.BACK / 'java110-db/src/main/resources/mapper')], cwd=ROOT).returncode


if __name__ == '__main__':
    raise SystemExit(main())
