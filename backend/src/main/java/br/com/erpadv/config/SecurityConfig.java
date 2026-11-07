package br.com.erpadv.config;

import br.com.erpadv.exceptions.ProblemFactory;
import br.com.erpadv.security.FirebaseAuthenticationFilter;
import br.com.erpadv.security.OfficeStatusCache;
import br.com.erpadv.security.PermissionResolver;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.RateLimitFilter;
import br.com.erpadv.security.UserProfileCache;
import com.google.firebase.auth.FirebaseAuth;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * API stateless protegida por ID tokens do Firebase.
 * CSRF fica desabilitado porque não há cookie de sessão: o token vai no cabeçalho Authorization,
 * que um site de terceiros não consegue anexar.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public FirebaseAuthenticationFilter firebaseAuthenticationFilter(FirebaseAuth auth, UserProfileCache cache,
                                                                     PermissionResolver resolver,
                                                                     OfficeStatusCache officeStatus,
                                                                     ProblemFactory problems, AppProperties properties) {
        return new FirebaseAuthenticationFilter(auth, cache, resolver, officeStatus, problems, properties);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(AppProperties properties, ProblemFactory problems) {
        return new RateLimitFilter(properties.security().rateLimitPerMinute(), problems);
    }

    /** Impede que os filtros sejam registrados também como filtros de servlet (executariam fora da cadeia). */
    @Bean
    public FilterRegistrationBean<FirebaseAuthenticationFilter> disableAuthFilterRegistration(FirebaseAuthenticationFilter f) {
        FilterRegistrationBean<FirebaseAuthenticationFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> disableRateLimitRegistration(RateLimitFilter f) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(f);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   FirebaseAuthenticationFilter authFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   ProblemFactory problems) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> {
                })
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                problems.write(res, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Autenticação necessária."))
                        .accessDeniedHandler((req, res, ex) ->
                                problems.write(res, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Acesso negado.")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/auth/**", "/api/onboarding/**").authenticated()
                        .requestMatchers("/api/platform/**").hasAuthority(Permission.PLATFORM_ADMIN.authority())
                        .requestMatchers("/api/portal/**").hasAuthority(Permission.PORTAL_ACCESS.authority())
                        .requestMatchers("/api/**").hasAuthority(FirebaseAuthenticationFilter.AUTHORITY_STAFF)
                        .anyRequest().denyAll())
                .addFilterBefore(authFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, FirebaseAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = properties.cors() != null && properties.cors().allowedOrigins() != null
                ? properties.cors().allowedOrigins()
                : List.of();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "Content-Disposition"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
