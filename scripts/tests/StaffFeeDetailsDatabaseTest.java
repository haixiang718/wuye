import com.alibaba.fastjson.JSONObject;
import com.java110.db.dao.IQueryServiceDAO;
import com.java110.entity.service.ServiceSql;
import com.java110.service.context.DataQuery;
import com.java110.service.smo.impl.QueryServiceSMOImpl;
import org.junit.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;
import com.alibaba.druid.pool.DruidDataSource;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import javax.sql.DataSource;

/** Runs the deployed query artifact through the real dynamic-SQL engine and MySQL. */
public class StaffFeeDetailsDatabaseTest {
    private Connection db;
    private Connection queryDb;
    private AutoCloseable sharding;
    private QueryServiceSMOImpl engine;
    private JSONObject artifact;
    private static final String[] TABLES={"pay_fee_detail","c_business","c_orders","pay_fee","pay_fee_config",
        "u_user","s_store_user","t_dict","building_room","building_unit","f_floor","owner_car"};

    @Before public void setup() throws Exception {
        for(String property:new String[]{"schema.audit","schema.empty"}) {
            String schema=System.getProperty(property,"");
            if(!schema.matches("audit_schema_[a-f0-9]{12}"))throw new IllegalStateException("Disposable schemas only");
        }
        if(System.getProperty("schema.audit").equals(System.getProperty("schema.empty")))throw new IllegalStateException("Schemas must be distinct");
        db=DriverManager.getConnection("jdbc:mysql://127.0.0.1:13306/"+System.getProperty("schema.audit")+
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai","root","");
        Map<String,DataSource> sources=new LinkedHashMap<>();
        for(String source:new String[]{"ds0","ds1"}) {
            DruidDataSource ds=new DruidDataSource();
            ds.setUrl("jdbc:mysql://127.0.0.1:13306/"+System.getProperty(source.equals("ds1")?"schema.audit":"schema.empty")+
                "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
            ds.setUsername("root");ds.setPassword("");ds.setInitialSize(0);ds.setMinIdle(0);ds.setMaxActive(2);
            sources.put(source,ds);
        }
        ShardingRuleConfiguration rule=new ShardingRuleConfiguration();rule.setDefaultDataSourceName("ds1");
        DataSource routed=ShardingDataSourceFactory.createDataSource(sources,rule,new Properties());
        sharding=(AutoCloseable)routed;queryDb=routed.getConnection();
        for(String table:TABLES) db.createStatement().execute("DELETE FROM "+table);
        artifact=JSONObject.parseObject(new String(Files.readAllBytes(Paths.get(System.getProperty("query.artifact"))),"UTF-8"));
        engine=new QueryServiceSMOImpl();
        Field field=QueryServiceSMOImpl.class.getDeclaredField("queryServiceDAOImpl");field.setAccessible(true);
        field.set(engine,Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{IQueryServiceDAO.class},(proxy,m,args)->{
            if(!m.getName().equals("executeSql")) throw new AssertionError("Only SELECT is expected");
            try(PreparedStatement statement=queryDb.prepareStatement((String)args[0])) {
                Object[] params=(Object[])args[1];for(int i=0;i<params.length;i++)statement.setObject(i+1,params[i]);
                List<Map<String,Object>> rows=new ArrayList<>();
                try(ResultSet rs=statement.executeQuery()) {
                    while(rs.next()) {Map<String,Object> row=new LinkedHashMap<>();
                        for(int i=1;i<=rs.getMetaData().getColumnCount();i++)row.put(rs.getMetaData().getColumnLabel(i),rs.getObject(i));
                        rows.add(row);
                    }
                }return rows;
            } catch(Exception error) {error.printStackTrace();throw error;}
        }));
        insert("u_user","user_id","staff","name","Collector");
        insert("s_store_user","store_user_id","rel1","store_id","store","user_id","staff");
        insert("s_store_user","store_user_id","rel2","store_id","store","user_id","staff");
        insert("c_orders","o_id","order1","user_id","staff");
        insert("c_business","b_id","business1","o_id","order1");
        insert("pay_fee_config","config_id","config","community_id","community","fee_name","Electricity");
        insert("pay_fee","fee_id","fee1","community_id","community","config_id","config","payer_obj_id","room1","payer_obj_type","3333");
        insert("f_floor","floor_id","floor1","community_id","community","floor_num","3");
        insert("building_unit","unit_id","unit1","floor_id","floor1","unit_num","2");
        insert("building_room","room_id","room1","community_id","community","unit_id","unit1","room_num","101");
        insert("t_dict","status_cd","1","table_name","pay_fee_detail","table_columns","prime_rate","name","Cash");
        payment("a","2026-11-02 00:00:00","10.00");
        payment("b","2026-11-02 23:59:59","20.00");
        payment("c","2026-11-03 00:00:00","30.00");
    }
    @After public void close() throws Exception {if(queryDb!=null)queryDb.close();if(sharding!=null)sharding.close();if(db!=null)db.close();}

    private void insert(String table,String... pairs) throws Exception {
        Map<String,Object> values=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)values.put(pairs[i],pairs[i+1]);
        try(ResultSet cols=db.createStatement().executeQuery("SHOW COLUMNS FROM "+table)) {
            while(cols.next()) {String name=cols.getString("Field"),type=cols.getString("Type");
                if(values.containsKey(name)||!cols.getString("Null").equals("NO")||cols.getString("Default")!=null||cols.getString("Extra").contains("auto_increment"))continue;
                values.put(name,type.contains("date")||type.contains("timestamp")?"2026-11-01 00:00:00":
                    type.contains("int")||type.contains("decimal")?"0":"x");
            }
        }
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO "+table+" (`"+String.join("`,`",values.keySet())+"`) VALUES ("+
                String.join(",",Collections.nCopies(values.size(),"?"))+")")) {
            int i=1;for(Object value:values.values())ps.setObject(i++,value);ps.executeUpdate();
        }
    }
    private void payment(String id,String time,String amount) throws Exception {
        insert("pay_fee_detail","detail_id",id,"b_id","business1","fee_id","fee1","community_id","community",
            "cycles","1","receivable_amount",amount,"received_amount",amount,"prime_rate","1","create_time",time);
    }
    private JSONObject query(String... pairs) throws Exception {
        JSONObject params=new JSONObject();params.put("communityId","community");params.put("storeId","store");
        params.put("page",0);params.put("row",30);params.put("userCode","");params.put("startTime","");params.put("endTime","");
        for(int i=0;i<pairs.length;i+=2)params.put(pairs[i],pairs[i+1]);
        DataQuery data=new DataQuery();data.setRequestParams(params);
        ServiceSql sql=new ServiceSql();sql.setSql(artifact.getJSONObject("queries").toJSONString());
        sql.setTemplate(artifact.getJSONObject("template").toJSONString());data.setServiceSql(sql);
        Method method=QueryServiceSMOImpl.class.getDeclaredMethod("doExecuteSql",DataQuery.class);method.setAccessible(true);
        method.invoke(engine,data);
        return data.getResponseInfo();
    }
    @Test public void separatePaymentsAndDuplicateMemberships() throws Exception {
        JSONObject result=query();assertEquals(3,result.getIntValue("total"));assertEquals(3,result.getJSONArray("staffFees").size());
        JSONObject first=result.getJSONArray("staffFees").getJSONObject(0);
        assertEquals("c",first.getString("detailId"));assertEquals("2026-11-03 00:00:00",first.getString("createTime"));
        assertEquals("3-2-101",first.getString("payObjName"));assertEquals("Cash",first.getString("primeRateName"));
    }
    @Test public void datesIncludeTheWholeEndDay() throws Exception {
        JSONObject result=query("startTime","2026-11-02","endTime","2026-11-02");assertEquals(2,result.getIntValue("total"));
        assertEquals("b",result.getJSONArray("staffFees").getJSONObject(0).getString("detailId"));
    }
    @Test public void pagesDoNotOverlap() throws Exception {
        JSONObject first=query("row","2","page","0"),second=query("row","2","page","2");
        assertEquals(2,first.getJSONArray("staffFees").size());assertEquals(1,second.getJSONArray("staffFees").size());
        assertEquals("a",second.getJSONArray("staffFees").getJSONObject(0).getString("detailId"));assertEquals(3,second.getIntValue("total"));
    }
    @Test public void storeCommunityAndEmployeeFilters() throws Exception {
        for(String key:new String[]{"storeId","communityId","userCode"}) {
            JSONObject result=query(key,"other");assertEquals(0,result.getIntValue("total"));assertEquals(0,result.getJSONArray("staffFees").size());
        }
        assertEquals(3,query("userCode","staff").getIntValue("total"));
    }
    @Test public void deletedAndRefundingRecordsAreExcluded() throws Exception {
        db.createStatement().execute("UPDATE pay_fee_detail SET state='1000' WHERE detail_id='a'");
        db.createStatement().execute("UPDATE pay_fee_detail SET status_cd='1' WHERE detail_id='b'");
        assertEquals(1,query().getIntValue("total"));
        db.createStatement().execute("UPDATE pay_fee SET status_cd='1'");assertEquals(0,query().getIntValue("total"));
    }
    @Test public void samePaymentDifferentFeeItemsStaySeparate() throws Exception {
        insert("pay_fee_config","config_id","water","community_id","community","fee_name","Water");
        insert("pay_fee","fee_id","fee2","community_id","community","config_id","water","payer_obj_id","room1");
        payment("d","2026-11-03 00:00:00","4.50");db.createStatement().execute("UPDATE pay_fee_detail SET fee_id='fee2' WHERE detail_id='d'");
        JSONObject result=query();assertEquals(4,result.getIntValue("total"));
        assertEquals("Water",result.getJSONArray("staffFees").getJSONObject(0).getString("feeName"));
        assertEquals(0,result.getJSONArray("staffFees").stream().map(x->((JSONObject)x).getBigDecimal("receivedAmount"))
            .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add).compareTo(new java.math.BigDecimal("64.50")));
    }
    @Test public void duplicatePaymentDictionaryEntriesDoNotDuplicateAmounts() throws Exception {
        insert("t_dict","status_cd","1","table_name","pay_fee_detail","table_columns","prime_rate","name","Cash duplicate");
        JSONObject result=query();assertEquals(3,result.getIntValue("total"));assertEquals(3,result.getJSONArray("staffFees").size());
        assertEquals("30.00",result.getJSONArray("staffFees").getJSONObject(0).getBigDecimal("receivedAmount").toPlainString());
    }
    @Test public void vehiclesAndMissingRoomsHaveIdentifiablePaymentObjects() throws Exception {
        db.createStatement().execute("UPDATE pay_fee SET payer_obj_id='missing-room'");
        assertEquals("missing-room",query().getJSONArray("staffFees").getJSONObject(0).getString("payObjName"));
        insert("owner_car","car_id","car1","community_id","community","car_num","TEST123");
        db.createStatement().execute("UPDATE pay_fee SET payer_obj_id='car1',payer_obj_type='6666'");
        assertEquals("TEST123",query().getJSONArray("staffFees").getJSONObject(0).getString("payObjName"));
    }
}
