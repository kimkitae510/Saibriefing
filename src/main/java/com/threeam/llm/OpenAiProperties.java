package com.threeam.llm;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 외부 판독 경로의 호출 설정.
// 단가는 모델 단위로 묶는다 — 하나짜리 기본값으로 두면 모델을 바꿨을 때 틀린 비용이
// 맞는 것처럼 찍힌다. 표에 없으면 0이라 계산을 건너뛰고 토큰 수만 남긴다(추측 금지).
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.openai")
public class OpenAiProperties {

    // 100만 토큰당 USD [신규입력, 캐시입력, 출력]
    // sol은 프로모션가(2026-08-23 인하, 최소 11-21까지) — 종료 후 5.0/0.5/30.0 재확인.
    private static final Map<String, double[]> PRICES = Map.of(
            "gpt-5.6-sol", new double[] {4.0, 0.4, 20.0},
            "gpt-5.6", new double[] {4.0, 0.4, 20.0},
            "gpt-5.6-terra", new double[] {2.0, 0.2, 12.0},
            "gpt-5.6-luna", new double[] {0.2, 0.02, 1.2});

    private String apiKey = "";

    // 기본값을 두지 않는다 — 비어 있으면 호출 전에 막고 이유를 로그로 남긴다.
    private String readingModel = "";

    // 결정 호출 전용 모델(선택). 비우면 판독 모델을 그대로 쓴다 — 결정은 재료가 다 차려진
    // 압축 작업이라 더 싼 모델로도 되는지 실험하는 스위치다.
    private String decisionModel = "";

    public String effectiveDecisionModel() {
        return decisionModel == null || decisionModel.isBlank() ? readingModel : decisionModel;
    }

    // 판정 호출 전용 모델(선택). 비우면 판독 모델을 그대로 쓴다 — 판정의 실행력이
    // 모델 능력에 달렸는지 가르는 실험 스위치다.
    private String verdictModel = "";

    public String effectiveVerdictModel() {
        return verdictModel == null || verdictModel.isBlank() ? readingModel : verdictModel;
    }

    // 탐색 채팅 전용 모델(선택). 비우면 채팅은 이 경로를 타지 않고 llm.provider 쪽(LlmClient)으로 돈다 —
    // 채팅은 싼 모델(luna)로, 판독은 강한 모델로 가르는 스위치다.
    private String chatModel = "";

    public boolean chatEnabled() {
        return chatModel != null && !chatModel.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    private long readingTimeoutSeconds = 240;

    // 채팅 응답 대기 상한. 판독(240초)보다 짧게 — 대화 리듬이고, usage.chat-lock-ttl-seconds보다 작아야 한다.
    private long chatTimeoutSeconds = 50;

    // 출력 토큰이 비용의 대부분이라 이 값이 단가를 가장 크게 움직인다.
    // 실효 기본값은 application.yml의 ${...:low}가 정한다(여기 필드 기본값은 yml 키가
    // 아예 없을 때만). 비우면 파라미터를 보내지 않는다.
    private String reasoningEffort = "low";

    // 100만 토큰당 USD.
    private double priceInput = 0;
    private double priceCachedInput = 0;
    private double priceOutput = 0;
    private double usdKrw = 1450;

    // [신규입력, 캐시입력, 출력] 순. 주입값이 있으면 표를 무시하고 그게 이긴다.
    // 모델을 인자로 받는다 — 판독과 결정이 다른 모델을 쓸 수 있어서다.
    public double[] effectivePrices(String model) {
        double[] table = PRICES.getOrDefault(model, new double[] {0, 0, 0});
        return new double[] {
                priceInput > 0 ? priceInput : table[0],
                priceCachedInput > 0 ? priceCachedInput : table[1],
                priceOutput > 0 ? priceOutput : table[2]};
    }
}
