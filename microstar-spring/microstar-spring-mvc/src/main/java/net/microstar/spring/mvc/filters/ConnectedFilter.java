package net.microstar.spring.mvc.filters;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.microstar.spring.mvc.application.DispatcherDelegateMvc;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ConnectedFilter implements Filter {
    final DispatcherDelegateMvc dispatcher;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if(requestIsFromDispatcher(request)) dispatcher.connectionChecker.setIsConnected();
        chain.doFilter(request, response);
    }

    private static boolean requestIsFromDispatcher(ServletRequest request) {
        return request instanceof HttpServletRequest req && req.getHeader("X-Forwarded-Path") != null;
    }
}