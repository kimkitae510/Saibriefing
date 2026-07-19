package com.threeam.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.payment.client.PaddleSignatureVerifier;
import com.threeam.payment.client.PaymentGatewayException;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.service.PaymentService;
import com.threeam.payment.service.WebhookRateLimiter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// 웹훅 응답 시간이 PG 조회 시간에 묶이면 PG가 느린 바로 그때 재전송이 몰린다.
// 기한 안에 끝난 조회는 결과대로(실패면 PG 재전송), 기한을 넘긴 조회는 200을 먼저 주는 경계를 고정한다.
@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {

    private static final long BUDGET_MILLIS = 200;

    @Mock
    private PaymentService paymentService;

    private PaymentWebhookController controller;
    private JsonNode payload;

    @BeforeEach
    void setUp() throws Exception {
        PaymentProperties properties = new PaymentProperties();
        properties.setWebhookResponseBudgetMillis(BUDGET_MILLIS);
        ObjectMapper objectMapper = new ObjectMapper();
        controller = new PaymentWebhookController(paymentService, new WebhookRateLimiter(),
                new PaddleSignatureVerifier(properties), objectMapper, properties);
        payload = objectMapper.readTree("{\"data\":{\"orderId\":\"order-1\"}}");
    }

    @Test
    @DisplayName("재조회가 기한 안에 끝나면 200으로 답한다")
    void respondsOkWhenSyncFinishesInTime() {
        given(paymentService.syncByOrderId("order-1")).willReturn(CompletableFuture.completedFuture(null));

        ResponseEntity<Void> response = controller.toss(payload).join();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("재조회가 기한 안에 실패하면 예외로 끝나 PG가 재전송하게 한다")
    void failsWhenSyncFailsInTime() {
        given(paymentService.syncByOrderId("order-1"))
                .willReturn(CompletableFuture.failedFuture(new PaymentGatewayException("토스 응답 5xx: 503")));

        assertThatThrownBy(() -> controller.toss(payload).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PaymentGatewayException.class);
    }

    @Test
    @DisplayName("재조회가 기한을 넘기면 200을 먼저 주고 조회는 계속 둔다")
    void respondsOkFirstWhenSyncIsSlow() {
        CompletableFuture<Void> slowSync = new CompletableFuture<>();
        given(paymentService.syncByOrderId("order-1")).willReturn(slowSync);

        long started = System.nanoTime();
        ResponseEntity<Void> response = controller.toss(payload).join();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(BUDGET_MILLIS - 20).isLessThan(2_000);
        assertThat(slowSync).isNotDone();
    }
}
