package com.java110.business;

import org.junit.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.java110.core.event.service.BusinessServiceDataFlowEventPublishing;
import com.java110.core.event.service.BusinessServiceDataFlowListener;
import com.java110.utils.factory.ApplicationContextFactory;
import com.java110.utils.factory.LogicalServiceContext;
import com.java110.core.client.LogicalServiceRoutingConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpMethod;
import java.net.URI;
import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import com.java110.community.listener.room.SaveRoomInfoListener;
import com.java110.user.listener.owner.SaveOwnerInfoListener;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class BusinessContainerTest {
    @Test
    public void ribbonKeepsOriginalServiceForGenericQueriesAndCompensation() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            org.springframework.web.client.RestTemplate template = new org.springframework.web.client.RestTemplate();
            context.getBeanFactory().registerSingleton("restTemplate", template);
            context.refresh();
            new LogicalServiceRoutingConfiguration().logicalServiceRestTemplates(context).afterSingletonsInstantiated();
            HttpRequest request = new HttpRequest() {
                private final HttpHeaders headers = new HttpHeaders();
                public HttpMethod getMethod() { return HttpMethod.POST; }
                public String getMethodValue() { return "POST"; }
                public URI getURI() { return URI.create("http://community-service/businessApi/fallBack"); }
                public HttpHeaders getHeaders() { return headers; }
            };
            template.getInterceptors().get(0).intercept(request, new byte[0], (forwarded, body) -> {
                assertEquals("community-service", forwarded.getHeaders().getFirst(LogicalServiceContext.HEADER));
                return null;
            });
        }
    }

    @Test
    public void genericCompensationRestoresDomainAndCleansUpOnFailure() throws Exception {
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (p, method, args) -> {
                    if (method.getName().equals("getRequestURI")) return "/businessApi/fallBack";
                    if (method.getName().equals("getHeader")) return "community-service";
                    return null;
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (p, method, args) -> null);
        try {
            new BusinessDomainFilter().doFilterInternal(request, response, (req, res) -> {
                assertEquals("community", BusinessDataSourceConfiguration.currentRoute());
                assertEquals("community-service", LogicalServiceContext.resolve("business-service"));
                throw new IOException("synthetic failure");
            });
            fail("Expected synthetic failure");
        } catch (IOException expected) { assertEquals("synthetic failure", expected.getMessage()); }
        assertEquals("default", BusinessDataSourceConfiguration.currentRoute());
        assertNull(LogicalServiceContext.current());
        assertEquals("user-service", LogicalServiceContext.resolve("user-service"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void coldListenerDiscoveryKeepsTransactionRouteAcrossDomains() throws Exception {
        List<String> registered = BusinessServiceDataFlowEventPublishing.getListeners();
        List<String> previousListeners = new ArrayList<>(registered);
        Field cacheField = BusinessServiceDataFlowEventPublishing.class.getDeclaredField("cacheListenersMap");
        cacheField.setAccessible(true);
        Map<String, List<BusinessServiceDataFlowListener>> cache =
                (Map<String, List<BusinessServiceDataFlowListener>>) cacheField.get(null);
        Map<String, List<BusinessServiceDataFlowListener>> previousCache = new LinkedHashMap<>(cache);
        Field contextField = ApplicationContextFactory.class.getDeclaredField("applicationContext");
        contextField.setAccessible(true);
        org.springframework.context.ApplicationContext previousContext =
                (org.springframework.context.ApplicationContext) contextField.get(null);
        List<String> opened = new ArrayList<>();
        BusinessDataSourceConfiguration.DomainDataSource routing = testDataSource(opened, new ArrayList<>());
        DataSourceTransactionManager manager = new DataSourceTransactionManager(routing);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            registered.clear();
            Object[] targets = {new SaveOwnerInfoListener(), new SaveRoomInfoListener(), new SaveRoomInfoListener()};
            for (int i = 0; i < targets.length; i++) {
                ProxyFactory factory = new ProxyFactory(targets[i]);
                factory.setProxyTargetClass(true);
                factory.addAdvisor(BusinessDataSourceConfiguration.businessDataSourceAdvisor());
                // Real listeners have class-level @Transactional, including their metadata methods.
                factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
                context.getBeanFactory().registerSingleton("listener" + i, factory.getProxy());
                BusinessServiceDataFlowEventPublishing.addListener("listener" + i);
            }
            context.refresh();
            ApplicationContextFactory.setApplicationContext(context);
            for (String service : new String[]{"user-service", "community-service"}) {
                cache.clear();
                opened.clear();
                String route = service.equals("community-service") ? "community" : "default";
                try (LogicalServiceContext.Scope ignored = LogicalServiceContext.enter(service)) {
                    new TransactionTemplate(manager).execute(status -> {
                        String type = new SaveRoomInfoListener().getBusinessTypeCd();
                        List<BusinessServiceDataFlowListener> listeners = BusinessServiceDataFlowEventPublishing.getListeners(type);
                        assertEquals(2, listeners.size()); // Exercises getOrder() during sorting as well.
                        assertSame(context.getBean("listener1"), listeners.get(0));
                        ((BusinessServiceDataFlowListener) context.getBean("listener0")).getOrder();
                        assertEquals(route, BusinessDataSourceConfiguration.currentRoute());
                        assertEquals(service, LogicalServiceContext.current());
                        return null;
                    });
                    assertEquals(java.util.Collections.singletonList(route), opened);
                }
                assertEquals("default", BusinessDataSourceConfiguration.currentRoute());
                assertNull(LogicalServiceContext.current());
            }
        } finally {
            registered.clear();
            registered.addAll(previousListeners);
            cache.clear();
            cache.putAll(previousCache);
            ApplicationContextFactory.setApplicationContext(previousContext);
        }
    }

    @Test
    public void realListenerInvocationStillRejectsCrossRouteTransaction() {
        List<String> opened = new ArrayList<>();
        BusinessDataSourceConfiguration.DomainDataSource routing = testDataSource(opened, new ArrayList<>());
        ProxyFactory factory = new ProxyFactory(new SaveRoomInfoListener());
        factory.setProxyTargetClass(true);
        factory.addAdvisor(BusinessDataSourceConfiguration.businessDataSourceAdvisor());
        factory.addAdvice((MethodInterceptor) invocation -> { fail("Cross-route listener body must not run"); return null; });
        SaveRoomInfoListener listener = (SaveRoomInfoListener) factory.getProxy();
        new TransactionTemplate(new DataSourceTransactionManager(routing)).execute(status -> {
            try { listener.soService(null); fail("Expected cross-route transaction rejection"); }
            catch (IllegalStateException expected) {
                assertEquals("Cannot change business data source inside an active transaction", expected.getMessage());
            }
            assertEquals("default", BusinessDataSourceConfiguration.currentRoute());
            assertNull(LogicalServiceContext.current());
            return null;
        });
        assertEquals(java.util.Collections.singletonList("default"), opened);
    }

    private BusinessDataSourceConfiguration.DomainDataSource testDataSource(List<String> opened, List<String> completed) {
        Map<Object, Object> sources = new LinkedHashMap<>();
        for (String domain : new String[]{"default", "community", "report"}) {
            Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, method, args) -> {
                        if (method.getName().equals("getAutoCommit")) return true;
                        if (method.getName().equals("isReadOnly") || method.getName().equals("isClosed")) return false;
                        if (method.getName().equals("rollback") || method.getName().equals("commit")) completed.add(domain + ":" + method.getName());
                        return null;
                    });
            sources.put(domain, Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                    (p, method, args) -> { opened.add(domain); return connection; }));
        }
        return new BusinessDataSourceConfiguration.DomainDataSource(sources);
    }

    @Test
    public void inheritedListenerRoutesBeforeTransactionAndCleansAfterRollback() throws Exception {
        List<String> opened = new ArrayList<>();
        List<String> completed = new ArrayList<>();
        BusinessDataSourceConfiguration.DomainDataSource routing = testDataSource(opened, completed);
        DefaultPointcutAdvisor advisor = (DefaultPointcutAdvisor) BusinessDataSourceConfiguration.businessDataSourceAdvisor();
        assertTrue(advisor.getPointcut().getMethodMatcher().matches(
                SaveRoomInfoListener.class.getMethod("soService", com.java110.core.event.service.BusinessServiceDataFlowEvent.class),
                SaveRoomInfoListener.class));
        ProxyFactory factory = new ProxyFactory(new SaveRoomInfoListener());
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        NameMatchTransactionAttributeSource transactions = new NameMatchTransactionAttributeSource();
        transactions.addTransactionalMethod("soService", new DefaultTransactionAttribute());
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(routing), transactions));
        factory.addAdvice((MethodInterceptor) invocation -> {
            assertEquals("community", BusinessDataSourceConfiguration.currentRoute());
            throw new IllegalArgumentException("synthetic rollback");
        });
        SaveRoomInfoListener listener = (SaveRoomInfoListener) factory.getProxy();
        try { listener.soService(null); fail("Expected rollback"); }
        catch (IllegalArgumentException expected) { assertEquals("synthetic rollback", expected.getMessage()); }
        assertEquals("community", opened.get(0));
        assertTrue(completed.contains("community:rollback"));
        assertEquals("default", BusinessDataSourceConfiguration.currentRoute());
        // Reused worker threads must not inherit the previous listener's route.
        routing.getConnection().close();
        assertEquals("default", opened.get(1));
    }

    @FeignClient(name = "test-owner-service")
    @RequestMapping("/testOwner")
    interface RemoteOwner {
        @RequestMapping(value = "/query", method = RequestMethod.POST)
        String query();
    }

    @FeignClient(name = "test-community-service")
    @RequestMapping("/testCommunity")
    interface RemoteCommunity {
        @RequestMapping(value = "/query", method = RequestMethod.POST)
        String query(@RequestBody Map<String, String> body);
    }

    @RestController
    static class LocalCommunity implements RemoteCommunity {
        public String query(Map<String, String> body) { return body.get("memberId"); }
    }

    @Test
    public void legacyInterfaceRequestBodyRemainsVisibleToMvc() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("realCommunity", new LocalCommunity());
            context.refresh();
            RequestMappingHandlerMapping mapping = new BusinessWebMvcConfiguration().getRequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            assertEquals(1, mapping.getHandlerMethods().size());
            assertNotNull(mapping.getHandlerMethods().values().iterator().next().getMethodParameters()[0]
                    .getParameterAnnotation(RequestBody.class));
        }
    }

    @RestController
    static class LocalOwner implements RemoteOwner {
        public String query() { return "local"; }
    }

    @Test
    public void feignProxyMustNotCompeteWithRealController() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getBeanFactory().registerSingleton("realOwner", new LocalOwner());
            Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteOwner.class},
                    (p, method, args) -> "remote");
            context.getBeanFactory().registerSingleton("remoteOwner", proxy);
            context.refresh();
            RequestMappingHandlerMapping mapping = new BusinessWebMvcConfiguration().getRequestMappingHandlerMapping();
            mapping.setApplicationContext(context);
            mapping.afterPropertiesSet();
            assertEquals(1, mapping.getHandlerMethods().size());
            assertEquals("realOwner", mapping.getHandlerMethods().values().iterator().next().getBean());
        }
    }

    @Test
    public void allBusinessKafkaConsumersSurviveOneContext() throws Exception {
        String[] domains = {"user", "community", "fee", "acct", "store", "common"};
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            Set<String> placeholders = new HashSet<>();
            for (String domain : domains) {
                String title = Character.toUpperCase(domain.charAt(0)) + domain.substring(1);
                Class<?> config = Class.forName("com.java110." + domain + ".kafka." + title + "ServiceBean");
                Class<?> consumer = Class.forName("com.java110." + domain + ".kafka." + title + "ServiceKafka");
                for (Class<?> type = consumer; type != Object.class; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (!field.isAnnotationPresent(Autowired.class)) continue;
                        String name = field.getType().getName();
                        if (context.getBeanFactory().containsSingleton(name)) continue;
                        Object stub = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{field.getType()},
                                (p, method, args) -> null);
                        context.getBeanFactory().registerSingleton(name, stub);
                    }
                }
                KafkaListener annotation = consumer.getMethod("listen", org.apache.kafka.clients.consumer.ConsumerRecord.class)
                        .getAnnotation(KafkaListener.class);
                assertTrue(annotation.groupId().contains("${kafka.consumer.group.id}"));
                placeholders.add(annotation.groupId());
                context.register(config);
            }
            context.refresh();
            assertEquals(6, placeholders.size());
            for (String domain : domains) {
                assertNotNull(context.getBean(domain + "BusinessKafkaListener"));
            }
        }
    }
}
