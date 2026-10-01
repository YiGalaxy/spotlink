package com.bulk.trade.shared.security;

import com.bulk.trade.shared.web.ApiResponse;
import com.bulk.trade.shared.web.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 无状态的 JWT 安全。
 *
 * <p>关闭 CSRF 防护，是因为没有任何会话 cookie 可以被伪造：凭证是一个持有者令牌，
 * 浏览器永远不会自动带上它。重新打开 CSRF 只会弄坏 API，换不来任何安全。
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 不带令牌也能访问的端点。 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/auth/login",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health",
            "/actuator/info"
    };

    /**
     * 橱窗：访客在表明身份之前可以做的读取。
     *
     * <p><b>限定在 GET 上，而这一点就是全部要点。</b>需要认证的写操作，就贴在这些路径
     * 隔壁：{@code /api/listings/mine} 挨着 {@code /api/listings/market}，而
     * {@code POST /api/listings} 又是同一个前缀。**放开前缀而不是放开这个读取，
     * 等于把这三个一起放开。**
     *
     * <p>这里放的是一个大宗商品交易场所向街面公布的东西：在挂的是什么，最近成交在
     * 什么价位。不在这里的——库存、订单、合同、资金、AI 顾问——全部是按企业划分的，
     * 而访客没有企业可以用来划分。
     *
     * <p>规则手册原先也在这里，理由是「交易场所会公开自己的规则」。这句话对规则本身成立，
     * 对那些接口却从来不成立：它们同时还提供运营视角下的知识库，那是一个运维面而不是
     * 公开面。它挪进了运营后台，而它的条目也在同一次改动里从这份清单上移除了——
     * **藏起一个页面却把它的接口敞着，不是同一改动的缩小版，而是另一个更糟的版本。**
     */
    private static final String[] PUBLIC_READS = {
            "/api/public/**",
            "/api/listings/market",
            "/api/categories/**",
            "/api/market/**"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET, PUBLIC_READS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeJson(response, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeJson(response, HttpStatus.FORBIDDEN, ResultCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // 强度 10 是 BCrypt 的默认值：在普通硬件上大约每次哈希 50-100 毫秒，
        // 这正是它想要的代价因子。
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void writeJson(jakarta.servlet.http.HttpServletResponse response,
                           HttpStatus status,
                           ResultCode resultCode) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.failure(resultCode)));
    }
}
