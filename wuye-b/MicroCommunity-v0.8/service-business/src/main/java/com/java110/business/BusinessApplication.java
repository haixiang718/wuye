package com.java110.business;

import com.java110.core.annotation.Java110ListenerDiscovery;
import com.java110.core.client.RestTemplate;
import com.java110.core.event.service.BusinessServiceDataFlowEventPublishing;
import com.java110.service.init.ServiceStartInit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import com.java110.db.DataSourceConfig;
import org.springframework.http.converter.StringHttpMessageConverter;

import java.nio.charset.StandardCharsets;

/** One context for the existing business modules; entry points remain independent. */
@ComponentScan(basePackages = {
        "com.java110.service", "com.java110.core", "com.java110.config.properties.code", "com.java110.db",
        "com.java110.user", "com.java110.community", "com.java110.fee", "com.java110.acct",
        "com.java110.store", "com.java110.common", "com.java110.report"
}, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DataSourceConfig.class))
@SpringBootApplication(exclude = {LiquibaseAutoConfiguration.class,
        org.activiti.spring.boot.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class})
@EnableDiscoveryClient
@Import({BusinessWebMvcConfiguration.class, BusinessDataSourceConfiguration.class, BusinessDomainFilter.class})
@EnableFeignClients(basePackages = "com.java110.intf")
@Java110ListenerDiscovery(listenerPublishClass = BusinessServiceDataFlowEventPublishing.class,
        basePackages = {"com.java110.user.listener", "com.java110.community.listener", "com.java110.fee.listener",
                "com.java110.acct.listener", "com.java110.store.listener", "com.java110.common.listener",
                "com.java110.report.listener"})
public class BusinessApplication {
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(BusinessApplication.class, args);
        ServiceStartInit.initSystemConfig(context);
    }

    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplateBuilder().additionalMessageConverters(
                new StringHttpMessageConverter(StandardCharsets.UTF_8)).build(RestTemplate.class);
    }

    @Bean
    public RestTemplate outRestTemplate() {
        return new RestTemplateBuilder().additionalMessageConverters(
                new StringHttpMessageConverter(StandardCharsets.UTF_8)).build(RestTemplate.class);
    }
}
