"""Isolated review transition tests; never compiles into shared billing-audit output."""
import os
from pathlib import Path
import subprocess
import sys
import zipfile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
import local

def compile_tests():
    output=local.RUN/'bill-review-transition-tests';output.mkdir(parents=True,exist_ok=True)
    libraries=output/'lib';libraries.mkdir(exist_ok=True)
    with zipfile.ZipFile(local.BACK/'service-job/target/service-job.jar') as archive:
        for name in archive.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                (libraries/Path(name).name).write_bytes(archive.read(name))
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join(map(str,[output]+list(local.BACK.glob('*/target/classes'))+
        [p for p in libraries.glob('*.jar') if not p.name.startswith(('java110-','log4j-slf4j-impl-'))]+
        [repo/'junit/junit/4.13.1/junit-4.13.1.jar',repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar']))
    sources=[local.BACK/p for p in [
        'java110-bean/src/main/java/com/java110/dto/fee/BillDto.java',
        'java110-bean/src/main/java/com/java110/dto/fee/BillOweFeeDto.java',
        'service-fee/src/main/java/com/java110/fee/smo/impl/FeeInnerServiceSMOImpl.java',
        'service-api/src/main/java/com/java110/api/bmo/fee/impl/BillReviewService.java',
        'service-api/src/main/java/com/java110/api/listener/fee/ReviewBillListener.java',
        'service-api/src/main/java/com/java110/api/bmo/fee/impl/BillPublicationService.java',
        'service-api/src/main/java/com/java110/api/bmo/fee/impl/BillNotificationTaskStore.java',
        'service-api/src/main/java/com/java110/api/bmo/fee/impl/BillNotificationService.java',
        'service-api/src/main/java/com/java110/api/listener/fee/BillNotificationStatusListener.java',
        'service-front/src/main/java/com/java110/front/smo/api/impl/OwnerDataAccessService.java',
        'service-front/src/main/java/com/java110/front/controller/app/PaymentController.java']]
    sources.append(Path(__file__).with_name('BillReviewTransitionTest.java'))
    sources.append(Path(__file__).with_name('FeeReviewValidationTest.java'))
    sources.append(Path(__file__).with_name('BillPublicationTest.java'))
    sources.append(Path(__file__).with_name('BillPublicationWorkflowTest.java'))
    java=Path(local.java())
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
    logging=output/'logback.xml';logging.write_text('<configuration><root level="OFF"/></configuration>',encoding='utf-8')
    return java,cp,logging,output

if __name__=='__main__':
    java,cp,logging,output=compile_tests()
    raise SystemExit(subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,'org.junit.runner.JUnitCore','BillReviewTransitionTest','FeeReviewValidationTest','BillPublicationTest','BillPublicationWorkflowTest']).returncode)
