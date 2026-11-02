import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DataAccessException;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;

/** Real production mapper statements; fixtures exist only in an exact random audit database. */
public class AccountContractDatabaseTest {
    static JdbcTemplate sql;
    static SqlSessionTemplate mapper;
    static TransactionTemplate transaction;
    static final String ACCOUNT="accountServiceDaoImpl.";
    static final String DETAIL="accountDetailServiceDaoImpl.";
    static final String CONTRACT="contractServiceDaoImpl.";

    @BeforeClass public static void setup() throws Exception {
        String database=System.getProperty("schema.audit", "");
        if(!database.matches("audit_schema_[a-f0-9]{12}"))throw new IllegalArgumentException("Invalid audit database");
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+database+
            "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false", "root", "");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        sql=new JdbcTemplate(source);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        Configuration config=new Configuration(new Environment("schema-test",new SpringManagedTransactionFactory(),source));
        for(String file:Arrays.asList("acct/AccountServiceDaoImplMapper.xml","acct/AccountDetailServiceDaoImplMapper.xml","store/ContractServiceDaoImplMapper.xml")) {
            try(InputStream stream=Files.newInputStream(Paths.get(System.getProperty("schema.mapper.root"),file))) {
                new XMLMapperBuilder(stream,config,file,config.getSqlFragments()).parse();
            }
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }
    @Before public void reset() {
        for(String table:Arrays.asList("account_detail","business_account_detail","account","business_account","contract"))
            sql.update("DELETE FROM "+table);
    }
    static Map<String,Object> data(Object... pairs) {
        Map<String,Object> result=new HashMap<>();
        for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    static Map<String,Object> account(String id,String owner,String amount) {
        return data("acctId",id,"bId","batch-"+id,"operate","ADD","acctName","Synthetic "+id,
            "acctType","2003","objId",owner,"objType","owner","amount",new BigDecimal(amount));
    }
    static void saveAccount(Map<String,Object> value) {
        assertEquals(1,mapper.insert(ACCOUNT+"saveBusinessAccountInfo",value));
        assertEquals(1,mapper.insert(ACCOUNT+"saveAccountInfoInstance",data("acctId",value.get("acctId"),"bId",value.get("bId"))));
    }
    static Map<String,Object> detail(String id,String acct,String owner,String amount) {
        return data("detailId",id,"acctId",acct,"bId","batch-"+id,"operate","ADD","detailType","1001",
            "objId",owner,"objType","owner","amount",new BigDecimal(amount),"orderId","order-"+id,"remark","Synthetic");
    }
    static void saveDetail(Map<String,Object> value) {
        assertEquals(1,mapper.insert(DETAIL+"saveBusinessAccountDetailInfo",value));
        assertEquals(1,mapper.insert(DETAIL+"saveAccountDetailInfoInstance",data("detailId",value.get("detailId"),"bId",value.get("bId"))));
    }
    static int count(String table) {return sql.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    static BigDecimal amount(String id) {return sql.queryForObject("SELECT amount FROM account WHERE acct_id=?",BigDecimal.class,id);}

    @Test public void accountRoundTripPreservesCentsAndScopesOwner() {
        saveAccount(account("a","owner-a","0.01"));saveAccount(account("b","owner-b","123.45"));
        List<Map<String,Object>> rows=mapper.selectList(ACCOUNT+"getAccountInfo",data("objId","owner-a","statusCd","0"));
        assertEquals(1,rows.size());assertEquals("a",rows.get(0).get("acctId"));
        assertEquals(new BigDecimal("0.01"),rows.get(0).get("amount"));
        Map<String,Object> total=mapper.selectOne(ACCOUNT+"queryAccountsCount",data("objId","owner-b","statusCd","0"));
        assertEquals(1,((Number)total.get("count")).intValue());
    }
    @Test public void detailRoundTripJoinsCorrectAccountAndScopesOwner() {
        saveAccount(account("a","owner-a","1.00"));saveAccount(account("b","owner-b","2.00"));
        saveDetail(detail("da","a","owner-a","0.01"));saveDetail(detail("db","b","owner-b","0.02"));
        List<Map<String,Object>> rows=mapper.selectList(DETAIL+"getAccountDetailInfo",data("objId","owner-a","statusCd","0"));
        assertEquals(1,rows.size());assertEquals("Synthetic a",rows.get(0).get("acctName"));
        assertEquals(new BigDecimal("0.01"),rows.get(0).get("amount"));
        Map<String,Object> total=mapper.selectOne(DETAIL+"queryAccountDetailsCount",data("acctId","b","objId","owner-b"));
        assertEquals(1,((Number)total.get("count")).intValue());
    }
    @Test public void supportsSameBusinessIdAddAndDeleteHistory() {
        Map<String,Object> a=account("a","owner-a","1.00");saveAccount(a);a.put("operate","DEL");
        assertEquals(1,mapper.insert(ACCOUNT+"saveBusinessAccountInfo",a));
        assertEquals(2,mapper.selectList(ACCOUNT+"getBusinessAccountInfo",data("bId","batch-a")).size());
        Map<String,Object> d=detail("da","a","owner-a","0.01");saveDetail(d);d.put("operate","DEL");
        assertEquals(1,mapper.insert(DETAIL+"saveBusinessAccountDetailInfo",d));
        assertEquals(2,mapper.selectList(DETAIL+"getBusinessAccountDetailInfo",data("bId","batch-da")).size());
        assertEquals(1,count("account"));assertEquals(1,count("account_detail"));
    }
    @Test public void rejectsDuplicateAccountAndDetailIdentifiers() {
        saveAccount(account("a","owner-a","1.00"));saveDetail(detail("da","a","owner-a","0.01"));
        try {mapper.insert(ACCOUNT+"saveAccountInfoInstance",data("bId","batch-a"));fail("duplicate account accepted");}
        catch(DataAccessException expected) {}
        try {mapper.insert(DETAIL+"saveAccountDetailInfoInstance",data("bId","batch-da"));fail("duplicate detail accepted");}
        catch(DataAccessException expected) {}
        assertEquals(1,count("account"));assertEquals(1,count("account_detail"));
    }
    @Test public void accountUpdateWithObjectConditionWorks() {
        saveAccount(account("a","owner-a","1.00"));saveAccount(account("b","owner-b","2.00"));
        assertEquals(1,mapper.update(ACCOUNT+"updateAccount",data("acctId","a","objId","owner-a","amount",new BigDecimal("3.45"))));
        assertEquals(new BigDecimal("3.45"),amount("a"));assertEquals(new BigDecimal("2.00"),amount("b"));
    }
    @Test public void directAccountUpdateCanSetExactZero() {
        saveAccount(account("a","owner-a","1.00"));
        assertEquals(1,mapper.update(ACCOUNT+"updateAccount",data("acctId","a","amount",new BigDecimal("0.00"))));
        assertEquals(new BigDecimal("0.00"),amount("a"));
    }
    @Test public void businessMapUpdateCanZeroAccountAndDetailAmounts() {
        saveAccount(account("a","owner-a","1.00"));saveDetail(detail("da","a","owner-a","1.00"));
        Map<String,Object> zero=account("a","owner-a","0.00");zero.put("bId","zero-batch");
        mapper.insert(ACCOUNT+"saveBusinessAccountInfo",zero);
        Map<String,Object> value=mapper.selectOne(ACCOUNT+"getBusinessAccountInfo",data("acctId","a","bId","zero-batch"));
        assertTrue(value.get("amount") instanceof BigDecimal);value.put("statusCd","0");value.remove("bId");
        assertEquals(1,mapper.update(ACCOUNT+"updateAccountInfoInstance",value));
        Map<String,Object> zeroDetail=detail("da","a","owner-a","0.00");zeroDetail.put("bId","zero-detail");
        mapper.insert(DETAIL+"saveBusinessAccountDetailInfo",zeroDetail);
        Map<String,Object> detailValue=mapper.selectOne(DETAIL+"getBusinessAccountDetailInfo",data("detailId","da","bId","zero-detail"));
        detailValue.put("statusCd","0");detailValue.remove("bId");
        assertEquals(1,mapper.update(DETAIL+"updateAccountDetailInfoInstance",detailValue));
        assertEquals(new BigDecimal("0.00"),amount("a"));
        assertEquals(new BigDecimal("0.00"),sql.queryForObject("SELECT amount FROM account_detail WHERE detail_id='da'",BigDecimal.class));
    }
    @Test public void missingAccountIdentifierCannotUpdateEveryone() {
        saveAccount(account("a","owner-a","1.00"));saveAccount(account("b","owner-b","2.00"));
        assertEquals(0,mapper.update(ACCOUNT+"updateAccount",data("amount",new BigDecimal("9.99"))));
        assertEquals(new BigDecimal("1.00"),amount("a"));assertEquals(new BigDecimal("2.00"),amount("b"));
    }
    @Test public void wrongObjectCannotUpdateAccount() {
        saveAccount(account("a","owner-a","1.00"));
        assertEquals(0,mapper.update(ACCOUNT+"updateAccount",data("acctId","a","objId","owner-b","amount",new BigDecimal("9.99"))));
        assertEquals(new BigDecimal("1.00"),amount("a"));
    }
    @Test public void explicitTransactionRollsBackAllFourTablesOnLateFailure() {
        try {
            transaction.execute(status->{
                saveAccount(account("a","owner-a","1.00"));saveDetail(detail("da","a","owner-a","0.01"));
                // Fails after account and detail instances have been written.
                mapper.insert(DETAIL+"saveBusinessAccountDetailInfo",detail("da","a","owner-a","0.01"));
                return null;
            });fail("Expected duplicate failure");
        } catch(DataAccessException expected) {}
        for(String table:Arrays.asList("account","business_account","account_detail","business_account_detail"))assertEquals(0,count(table));
    }
    @Test public void directDetailInsertHasActiveDefaultAndSupportsNegativeAdjustments() {
        mapper.insert(DETAIL+"saveAccountDetails",detail("negative","a","owner-a","-0.01"));
        assertEquals("0",sql.queryForObject("SELECT status_cd FROM account_detail",String.class));
        assertEquals(new BigDecimal("-0.01"),sql.queryForObject("SELECT amount FROM account_detail",BigDecimal.class));
    }
    static Map<String,Object> contract(String id,String store,String parent) {
        return data("contractId",id,"contractName","Synthetic "+id,"contractCode","code-"+id,"contractType","test-type",
            "storeId",store,"operator","Tester","signingTime","2026-11-02 00:00:00","partyA","A","partyB","B",
            "aLink","19900000001","aContacts","A","bLink","19900000002","bContacts","B","amount","12.34",
            "objId","test-room","startTime","2026-11-02 00:00:00","endTime","2027-11-02 00:00:00","state","11",
            "objType","3333","operatorLink","19900000001","contractParentId",parent,
            "objName","Synthetic room","objPersonName","Synthetic person","objPersonId","test-owner","startUserId","tester");
    }
    @Test public void contractSaveQueryUpdateAndParentLinkWork() {
        mapper.insert(CONTRACT+"saveContractInfo",contract("parent","store-a",null));
        mapper.insert(CONTRACT+"saveContractInfo",contract("child","store-a","parent"));
        Map<String,Object> row=mapper.selectOne(CONTRACT+"getContractInfo",data("contractId","child","storeId","store-a","statusCd","0"));
        assertEquals("Synthetic parent",row.get("parentContractName"));assertEquals("test-owner",row.get("objPersonId"));
        assertEquals("tester",row.get("startUserId"));
        assertEquals(1,mapper.update(CONTRACT+"updateContractInfo",data("contractId","child","statusCd","0","newBId","update-batch","objName","Updated room")));
        row=mapper.selectOne(CONTRACT+"getContractInfo",data("contractId","child","storeId","store-a"));
        assertEquals("Updated room",row.get("objName"));assertEquals("update-batch",sql.queryForObject("SELECT b_id FROM contract WHERE contract_id='child'",String.class));
    }
    @Test public void contractParentJoinCannotExposeAnotherStore() {
        mapper.insert(CONTRACT+"saveContractInfo",contract("parent","store-b",null));
        mapper.insert(CONTRACT+"saveContractInfo",contract("child","store-a","parent"));
        Map<String,Object> row=mapper.selectOne(CONTRACT+"getContractInfo",data("contractId","child","storeId","store-a"));
        assertNull(row.get("parentContractName"));
        assertTrue(mapper.selectList(CONTRACT+"getContractInfo",data("contractId","parent","storeId","store-a")).isEmpty());
    }
    @Test public void legacyContractWithNullAddedFieldsRemainsReadable() {
        Map<String,Object> old=contract("legacy","store-a",null);
        for(String field:Arrays.asList("objName","objPersonName","objPersonId","startUserId"))old.put(field,null);
        mapper.insert(CONTRACT+"saveContractInfo",old);
        Map<String,Object> row=mapper.selectOne(CONTRACT+"getContractInfo",data("contractId","legacy","storeId","store-a"));
        assertEquals("Synthetic legacy",row.get("contractName"));assertNull(row.get("startUserId"));
    }
}
