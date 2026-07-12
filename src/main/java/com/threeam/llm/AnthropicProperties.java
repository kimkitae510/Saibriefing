package com.threeam.llm;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Anthropic(Claude) 호출 설정 — 정밀 판독 실험과 탐색 채팅 두 경로.
// LlmClient 전체 구현이 아니라 쓰는 경로만 든다(AnthropicMessagesClient). 진단(1호출)은 기존 프로바이더.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.anthropic")
public class AnthropicProperties {

    // 100만 토큰당 USD [신규입력, 캐시읽기, 캐시생성(5분), 출력]. 모델 단위로 묶어 두는 이유는
    // 단가를 고정 기본값 하나로 두면 모델을 바꿨을 때 틀린 비용이 맞는 것처럼 찍히기 때문이다.
    // 표에 없는 모델은 0으로 두고 계산을 건너뛴다(추측 금지). 청구 기준은 콘솔이다.
    private static final Map<String, double[]> PRICES = Map.of(
            "claude-opus-5", new double[] {5.0, 0.5, 6.25, 25.0},
            "claude-sonnet-5", new double[] {3.0, 0.3, 3.75, 15.0},
            "claude-sonnet-5-5", new double[] {2.0, 0.2, 2.5, 10.0},
            "claude-haiku-4-5", new double[] {1.0, 0.1, 1.25, 5.0},
            "claude-haiku-5-5", new double[] {0.1, 0.01, 0.125, 0.5});

    private String apiKey = "";

    private String readingModel = "claude-sonnet-5";

    // 판독은 thinking이 기본 켜진 모델이라 한 호출이 분석(90초)보다 오래 걸릴 수 있다.
    private long readingTimeoutSeconds = 180;

    // 탐색 채팅 모델. 비우면 채팅은 기존 경로(OpenAI 채팅 모델, 없으면 LLM_PROVIDER)로 돈다.
    private String chatModel = "";

    // 채팅 추론 세기(output_config.effort). Haiku 5.5는 기본이 medium이라 명시해 둔다.
    private String chatEffort = "medium";

    private long chatTimeoutSeconds = 30;

    public boolean chatEnabled() {
        return chatModel != null && !chatModel.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    // 0이면 위 표에서 모델에 맞는 값을 쓴다. 값을 넣으면 표를 무시하고 그 값이 이긴다
    // (가격 개편이나 할인 적용분을 코드 수정 없이 반영하려고 남긴 구멍).
    private double priceInput = 0;
    private double priceCachedInput = 0;
    private double priceCacheWrite = 0;
    private double priceOutput = 0;
    private double usdKrw = 1400;

    // [신규입력, 캐시읽기, 캐시생성, 출력] 순. 표에도 없고 주입도 없으면 전부 0 → 비용 로그 생략.
    public double[] effectivePrices(String model) {
        double[] table = PRICES.getOrDefault(model, new double[] {0, 0, 0, 0});
        return new double[] {
                priceInput > 0 ? priceInput : table[0],
                priceCachedInput > 0 ? priceCachedInput : table[1],
                priceCacheWrite > 0 ? priceCacheWrite : table[2],
                priceOutput > 0 ? priceOutput : table[3]};
    }
}
