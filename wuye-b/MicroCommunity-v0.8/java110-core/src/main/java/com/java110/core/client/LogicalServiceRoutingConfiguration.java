package com.java110.core.client;

import com.java110.utils.factory.LogicalServiceContext;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;

import java.util.ArrayList;
import java.util.List;

/** Preserve the destination name before Ribbon replaces it with the merged address. */
@Configuration
@ConditionalOnProperty(name = "java110.runtime.merged-enabled", havingValue = "true")
public class LogicalServiceRoutingConfiguration {
    @Bean
    public SmartInitializingSingleton logicalServiceRestTemplates(ApplicationContext context) {
        return () -> context.getBeansOfType(org.springframework.web.client.RestTemplate.class).values().forEach(template -> {
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(template.getInterceptors());
            interceptors.add(0, (request, body, execution) -> {
                String service = request.getURI().getHost();
                if (LogicalServiceContext.isBusinessService(service)) {
                    request.getHeaders().set(LogicalServiceContext.HEADER, service);
                }
                return execution.execute(request, body);
            });
            template.setInterceptors(interceptors);
        });
    }
}
