package com.watchtower.config;

import com.watchtower.audit.AuditLogger;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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
    private final AuditLogger auditLogger;

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
     * Prometheus scrape endpoint: Bearer-token auth (Grafana Agent / Prometheus scrape convention).
     * When {@code prometheus-scrape-token} is empty, returns 403 for every request.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain prometheusFilterChain(HttpSecurity http) throws Exception {
        String token = properties.getSecurity().getPrometheusScrapeToken();
        http
            .securityMatcher("/actuator/prometheus")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new PrometheusScrapeTokenFilter(token),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * Dashboard & API: HTTP Basic authentication, plus optional OIDC single sign-on.
     * When OIDC is enabled, unauthenticated browser requests redirect to the IdP; API
     * clients that send {@code Authorization: Basic ...} continue to work unchanged.
     */
    @Bean
    @Order(4)
    public SecurityFilterChain dashboardFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrationRepository) throws Exception {
        http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
            .headers(headers -> headers
                    .frameOptions(fo -> fo.deny())
                    .contentTypeOptions(cto -> {})
                    .referrerPolicy(rp -> rp.policy(
                            org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .httpStrictTransportSecurity(hsts -> hsts
                            .includeSubDomains(true)
                            .maxAgeInSeconds(31536000))
                    .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                            "Permissions-Policy",
                            "accelerometer=(), camera=(), geolocation=(), gyroscope=(), " +
                            "magnetometer=(), microphone=(), payment=(), usb=()"))
                    .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter(
                            "Content-Security-Policy",
                            "default-src 'self'; " +
                            "script-src 'self' https://cdn.jsdelivr.net; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "img-src 'self' data:; " +
                            "font-src 'self' data:; " +
                            "connect-src 'self' ws: wss:; " +
                            "frame-ancestors 'none'; " +
                            "base-uri 'self'; " +
                            "form-action 'self'")))
            .addFilterBefore(new LoginRateLimitFilter(loginRateLimiter, auditLogger), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/alarms/*/ack").hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/maintenance/**").hasAnyRole("ADMIN", "OPERATOR")
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/**").hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                    .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                    .anyRequest().authenticated())
            .httpBasic(basic -> {});

        MonitorProperties.Oidc oidc = properties.getSecurity().getOidc();
        if (oidc.isEnabled() && clientRegistrationRepository.getIfAvailable() != null) {
            WatchtowerOidcUserService oidcUsers = new WatchtowerOidcUserService(oidc);
            http.oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(oidcUsers)));
        }
        return http.build();
    }

    /**
     * Only registers a ClientRegistrationRepository when OIDC is enabled and an issuer
     * URI is configured; keeps Spring's OAuth2 auto-config dormant otherwise.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "watchtower.security.oidc.enabled", havingValue = "true")
    public ClientRegistrationRepository watchtowerClientRegistrationRepository() {
        MonitorProperties.Oidc oidc = properties.getSecurity().getOidc();
        if (oidc.getIssuerUri() == null || oidc.getIssuerUri().isBlank()) {
            throw new IllegalStateException(
                    "watchtower.security.oidc.enabled=true but issuer-uri is empty");
        }
        ClientRegistration registration = ClientRegistrations
                .fromIssuerLocation(oidc.getIssuerUri())
                .registrationId("watchtower")
                .clientId(oidc.getClientId())
                .clientSecret(oidc.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/watchtower")
                .scope(oidc.getScope())
                .build();
        return new InMemoryClientRegistrationRepository(registration);
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
        private final AuditLogger audit;

        LoginRateLimitFilter(LoginRateLimiter limiter, AuditLogger audit) {
            this.limiter = limiter;
            this.audit = audit;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String ip = clientIp(request);
            if (limiter.isBlocked(ip)) {
                audit.record("auth.blocked", "-", ip, Map.of("path", request.getRequestURI()));
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
                audit.record("auth.failure", "-", ip, Map.of("path", request.getRequestURI()));
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
