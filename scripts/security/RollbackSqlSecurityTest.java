import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.java110.db.Java110MybatisInterceptor;
import com.java110.dto.order.OrderItemDto;
import com.java110.order.smo.impl.OIdServiceSMOImpl;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import java.sql.*;
import java.util.*;

public class RollbackSqlSecurityTest {
    private static final String HOSTILE = "中文 O'Brien $1 ? \\path\n' OR 1=1 -- ";
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static Map<String,Object> row(Object... values) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]);
        return result;
    }
    private static BoundSql bound(Configuration config, String sql, Map<String,Object> params, String... fields) {
        List<ParameterMapping> mappings=new ArrayList<>();
        for(String field:fields) mappings.add(new ParameterMapping.Builder(config,field,Object.class).build());
        return new BoundSql(config,sql,mappings,params);
    }
    private static String fallback(String action, Map<String,Object> before, Map<String,Object> after) throws Exception {
        JSONArray pre=new JSONArray();if(before!=null)pre.add(before);
        JSONArray post=new JSONArray();if(after!=null)post.add(after);
        JSONObject log=new JSONObject();log.put("preValue",pre);log.put("afterValue",post);
        OrderItemDto item=new OrderItemDto();item.setAction(action);item.setActionObj("audit_rows");item.setLogText(log.toJSONString());
        Method method=OIdServiceSMOImpl.class.getDeclaredMethod("generateParam",OrderItemDto.class);method.setAccessible(true);
        JSONArray result=(JSONArray)method.invoke(new OIdServiceSMOImpl(),item);
        require(result.size()==1,"Expected exactly one rollback statement");
        return result.getJSONObject(0).getString("fallBackSql");
    }
    private static Map<String,Object> encoded(Java110MybatisInterceptor interceptor,Map<String,Object> input)throws Exception {
        Method method=Java110MybatisInterceptor.class.getDeclaredMethod("dealReturnMap",Map.class);method.setAccessible(true);
        method.invoke(interceptor,input);return input;
    }
    private static MappedStatement mapped(Configuration config, String sql, SqlCommandType type, String... fields) {
        List<ParameterMapping> mappings=new ArrayList<>();
        for(String field:fields)mappings.add(new ParameterMapping.Builder(config,field,Object.class).build());
        return new MappedStatement.Builder(config,"audit."+UUID.randomUUID(),new StaticSqlSource(config,sql,mappings),type).build();
    }
    private static void execute(Connection connection,MappedStatement mapped,Map<String,Object> params)throws Exception {
        BoundSql bound=mapped.getBoundSql(params);
        try(PreparedStatement statement=connection.prepareStatement(bound.getSql())) {
            new DefaultParameterHandler(mapped,params,bound).setParameters(statement);statement.executeUpdate();
        }
    }
    private static JSONArray fallback(JSONObject snapshot)throws Exception {
        OrderItemDto item=new OrderItemDto();item.setAction(snapshot.getString("action"));item.setActionObj(snapshot.getString("table"));item.setLogText(snapshot.toJSONString());
        Method method=OIdServiceSMOImpl.class.getDeclaredMethod("generateParam",OrderItemDto.class);method.setAccessible(true);
        return (JSONArray)method.invoke(new OIdServiceSMOImpl(),item);
    }
    private static void verifyFeeSearch(Connection connection)throws Exception {
        Configuration configuration=new Configuration();
        try(InputStream stream=Files.newInputStream(Paths.get(System.getProperty("fee.config.mapper")))) {
            new XMLMapperBuilder(stream,configuration,"fee-config",configuration.getSqlFragments()).parse();
        }
        try(Statement statement=connection.createStatement()) {
            statement.execute("create table pay_fee_config(fee_type_cd varchar(20),fee_flag varchar(20),bill_type varchar(20),fee_name varchar(200),community_id varchar(20),status_cd varchar(1))");
            statement.execute("create table t_dict(table_name varchar(30),table_columns varchar(30),status_cd varchar(20))");
            statement.execute("insert into t_dict values('pay_fee_config','fee_type_cd','type'),('pay_fee_config','fee_flag','flag'),('pay_fee_config','bill_type','bill')");
        }
        try(PreparedStatement insert=connection.prepareStatement("insert into pay_fee_config values('type','flag','bill',?,?,'0')")) {
            for(String[] fixture:new String[][]{{"O'Brien service","owned"},{"正常物业费","owned"},{"O'Brien service","other"}}) {
                insert.setString(1,fixture[0]);insert.setString(2,fixture[1]);insert.executeUpdate();
            }
        }
        MappedStatement mapped=configuration.getMappedStatement("feeConfigServiceDaoImpl.queryFeeConfigsCount");
        for(String mode:new String[]{"","NO_BACKSLASH_ESCAPES"}) {
            try(Statement statement=connection.createStatement()){statement.execute("set session sql_mode='"+mode+"'");}
            String[] searches={"O'Brien","正常物业",HOSTILE,"missing' OR 1=1 -- ","%","unknown?\\$1"};
            int[] expected={1,1,0,0,2,0};
            for(int i=0;i<searches.length;i++) {
                Map<String,Object> params=row("feeName",searches[i],"communityId","owned","statusCd","0");
                BoundSql bound=mapped.getBoundSql(params);
                try(PreparedStatement query=connection.prepareStatement(bound.getSql())) {
                    new DefaultParameterHandler(mapped,params,bound).setParameters(query);
                    try(ResultSet rows=query.executeQuery()){require(rows.next()&&rows.getInt(1)==expected[i],"Fee search changed tenant or text semantics");}
                }
            }
        }
    }
    public static void main(String[] args)throws Exception {
        Configuration config=new Configuration();
        Java110MybatisInterceptor interceptor=new Java110MybatisInterceptor();
        Map<String,Object> values=new LinkedHashMap<>();
        String rendered=interceptor.showSql(config,bound(config,"update audit_rows set text_value=? where id=?",
            row("text",HOSTILE,"id","selected"),"text","id"),values,SqlCommandType.UPDATE);
        require(!rendered.contains(HOSTILE),"Untrusted text entered executable rollback SQL");
        String database=System.getProperty("schema.audit","");
        require(database.matches("ops_restore_[a-f0-9]{12}"),"Invalid isolated schema");
        Class.forName("com.mysql.cj.jdbc.Driver");
        try(Connection connection=DriverManager.getConnection("jdbc:mysql://127.0.0.1:13306/"+database+"?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&useServerPrepStmts=true","root","");
            Statement statement=connection.createStatement()) {
            verifyFeeSearch(connection);
            statement.execute("create table audit_rows(id varchar(200) primary key,text_value text,note text null)");
            for(String mode:new String[]{"","NO_BACKSLASH_ESCAPES"}) {
                statement.execute("set session sql_mode='"+mode+"'");
                statement.executeUpdate("delete from audit_rows");
                try(PreparedStatement insert=connection.prepareStatement("insert into audit_rows values(?,?,?)")) {
                    insert.setString(1,"selected");insert.setString(2,HOSTILE);insert.setNull(3,Types.VARCHAR);insert.executeUpdate();
                    insert.setString(1,"other");insert.setString(2,"untouched");insert.setString(3,"");insert.executeUpdate();
                }
                Map<String,Object> before=encoded(interceptor,row("id","selected","text_value",HOSTILE,"note",null));
                Map<String,Object> after=encoded(interceptor,row("id","selected","text_value","new $ ? \\ 文本","note",""));
                try(PreparedStatement update=connection.prepareStatement("update audit_rows set text_value=?,note='' where id='selected'")) {
                    update.setString(1,"new $ ? \\ 文本");update.executeUpdate();
                }
                require(statement.executeUpdate(fallback("MOD",before,after))==1,"Rollback must restore only selected row");
                try(ResultSet result=statement.executeQuery("select text_value,note from audit_rows where id='selected'")) {
                    require(result.next()&&HOSTILE.equals(result.getString(1))&&result.getObject(2)==null,"Roundtrip lost text or NULL");
                }
                require(statement.executeUpdate(fallback("ADD",null,before))==1,"Insert compensation failed");
                require(statement.executeUpdate(fallback("DEL",before,null))==1,"Delete compensation failed");
                Map<String,Object> legacy=row("id","'selected'","text_value","'"+HOSTILE+"'","note","''");
                statement.executeUpdate("delete from audit_rows where id='selected'");
                require(statement.executeUpdate(fallback("DEL",legacy,null))==1,"Legacy literal log failed");
                try(ResultSet result=statement.executeQuery("select text_value from audit_rows where id='selected'")) {
                    require(result.next()&&HOSTILE.equals(result.getString(1)),"Legacy log lost raw special characters");
                }
                try(ResultSet result=statement.executeQuery("select text_value from audit_rows where id='other'")) {
                    require(result.next()&&"untouched".equals(result.getString(1)),"Unrelated row changed");
                }
                MappedStatement update=mapped(config,"update audit_rows t set t.text_value=?,t.note=concat('literal ?',?) where t.id=?",SqlCommandType.UPDATE,"text","note","id");
                Map<String,Object> params=row("text",HOSTILE,"note","a,b' ?","id","selected");
                JSONObject capture=interceptor.captureLog(update,params,connection);
                require(capture.getJSONArray("preValue").size()==1,"Bound old-row query widened its predicate");
                execute(connection,update,params);
                require(statement.executeUpdate(fallback(capture).getJSONObject(0).getString("fallBackSql"))==1,"SET expression projection did not match updated row");
                params.put("id",HOSTILE);
                require(interceptor.captureLog(update,params,connection).getJSONArray("preValue").isEmpty(),"Hostile identifier changed WHERE structure");
                MappedStatement delete=mapped(config,"delete from audit_rows where id=?",SqlCommandType.DELETE,"id");
                JSONObject deleted=interceptor.captureLog(delete,row("id","selected"),connection);
                execute(connection,delete,row("id","selected"));
                require(statement.executeUpdate(fallback(deleted).getJSONObject(0).getString("fallBackSql"))==1,"Actual delete compensation failed");
                MappedStatement batch=mapped(config,"insert into audit_rows(id,text_value,note) values(?,?,NULL),(?,?,?)",SqlCommandType.INSERT,"id1","text1","id2","text2","note2");
                Map<String,Object> batchValues=row("id1","batch-one","text1",HOSTILE,"id2","batch-two","text2","?,$\\\n中文","note2","");
                JSONObject inserted=interceptor.captureLog(batch,batchValues,connection);
                require(inserted.getJSONArray("afterValue").size()==2,"Batch INSERT lost rows");
                execute(connection,batch,batchValues);
                for(Object command:fallback(inserted))require(statement.executeUpdate(((JSONObject)command).getString("fallBackSql"))==1,"Batch compensation failed");
                MappedStatement copy=mapped(config,"insert into audit_rows(id,text_value,note) select ?,text_value,note from audit_rows where id=?",SqlCommandType.INSERT,"newId","oldId");
                Map<String,Object> copyValues=row("newId","copied","oldId","selected");
                JSONObject copied=interceptor.captureLog(copy,copyValues,connection);
                execute(connection,copy,copyValues);
                require(statement.executeUpdate(fallback(copied).getJSONObject(0).getString("fallBackSql"))==1,"INSERT SELECT compensation failed");
            }
        }
        boolean rejected=false;
        try { encoded(interceptor,row("bad",new Object(){public String toString(){return "0 OR 1=1";}})); }
        catch(java.lang.reflect.InvocationTargetException expected){rejected=expected.getCause() instanceof IllegalArgumentException;}
        require(rejected,"Unknown value types must be rejected");
        System.out.println("PASS: 12 actual fee-search checks, bound snapshots, SET expressions, batch VALUES, INSERT SELECT, all rollback actions, legacy logs, NULL and unknown-type rejection under default/NO_BACKSLASH_ESCAPES; isolated schema only");
    }
}
