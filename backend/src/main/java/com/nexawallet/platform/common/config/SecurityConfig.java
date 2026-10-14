package com.nexawallet.platform.common.config;

import com.nexawallet.platform.common.ratelimit.RateLimitFilter;
import com.nexawallet.platform.common.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Spring Security 6 Configuration for NexaWallet.
 * Configures stateless JWT authentication, fine-grained endpoint protection,
 * BCrypt password hashing, and unified JSON error handling for unauthorized requests.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;
    private final UserDetailsService userDetailsService;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, RateLimitFilter rateLimitFilter, UserDetailsService userDetailsService) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.userDetailsService = userDetailsService;
    }

    /**
     * {@link RateLimitFilter} is a {@code @Component} so it can be constructor-injected here, but
     * that also makes Spring Boot auto-register it as a second, independent servlet-container
     * filter mapped to every URL — which would run it twice per request (once standalone, once
     * inside the Spring Security chain below) and silently double-consume rate-limit quota. This
     * bean disables that automatic registration; the chain wiring in {@link #securityFilterChain}
     * is the only place it actually runs.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableRateLimitFilterAutoRegistration(RateLimitFilter rateLimitFilter) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(rateLimitFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {}) // Handled via WebMvcConfig registry
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.getWriter().write(String.format(
                                    "{\"timestamp\":\"%s\",\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Full authentication is required to access this resource\",\"path\":\"%s\"}",
                                    OffsetDateTime.now(ZoneOffset.UTC),
                                    request.getRequestURI()
                            ));
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write(String.format(
                                    "{\"timestamp\":\"%s\",\"status\":403,\"error\":\"Forbidden\",\"message\":\"Access is denied\",\"path\":\"%s\"}",
                                    OffsetDateTime.now(ZoneOffset.UTC),
                                    request.getRequestURI()
                            ));
                        })
                )
                .authorizeHttpRequests(auth -> auth
                        // Pre-flight CORS OPTIONS requests
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public Health & Actuator
                        .requestMatchers("/api/v1/health/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        // Public OpenAPI / Swagger Documentation
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Explicit Public Authentication Endpoints
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        // Public read-only location discovery (no financial data, no JWT required — like any "find a nearby ATM" feature)
                        .requestMatchers(HttpMethod.GET, "/api/v1/service-points/nearby").permitAll()
                        // All other endpoints (including /api/v1/auth/me and every /api/v1/admin/** write) require valid JWT authentication
                        .anyRequest().authenticated()
                );

        return http.build();
    }
}
