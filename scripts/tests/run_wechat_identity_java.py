"""OAuth identity binding regressions; no real Redis, property writes or WeChat requests."""
import os
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'billing'))
from verify import compile_audit, local

def main():
    java, cp, logging, base = compile_audit()
    output=base/'wechat-identity';output.mkdir(exist_ok=True)
    repo=Path.home()/'.m2/repository'
    cp=os.pathsep.join([str(output),cp,str(local.BACK/'service-api/target/classes'),str(local.BACK/'service-front/target/classes'),
        str(repo/'junit/junit/4.13.1/junit-4.13.1.jar'),str(repo/'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar')])
    sources=[local.BACK/'java110-core/src/main/java/com/java110/core/factory/OwnerWechatSession.java',
        local.BACK/'service-api/src/main/java/com/java110/api/listener/owner/BindWechatIdentityListener.java',
        Path(__file__).with_name('WechatIdentityBindingTest.java')]
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',cp,'-d',str(output)]+list(map(str,sources)),check=True)
    return subprocess.run([str(java),'-Dlogback.configurationFile='+str(logging),'-cp',cp,
        'org.junit.runner.JUnitCore','WechatIdentityBindingTest']).returncode

if __name__=='__main__':
    try:raise SystemExit(main())
    except subprocess.CalledProcessError as error:raise SystemExit('Identity binding compile failed (exit '+str(error.returncode)+')')
