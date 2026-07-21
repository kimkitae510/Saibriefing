package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class LlmFailureReasonTest {

    @Test
    void none_whenNoFailure() {
        assertThat(LlmFailureReason.of(null)).isEqualTo("none");
    }

    @Test
    void credit_throughAsyncWrapper() {
        assertThat(LlmFailureReason.of(new CompletionException(new LlmCreditException()))).isEqualTo("credit");
    }

    @Test
    void status_splitsRateLimitOverloadAndOthers() {
        assertThat(LlmFailureReason.of(new CompletionException(new LlmHttpStatusException(429)))).isEqualTo("quota");
        assertThat(LlmFailureReason.of(new LlmHttpStatusException(503))).isEqualTo("overloaded");
        assertThat(LlmFailureReason.of(new LlmHttpStatusException(529))).isEqualTo("overloaded");
        assertThat(LlmFailureReason.of(new LlmHttpStatusException(500))).isEqualTo("http_5xx");
        assertThat(LlmFailureReason.of(new LlmHttpStatusException(400))).isEqualTo("http_4xx");
    }

    @Test
    void timeout_beforeNetwork() {
        // HttpTimeoutException은 IOException의 하위라 network로 새면 타임아웃 알림이 안 울린다.
        assertThat(LlmFailureReason.of(new CompletionException(new HttpTimeoutException("request timed out"))))
                .isEqualTo("timeout");
        assertThat(LlmFailureReason.of(new CompletionException(new TimeoutException()))).isEqualTo("timeout");
    }

    @Test
    void connectFailed_forRefusedConnection() {
        assertThat(LlmFailureReason.of(new CompletionException(new ConnectException("refused"))))
                .isEqualTo("connect_failed");
    }

    @Test
    void parse_forUnusableResponse_otherForTheRest() {
        assertThat(LlmFailureReason.of(new CompletionException(new LlmException()))).isEqualTo("parse");
        assertThat(LlmFailureReason.of(new IllegalStateException())).isEqualTo("other");
    }
}
