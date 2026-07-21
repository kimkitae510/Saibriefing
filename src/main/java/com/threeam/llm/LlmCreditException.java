package com.threeam.llm;

// 선불 크레딧이 바닥난 실패. 충전 전까지 모든 호출이 같은 이유로 실패하므로 일시 장애(한도, 과부하)와
// 섞이면 안 된다 — 지표 reason=credit으로 따로 세서 한 건만 나와도 바로 알린다.
// 처리 흐름은 부모(LlmException)와 같다.
class LlmCreditException extends LlmException {

    // OpenAI는 한도 초과와 크레딧 소진이 둘 다 429다. 본문의 코드로만 갈린다(rate_limit_exceeded / insufficient_quota).
    static boolean openAi(int status, String body) {
        return status == 429 && body != null && body.contains("insufficient_quota");
    }

    // Anthropic은 크레딧 소진을 400(invalid_request_error)에 "credit balance" 문구로 돌려준다.
    // 결제 문제 전용 402 billing_error도 같은 성격이라 함께 묶는다.
    static boolean anthropic(int status, String body) {
        if (status == 402) {
            return true;
        }
        return body != null && (body.contains("billing_error") || body.contains("credit balance"));
    }
}
