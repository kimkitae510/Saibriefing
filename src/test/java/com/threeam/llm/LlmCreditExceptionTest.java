package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LlmCreditExceptionTest {

    @Test
    void openAi_insufficientQuotaIsCredit() {
        String body = """
                {"error": {"message": "You exceeded your current quota, please check your plan and billing details.",
                 "type": "insufficient_quota", "param": null, "code": "insufficient_quota"}}
                """;
        assertThat(LlmCreditException.openAi(429, body)).isTrue();
    }

    @Test
    void openAi_rateLimitIsNotCredit() {
        String body = """
                {"error": {"message": "Rate limit reached for requests", "type": "requests",
                 "param": null, "code": "rate_limit_exceeded"}}
                """;
        assertThat(LlmCreditException.openAi(429, body)).isFalse();
        assertThat(LlmCreditException.openAi(500, null)).isFalse();
    }

    @Test
    void anthropic_lowCreditBalanceIsCredit() {
        String body = """
                {"type": "error", "error": {"type": "invalid_request_error",
                 "message": "Your credit balance is too low to access the Anthropic API."}}
                """;
        assertThat(LlmCreditException.anthropic(400, body)).isTrue();
        assertThat(LlmCreditException.anthropic(402, "")).isTrue();
    }

    @Test
    void anthropic_otherBadRequestIsNotCredit() {
        String body = """
                {"type": "error", "error": {"type": "invalid_request_error", "message": "max_tokens: field required"}}
                """;
        assertThat(LlmCreditException.anthropic(400, body)).isFalse();
        assertThat(LlmCreditException.anthropic(429, null)).isFalse();
    }
}
