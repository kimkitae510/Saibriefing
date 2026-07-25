package com.threeam.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.payment.client.PaddleSignatureVerifier;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.service.PaymentService;
import com.threeam.payment.service.WebhookRateLimiter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// PG 웹훅 수신. 인증 없이 열리는 경로라 페이로드를 절대 믿지 않는다 —
// orderId만 꺼내 "이 주문을 다시 봐라"는 트리거로 쓰고, 실상태는 PG 조회 API로 재확인한다.
// 위조 웹훅이 할 수 있는 최대치는 불필요한 조회 한 번이지 상태 변경이 아니다.
@Slf4j
@RestController
@RequestMapping("/api/payments/webhook")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentService paymentService;
    private final WebhookRateLimiter webhookRateLimiter;
    private final PaddleSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final PaymentProperties properties;

    @PostMapping("/toss")
    public CompletableFuture<ResponseEntity<Void>> toss(@RequestBody JsonNode payload) {
        // 상태 변경 이벤트는 data.orderId, 가상계좌 입금 콜백은 최상위 orderId에 실려 온다.
        String orderId = payload.path("data").path("orderId").asText(
                payload.path("orderId").asText(null));
        if (orderId == null || orderId.isBlank()) {
            log.warn("orderId 없는 웹훅 무시: eventType={}", payload.path("eventType").asText(""));
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }
        // 같은 주문의 반복 웹훅은 쿨다운 안에서 삼킨다(위조 반복 POST로 PG 조회 비용을 태우는 것 방지).
        // 200으로 응답해 토스가 재전송하지 않게 한다 — 진짜 이벤트를 흘려도 재동기화가 확정한다.
        if (!webhookRateLimiter.allow(orderId)) {
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }
        // 논블로킹 — 웹훅이 몰려도 서블릿 스레드를 PG 조회 시간만큼 잡아두지 않는다.
        return syncWithinBudget(orderId);
    }

    // 패들은 결제창에서 결제가 끝나버려 이 웹훅이 지급의 주 경로다(프론트가 결과를 못 알리고
    // 창이 닫히는 경우가 있다). 그래서 토스 웹훅보다 한 겹 더 조인다 — 서명을 검증해
    // 패들이 보낸 것만 받아들이고, 그 다음에야 orderId를 꺼내 실상태를 재조회한다.
    @PostMapping("/paddle")
    public CompletableFuture<ResponseEntity<Void>> paddle(
            @RequestHeader(value = "Paddle-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        // 서명은 본문 원문 그대로에 대해 계산된다 — 파싱한 뒤 다시 만든 문자열로는 검증이 안 된다.
        if (!signatureVerifier.verify(rawBody, signature)) {
            log.warn("패들 웹훅 서명 검증 실패 — 무시");
            return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("패들 웹훅 본문 파싱 실패", e);
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }
        String orderId = payload.path("data").path("custom_data").path("order_id").asText(null);
        if (orderId == null || orderId.isBlank()) {
            // 환불 확정은 거래가 아니라 조정(adjustment) 이벤트로 온다. 조정에는 custom_data가
            // 없고 거래 id만 실려 있어, 그 id로 우리 주문을 찾는다 — 이 경로가 없으면
            // 대시보드에서 환불해도 서버가 몰라 이용권이 회수되지 않는다.
            String transactionId = payload.path("data").path("transaction_id").asText(null);
            if (transactionId != null && !transactionId.isBlank()) {
                orderId = paymentService.orderIdByPaymentKey(transactionId).orElse(null);
            }
        }
        if (orderId == null || orderId.isBlank()) {
            // 구독, 고객 이벤트 등 우리 주문과 무관한 알림. 200으로 받아 재전송을 부르지 않는다.
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }
        if (!webhookRateLimiter.allow(orderId)) {
            return CompletableFuture.completedFuture(ResponseEntity.ok().build());
        }
        return syncWithinBudget(orderId);
    }

    // PG 재조회가 응답 기한 안에 끝나면 그 결과로 답한다 — 실패면 500이라 PG가 재전송한다.
    // 못 끝나면 200을 먼저 돌려주고 조회는 뒤에서 계속한다. PG가 느린 바로 그때 응답까지 늦으면
    // 기한을 넘긴 웹훅이 재전송돼 같은 조회가 겹쳐 쌓인다. 늦게 실패한 조회는 불명 상태면 재동기화가,
    // 입금 대기면 입금 기한 뒤 확인이 받친다.
    private CompletableFuture<ResponseEntity<Void>> syncWithinBudget(String orderId) {
        CompletableFuture<Void> sync = paymentService.syncByOrderId(orderId);
        sync.whenComplete((ignored, ex) -> {
            if (ex != null) {
                log.error("웹훅 PG 재조회 실패 orderId={}", orderId, ex);
            }
        });
        return sync.thenApply(ignored -> ResponseEntity.ok().<Void>build())
                .completeOnTimeout(ResponseEntity.ok().build(),
                        properties.getWebhookResponseBudgetMillis(), TimeUnit.MILLISECONDS);
    }
}
