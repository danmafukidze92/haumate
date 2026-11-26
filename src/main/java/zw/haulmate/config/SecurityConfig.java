package zw.haulmate.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import zw.haulmate.domain.Types.Role;
import zw.haulmate.repo.UserRepo;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /** Mobile apps: stateless JWT. Paynow result webhook is public (verified by hash). */
    @Bean @Order(1)
    SecurityFilterChain api(HttpSecurity http, JwtAuthFilter jwt) throws Exception {
        http.securityMatcher("/api/**", "/ws/**")
            .cors(c -> c.configurationSource(corsConfigurationSource()))
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/**", "/api/ping", "/api/payments/paynow/result", "/api/pricing/estimate",
                                 "/api/docs/**", "/ws/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Lets the web build of the app (flutter run -d chrome) call the API. Mobile apps don't need CORS. */
    @Bean
    org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        var c = new org.springframework.web.cors.CorsConfiguration();
        c.setAllowedOriginPatterns(java.util.List.of("http://localhost:*", "http://127.0.0.1:*", "http://192.168.*.*:*",
                "https://cherai.nexigent-labs.co.zw"));
        c.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(java.util.List.of("*"));
        c.setMaxAge(3600L);
        var src = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/api/**", c);
        src.registerCorsConfiguration("/ws/**", c);
        return src;
    }

    /** Admin panel (PC browser / installable PWA on Android & iOS): session + form login. */
    @Bean @Order(2)
    SecurityFilterChain admin(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                .requestMatchers("/assets/**", "/admin/login", "/manifest.webmanifest", "/sw.js",
                                 "/v3/api-docs/**", "/swagger-ui/**", "/actuator/health", "/payment/return",
                                 "/", "/download/**", "/privacy", "/login", "/signup", "/account", "/account/").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().permitAll())
            .formLogin(f -> f.loginPage("/admin/login").usernameParameter("phone")
                .defaultSuccessUrl("/admin", true).permitAll())
            .logout(l -> l.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout"));
        return http.build();
    }

    @Bean
    UserDetailsService adminUsers(UserRepo users) {
        return phone -> users.findByPhone(phone)
                .filter(u -> u.getRole() == Role.ADMIN)
                .map(u -> User.withUsername(u.getPhone()).password(u.getPasswordHash()).roles("ADMIN").build())
                .orElseThrow(() -> new UsernameNotFoundException("not an admin"));
    }
}
