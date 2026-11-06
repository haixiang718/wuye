"""Real MySQL index maintenance on synthetic schemas, including reversal and data checks."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import migrate_database_optimization as m


class OptimizationTest(unittest.TestCase):
    def setUp(self):
        self.db='ops_restore_'+uuid.uuid4().hex[:12]
        m.ops.mysql_query('CREATE DATABASE '+m.ident(self.db)+' CHARACTER SET utf8mb4')
        self.addCleanup(lambda:m.ops.mysql_query('DROP DATABASE '+m.ident(self.db)))
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.query("CREATE TABLE bill(bill_id varchar(30) NOT NULL,community_id varchar(30) NOT NULL,cur_bill varchar(12) NOT NULL,create_time datetime NOT NULL,amount decimal(10,2),KEY idx_billing_identity(bill_id,community_id)) ENGINE=InnoDB")
        self.query("CREATE TABLE bill_owe_fee(owe_id varchar(30) NOT NULL,community_id varchar(30) NOT NULL,bill_id varchar(30) NOT NULL,amount decimal(10,2),KEY idx_billing_owe_identity(owe_id,community_id,bill_id)) ENGINE=InnoDB")
        self.query("CREATE TABLE pay_fee(fee_id varchar(30) NOT NULL,amount decimal(10,2),UNIQUE KEY fee_id(fee_id),UNIQUE KEY fee_id_2(fee_id),KEY unrelated_amount(amount)) ENGINE=InnoDB")
        self.query("INSERT INTO bill VALUES('bill','community','T','2026-11-01',0.01)")
        self.query("INSERT INTO bill_owe_fee VALUES('owe','community','bill',0.01)")
        self.query("INSERT INTO pay_fee VALUES('fee',0.01)")
        self.hints=patch.object(m,'index_hints',return_value='');self.hints.start();self.addCleanup(self.hints.stop)

    def query(self,sql):return m.ops.mysql_query(sql,self.db)
    def plan(self):return m.plan(self.db,'TT')
    def apply(self):return m.apply(self.db,'TT',Path(self.temp.name))

    def test_migrate_preserves_data_clustered_index_unique_constraints_and_reverses(self):
        before=m.snapshot(self.db,['bill','bill_owe_fee','pay_fee']);original=m.inspect(self.db)
        report=self.apply();self.assertTrue(report['passed']);self.assertEqual(before,report['after'])
        after=m.inspect(self.db)
        self.assertEqual(('bill_id',),m.columns(after['bill']['indexes']['PRIMARY']))
        self.assertEqual(('owe_id',),m.columns(after['bill_owe_fee']['indexes']['PRIMARY']))
        self.assertEqual(original['pay_fee']['clustered'],after['pay_fee']['clustered'])
        self.assertNotIn('fee_id_2',after['pay_fee']['indexes']);self.assertIn('unrelated_amount',after['pay_fee']['indexes'])
        self.assertEqual([],self.plan());self.assertEqual(0,self.apply()['applied'])
        with self.assertRaises(m.ops.OpsError):self.query("INSERT INTO bill SELECT * FROM bill")
        with self.assertRaises(m.ops.OpsError):self.query("INSERT INTO pay_fee SELECT * FROM pay_fee")
        for item in reversed(report['planned']):self.query(item['rollbackSql'])
        self.assertEqual(before,m.snapshot(self.db,['bill','bill_owe_fee','pay_fee']))
        self.assertEqual(original['pay_fee']['indexes'],m.inspect(self.db)['pay_fee']['indexes'])
        self.assertGreater(len(self.plan()),0)

    def test_bad_billing_ids_or_named_indexes_refuse_before_backup(self):
        self.query("INSERT INTO bill SELECT * FROM bill")
        with patch.object(m,'backup') as backup:
            with self.assertRaisesRegex(ValueError,'duplicated'):self.apply()
            backup.assert_not_called()
        self.query("DELETE FROM bill LIMIT 1")
        self.query("UPDATE bill SET bill_id=''")
        with self.assertRaisesRegex(ValueError,'blank'):self.plan()
        self.query("UPDATE bill SET bill_id='bill'")
        self.query("ALTER TABLE bill ADD INDEX idx_billing_current_date(amount)")
        with self.assertRaisesRegex(ValueError,'ordering'):self.plan()

    def test_failed_backup_blocks_all_ddl_and_hinted_index_is_preserved(self):
        before=self.plan()
        with patch.object(m,'backup',side_effect=ValueError('synthetic backup failure')):
            with self.assertRaisesRegex(ValueError,'backup'):self.apply()
        self.assertEqual(before,self.plan())
        with self.assertRaisesRegex(ValueError,'hint'):m.plan(self.db,'TT',hints='USE INDEX (fee_id_2)')

    def test_foreign_keys_and_framework_tables_are_outside_cleanup(self):
        self.query("CREATE TABLE bill_child(id int primary key,fee_id varchar(30),FOREIGN KEY(fee_id) REFERENCES pay_fee(fee_id)) ENGINE=InnoDB")
        self.query("CREATE TABLE act_example(id int,KEY a(id),KEY b(id)) ENGINE=InnoDB")
        tables={item['table'] for item in self.plan()}
        self.assertNotIn('pay_fee',tables);self.assertNotIn('act_example',tables)
        with self.assertRaises((ValueError,m.ops.OpsError)):m.plan('production')
        with self.assertRaises(ValueError):m.plan('TT','hc_community')

    def test_current_bill_list_ordering_uses_index_for_representative_fixture(self):
        self.query('DELETE FROM bill')
        # 5,000 deterministic bills, with multiple communities and current/history states.
        self.query("INSERT INTO bill(bill_id,community_id,cur_bill,create_time,amount) SELECT CONCAT('b',n),CONCAT('c',MOD(n,10)),IF(MOD(FLOOR(n/10),2)=0,'T','F'),DATE_ADD('2020-01-01',INTERVAL n HOUR),1.00 FROM (SELECT a.n+10*b.n+100*c.n+1000*d.n n FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4) d) fixture_numbers")
        query="SELECT bill_id FROM bill WHERE community_id='c1' AND cur_bill='T' ORDER BY create_time DESC LIMIT 20"
        expected=self.query(query)
        self.assertIn('filesort',self.query('EXPLAIN '+query).lower())
        self.apply()
        plan=self.query('EXPLAIN '+query)
        self.assertIn('idx_billing_current_date',plan);self.assertNotIn('filesort',plan.lower())
        self.assertEqual(expected,self.query(query))

    def test_partial_failure_rollback_contains_only_committed_changes(self):
        original=m.inspect(self.db);real_query=m.ops.mysql_query;calls=[]
        def fail_second(sql,database=None):
            if sql.startswith('SET SESSION lock_wait_timeout'):
                calls.append(sql)
                if len(calls)==2:raise m.ops.OpsError('Synthetic second-table failure before DDL')
            return real_query(sql,database)
        with patch.object(m.ops,'mysql_query',side_effect=fail_second):
            with self.assertRaisesRegex(m.ops.OpsError,'second-table'):self.apply()
        folder=next(Path(self.temp.name).iterdir());report=json.loads((folder/'result.json').read_text())
        self.assertEqual(1,report['applied']);self.assertFalse(report['passed']);self.assertTrue(report['needsSchemaInspection'])
        self.assertEqual('bill_owe_fee',report['inProgress'])
        rollback=(folder/'rollback.sql').read_text();self.assertEqual(1,rollback.count('ALTER TABLE'))
        self.query(rollback)
        after=m.inspect(self.db)
        for table in original:self.assertEqual(original[table]['indexes'],after[table]['indexes'])

    def test_committed_ddl_with_lost_reply_is_included_in_rollback(self):
        original=m.inspect(self.db);real_query=m.ops.mysql_query
        def lost_reply(sql,database=None):
            result=real_query(sql,database)
            if sql.startswith('SET SESSION lock_wait_timeout'):raise m.ops.OpsError('Synthetic lost reply after commit')
            return result
        with patch.object(m.ops,'mysql_query',side_effect=lost_reply):
            with self.assertRaisesRegex(m.ops.OpsError,'lost reply'):self.apply()
        folder=next(Path(self.temp.name).iterdir());report=json.loads((folder/'result.json').read_text())
        self.assertEqual(1,report['applied']);self.assertFalse(report['needsSchemaInspection'])
        self.query((folder/'rollback.sql').read_text())
        after=m.inspect(self.db)
        for table in original:self.assertEqual(original[table]['indexes'],after[table]['indexes'])

    def test_community_role_only_deduplicates_without_changing_bill_tables(self):
        result=m.apply(self.db,'hc_community',Path(self.temp.name))
        self.assertEqual(['pay_fee'],[item['table'] for item in result['planned']])
        self.assertNotIn('PRIMARY',m.inspect(self.db)['bill']['indexes'])
        self.assertEqual([],m.plan(self.db,'hc_community'))
        self.query(result['planned'][0]['rollbackSql'])
        self.assertEqual(1,len(m.plan(self.db,'hc_community')))


if __name__=='__main__':unittest.main()
