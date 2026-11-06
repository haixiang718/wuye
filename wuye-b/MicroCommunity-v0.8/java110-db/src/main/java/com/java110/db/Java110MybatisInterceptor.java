package com.java110.db;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlUpdateStatement;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.java110.core.client.RestTemplate;
import com.java110.core.factory.Java110TransactionalFactory;
import com.java110.dto.order.OrderItemDto;
import com.java110.utils.constant.ServiceConstant;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.factory.LogicalServiceContext;
import com.java110.utils.util.RollbackSqlLiteral;
import com.java110.utils.util.StringUtil;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.springframework.http.*;
import java.sql.*;
import java.util.*;

/** Capture compensation data without converting bound parameters into executable SQL. */
@Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
public class Java110MybatisInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (StringUtil.isEmpty(Java110TransactionalFactory.getOId())) return invocation.proceed();
        MappedStatement mapped = (MappedStatement) invocation.getArgs()[0];
        Object parameter = invocation.getArgs()[1];
        Connection connection = ((Executor) invocation.getTarget()).getTransaction().getConnection();
        JSONObject log = captureLog(mapped, parameter, connection);
        OrderItemDto item = new OrderItemDto();
        item.setbId("-1");
        item.setAction(log.getString("action"));
        item.setActionObj(log.getString("table"));
        log.remove("action");
        log.remove("table");
        item.setLogText(log.toJSONString());
        item.setServiceName(LogicalServiceContext.resolve(ApplicationContextFactory.getApplicationName()));
        item.setoId(Java110TransactionalFactory.getOId());
        RestTemplate rest = ApplicationContextFactory.getBean("restTemplate", RestTemplate.class);
        ResponseEntity<String> response = rest.exchange(ServiceConstant.SERVICE_ORDER_URL + "/order/oIdApi/createOrderItem",
            HttpMethod.POST, new HttpEntity<String>(item.toString(), new HttpHeaders()), String.class);
        if (response.getStatusCode() != HttpStatus.OK) throw new IllegalArgumentException("Unable to register compensation log");
        return invocation.proceed();
    }

    /** Uses the Executor transaction connection and the original MyBatis type handlers. */
    public JSONObject captureLog(MappedStatement mapped, Object parameter, Connection connection) throws SQLException {
        BoundSql original = mapped.getBoundSql(parameter);
        List<SQLStatement> statements = SQLUtils.parseStatements(original.getSql(), "mysql");
        if (statements.size() != 1) throw new IllegalArgumentException("Compensation requires one SQL statement");
        SQLStatement statement = statements.get(0);
        JSONArray before = new JSONArray();
        JSONArray after = new JSONArray();
        String table;
        String action;
        if (statement instanceof SQLUpdateStatement) {
            SQLUpdateStatement update = (SQLUpdateStatement) statement;
            if (update.getWhere() == null || update.getFrom() != null) throw new IllegalArgumentException("Unsupported compensation UPDATE");
            table = tableName(update.getTableSource());
            List<String> columns = new ArrayList<>();
            Query query = new Query(original, mapped);
            String alias = update.getTableSource().getAlias();
            query.sql.append("SELECT ").append(alias == null ? RollbackSqlLiteral.identifier(table) : RollbackSqlLiteral.identifier(alias)).append(".*");
            for (SQLUpdateSetItem item : update.getItems()) {
                columns.add(columnName(item.getColumn()));
                query.sql.append(", ");
                query.append(item.getValue());
                query.sql.append(" AS `__java110_rollback_after_").append(columns.size() - 1).append("`");
            }
            query.sql.append(" FROM "); query.append(update.getTableSource());
            query.sql.append(" WHERE "); query.append(update.getWhere());
            if (update.getOrderBy() != null) { query.sql.append(' '); query.append(update.getOrderBy()); }
            if (update instanceof MySqlUpdateStatement && ((MySqlUpdateStatement) update).getLimit() != null) {
                query.sql.append(' '); query.append(((MySqlUpdateStatement) update).getLimit());
            }
            query.sql.append(" FOR UPDATE");
            for (Map<String,Object> row : query.rows(connection)) {
                Map<String,Object> projected = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    String column = columns.get(i);
                    for (String existing : row.keySet()) if (existing.equalsIgnoreCase(column)) { column = existing; break; }
                    projected.put(column, row.remove("__java110_rollback_after_" + i));
                }
                Map<String,Object> next = new LinkedHashMap<>(row); next.putAll(projected);
                dealReturnMap(row); dealReturnMap(next);
                before.add(row); after.add(next);
            }
            action = "MOD";
        } else if (statement instanceof SQLDeleteStatement) {
            SQLDeleteStatement delete = (SQLDeleteStatement) statement;
            if (delete.getWhere() == null || delete.getFrom() != null || delete.getUsing() != null) throw new IllegalArgumentException("Unsupported compensation DELETE");
            table = tableName(delete.getTableSource());
            Query query = new Query(original, mapped);
            query.sql.append("SELECT * FROM "); query.append(delete.getTableSource());
            query.sql.append(" WHERE "); query.append(delete.getWhere());
            if (delete instanceof MySqlDeleteStatement) {
                MySqlDeleteStatement mysql = (MySqlDeleteStatement) delete;
                if (mysql.getOrderBy() != null) { query.sql.append(' '); query.append(mysql.getOrderBy()); }
                if (mysql.getLimit() != null) { query.sql.append(' '); query.append(mysql.getLimit()); }
            }
            query.sql.append(" FOR UPDATE");
            for (Map<String,Object> row : query.rows(connection)) { dealReturnMap(row); before.add(row); }
            action = "DEL";
        } else if (statement instanceof SQLInsertStatement) {
            SQLInsertStatement insert = (SQLInsertStatement) statement;
            table = tableName(insert.getTableSource());
            if (insert.getColumns().isEmpty() || (insert instanceof MySqlInsertStatement && !((MySqlInsertStatement) insert).getDuplicateKeyUpdate().isEmpty())) {
                throw new IllegalArgumentException("Compensation INSERT requires explicit columns and no upsert");
            }
            List<String> columns = new ArrayList<>();
            for (SQLExpr column : insert.getColumns()) columns.add(columnName(column));
            Query query = new Query(original, mapped);
            if (insert.getQuery() != null) {
                query.append(insert.getQuery());
            } else {
                for (SQLInsertStatement.ValuesClause tuple : insert.getValuesList()) {
                    if (tuple.getValues().size() != columns.size()) throw new IllegalArgumentException("Invalid INSERT column count");
                    if (query.sql.length() > 0) query.sql.append(" UNION ALL ");
                    query.sql.append("SELECT ");
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) query.sql.append(',');
                        query.append(tuple.getValues().get(i));
                        query.sql.append(" AS ").append(RollbackSqlLiteral.identifier(columns.get(i)));
                    }
                }
            }
            for (Map<String,Object> row : query.rows(connection, columns)) {
                dealReturnMap(row); after.add(row);
            }
            action = "ADD";
        } else {
            throw new IllegalArgumentException("Unsupported compensation SQL statement");
        }
        JSONObject result = new JSONObject();
        result.put("table", table); result.put("action", action);
        result.put("preValue", before); result.put("afterValue", after);
        return result;
    }

    private static String columnName(SQLExpr expression) {
        String name;
        if (expression instanceof SQLIdentifierExpr) name = ((SQLIdentifierExpr) expression).getName();
        else if (expression instanceof SQLPropertyExpr) name = ((SQLPropertyExpr) expression).getName();
        else throw new IllegalArgumentException("Invalid compensation column");
        String quoted = RollbackSqlLiteral.identifier(name);
        return quoted.substring(1, quoted.length() - 1);
    }

    private static String tableName(SQLTableSource source) {
        if (!(source instanceof SQLExprTableSource) || !(((SQLExprTableSource) source).getExpr() instanceof SQLIdentifierExpr)) {
            throw new IllegalArgumentException("Compensation requires a single local table");
        }
        return columnName(((SQLExprTableSource) source).getExpr());
    }

    private void dealReturnMap(Map<String,Object> row) {
        for (Map.Entry<String,Object> entry : row.entrySet()) entry.setValue(RollbackSqlLiteral.value(entry.getValue()));
    }

    private static final class Query {
        final StringBuilder sql = new StringBuilder();
        final List<ParameterMapping> mappings = new ArrayList<>();
        final BoundSql original;
        final MappedStatement mapped;
        Query(BoundSql original, MappedStatement mapped) { this.original = original; this.mapped = mapped; }
        void append(SQLObject part) {
            part.accept(new SQLASTVisitorAdapter() {
                @Override public boolean visit(SQLVariantRefExpr parameter) {
                    if (!"?".equals(parameter.getName()) || parameter.getIndex() < 0 || parameter.getIndex() >= original.getParameterMappings().size()) {
                        throw new IllegalArgumentException("Invalid compensation parameter");
                    }
                    mappings.add(original.getParameterMappings().get(parameter.getIndex()));
                    return false;
                }
            });
            sql.append(SQLUtils.toSQLString(part, "mysql"));
        }
        List<Map<String,Object>> rows(Connection connection) throws SQLException {
            return rows(connection, null);
        }
        List<Map<String,Object>> rows(Connection connection, List<String> targetColumns) throws SQLException {
            BoundSql bound = new BoundSql(mapped.getConfiguration(), sql.toString(), mappings, original.getParameterObject());
            for (ParameterMapping mapping : mappings) {
                String root = new PropertyTokenizer(mapping.getProperty()).getName();
                if (original.hasAdditionalParameter(root)) bound.setAdditionalParameter(root, original.getAdditionalParameter(root));
            }
            List<Map<String,Object>> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(bound.getSql())) {
                new DefaultParameterHandler(mapped, original.getParameterObject(), bound).setParameters(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    ResultSetMetaData metadata = rows.getMetaData();
                    if (targetColumns != null && metadata.getColumnCount() != targetColumns.size()) throw new IllegalArgumentException("Invalid INSERT projection");
                    while (rows.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= metadata.getColumnCount(); i++) {
                            String label = targetColumns == null ? metadata.getColumnLabel(i) : targetColumns.get(i - 1);
                            if (row.containsKey(label)) throw new IllegalArgumentException("Ambiguous compensation projection");
                            row.put(label, rows.getObject(i));
                        }
                        result.add(row);
                    }
                }
            }
            return result;
        }
    }

    /** Kept for callers that inspect SQL; this never renders parameter values into SQL. */
    public String showSql(Configuration config, BoundSql bound, Map<String,Object> values, SqlCommandType command) {
        Object parameter = bound.getParameterObject();
        for (ParameterMapping mapping : bound.getParameterMappings()) {
            String name = mapping.getProperty();
            Object value = bound.hasAdditionalParameter(name) ? bound.getAdditionalParameter(name)
                : parameter == null ? null : config.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass()) ? parameter
                : config.newMetaObject(parameter).getValue(name);
            values.put(name, RollbackSqlLiteral.value(value));
        }
        return bound.getSql();
    }
    @Override public Object plugin(Object target) { return Plugin.wrap(target, this); }
    @Override public void setProperties(Properties properties) { }
}
