package com.threeam.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.net.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Vertex AI 실연동. 같은 Gemini 모델을 GCP 정식 상품 경로로 호출한다 —
// 사용료가 GCP 청구서로 잡혀 크레딧이 적용된다. llm.provider=vertex 일 때만 빈으로 등록된다.
// AI Studio 경로(GeminiLlmClient)와 언제든 왕복 가능: LLM_PROVIDER만 바꿔 재시작.
@Slf4j
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "vertex")
public class VertexAiLlmClient extends GoogleGenerateContentClient {

    private static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private final VertexAiProperties properties;
    private final GoogleCredentials credentials;

    // 생성자가 둘(운영/테스트용)이라 Spring이 쓸 쪽을 명시해야 한다.
    @Autowired
    public VertexAiLlmClient(VertexAiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, loadCredentials());
    }

    VertexAiLlmClient(VertexAiProperties properties, ObjectMapper objectMapper, GoogleCredentials credentials) {
        super(objectMapper);
        this.properties = properties;
        this.credentials = credentials;
    }

    // ADC 표준: GOOGLE_APPLICATION_CREDENTIALS가 가리키는 서비스 계정 키를 읽는다.
    // 기동 시점에 로드해서, 자격증명 누락이면 요청 단계가 아니라 부팅에서 바로 죽게 한다.
    private static GoogleCredentials loadCredentials() {
        try {
            return GoogleCredentials.getApplicationDefault().createScoped(CLOUD_PLATFORM_SCOPE);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "GCP 자격증명 로드 실패 — GOOGLE_APPLICATION_CREDENTIALS 환경변수 확인 필요", e);
        }
    }

    @Override
    String endpoint() {
        return properties.endpoint();
    }

    @Override
    String deepEndpoint() {
        return properties.assessmentEndpoint();
    }

    @Override
    String quickEndpoint() {
        return properties.matchEndpoint();
    }

    @Override
    String quickThinkingLevel() {
        return properties.getMatchThinkingLevel();
    }

    @Override
    int quickThinkingBudget() {
        return properties.getMatchThinkingBudget();
    }

    @Override
    void authorize(HttpRequest.Builder builder) {
        try {
            // 유효한 토큰이 있으면 no-op, 만료 시에만 재발급(약 1시간 주기)이라 요청마다 불러도 싸다.
            credentials.refreshIfExpired();
            builder.header("Authorization", "Bearer " + credentials.getAccessToken().getTokenValue());
        } catch (IOException e) {
            log.error("Vertex AI 토큰 갱신 실패", e);
            throw new LlmException();
        }
    }

    @Override
    long timeoutSeconds() {
        return properties.getTimeoutSeconds();
    }

    @Override
    long assessmentTimeoutSeconds() {
        return properties.getAssessmentTimeoutSeconds();
    }

    @Override
    int thinkingBudget() {
        return properties.getThinkingBudget();
    }

    @Override
    String thinkingLevel() {
        return properties.getThinkingLevel();
    }

    @Override
    double temperature() {
        return properties.getTemperature();
    }

    @Override
    int assessmentThinkingBudget() {
        return properties.getAssessmentThinkingBudget();
    }

    @Override
    String assessmentThinkingLevel() {
        return properties.getAssessmentThinkingLevel();
    }

    @Override
    double[] pricesPerMillion() {
        return new double[] {properties.getInputPricePerMillion(),
                properties.getCachedInputPricePerMillion(),
                properties.getOutputPricePerMillion()};
    }

    @Override
    String providerName() {
        return "Vertex AI";
    }
}
