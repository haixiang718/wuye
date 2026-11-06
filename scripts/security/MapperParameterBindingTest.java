import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import java.sql.PreparedStatement;

/** Real MyBatis rendering and JDBC binding, with no connection or database access. */
public final class MapperParameterBindingTest {
    private static final String[][] CASES = {
        {"community/InspectionPlanServiceDaoImplMapper.xml", "inspectionPlanServiceDaoImpl", "getInspectionPlanInfo,queryInspectionPlansCount", "inspectionPlanName"},
        {"community/InspectionTaskServiceDaoImplMapper.xml", "inspectionTaskServiceDaoImpl", "getInspectionTaskInfo,queryInspectionTasksCount", "planUserName"},
        {"community/RepairReturnVisitServiceDaoImplMapper.xml", "repairReturnVisitServiceDaoImpl", "getRepairReturnVisitInfo,queryRepairReturnVisitsCount", "repairName"},
        {"community/RepairServiceDaoImplMapper.xml", "repairServiceDaoImpl", "getRepairInfo,queryRepairsCount", "repairName"},
        {"community/RepairSettingServiceDaoImplMapper.xml", "repairSettingServiceDaoImpl", "getRepairSettingInfo,queryRepairSettingsCount", "repairTypeName"},
        {"community/RoomRenovationServiceDaoImplMapper.xml", "roomRenovationServiceDaoImpl", "getRoomRenovationInfo,queryRoomRenovationsCount", "personName"},
        {"fee/FeeConfigServiceDaoImplMapper.xml", "feeConfigServiceDaoImpl", "getBusinessFeeConfigInfo,getFeeConfigInfo,queryFeeConfigsCount", "feeName"},
        {"report/ReportFeeMonthStatisticsServiceDaoImplMapper.xml", "reportFeeMonthStatisticsServiceDaoImpl", "getFeeConfigInfo", "feeName"},
        {"report/ReportFeeServiceDaoImplMapper.xml", "reportFeeServiceDaoImpl", "getFeeConfigs", "feeName"},
        {"store/AllocationStorehouseServiceDaoImplMapper.xml", "allocationStorehouseServiceDaoImpl", "getAllocationStorehouseInfo,queryAllocationStorehousesCount", "resName"},
        {"store/AllocationUserStorehouseServiceDaoImplMapper.xml", "allocationUserStorehouseServiceDaoImpl", "getAllocationUserStorehouseInfo,queryAllocationUserStorehousesCount", "acceptUserName,resName,startUserName"},
        {"store/PurchaseApplyDetailServiceDaoImplMapper.xml", "purchaseApplyDetailServiceDaoImpl", "getPurchaseApplyDetailInfo,queryPurchaseApplyDetailsCount", "resName,userName,endUserName"},
        {"store/ResourceStoreSpecificationServiceDaoImplMapper.xml", "resourceStoreSpecificationServiceDaoImpl", "getResourceStoreSpecificationInfo,queryResourceStoreSpecificationsCount", "specName"},
        {"store/ResourceStoreTypeServiceDaoImplMapper.xml", "resourceResourceStoreTypeTypeServiceDaoImpl", "getResourceStoreTypeInfo,queryResourceStoreTypesCount", "name"},
        {"store/ResourceStoreUseRecordServiceDaoImplMapper.xml", "resourceResourceStoreUseRecordUseRecordServiceDaoImpl", "getResourceStoreUseRecordInfo,queryResourceStoreUseRecordsCount", "createUserName"},
        {"store/UserStorehouseServiceDaoImplMapper.xml", "userStorehouseServiceDaoImpl", "getUserStorehouseInfo,queryUserStorehousesCount", "resName"},
        {"user/RoomRenovationsServiceDaoImplMapper.xml", "roomRenovationsServiceDaoImpl", "getRoomRenovationInfo,queryRoomRenovationsCount", "personName"}
    };
    private static final String[] VALUES = {
        "审计搜索", "审计' OR 1=1 OR 'x'='x", "O'Brien", "audit%wildcard",
        "audit_under_score", "audit\\path", "audit$?value", "audit\nvalue"
    };

    private static Map<String, Object> parameters(String[] fields, String value) {
        Map<String, Object> params = new HashMap<>();
        params.put("communityId", "audit-community");
        params.put("statusCd", "0");
        params.put("page", 0);
        params.put("row", 10);
        for (String field : fields) params.put(field, value);
        return params;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        int statements = 0;
        int boundValues = 0;
        for (String[] testCase : CASES) {
            Configuration config = new Configuration();
            config.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            try (InputStream input = Files.newInputStream(root.resolve(testCase[0]))) {
                new XMLMapperBuilder(input, config, testCase[0], config.getSqlFragments()).parse();
            }
            String[] fields = testCase[3].split(",");
            for (String statement : testCase[2].split(",")) {
                String id = testCase[1] + "." + statement;
                MappedStatement mapped = config.getMappedStatement(id);
                String safeSql = mapped.getBoundSql(parameters(fields, "audit-search")).getSql();
                for (String value : VALUES) {
                    Map<String, Object> params = parameters(fields, value);
                    BoundSql bound = mapped.getBoundSql(params);
                    require(safeSql.equals(bound.getSql()), id + ": search input changed executable SQL");
                    final Map<Integer, Object> jdbcValues = new HashMap<>();
                    PreparedStatement jdbc = (PreparedStatement) Proxy.newProxyInstance(
                        MapperParameterBindingTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().startsWith("set") && arguments != null && arguments.length >= 2) {
                                jdbcValues.put((Integer) arguments[0], "setNull".equals(method.getName()) ? null : arguments[1]);
                            }
                            return null;
                        });
                    new DefaultParameterHandler(mapped, params, bound).setParameters(jdbc);
                    for (String field : fields) {
                        boolean found = false;
                        for (int index = 0; index < bound.getParameterMappings().size(); index++) {
                            ParameterMapping mapping = bound.getParameterMappings().get(index);
                            if (field.equals(mapping.getProperty())) {
                                require(value.equals(jdbcValues.get(index + 1)), id + ": JDBC value changed for " + field);
                                found = true;
                                boundValues++;
                            }
                        }
                        require(found, id + ": search parameter was not bound: " + field);
                    }
                }
                statements++;
            }
        }
        require(statements == 33, "Unexpected statement coverage: " + statements);
        require(boundValues == 328, "Unexpected binding coverage: " + boundValues);
        System.out.println("PASS: 17 mapper files, 33 statements, 41 search positions, 8 inputs, 328 JDBC bindings; no database connection");
    }
}
