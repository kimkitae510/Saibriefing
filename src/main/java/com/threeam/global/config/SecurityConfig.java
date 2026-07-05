package com.threeam.global.config;

import com.threeam.assessment.AssessmentProperties;
import com.threeam.assessment.ReadingProperties;
import com.threeam.auth.oauth.OAuthProperties;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.llm.FactExtractionProperties;
import com.threeam.llm.GeminiProperties;
import com.threeam.llm.VertexAiProperties;
import com.threeam.match.MatchProperties;
import com.threeam.mail.MailProperties;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.usage.UsageProperties;
import com.threeam.security.handler.JwtAccessDeniedHandler;
import com.threeam.security.handler.JwtAuthenticationEntryPoint;
import com.threeam.security.jwt.JwtAuthenticationFilter;
import com.threeam.security.jwt.JwtTokenProvider;
import com.threeam.security.jwt.TokenInvalidationRegistry;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, GeminiProperties.class, VertexAiProperties.class,
        ChatPersonaProperties.class, com.threeam.llm.ChatGoalProperties.class, FactExtractionProperties.class,
        AssessmentProperties.class, ReadingProperties.class, com.threeam.llm.AnthropicProperties.class, com.threeam.llm.OpenAiProperties.class, UsageProperties.class,
        MatchProperties.class, PaymentProperties.class, MailProperties.class, OAuthProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final TokenInvalidationRegistry tokenInvalidationRegistry;

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    // 액추에이터 전용 포트(운영에서 127.0.0.1 바인딩). 미설정(개발)이면 -1이라 어떤 요청과도
    // 안 맞아 기존 인증 규칙이 그대로 적용된다.
    @Value("${management.server.port:-1}")
    private int managementPort;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 비동기(CompletableFuture) 응답의 ASYNC 재디스패치, 에러 포워딩은 최초 REQUEST에서
                        // 이미 인증을 통과한 내부 흐름 — Security 6은 기본으로 이것까지 검사해서,
                        // 면제하지 않으면 분석 API가 처리 완료 후 401로 떨어진다.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/users/signup", "/api/users/email-verifications",
                                "/api/auth/login", "/api/auth/reissue", "/api/auth/oauth/**",
                                "/api/auth/guest").permitAll()
                        // 외부 감시의 DB 관통 체크. 상태 한 단어만 내려줘 무인증 공개해도 새는 게 없다.
                        .requestMatchers(HttpMethod.GET, "/api/health/db").permitAll()
                        // 상품과 가격은 공개 정보다. 결제 대행사 심사와 전자상거래법 모두
                        // "로그인 없이 무엇을 얼마에 파는지 보일 것"을 요구해서 이용권 안내
                        // 페이지가 로그아웃 상태로 이 응답을 읽는다. 내려주는 값은 상품 목록과
                        // 프론트 결제창용 공개 키뿐이라 유저 정보가 섞이지 않는다.
                        .requestMatchers(HttpMethod.GET, "/api/payments/config").permitAll()
                        // 분석 공유 — 링크를 받은 비회원이 여는 공개 조회와 OG 페이지.
                        // 토큰(192비트 랜덤)이 곧 열쇠라 무인증으로 열어도 추측으로 남의 결과에
                        // 닿을 수 없고, 내려가는 값도 사연 원문이 빠진 요약뿐이다.
                        .requestMatchers(HttpMethod.GET, "/api/share/*", "/s/*").permitAll()
                        // PG 웹훅 — 토스 서버가 호출하므로 JWT가 없다. 페이로드를 신뢰하지 않고
                        // PG 조회로 재확인하는 구조라(PaymentWebhookController) 열어도 상태 위조가 불가능하다.
                        .requestMatchers("/api/payments/webhook/**").permitAll()
                        // 개발용 재분류 — 컨트롤러 자체가 llm.reading.dev-endpoints=true일 때만 뜨고
                        // 루프백 요청만 받는다. 운영에는 그 속성이 없어 404다.
                        .requestMatchers("/api/dev/**").permitAll()
                        // management 포트(운영: 127.0.0.1:9090)로 온 요청은 인증 면제 — 그 포트는
                        // 외부에서 닿을 수 없고, 같은 서버의 수집 에이전트(Alloy)가 지표를 긁는 통로다.
                        // JWT를 에이전트에 쥐여주면 토큰 만료/회전이 수집 장애가 되므로 포트 격리로 대체한다.
                        .requestMatchers(request -> request.getLocalPort() == managementPort).permitAll()
                        // health만 공개(로드밸런서 헬스체크용). prometheus, info 등 내부 메트릭은
                        // 인증 뒤로 둔다 — 무인증 노출 시 트래픽 패턴, JVM, URI별 요청수가 새어 나간다.
                        // 모니터링 스크래핑은 인증 토큰 또는 내부망에서 호출한다.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider, tokenInvalidationRegistry),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
