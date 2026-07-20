package com.threeam.global.config;

import com.threeam.payment.client.PaymentProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// 운영 배포에서 개발용 기본값(mock 프로바이더, 폴백 시크릿)이 그대로 뜨는 사고를 막는다.
// 이 값들은 개발 편의를 위해 기본값이 안전하게(키 없이 도는) 잡혀 있는데, 운영에서 살아 있으면
// 결제 무단 승인, 소셜 인증 우회, 토큰 위조 같은 치명적 사고가 된다. 환경변수 누락은 흔한 실수라
// 로그 경고만으론 부족하고, 운영 프로파일(prod)에서는 위반이 있으면 부팅을 세워 배포에서 즉시 드러낸다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductionReadinessGuard {

    private static final String PROD_PROFILE = "prod";

    // application.yml에 박힌 JWT 시크릿 폴백. 이 값 그대로면 시크릿이 저장소에 공개된 것과 같다.
    private static final String JWT_FALLBACK_SECRET =
            "change-me-to-a-long-random-secret-key-at-least-256-bits";

    private final JwtProperties jwtProperties;
    private final PaymentProperties paymentProperties;
    private final Environment environment;

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        boolean prod = isProdProfile();
        List<String> violations = new ArrayList<>();

        // 1) JWT 시크릿 — 폴백값 그대로거나 비면 토큰 위조가 가능하다(양쪽 보안 감사 1순위).
        if (isBlank(jwtProperties.getSecret()) || JWT_FALLBACK_SECRET.equals(jwtProperties.getSecret())) {
            violations.add("JWT_SECRET이 주입되지 않아 공개된 폴백 시크릿을 쓰고 있음(토큰 위조 위험)");
        }

        // 2) 프로바이더 mock — 운영에서 mock은 인증/결제를 무조건 통과시킨다.
        if (isMock(environment.getProperty("payment.provider"))) {
            violations.add("결제 프로바이더가 mock임(모든 결제가 무단 승인됨). PAYMENT_PROVIDER=toss|paddle + 키 주입 필요");
        }
        if (isMock(environment.getProperty("oauth.provider"))) {
            violations.add("소셜 로그인 프로바이더가 mock임(임의 code로 계정 탈취 가능). OAUTH_PROVIDER=real 필요");
        }
        if (isMock(environment.getProperty("mail.provider"))) {
            violations.add("메일 프로바이더가 mock임(인증 코드가 발송되지 않고 로그로만 노출). MAIL_PROVIDER=smtp 필요");
        }
        if (isMock(environment.getProperty("llm.provider"))) {
            violations.add("LLM 프로바이더가 mock임(가짜 응답). LLM_PROVIDER=gemini|vertex 필요");
        }

        // 3) 결제 실연동인데 키가 비면 첫 결제 시점에야 실패한다 — 부팅 때 미리 잡는다.
        if ("toss".equalsIgnoreCase(paymentProperties.getProvider())) {
            if (isBlank(paymentProperties.getToss().getSecretKey())) {
                violations.add("결제 프로바이더가 toss인데 TOSS_SECRET_KEY가 비어 있음");
            }
            if (isBlank(paymentProperties.getToss().getClientKey())) {
                violations.add("결제 프로바이더가 toss인데 TOSS_CLIENT_KEY가 비어 있음");
            }
        }
        if ("paddle".equalsIgnoreCase(paymentProperties.getProvider())) {
            var paddle = paymentProperties.getPaddle();
            if (isBlank(paddle.getApiKey())) {
                violations.add("결제 프로바이더가 paddle인데 PADDLE_API_KEY가 비어 있음");
            }
            if (isBlank(paddle.getClientToken())) {
                violations.add("결제 프로바이더가 paddle인데 PADDLE_CLIENT_TOKEN이 비어 있음");
            }
            // 시크릿이 없으면 웹훅을 전부 거부한다 — 패들 경로에서 웹훅은 지급의 주 경로다.
            if (isBlank(paddle.getWebhookSecret())) {
                violations.add("결제 프로바이더가 paddle인데 PADDLE_WEBHOOK_SECRET이 비어 있음(웹훅이 전부 거부됨)");
            }
            // 심사와 실키 발급 전에는 운영 서버에서 샌드박스로 검증할 수밖에 없다(웹훅이 공개
            // 주소로만 오므로 로컬에서는 전 구간을 못 돈다). 그래서 무조건 막지 않고,
            // "알고 켰다"는 표시를 명시적으로 요구한다 — 실수로 이 상태가 남으면 부팅이 선다.
            if (paddle.getBaseUrl() != null && paddle.getBaseUrl().contains("sandbox")
                    && !paddle.isAllowSandboxInProd()) {
                violations.add("결제 프로바이더가 paddle인데 PADDLE_BASE_URL이 샌드박스임"
                        + "(실결제 안 됨). 검증 목적이면 PADDLE_ALLOW_SANDBOX_IN_PROD=true로 명시 필요");
            }
            if (paddle.isAllowSandboxInProd()) {
                log.warn("[결제] 샌드박스 허용이 켜져 있음 — 실결제 안 됨. 오픈 전에 반드시 끌 것");
            }
            paddle.getPriceIds().forEach((item, priceId) -> {
                if (isBlank(priceId)) {
                    violations.add("패들 가격 ID가 비어 있음: " + item);
                }
            });
        }

        if (violations.isEmpty()) {
            return;
        }
        if (prod) {
            // 운영에서는 조용히 뜨는 것이 가장 위험하다 — 부팅을 세워 배포 파이프라인에서 즉시 드러낸다.
            throw new IllegalStateException(
                    "운영 프로파일(prod) 부팅 중단 — 위험한 개발용 설정 감지:\n - "
                            + String.join("\n - ", violations));
        }
        // 개발/스테이징에서는 경고만. 어떤 개발 기본값이 살아 있는지 눈에 띄게 남긴다.
        violations.forEach(v -> log.warn("[운영준비 경고] {}", v));
    }

    private boolean isProdProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if (PROD_PROFILE.equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private boolean isMock(String value) {
        return "mock".equalsIgnoreCase(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
