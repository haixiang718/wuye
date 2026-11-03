#!/usr/bin/env python3
"""Write editable examples only; never read local secrets or mark production ready."""
import argparse
import json
import os
from pathlib import Path


def prepare(target):
    target.mkdir(parents=True, exist_ok=True)
    common = {'eureka.client.serviceUrl.defaultZone': 'http://127.0.0.1:8761/eureka/',
              'eureka.instance.hostname': 'localhost', 'eureka.instance.ip-address': '127.0.0.1',
              'eureka.instance.preferIpAddress': 'true', 'spring.redis.host': '127.0.0.1',
              'spring.redis.port': 6379, 'spring.redis.password': 'REPLACE_IN_PRODUCTION',
              'jedis.pool.host': '127.0.0.1', 'jedis.pool.port': 6379,
              'jedis.pool.password': 'REPLACE_IN_PRODUCTION', 'jedis.pool.config.maxTotal': 20,
              'jedis.pool.config.maxIdle': 2, 'jedis.pool.timeout': 3000,
              'kafka.consumer.servers': '127.0.0.1:9092', 'kafka.producer.servers': '127.0.0.1:9092',
              'kafka.consumer.zookeeper.connect': '127.0.0.1:2181',
              'kafka.producer.zookeeper.connect': '127.0.0.1:2181',
              'kafka.consumer.concurrency': 1, 'java110.code.zookeeper.url': '127.0.0.1',
              'java110.code.zookeeper.port': 2181, 'server.tomcat.max-threads': 30,
              'server.tomcat.min-spare-threads': 2,
              'spring.datasource.url': 'jdbc:mysql://127.0.0.1:3306/TT?useUnicode=true&characterEncoding=utf8&useSSL=false',
              'spring.datasource.username': 'REPLACE_IN_PRODUCTION',
              'spring.datasource.password': 'REPLACE_IN_PRODUCTION',
              'java110.ftpServer': 'REPLACE_IN_PRODUCTION', 'java110.ftpUserName': 'REPLACE_IN_PRODUCTION',
              'java110.ftpUserPassword': 'REPLACE_IN_PRODUCTION'}
    for service in ('eureka', 'order', 'api', 'front', 'job', 'business'):
        config = dict(common)
        if service in ('order', 'job', 'business'):
            config['java110.datasource.config'] = (target / (service + '-datasource.yml')).as_uri()
        if service == 'business':
            for domain in ('community', 'report'):
                config['java110.datasource.' + domain + '.config'] = (target / ('business-' + domain + '-datasource.yml')).as_uri()
            for domain, group in {'user': 'notifyBusinessStatus', 'acct': 'notifyBusinessStatus',
                                  'community': 'communityBusinessStatus', 'fee': 'communityBusinessStatus',
                                  'store': 'storeBusinessStatus', 'common': 'commonBusinessStatus'}.items():
                config['java110.kafka.' + domain + '.group-id'] = group
        file = target / (service + '.json.example')
        with file.open('x', encoding='utf-8') as stream:
            json.dump(config, stream, indent=2)
            stream.write('\n')
    for file in (Path(__file__).parent / 'config/examples').glob('*.example'):
        destination = target / file.name
        with destination.open('xb') as stream:
            stream.write(file.read_bytes())
    print('Examples created. Fill and rename them; READY_FOR_NONPAYMENT is deliberately not created.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    args = parser.parse_args()
    os.umask(0o027)
    prepare(args.directory.resolve())
