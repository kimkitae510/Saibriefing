package com.threeam.payment.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.threeam.payment.entity.PaymentItem;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 토스페이먼츠 실연동. LLM 클라이언트와 같은 원칙 — java.net.http sendAsync 논블로킹,
// 느린 외부 호출이 서블릿 스레드와 DB 커넥션을 점유하지 않게 한다.
//
// 완료 규약(PaymentGateway 참고)을 여기서 구현한다:
// - 2xx → 실상태 파싱해 정상 완료
// - 4xx → "확실한 거절"도 앎이다. FAILED(또는 조회 404 → NOT_FOUND)로 정상 완료
// - 5xx, 타임아웃, 파싱 불능 → 예외 완료(불명) → 호출부가 재동기화로 넘긴다
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "toss")
public class TossPaymentsClient implements PaymentGateway {

    // 응답이 끊긴 승인을 같은 키로 다시 묻기 전의 대기. 끊긴 직후 바로 붙으면 같은 순간 단절에 또 걸리기 쉽다.
    private static final long RESEND_DELAY_MILLIS = 300;

    // 거절이 아니라 "같은 키의 승인을 처리 중이거나 이미 처리했다"는 응답. FAILED로 적으면 승인된 결제를
    // 실패로 남기게 되므로 불명으로 넘겨 재동기화가 조회로 확정하게 한다.
    private static final Set<String> CONFIRM_IN_DOUBT_CODES =
            Set.of("IDEMPOTENT_REQUEST_PROCESSING", "ALREADY_PROCESSED_PAYMENT");

    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TossPaymentsClient(PaymentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public CompletableFuture<String> prepare(String orderId, PaymentItem item, int amount) {
        // 토스는 위젯이 인증을 마친 뒤에야 paymentKey가 생긴다 — 미리 만들어 둘 주문이 없다.
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<PgPaymentResult> confirm(String paymentKey, String orderId, int amount) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("paymentKey", paymentKey)
                .put("orderId", orderId)
                .put("amount", amount);
        HttpRequest request = baseRequest("/payments/confirm")
                // 주문 하나에 승인은 한 번(claimConfirm 선점)이라 키를 주문번호에서 만든다. 재전송이 같은 키를
                // 실어야 토스가 새 승인으로 처리하지 않고 첫 승인의 결과를 돌려준다.
                .header("Idempotency-Key", confirmIdempotencyKey(orderId))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        // 승인 4xx = 카드 한도, 잔액 부족 등 확실한 거절. 사유를 남기고 FAILED로 확정한다.
        Function<JsonNode, PgPaymentResult> on4xx = node -> confirmRejected(paymentKey, orderId, node);
        return send(request, on4xx)
                .exceptionallyCompose(ex -> resendIfResponseLost(ex, request, on4xx, orderId));
    }

    static String confirmIdempotencyKey(String orderId) {
        return orderId + "-confirm";
    }

    // 응답만 잃은 승인(연결 끊김)은 같은 키로 한 번 더 물어 그 자리에서 확정한다. 느려서 끊긴(타임아웃)
    // 승인은 다시 보내지 않는다 — 토스는 아직 처리 중이라 재전송도 처리 중 응답만 받고, 느린 PG에 호출만 얹는다.
    private CompletableFuture<PgPaymentResult> resendIfResponseLost(
            Throwable ex, HttpRequest request, Function<JsonNode, PgPaymentResult> on4xx, String orderId) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        if (!(cause instanceof IOException) || cause instanceof HttpTimeoutException) {
            return CompletableFuture.failedFuture(cause);
        }
        log.warn("토스 승인 응답 유실 — 같은 멱등키로 1회 재전송 orderId={} cause={}", orderId, cause.toString());
        Metrics.counter("payment.confirm.resend").increment();
        return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(RESEND_DELAY_MILLIS, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> send(request, on4xx));
    }

    private PgPaymentResult confirmRejected(String paymentKey, String orderId, JsonNode errorBody) {
        String code = errorBody.path("code").asText("");
        if (CONFIRM_IN_DOUBT_CODES.contains(code)) {
            throw new PaymentGatewayException("토스 승인 결과 미확정: " + code);
        }
        return rejected(paymentKey, orderId, errorBody);
    }

    @Override
    public CompletableFuture<PgPaymentResult> cancel(String paymentKey, int cancelAmount, String reason,
                                                     String idempotencyKey, RefundAccount refundAccount) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("cancelReason", reason)
                .put("cancelAmount", cancelAmount);
        if (refundAccount != null) {
            body.set("refundReceiveAccount", objectMapper.createObjectNode()
                    .put("bank", refundAccount.bank())
                    .put("accountNumber", refundAccount.accountNumber())
                    .put("holderName", refundAccount.holderName()));
        }
        HttpRequest request = baseRequest("/payments/" + paymentKey + "/cancel")
                // 같은 키의 재시도를 토스가 최초 1회로 흡수한다 — 응답 유실 후 재시도가 이중 환불이 되지 않는다.
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return send(request, node -> rejected(paymentKey, null, node));
    }

    @Override
    public CompletableFuture<PgPaymentResult> findByOrderId(String orderId, String paymentKey) {
        HttpRequest request = baseRequest("/payments/orders/" + orderId)
                .GET()
                .build();
        // 조회 404 = 토스에 이 주문이 없다(위젯까지 못 감). 거절이 아니라 부재로 구분한다.
        return send(request, node -> "NOT_FOUND_PAYMENT".equals(node.path("code").asText())
                ? PgPaymentResult.of(null, orderId, PgStatus.NOT_FOUND)
                : rejected(null, orderId, node));
    }

    private HttpRequest.Builder baseRequest(String path) {
        String basic = Base64.getEncoder()
                .encodeToString((properties.getToss().getSecretKey() + ":").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.getToss().getBaseUrl() + path))
                .timeout(Duration.ofSeconds(properties.getToss().getTimeoutSeconds()))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/json");
    }

    private CompletableFuture<PgPaymentResult> send(HttpRequest request,
                                                    Function<JsonNode, PgPaymentResult> on4xx) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    JsonNode body = readTree(response.body());
                    if (response.statusCode() / 100 == 2) {
                        return parse(body);
                    }
                    if (response.statusCode() / 100 == 4) {
                        log.warn("토스 거절 응답 status={} body={}", response.statusCode(), response.body());
                        return on4xx.apply(body);
                    }
                    // 5xx는 토스 쪽 장애 — 실제 처리 여부를 모른다. 불명으로 던져 재동기화에 맡긴다.
                    throw new PaymentGatewayException("토스 응답 5xx: " + response.statusCode());
                });
    }

    private PgPaymentResult rejected(String paymentKey, String orderId, JsonNode errorBody) {
        String code = errorBody.path("code").asText("");
        String message = errorBody.path("message").asText("");
        return new PgPaymentResult(paymentKey, orderId, PgStatus.FAILED, null, null,
                code + ": " + message, 0, null);
    }

    private PgPaymentResult parse(JsonNode node) {
        int totalAmount = node.path("totalAmount").asInt(0);
        int balanceAmount = node.path("balanceAmount").asInt(totalAmount);
        PgPaymentResult.VirtualAccount virtualAccount = null;
        if (node.hasNonNull("virtualAccount")) {
            JsonNode va = node.get("virtualAccount");
            virtualAccount = new PgPaymentResult.VirtualAccount(
                    va.path("bankCode").asText(null),
                    va.path("accountNumber").asText(null),
                    parseTime(va.path("dueDate").asText(null)));
        }
        String failReason = null;
        if (node.hasNonNull("failure")) {
            failReason = node.path("failure").path("code").asText("")
                    + ": " + node.path("failure").path("message").asText("");
        }
        return new PgPaymentResult(
                node.path("paymentKey").asText(null),
                node.path("orderId").asText(null),
                mapStatus(node.path("status").asText("")),
                node.path("method").asText(null),
                parseTime(node.path("approvedAt").asText(null)),
                failReason,
                Math.max(0, totalAmount - balanceAmount),
                virtualAccount);
    }

    private PgStatus mapStatus(String tossStatus) {
        return switch (tossStatus) {
            case "READY" -> PgStatus.READY;
            case "IN_PROGRESS" -> PgStatus.IN_PROGRESS;
            case "WAITING_FOR_DEPOSIT" -> PgStatus.WAITING_FOR_DEPOSIT;
            case "DONE" -> PgStatus.DONE;
            case "CANCELED" -> PgStatus.CANCELED;
            case "PARTIAL_CANCELED" -> PgStatus.PARTIAL_CANCELED;
            case "ABORTED" -> PgStatus.FAILED;
            case "EXPIRED" -> PgStatus.EXPIRED;
            default -> {
                log.warn("알 수 없는 토스 상태: {}", tossStatus);
                yield PgStatus.UNKNOWN;
            }
        };
    }

    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (RuntimeException e) {
            // 시각은 부가 정보 — 파싱 실패로 상태 반영 전체를 불명으로 만들지 않는다.
            return LocalDateTime.now();
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new PaymentGatewayException("토스 응답 파싱 실패", e);
        }
    }
}
