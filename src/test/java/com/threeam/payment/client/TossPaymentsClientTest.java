package com.threeam.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 승인은 돈이 걸린 호출이라 "다시 보내도 되는가"의 경계를 실제 HTTP로 못 박는다.
// 응답만 잃은 경우는 같은 멱등키로 한 번 더 묻고, 느려서 끊긴 경우와 처리 중 응답은 불명으로 남긴다.
class TossPaymentsClientTest {

    private static final String DONE_BODY = """
            {"paymentKey":"pk_1","orderId":"order-1","status":"DONE","method":"카드",
             "approvedAt":"2026-07-19T10:00:00+09:00","totalAmount":6900,"balanceAmount":6900}
            """;

    private interface Reply {
        void handle(HttpExchange exchange) throws IOException;
    }

    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private TossPaymentsClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1", exchange -> {
            idempotencyKeys.add(String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key")));
            exchange.getRequestBody().readAllBytes();
            Reply reply = replies.poll();
            if (reply == null) {
                respond(exchange, 500, "{}");
                return;
            }
            reply.handle(exchange);
        });
        server.start();

        PaymentProperties properties = new PaymentProperties();
        properties.getToss().setSecretKey("test_sk");
        properties.getToss().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.getToss().setTimeoutSeconds(1);
        client = new TossPaymentsClient(properties, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("승인 요청은 주문번호에서 만든 멱등키를 싣는다")
    void confirmSendsIdempotencyKey() {
        replies.add(ex -> respond(ex, 200, DONE_BODY));

        PgPaymentResult result = client.confirm("pk_1", "order-1", 6900).join();

        assertThat(result.status()).isEqualTo(PgStatus.DONE);
        assertThat(idempotencyKeys).containsExactly("order-1-confirm");
    }

    @Test
    @DisplayName("응답이 끊기면 같은 멱등키로 한 번 다시 보내 결과를 받는다")
    void resendsOnceWithSameKeyWhenResponseLost() {
        replies.add(HttpExchange::close);
        replies.add(ex -> respond(ex, 200, DONE_BODY));

        PgPaymentResult result = client.confirm("pk_1", "order-1", 6900).join();

        assertThat(result.status()).isEqualTo(PgStatus.DONE);
        assertThat(idempotencyKeys).containsExactly("order-1-confirm", "order-1-confirm");
    }

    @Test
    @DisplayName("느려서 끊긴 승인은 다시 보내지 않고 불명으로 남긴다")
    void doesNotResendOnTimeout() throws InterruptedException {
        replies.add(ex -> {
            sleep(2000);
            respond(ex, 200, DONE_BODY);
        });

        assertThatThrownBy(() -> client.confirm("pk_1", "order-1", 6900).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(HttpTimeoutException.class);
        TimeUnit.MILLISECONDS.sleep(500);
        assertThat(idempotencyKeys).hasSize(1);
    }

    @Test
    @DisplayName("재전송이 처리 중 응답을 받으면 거절이 아니라 불명이다")
    void processingReplyIsInDoubtNotRejected() {
        replies.add(HttpExchange::close);
        replies.add(ex -> respond(ex, 409,
                "{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\",\"message\":\"처리 중\"}"));

        assertThatThrownBy(() -> client.confirm("pk_1", "order-1", 6900).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(PaymentGatewayException.class);
        assertThat(idempotencyKeys).hasSize(2);
    }

    @Test
    @DisplayName("카드 거절 같은 확실한 4xx는 다시 보내지 않고 FAILED로 확정한다")
    void definiteRejectionIsFailed() {
        replies.add(ex -> respond(ex, 400,
                "{\"code\":\"REJECT_CARD_PAYMENT\",\"message\":\"한도초과\"}"));

        PgPaymentResult result = client.confirm("pk_1", "order-1", 6900).join();

        assertThat(result.status()).isEqualTo(PgStatus.FAILED);
        assertThat(result.failReason()).startsWith("REJECT_CARD_PAYMENT");
        assertThat(idempotencyKeys).hasSize(1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
