"""Deterministic combined publication/task tests, isolated from real Redis, DB and WeChat."""
from pathlib import Path
import os,subprocess,sys,zipfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import local
output=local.RUN/'bill-publication-workflow-tests';libs=output/'lib';libs.mkdir(parents=True,exist_ok=True);application=output/'application'
with zipfile.ZipFile(local.BACK/'service-api/target/service-api.jar') as archive:
    for name in archive.namelist():
        if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
            (libs/Path(name).name).write_bytes(archive.read(name))
        elif name.startswith('BOOT-INF/classes/') and not name.endswith('/'):
            target=application/Path(name).relative_to('BOOT-INF/classes');target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
repo=Path.home()/'.m2/repository'
cp=os.pathsep.join(map(str,[output,application]+[p for p in libs.glob('*.jar') if not p.name.startswith('log4j-slf4j-impl-')]+[repo/'junit/junit/4.13.1/junit-4.13.1.jar',repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar']))
sources=[local.BACK/'java110-bean/src/main/java/com/java110/dto/fee/BillOweFeeDto.java']
sources += [local.BACK/('service-api/src/main/java/com/java110/api/'+name) for name in ['bmo/fee/impl/BillPublicationService.java','bmo/fee/impl/BillNotificationTaskStore.java','bmo/fee/impl/BillNotificationService.java','bmo/fee/impl/BillReviewService.java','listener/fee/ReviewBillListener.java','listener/fee/BillNotificationStatusListener.java']]
java=Path(local.java())
subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-source','7','-target','7','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-source','8','-target','8','-cp',cp,'-d',str(output),str(Path(__file__).with_name('BillPublicationWorkflowTest.java'))],check=True)
logging=output/'logback.xml';logging.write_text('<configuration><root level="OFF"/></configuration>',encoding='utf-8')
raise SystemExit(subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,'org.junit.runner.JUnitCore','BillPublicationWorkflowTest']).returncode)
