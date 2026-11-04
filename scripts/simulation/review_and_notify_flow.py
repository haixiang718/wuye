"""Real local combined bill publication with an unbound fixture; no WeChat sends."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wechat_direct_flow as fixture

fixture.RUN = fixture.local.RUN / 'review-and-notify-flow'
fixture.RECORD = fixture.RUN / 'fixture.json'


def prepare():
    fixture.prepare()
    fixture.generate()
    print('Prepared one 0.01 draft with no WeChat binding; no notification can be delivered.')


def exercise():
    fixture.ensure_local()
    record = fixture.load()
    q = fixture.q
    assert fixture.query('SELECT count(*) FROM owner_app_user WHERE member_id=' + q(record['ids']['member'])) == '0'
    subprocess.run(['node', str(Path(__file__).with_name('review_and_notify_publish.cjs'))], check=True)
    assert fixture.query('SELECT review_state FROM bill WHERE bill_id=' + q(record['billId'])) == 'PUBLISHED'
    assert fixture.query('SELECT state FROM bill_owe_fee WHERE owe_id=' + q(record['oweId'])) == '1000'
    report = json.loads((fixture.RUN / 'publication.json').read_text(encoding='utf-8'))
    assert report.get('passed') and report.get('terminalUnboundFailure') and report.get('retryFailedOnly')
    assert_task_stopped(record)
    # The Node check proves the task has stopped before creating any synthetic binding.
    # No more review/retry requests may occur after this point.
    fixture.exercise()
    print('Combined review queued notification, exposed unbound failure, retried explicitly; published H5 read passed.')


def cleanup():
    record = fixture.load()
    if record.get('cleanupVerified'):
        fixture.cleanup()
        return
    if record.get('billId'):
        task_key = assert_task_stopped(record)
        # Compare provenance, lease and active membership atomically before deleting.
        script = """
local raw=redis.call('get',KEYS[1]);if not raw then return 1 end
local task=cjson.decode(raw)
if task.communityId~=ARGV[1] or task.billId~=ARGV[2] then return 0 end
if task.state~='COMPLETED' and task.state~='FAILED' then return 0 end
if #task.items~=1 or task.items[1].oweId~=ARGV[3] then return 0 end
if redis.call('sismember',KEYS[3],KEYS[1])~=0 or redis.call('exists',KEYS[2])~=0 then return 0 end
redis.call('del',KEYS[1]);return 1
"""
        result = fixture.redis_command('EVAL', script, 3, task_key, task_key + ':LOCK', 'BILL_PUBLICATION_ACTIVE',
                                       record['communityId'], record['billId'], record['oweId'])
        if str(result) != '1':
            raise RuntimeError('Queue provenance changed; preserving fixture')
    fixture.cleanup()


def assert_task_stopped(record):
    task_key = 'BILL_PUBLICATION_TASK:' + record['communityId'] + ':' + record['billId']
    raw = fixture.redis_command('GET', task_key)
    if raw is None:
        return task_key
    task = json.loads(raw)
    assert task['communityId'] == record['communityId'] and task['billId'] == record['billId']
    assert len(task['items']) == 1 and task['items'][0]['oweId'] == record['oweId']
    assert task['state'] in ('COMPLETED', 'FAILED')
    assert str(fixture.redis_command('SISMEMBER', 'BILL_PUBLICATION_ACTIVE', task_key)) == '0'
    assert fixture.redis_command('GET', task_key + ':LOCK') is None
    return task_key


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'exercise', 'cleanup'])
    args = parser.parse_args()
    try:
        globals()[args.action]()
    except Exception as failure:
        print('Combined publication check stopped: ' + type(failure).__name__ + '. Preserve private evidence and clean the scoped fixture.', file=sys.stderr)
        raise SystemExit(2)
