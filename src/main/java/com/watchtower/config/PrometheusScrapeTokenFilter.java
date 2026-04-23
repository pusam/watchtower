package com.watchtower.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorizes {@code /actuator/prometheus} via a static Bearer token configured in
 * {@code watchtower.security.prometheus-scrape-token}. Empty token = endpoint disabled (403).
 */
public class PrometheusScrapeTokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public PrometheusScrapeTokenFilter(String token) {
        this.expected = (token == null || token.isBlank())
                ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (expected == null) {
            deny(response, 403, "prometheus scrape disabled");
            return;
        }
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            deny(response, 401, "bearer token required");
            return;
        }
        byte[] provided = auth.substring("Bearer ".length()).trim()
                .getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            deny(response, 401, "invalid scrape token");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "prometheus", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SCRAPE"))));
        filterChain.doFilter(request, response);
    }

    private static void deny(HttpServletResponse response, int status, String msg) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
