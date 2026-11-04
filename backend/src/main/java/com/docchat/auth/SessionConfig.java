package com.docchat.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;

@Configuration(proxyBeanMethods = false)
class SessionConfig {

    /** Declared as a bean so that Spring Boot still applies the cookie settings from application.yml. */
    @Bean
    CookieHttpSessionIdResolver cookieHttpSessionIdResolver(CookieSerializer cookieSerializer) {
        CookieHttpSessionIdResolver resolver = new CookieHttpSessionIdResolver();
        resolver.setCookieSerializer(cookieSerializer);
        return resolver;
    }

    /**
     * Sessions live in the database, and the framework reads them on the way out of every request.
     * The routes that report the state of the system use no session, so they ignore the cookie. They
     * are what must keep working during a database outage, and a browser sends the cookie to them too.
     */
    @Bean
    @Primary
    HttpSessionIdResolver httpSessionIdResolver(CookieHttpSessionIdResolver cookies) {
        return new HttpSessionIdResolver() {

            @Override
            public List<String> resolveSessionIds(HttpServletRequest request) {
                return reportsSystemState(request.getRequestURI()) ? List.of() : cookies.resolveSessionIds(request);
            }

            @Override
            public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
                cookies.setSessionId(request, response, sessionId);
            }

            @Override
            public void expireSession(HttpServletRequest request, HttpServletResponse response) {
                cookies.expireSession(request, response);
            }
        };
    }

    private static boolean reportsSystemState(String path) {
        return path.equals("/api/system/status")
                || path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.equals("/actuator/info")
                || path.equals("/actuator/prometheus");
    }
}
