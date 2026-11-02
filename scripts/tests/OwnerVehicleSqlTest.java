import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Generate the real mapper SQL: a hostile search string must remain a JDBC value. */
public class OwnerVehicleSqlTest {
    private void check(String statement)throws Exception {
        Configuration config=new Configuration();
        try(InputStream input=Files.newInputStream(Paths.get(System.getProperty("owner.car.mapper")))) {
            new XMLMapperBuilder(input,config,"ownerCar.xml",config.getSqlFragments()).parse();
        }
        String hostile="' OR 1=1 OR bow.name LIKE '";
        Map<String,Object> params=new HashMap<>();params.put("ownerId","own-member");params.put("communityId","own-community");
        params.put("ownerName",hostile);params.put("page",0);params.put("row",10);
        BoundSql bound=config.getMappedStatement("ownerCarServiceDaoImpl."+statement).getBoundSql(params);
        assertFalse("Search text was interpolated into executable SQL",bound.getSql().contains(hostile));
        assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->"ownerName".equals(mapping.getProperty())));
        assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->"ownerId".equals(mapping.getProperty())));
        assertTrue(bound.getParameterMappings().stream().anyMatch(mapping->"communityId".equals(mapping.getProperty())));
    }
    @Test public void listBindsOwnerSearch()throws Exception{check("getOwnerCarInfo");}
    @Test public void countBindsOwnerSearch()throws Exception{check("queryOwnerCarsCount");}
}
