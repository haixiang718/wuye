package com.java110.api.configuration;

import io.swagger.annotations.ApiOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/** Skip documentation scanning/caches in the property runtime; business APIs are unchanged. */
@Configuration
@ConditionalOnProperty(name = "java110.runtime.api-docs-enabled", havingValue = "true", matchIfMissing = true)
@EnableSwagger2
public class ApiDocumentationConfiguration {
    @Bean
    public Docket swaggerSpringMvcPlugin() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(new ApiInfoBuilder()
                        .title("HC小区管理系统 APIs")
                        .description("HC小区管理系统提供的所有能力")
                        .termsOfServiceUrl("https://github.com/java110/MicroCommunity")
                        .contact("吴学文")
                        .version("1.0").build())
                .select()
                .paths(PathSelectors.any())
                .apis(RequestHandlerSelectors.withMethodAnnotation(ApiOperation.class)).build();
    }
}
