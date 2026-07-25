package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Gemini 연동 설정. 실제 키는 환경변수(LLM_API_KEY)로 주입한다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.gemini")
public class GeminiProperties {

    private String apiKey;
    private String model = "gemini-2.5-flash-lite";

    // 정밀 판단(분석 리포트) 전용 모델. 비우면 기본 model을 그대로 쓴다.
    // vertex 경로와 같은 분리 — 어느 경로로 돌아도 분석만 강한 모델을 쓸 수 있게.
    private String assessmentModel;

    private String baseUrl = "https://generativelanguage.googleapis.com/v1beta";

    // 응답 전체 대기 상한. 초과 시 호출이 실패로 완료되고 폴백 메시지가 저장된다.
    private long timeoutSeconds = 50;

    // 분석(deep) 전용 응답 상한. 채팅과 분리해 둔다 — 전에는 채팅 값의 3배로 계산했는데,
    // 채팅 타임아웃을 만질 때마다 분석이 조용히 따라 움직여 usage.assessment-lock-ttl-seconds와
    // spring request-timeout을 넘길 뻔했다(실측). 두 값은 각자의 이유로 정해져야 한다.
    private long assessmentTimeoutSeconds = 90;

    // thinking 세기. 제어 필드가 세대마다 달라 둘을 따로 둔다 — 2.5 계열은 토큰 예산(thinkingBudget),
    // 3.x는 단계(thinkingLevel). 모델을 바꿀 때 코드를 안 고쳐도 되게 설정으로 뺐다.
    private int thinkingBudget = 2048;
    private String thinkingLevel = "low";

    // 채팅 temperature. 분석은 0으로 고정이고, 채팅은 말투가 굳지 않을 만큼만 기본값(1.0)보다 낮춘다.
    private double temperature = 0.7;

    // 분석(deep) 전용 thinking. 분석은 유형 게이트 → 요인 5슬롯 → 교차 점검의 다단 절차라
    // 채팅보다 추론량이 크다. 2.5 계열은 -1이 동적(모델 재량), 3.x는 high 고정.
    private int assessmentThinkingBudget = -1;
    private String assessmentThinkingLevel = "medium";

    // 100만 토큰당 단가(USD). 모델마다 다르므로 기본값은 0으로 두고 실제 값은 설정으로 주입한다 —
    // 여기 임의의 숫자를 박아두면 모델을 바꿨을 때 옛 단가로 계산된 비용이 맞는 것처럼 찍힌다.
    // 0이면 비용 계산을 건너뛰고 토큰만 남긴다. 값은 공식 가격표에서 확인해 넣어라.
    private double inputPricePerMillion = 0;
    private double cachedInputPricePerMillion = 0;
    private double outputPricePerMillion = 0;

    // 저가 판별 전용 모델. 비우면 채팅 모델을 그대로 쓴다 — 설정을 안 넣어도 돌아야 한다.
    // 상담이 아니라 "이 말이 어느 갈래인가"만 가리는 자리라 강한 모델도 긴 추론도 값을 안 한다.
    private String matchModel;
    private int matchThinkingBudget = 0;
    private String matchThinkingLevel = "low";
}
