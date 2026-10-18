package com.example.jobqueue.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * For a public demo: anyone may read, but changing anything (POST / PUT / PATCH / DELETE under /api)
 * needs HTTP Basic credentials. Only active when jobqueue.admin.password is set
 * (environment variable JOBQUEUE_ADMIN_PASSWORD), so local development is unaffected.
 * Browsers show their normal login box when the API answers 401 with WWW-Authenticate.
 */
@Component
@ConditionalOnProperty(name = "jobqueue.admin.password")
public class AdminWriteFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public AdminWriteFilter(@Value("${jobqueue.admin.user:admin}") String user,
                            @Value("${jobqueue.admin.password}") String password) {
        this.expected = (user + ":" + password).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean write = "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
        return !(write && request.getRequestURI().startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                byte[] given = Base64.getDecoder().decode(header.substring(6).trim());
                if (MessageDigest.isEqual(given, expected)) { // constant-time comparison
                    chain.doFilter(request, response);
                    return;
                }
            } catch (IllegalArgumentException malformedBase64) {
                // fall through to 401
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Basic realm=\"JobQueue admin\", charset=\"UTF-8\"");
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":401,\"detail\":\"Admin login required to change anything\"}");
    }
}
