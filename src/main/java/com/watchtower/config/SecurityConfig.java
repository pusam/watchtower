package com.watchtower.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final MonitorProperties properties;

    /**
     * Agent endpoint: API key header authentication, stateless.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain agentFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/agent/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ApiKeyFilter(properties.getSecurity().getAgentApiKey()),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    /**
     * Dashboard & API: HTTP Basic authentication.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain dashboardFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/css/**", "/js/**", "/favicon.ico").permitAll()
                    .anyRequest().authenticated())
            .httpBasic(basic -> {});
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        MonitorProperties.Security sec = properties.getSecurity();
        var user = User.builder()
                .username(sec.getDashboardUsername())
                .password(encoder.encode(sec.getDashboardPassword()))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Filter that validates the X-API-Key header for agent endpoints.
     */
    static class ApiKeyFilter extends OncePerRequestFilter {

        private final String expectedKey;

        ApiKeyFilter(String expectedKey) {
            this.expectedKey = expectedKey;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            String provided = request.getHeader("X-API-Key");
            if (expectedKey != null && !expectedKey.isBlank() && expectedKey.equals(provided)) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("agent", null, List.of()));
                filterChain.doFilter(request, response);
            } else {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Invalid or missing API key\"}");
            }
        }
    }
}
