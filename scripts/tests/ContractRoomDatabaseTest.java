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
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Uses the production contract-room mapper, solely in an exact random audit schema. */
public class ContractRoomDatabaseTest {
    static JdbcTemplate sql;
    static SqlSessionTemplate mapper;
    static TransactionTemplate transaction;
    static final String NS="contractRoomServiceDaoImpl.";
    static Map<String,Object> data(String... pairs) {
        Map<String,Object> result=new HashMap<>();
        for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);
        return result;
    }
    @BeforeClass public static void setup() throws Exception {
        String db=System.getProperty("schema.audit","");
        if(!db.matches("audit_schema_[a-f0-9]{12}"))throw new IllegalArgumentException("Unsafe audit target");
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+db+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false","root","");
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");sql=new JdbcTemplate(ds);
        transaction=new TransactionTemplate(new DataSourceTransactionManager(ds));
        Configuration config=new Configuration(new Environment("test",new SpringManagedTransactionFactory(),ds));
        try(InputStream stream=Files.newInputStream(Paths.get(System.getProperty("schema.mapper")))){
            new XMLMapperBuilder(stream,config,"contract-room",config.getSqlFragments()).parse();
        }
        mapper=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
    }
    @Before public void clean(){sql.execute("DELETE FROM contract_room");}
    static void save(String id,String room,String store) {
        mapper.insert(NS+"saveContractRoomInfo",data("crId",id,"roomId",room,"storeId",store,"contractId","contract-"+id,"ownerName","测试业主","ownerId","owner-"+id,"roomName","测试房屋"));
    }
    @Test public void uncontractedRoomReturnsEmptyInsteadOfMissingTableError() {
        assertTrue(mapper.selectList(NS+"getContractRoomInfo",data("roomId","ordinary-room","statusCd","0")).isEmpty());
    }
    @Test public void queryUsesRequestedRoomAndStore() {
        save("a","room-a","store-a");save("b","room-b","store-b");
        List<Map<String,Object>> rows=mapper.selectList(NS+"getContractRoomInfo",data("roomId","room-a","storeId","store-a","statusCd","0"));
        assertEquals(1,rows.size());assertEquals("contract-a",rows.get(0).get("contractId"));
        assertTrue(mapper.selectList(NS+"getContractRoomInfo",data("roomId","room-a","storeId","store-b")).isEmpty());
    }
    @Test public void softDeleteIsExcludedAndOtherRoomSurvives() {
        save("a","room-a","store-a");save("b","room-b","store-b");
        assertEquals(1,mapper.update(NS+"updateContractRoomInfo",data("crId","a","storeId","store-a","statusCd","1")));
        assertTrue(mapper.selectList(NS+"getContractRoomInfo",data("roomId","room-a","statusCd","0")).isEmpty());
        assertEquals(1,mapper.selectList(NS+"getContractRoomInfo",data("roomId","room-b","statusCd","0")).size());
    }
    @Test public void databaseTransactionRollsBackRelationInsert() {
        try{transaction.execute(s->{save("a","room-a","store-a");throw new IllegalStateException("Injected failure");});fail();}
        catch(IllegalStateException expected){}
        assertEquals(0,(int)sql.queryForObject("SELECT count(*) FROM contract_room",Integer.class));
    }
    @Test public void countAndBusinessIdUpdateMatchMapper() {
        save("a","room-a","store-a");
        mapper.update(NS+"updateContractRoomInfo",data("crId","a","statusCd","0","newBId","batch-a"));
        assertEquals("batch-a",sql.queryForObject("SELECT b_id FROM contract_room WHERE cr_id='a'",String.class));
        Map<String,Object> count=mapper.selectOne(NS+"queryContractRoomsCount",data("roomId","room-a","statusCd","0"));
        assertEquals(1,((Number)count.get("count")).intValue());
    }
}
