package com.watchtower.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final MonitorProperties properties;
    private final LoginRateLimiter loginRateLimiter;

    /**
     * Agent endpoint: per-agent HMAC signature authentication, stateless.
     * Falls back to legacy single-key X-API-Key if allow-legacy-api-key is true.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain agentFilterChain(HttpSecurity http) throws Exception {
        MonitorProperties.Security sec = properties.getSecurity();
        AgentSignatureFilter agentAuth = new AgentSignatureFilter(
                sec.getAgents(),
                sec.getAgentApiKey(),
                sec.isAllowLegacyApiKey(),
                sec.getAgentMaxClockSkewSeconds());
        http
            .securityMatcher("/api/agent/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(agentAuth, UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * Actuator health endpoint: public (for LB/k8s probes).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/health", "/actuator/health/**", "/actuator/info")
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Dashboard & API: HTTP Basic authentication.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain dashboardFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .addFilterBefore(new LoginRateLimitFilter(loginRateLimiter), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/alarms/*/ack").hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/maintenance/**").hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/**").hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .httpBasic(basic -> {});
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        MonitorProperties.Security sec = properties.getSecurity();
        java.util.List<org.springframework.security.core.userdetails.UserDetails> principals = new java.util.ArrayList<>();
        principals.add(User.builder()
                .username(sec.getDashboardUsername())
                .password(encoder.encode(sec.getDashboardPassword()))
                .roles("ADMIN")
                .build());
        if (sec.getUsers() != null) {
            for (MonitorProperties.DashboardUser u : sec.getUsers()) {
                if (u.getUsername() == null || u.getUsername().isBlank()) continue;
                if (u.getUsername().equals(sec.getDashboardUsername())) continue;
                String role = u.getRole() == null ? "VIEWER" : u.getRole().toUpperCase();
                principals.add(User.builder()
                        .username(u.getUsername())
                        .password(encoder.encode(u.getPassword() == null ? "" : u.getPassword()))
                        .roles(role)
                        .build());
            }
        }
        return new InMemoryUserDetailsManager(principals);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Blocks dashboard authentication attempts from an IP after too many failures
     * and records basic-auth failures/successes observed after the Basic filter runs.
     */
    static class LoginRateLimitFilter extends OncePerRequestFilter {

        private final LoginRateLimiter limiter;

        LoginRateLimitFilter(LoginRateLimiter limiter) {
            this.limiter = limiter;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String ip = clientIp(request);
            if (limiter.isBlocked(ip)) {
                response.setStatus(429);
                response.setHeader("Retry-After", "900");
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"too many failed login attempts; try again later\"}");
                return;
            }

            filterChain.doFilter(request, response);

            if (request.getHeader("Authorization") == null) {
                return;
            }
            int status = response.getStatus();
            if (status == HttpServletResponse.SC_UNAUTHORIZED) {
                limiter.recordFailure(ip);
            } else if (status < 400 && request.getUserPrincipal() != null) {
                limiter.recordSuccess(ip);
            }
        }

        private static String clientIp(HttpServletRequest request) {
            String fwd = request.getHeader("X-Forwarded-For");
            if (fwd != null && !fwd.isBlank()) {
                int comma = fwd.indexOf(',');
                return (comma < 0 ? fwd : fwd.substring(0, comma)).trim();
            }
            return request.getRemoteAddr();
        }
    }

}
