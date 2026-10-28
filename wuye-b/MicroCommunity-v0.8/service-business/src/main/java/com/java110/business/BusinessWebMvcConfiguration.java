package com.java110.business;

import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Boot 2.0 also treats RequestMapping on Feign interfaces as MVC handlers. */
@Configuration
public class BusinessWebMvcConfiguration implements WebMvcRegistrations {
    @Override
    public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
        return new RequestMappingHandlerMapping() {
            @Override
            protected HandlerMethod createHandlerMethod(Object handler, Method method) {
                // Spring 5.0 does not inherit parameter annotations from interfaces.
                // Some legacy inner controllers only declare RequestBody on their Feign interface.
                for (Class<?> type : ClassUtils.getAllInterfacesForClass(method.getDeclaringClass())) {
                    if (!type.isAnnotationPresent(FeignClient.class)) continue;
                    try {
                        Method contract = type.getMethod(method.getName(), method.getParameterTypes());
                        for (int index = 0; index < method.getParameterCount(); index++) {
                            if (!method.getParameters()[index].isAnnotationPresent(RequestBody.class)
                                    && contract.getParameters()[index].isAnnotationPresent(RequestBody.class)) {
                                return super.createHandlerMethod(handler, contract);
                            }
                        }
                    } catch (NoSuchMethodException ignored) { }
                }
                return super.createHandlerMethod(handler, method);
            }

            @Override
            protected boolean isHandler(Class<?> beanType) {
                if (beanType.isInterface() && beanType.isAnnotationPresent(FeignClient.class)) {
                    return false;
                }
                if (Proxy.isProxyClass(beanType)) {
                    for (Class<?> type : beanType.getInterfaces()) {
                        if (type.isAnnotationPresent(FeignClient.class)) {
                            return false;
                        }
                    }
                }
                return super.isHandler(beanType);
            }
        };
    }
}
