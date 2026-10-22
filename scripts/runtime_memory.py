"""Read Windows memory for tracked project processes without printing command lines/secrets."""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import local


def snapshot(label):
    if os.name != 'nt':
        raise RuntimeError('This measurement helper uses Windows CIM; use RSS/PSS tools on Linux')
    expected = []
    for name, value in local.processes().items():
        command = value['command']
        token = (command[0] if name in ('mysql', 'redis') else
                 command[1] if name == 'web' else
                 next((item for item in command if item.endswith('.jar')), command[-1]))
        expected.append({'name': name, 'pid': int(value['pid']), 'token': token})
    literal = json.dumps(expected).replace("'", "''")
    script = r"""
$taskExpected = ConvertFrom-Json 'RECORDS';
$taskRows = @(foreach ($taskEntry in $taskExpected) {
    $taskProcess = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $taskEntry.pid);
    if ($taskProcess -and $taskProcess.CommandLine -and
        $taskProcess.CommandLine.IndexOf($taskEntry.token,[StringComparison]::OrdinalIgnoreCase) -ge 0) {
        [pscustomobject]@{name=$taskEntry.name; pid=$taskEntry.pid;
          workingSetBytes=[long]$taskProcess.WorkingSetSize;
          privateBytes=[long]$taskProcess.PrivatePageCount}
    }
});
ConvertTo-Json -InputObject $taskRows -Compress
""".replace('RECORDS', literal)
    rows = json.loads(subprocess.run(['powershell', '-NoProfile', '-Command', script],
                                    capture_output=True, text=True, check=True).stdout)
    java_rows = [row for row in rows if row['name'] in local.SERVICES]
    return {'label': label, 'measuredAt': datetime.datetime.now().astimezone().isoformat(),
            'platform': 'Windows', 'javaServiceCount': len(java_rows),
            'javaWorkingSetMiB': round(sum(row['workingSetBytes'] for row in java_rows) / 2**20, 1),
            'javaPrivateMiB': round(sum(row['privateBytes'] for row in java_rows) / 2**20, 1),
            'totalWorkingSetMiB': round(sum(row['workingSetBytes'] for row in rows) / 2**20, 1),
            'totalPrivateMiB': round(sum(row['privateBytes'] for row in rows) / 2**20, 1),
            'processes': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', default='current')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = snapshot(args.label)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({key: value for key, value in result.items() if key != 'processes'}, indent=2))


if __name__ == '__main__':
    main()
