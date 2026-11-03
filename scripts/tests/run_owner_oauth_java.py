"""OAuth regressions; --baseline compiles the pre-repair login implementation to prove failures."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local


def main():
    java, cp, logging, output = compile_audit()
    output = output / ('oauth-baseline' if '--baseline' in sys.argv else 'oauth-fixed')
    output.mkdir(exist_ok=True)
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([str(output),cp,str(local.BACK/'service-front/target/classes'),
        str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    relative='service-front/src/main/java/com/java110/front/smo/login/impl/OwnerAppLoginSMOImpl.java'
    source=local.BACK/relative
    if '--baseline' in sys.argv:
        source=output/'OwnerAppLoginSMOImpl.java'
        original=subprocess.check_output(['git','show','3313a67:wuye-b/MicroCommunity-v0.8/'+relative],cwd=local.ROOT)
        source.write_bytes(original)
    test=local.BACK/'service-front/src/test/java/com/java110/front/smo/login/impl/OwnerWechatOAuthTest.java'
    extra=[local.BACK/'java110-core/src/main/java/com/java110/core/factory/OwnerWechatSession.java',
           local.BACK/'service-front/src/main/java/com/java110/front/smo/login/IOwnerAppLoginSMO.java']
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,extra))+[str(source),str(test)],check=True)
    return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,
        'org.junit.runner.JUnitCore','com.java110.front.smo.login.impl.OwnerWechatOAuthTest']).returncode


if __name__=='__main__':
    try:raise SystemExit(main())
    except subprocess.CalledProcessError as error:raise SystemExit('OAuth test compile failed (exit '+str(error.returncode)+')')
