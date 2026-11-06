import com.java110.fee.dao.OwnerPaymentHistoryRepository;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Production history SQL against cloned schemas and synthetic fixtures only. */
public class OwnerPaymentHistoryDatabaseTest {
    static JdbcTemplate sql;
    static OwnerPaymentHistoryRepository repository;
    static Map<String,Object> data(Object... pairs){Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value;}
    @BeforeClass public static void setup()throws Exception {
        String db=System.getProperty("schema.audit","");if(!db.matches("audit_schema_[a-f0-9]{12}"))throw new IllegalArgumentException("Disposable schema required");
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:13306/"+db+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false","root","");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");sql=new JdbcTemplate(source);
        Configuration config=new Configuration(new Environment("history",new SpringManagedTransactionFactory(),source));
        try(InputStream stream=Files.newInputStream(Paths.get(System.getProperty("schema.mapper.root"),"fee/OwnerPaymentHistoryMapper.xml"))){new XMLMapperBuilder(stream,config,"history",config.getSqlFragments()).parse();}
        repository=new OwnerPaymentHistoryRepository();repository.setSessionTemplate(new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config)));
    }
    static void insert(String table,Object... pairs) {
        Map<String,Object> values=data(pairs),row=new LinkedHashMap<>();
        for(Map<String,Object> column:sql.queryForList("SHOW COLUMNS FROM `"+table+"`")) {
            String name=column.get("Field").toString(),type=column.get("Type").toString();
            if(values.containsKey(name)){row.put(name,values.get(name));continue;}
            if("YES".equals(column.get("Null"))||column.get("Default")!=null||String.valueOf(column.get("Extra")).contains("auto_increment"))continue;
            row.put(name,type.contains("date")||type.contains("timestamp")?"2026-11-01 00:00:00":type.contains("int")||type.contains("decimal")?"1":"x");
        }
        sql.update("INSERT INTO `"+table+"` (`"+String.join("`,`",row.keySet())+"`) VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
    }
    @Before public void reset() {
        for(String table:Arrays.asList("pay_fee_detail","pay_fee","pay_fee_attrs","pay_fee_config","owner_app_user","building_owner","building_owner_room_rel","building_room","building_unit","f_floor","owner_car"))sql.update("DELETE FROM `"+table+"`");
        insert("building_owner","owner_id","root","member_id","family","community_id","community");
        insert("owner_app_user","app_user_id","binding","member_id","family","user_id","user","community_id","community","state","12000");
        insert("building_owner_room_rel","rel_id","rel","owner_id","root","room_id","room","state","2001");
        insert("f_floor","floor_id","floor","community_id","community","floor_num","3");
        insert("building_unit","unit_id","unit","floor_id","floor","unit_num","2");
        insert("building_room","room_id","room","community_id","community","unit_id","unit","room_num","101");
        insert("pay_fee_config","config_id","config","community_id","community","fee_name","Property fee");
        insert("pay_fee","fee_id","fee","community_id","community","payer_obj_type","3333","payer_obj_id","room","config_id","config","state","2009001");
        paid("a","fee","0.01");paid("b","fee","30.00");
    }
    static void paid(String id,String fee,String amount){insert("pay_fee_detail","detail_id",id,"fee_id",fee,"community_id","community","received_amount",amount,"receivable_amount",amount,"state","1400","create_time","2026-11-06 10:00:00");}
    static Map<String,Object> request(){return data("userId","user","communityId","community","page",1,"row",20);}
    static int total(Map<String,Object> q){return ((Number)repository.history(q).get("total")).intValue();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> result){return (List<Map<String,Object>>)result.get("feeDetails");}
    @Test public void completedFeesWithoutLegacyOwnerAttributeShowEachActualPayment(){
        Map<String,Object> result=repository.history(request());assertEquals(2,((Number)result.get("total")).intValue());
        assertEquals("b",rows(result).get(0).get("detailId"));assertEquals("3-2-101",rows(result).get(0).get("payerObjName"));
        assertEquals("2026-11-06 10:00:00",rows(result).get(0).get("createTime"));assertEquals("0.01",rows(result).get(1).get("receivedAmount").toString());
    }
    @Test public void pendingForeignCommunityAndUnboundCannotRead(){
        Map<String,Object> q=request();q.put("userId","other");assertEquals(0,total(q));q=request();q.put("communityId","other");assertEquals(0,total(q));
        sql.update("UPDATE owner_app_user SET state='10000'");assertEquals(0,total(request()));
        sql.update("UPDATE owner_app_user SET state='12000'");sql.update("UPDATE building_owner_room_rel SET status_cd='1'");assertEquals(0,total(request()));
    }
    @Test public void foreignRoomAndFeeCannotRead(){
        insert("pay_fee","fee_id","other","community_id","community","payer_obj_type","3333","payer_obj_id","foreign-room","config_id","config");paid("foreign","other","99.00");
        assertEquals(2,total(request()));Map<String,Object> q=request();q.put("feeId","other");assertEquals(0,total(q));
    }
    @Test public void duplicateBindingsAndAttributesNeverDuplicatePayments(){
        insert("owner_app_user","app_user_id","binding2","member_id","family","user_id","user","community_id","community","state","12000");
        for(String id:Arrays.asList("attr1","attr2"))insert("pay_fee_attrs","attr_id",id,"fee_id","fee","spec_cd","390002","value","Imported fee");
        Map<String,Object> result=repository.history(request());assertEquals(2,rows(result).size());assertEquals(2,total(request()));assertEquals("Imported fee",rows(result).get(0).get("feeName"));
    }
    @Test public void manualAndOnlineRecordsPaginateWithoutOverlap(){
        Map<String,Object> q=request();q.put("row",1);Map<String,Object> first=repository.history(q);q.put("page",2);Map<String,Object> second=repository.history(q);
        assertEquals("b",rows(first).get(0).get("detailId"));assertEquals("a",rows(second).get(0).get("detailId"));assertEquals(2L,first.get("records"));
    }
    @Test public void deletedAndRefundedRecordsAreExcluded(){sql.update("UPDATE pay_fee_detail SET state='1100' WHERE detail_id='a'");assertEquals(1,total(request()));sql.update("UPDATE pay_fee_detail SET status_cd='1'");assertEquals(0,total(request()));}
    @Test public void multipleRoomsAndFamilyCarAreScopedByCorrectOwnerIdentity(){
        insert("building_room","room_id","room2","community_id","community","unit_id","unit","room_num","102");
        insert("building_owner_room_rel","rel_id","rel2","owner_id","root","room_id","room2","state","2001");
        insert("pay_fee","fee_id","fee2","community_id","community","payer_obj_type","3333","payer_obj_id","room2","config_id","config");paid("c","fee2","5.00");
        insert("owner_car","car_id","car","owner_id","family","community_id","community","car_num","TEST123");
        insert("pay_fee","fee_id","car-fee","community_id","community","payer_obj_type","6666","payer_obj_id","car","config_id","config");paid("d","car-fee","6.00");
        Map<String,Object> result=repository.history(request());assertEquals(4,rows(result).size());assertEquals("TEST123",rows(result).get(0).get("payerObjName"));
        sql.update("UPDATE owner_car SET owner_id='root'");assertEquals(3,total(request()));
    }
    @Test public void scopeAndPaginationAreMandatoryAndFiltersCannotOverride(){
        Map<String,Object> q=request();q.put("state","1100");q.put("ownerId","other");assertEquals(2,total(q));
        for(Map<String,Object> invalid:Arrays.asList(data("userId","user"),data("userId","user","communityId","community","row",10000),data("userId","user","communityId","community","page",0))) {
            try{repository.history(invalid);fail("Expected validation");}catch(IllegalArgumentException expected){}
        }
    }
}
