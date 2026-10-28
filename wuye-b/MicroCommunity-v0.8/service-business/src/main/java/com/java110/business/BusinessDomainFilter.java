package com.java110.business;

import com.java110.utils.factory.LogicalServiceContext;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Generic legacy SQL/compensation endpoints have no domain in their Java package. */
@Component
public class BusinessDomainFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/businessApi/")) {
            chain.doFilter(request, response);
            return;
        }
        String service = request.getHeader(LogicalServiceContext.HEADER);
        if (!LogicalServiceContext.isBusinessService(service)) {
            response.sendError(400, "Logical business service is required");
            return;
        }
        try (LogicalServiceContext.Scope ignored = LogicalServiceContext.enter(service)) {
            chain.doFilter(request, response);
        }
    }
}
