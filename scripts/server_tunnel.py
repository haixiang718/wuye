"""Keep the fixed-domain demo's restricted SSH reverse tunnel connected on Windows."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
STATE=ROOT/'.runtime/domain-ingress/tunnel-process.json'
KEY=ROOT/'.runtime/ssh/domain-tunnel-ed25519'
KNOWN=ROOT/'.runtime/server-known-hosts'
LOG=ROOT/'.runtime/domain-ingress/tunnel.log'


def alive(record):
    pid=int(record['pid'])
    script="$p=Get-CimInstance Win32_Process -Filter 'ProcessId="+str(pid)+"'; if ($p) { $p | Select-Object CommandLine | ConvertTo-Json -Compress }"
    result=subprocess.run(['powershell','-NoProfile','-Command',script],capture_output=True,text=True,check=True)
    if not result.stdout.strip():return False
    command=json.loads(result.stdout).get('CommandLine','')
    return str(Path(__file__).resolve()).lower() in command.lower() and 'watch' in command


def start():
    if os.name!='nt':raise RuntimeError('This local tunnel launcher is for Windows')
    if STATE.exists() and alive(json.loads(STATE.read_text())):
        print('Tunnel supervisor is already running.');return
    if not KEY.is_file() or not KNOWN.is_file():raise RuntimeError('Install the dedicated tunnel key and verify the server first')
    STATE.parent.mkdir(exist_ok=True)
    with LOG.open('ab') as output:
        process=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'watch'],stdin=subprocess.DEVNULL,
            stdout=output,stderr=output,creationflags=subprocess.CREATE_NEW_PROCESS_GROUP|subprocess.DETACHED_PROCESS)
    STATE.write_text(json.dumps({'pid':process.pid,'host':'118.31.186.6','remotePort':18080}),encoding='utf-8')
    print('Tunnel supervisor started; verify remote listener and HTTPS separately.')


def watch():
    ssh=shutil.which('ssh')
    if not ssh:raise RuntimeError('OpenSSH client is required')
    command=[ssh,'-N','-T','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes',
        '-o','UserKnownHostsFile='+str(KNOWN),'-o','IdentitiesOnly=yes','-o','ExitOnForwardFailure=yes',
        '-o','ConnectTimeout=15','-o','ServerAliveInterval=20','-o','ServerAliveCountMax=3',
        '-i',str(KEY),'-R','127.0.0.1:18080:127.0.0.1:8080','wuye-tunnel@118.31.186.6']
    while True:
        subprocess.run(command,stdin=subprocess.DEVNULL)
        print('SSH connection ended; retrying in five seconds.',flush=True)
        time.sleep(5)


def stop():
    if STATE.exists():
        record=json.loads(STATE.read_text())
        if alive(record):
            subprocess.run(['taskkill','/PID',str(int(record['pid'])),'/T','/F'],capture_output=True,check=True)
        STATE.unlink()
    print('Tunnel supervisor stopped.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['start','stop','status','watch'])
    args=parser.parse_args()
    if args.action=='status':print('RUNNING' if STATE.exists() and alive(json.loads(STATE.read_text())) else 'STOPPED')
    else:globals()[args.action]()
