package com.java110.business;

import org.aopalliance.intercept.MethodInterceptor;
import org.apache.shardingsphere.shardingjdbc.api.yaml.YamlShardingDataSourceFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StreamUtils;
import com.java110.core.event.service.BusinessServiceDataFlowListener;
import com.java110.utils.factory.LogicalServiceContext;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keep each domain's original sharding semantics inside the shared JVM. */
@Configuration
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class BusinessDataSourceConfiguration {
    static String currentRoute() {
        String service = LogicalServiceContext.current();
        return "community-service".equals(service) ? "community" : "report-service".equals(service) ? "report" : "default";
    }

    @Bean
    public DataSource dataSource(@Value("${java110.datasource.config}") String standard,
                                 @Value("${java110.datasource.community.config}") String community,
                                 @Value("${java110.datasource.report.config}") String report) throws Exception {
        Map<Object, Object> sources = new LinkedHashMap<>();
        try {
            sources.put("default", load(standard));
            sources.put("community", load(community));
            sources.put("report", load(report));
            return new DomainDataSource(sources);
        } catch (Exception error) {
            close(sources);
            throw error;
        }
    }

    private static DataSource load(String location) throws Exception {
        try (InputStream input = new DefaultResourceLoader().getResource(location).getInputStream()) {
            return YamlShardingDataSourceFactory.createDataSource(StreamUtils.copyToByteArray(input));
        }
    }

    private static void close(Map<Object, Object> sources) throws Exception {
        for (Object source : sources.values()) {
            if (source instanceof AutoCloseable) ((AutoCloseable) source).close();
        }
    }

    static class DomainDataSource extends AbstractRoutingDataSource implements DisposableBean {
        private final Map<Object, Object> sources;

        DomainDataSource(Map<Object, Object> sources) {
            this.sources = sources;
            setTargetDataSources(sources);
            setDefaultTargetDataSource(sources.get("default"));
            setLenientFallback(false);
            afterPropertiesSet();
        }

        @Override
        protected Object determineCurrentLookupKey() { return currentRoute(); }

        @Override
        public void destroy() throws Exception { close(sources); }
    }

    @Bean
    public static Advisor businessDataSourceAdvisor() {
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                // Cold listener discovery reads metadata from every domain inside the caller's transaction.
                if (BusinessServiceDataFlowListener.class.isAssignableFrom(targetClass)
                        && method.getParameterCount() == 0
                        && ("getBusinessTypeCd".equals(method.getName()) || "getOrder".equals(method.getName()))) {
                    return false;
                }
                // Match the target class, including listener methods inherited from core.
                String name = targetClass.getName();
                String[] parts = name.split("\\.");
                return parts.length > 2 && LogicalServiceContext.isBusinessService(parts[2] + "-service");
            }
        };
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(pointcut, (MethodInterceptor) invocation -> {
            String service = invocation.getThis().getClass().getName().split("\\.")[2] + "-service";
            String route = "community-service".equals(service) ? "community" : "report-service".equals(service) ? "report" : "default";
            if (!route.equals(currentRoute()) && TransactionSynchronizationManager.isActualTransactionActive()) {
                throw new IllegalStateException("Cannot change business data source inside an active transaction");
            }
            try (LogicalServiceContext.Scope ignored = LogicalServiceContext.enter(service)) {
                return invocation.proceed();
            }
        });
        // Select the route before TransactionInterceptor obtains a connection.
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return advisor;
    }
}
