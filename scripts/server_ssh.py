"""SSH access for the explicitly selected demo ingress host; credentials stay local."""
import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/server-ssh'))


def connect():
    import paramiko
    config = json.loads((ROOT / '.runtime/server-connection.json').read_text(encoding='utf-8-sig'))
    if config.get('host') != '118.31.186.6' or config.get('port') != 22:
        raise ValueError('Connection target differs from the confirmed server; review configuration')
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_.-]{0,63}', config.get('username', '')):
        raise ValueError('Set the SSH login username in the local configuration file')
    private_key = config.get('privateKey')
    if private_key:
        allowed = (ROOT / '.runtime/ssh/domain-admin-ed25519').resolve()
        if Path(private_key).resolve() != allowed or not allowed.is_file():
            raise ValueError('Use the dedicated deployment key in the local runtime directory')
    if not config.get('password') and not private_key:
        raise ValueError('Configure an SSH password or install the dedicated deployment public key')
    known = ROOT / '.runtime/server-known-hosts'
    known.touch(exist_ok=True)
    client = paramiko.SSHClient()
    client.load_host_keys(str(known))
    # Trust the first connection to the user-confirmed IP, then pin its key locally.
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        client.connect(config['host'], port=config['port'], username=config['username'],
            password=config.get('password') or None, key_filename=private_key,
            look_for_keys=False, allow_agent=False,
            timeout=15, banner_timeout=20, auth_timeout=20)
    except Exception:
        client.close()
        raise RuntimeError('SSH connection failed; check the local username, installed key/password and server access') from None
    return client


def run(client, script, timeout=60):
    stdin, stdout, stderr = client.exec_command('bash -s', timeout=timeout)
    stdin.write(script)
    stdin.flush()
    stdin.channel.shutdown_write()
    output, errors = stdout.read().decode('utf-8', errors='replace'), stderr.read().decode('utf-8', errors='replace')
    code = stdout.channel.recv_exit_status()
    if code:
        raise RuntimeError('Remote command failed (exit '+str(code)+'): '+errors[:2000])
    return output


PREFLIGHT = '''set -eu
python3 - <<'PY'
import json,os,pathlib,shutil,subprocess
def command(args):
 result=subprocess.run(args,capture_output=True,text=True)
 return {'exit':result.returncode,'stdout':result.stdout.strip(),'stderr':result.stderr.strip()}
sites=pathlib.Path('/etc/nginx/sites-enabled')
print(json.dumps({'uid':os.getuid(),'os':pathlib.Path('/etc/os-release').read_text(),
 'memory':command(['free','-m']),'disk':command(['df','-h','/']),
 'listeners':command(['ss','-H','-lnt']),
 'nginxInstalled':bool(shutil.which('nginx')),'certbotInstalled':bool(shutil.which('certbot')),
 'nginxSites':[p.name for p in sites.iterdir()] if sites.exists() else [],
 'firewall':command(['ufw','status']) if shutil.which('ufw') else None},indent=2))
PY
'''


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['preflight'])
    args=parser.parse_args()
    client=None
    try:
        client=connect()
        result=run(client,PREFLIGHT)
        folder=ROOT/'.runtime/domain-ingress'
        folder.mkdir(exist_ok=True)
        (folder/'server-preflight.json').write_text(result,encoding='utf-8')
        print(result)
    except (OSError,ValueError,RuntimeError) as error:
        print(str(error),file=sys.stderr)
        raise SystemExit(1)
    finally:
        if client:client.close()
