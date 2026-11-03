package com.docchat.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Sessions live in the database, so during an outage a request can fail in the filters, before it
 * reaches a controller, and the container would answer with its own error page. This renders those
 * failures like any other error. It runs right after the correlation id and before the session.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DatabaseUnavailableFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver errors;

    DatabaseUnavailableFilter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (DataAccessResourceFailureException | CannotCreateTransactionException ex) {
            // If the response has already started, there is no way left to report it.
            if (response.isCommitted()) {
                throw ex;
            }
            errors.resolveException(request, response, null, ex);
        }
    }
}
