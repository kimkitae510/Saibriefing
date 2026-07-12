package com.threeam.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Anthropic Messages API 호출 — 정밀 판독 실험(LLM_READING_PROVIDER=anthropic)과
// 탐색 채팅(LLM_ANTHROPIC_CHAT_MODEL) 두 경로. 모델, 상한, 추론 세기만 호출마다 다르다.
//
// 스트리밍으로 받는다. 판독은 출력이 크고 thinking까지 얹혀 한 번에 다 받으면 응답이
// 도착하기 전에 클라이언트가 먼저 끊는다(실측: 180초 타임아웃). 스트림은 헤더가 즉시
// 오고 본문이 흘러 들어와, 오래 걸리는 판독과 정말 끊긴 연결이 구분된다.
@Slf4j
@Component
public class AnthropicMessagesClient {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long RETRY_DELAY_SECONDS = 2;
    // thinking(기본 켜짐)과 리포트 본문이 한 상한을 같이 쓴다 — 빠듯하면 응답이 중간에 잘린다.
    private static final int READING_MAX_TOKENS = 32000;
    // 채팅 답은 짧지만 thinking이 같은 상한을 쓴다(실측 평균 출력 500 안팎).
    private static final int CHAT_MAX_TOKENS = 8000;

    private final ObjectMapper objectMapper;
    private final AnthropicProperties properties;
    private final HttpClient httpClient;

    public AnthropicMessagesClient(ObjectMapper objectMapper, AnthropicProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        AtomicInteger seq = new AtomicInteger();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "llm-anthropic-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    // 판독 JSON 생성. messages의 SYSTEM은 system 필드로(프롬프트 캐시 표시 포함),
    // USER는 user 턴으로 나눠 싣는다 — Google 클라이언트와 같은 분리 규칙.
    //
    // 스키마 강제를 먼저 쓰고, 문법 한도로 거절당하면 같은 스키마를 프롬프트에 실어 재시도한다.
    // 판독 스키마는 Anthropic 구조화 출력이 컴파일할 수 있는 크기를 넘는다(실측 400) —
    // 스키마를 깎아 맞추면 두 프로바이더의 출력이 갈라지므로, 강제 수단만 바꾸고 계약은 그대로 둔다.
    public CompletableFuture<String> generateReadingJson(List<ChatMessage> messages,
                                                         Map<String, Object> googleSchema) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ANTHROPIC_API_KEY가 비어 있다 — 판독 프로바이더 설정 확인"));
        }
        Map<String, Object> schema = StrictJsonSchema.fromGoogle(googleSchema);
        Call call = new Call("판독", properties.getReadingModel(), READING_MAX_TOKENS, null,
                properties.getReadingTimeoutSeconds());
        return send(call, messages, schema)
                .thenCompose(response -> exceedsGrammarLimit(response)
                        ? send(call, withSchemaInPrompt(messages, schema), null)
                        : CompletableFuture.completedFuture(response))
                .thenApply(response -> readStream(call, response))
                .whenComplete((result, ex) -> countCall(call, ex));
    }

    // 탐색 채팅 답(산문). 실패하면 예외로 끝나고, 다른 경로로 넘기는 판단은 ChatLlm이 한다.
    public CompletableFuture<String> generateChatText(List<ChatMessage> messages) {
        if (!properties.chatEnabled()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("anthropic 채팅 모델이나 키가 비어 있다 — 채팅 프로바이더 설정 확인"));
        }
        Call call = new Call("채팅", properties.getChatModel(), CHAT_MAX_TOKENS,
                properties.getChatEffort(), properties.getChatTimeoutSeconds());
        return send(call, messages, null)
                .thenApply(response -> readStream(call, response))
                .whenComplete((result, ex) -> countCall(call, ex));
    }

    // 호출마다 달라지는 것만 묶는다. effort가 null이면 모델 기본값.
    private record Call(String kind, String model, int maxTokens, String effort, long timeoutSeconds) {
    }

    // 태그 키는 다른 클라이언트의 llm.calls와 같아야 한다 — Prometheus는 키가 다른 같은 이름을 거부한다.
    private void countCall(Call call, Throwable ex) {
        Metrics.counter("llm.calls",
                "provider", "anthropic",
                "result", ex == null ? "success" : "error",
                "reason", ex == null ? "none" : "call_failed").increment();
    }

    private CompletableFuture<Response> send(Call call, List<ChatMessage> messages,
                                             Map<String, Object> schema) {
        return sendOnce(call, messages, schema).thenCompose(response -> {
            // 529/503은 잠깐 기다렸다 1회 재시도. 429(한도)와 4xx는 다시 보내도 같은 결과라 그대로 둔다.
            if (response.status() != 529 && response.status() != 503) {
                return CompletableFuture.completedFuture(response);
            }
            log.warn("anthropic {} {}(과부하) — {}초 뒤 1회 재시도", call.kind(), response.status(),
                    RETRY_DELAY_SECONDS);
            Metrics.counter("llm.retries", "provider", "anthropic", "reason", "overloaded")
                    .increment();
            return CompletableFuture.supplyAsync(() -> null,
                            CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS))
                    .thenCompose(ignored -> sendOnce(call, messages, schema));
        });
    }

    private CompletableFuture<Response> sendOnce(Call call, List<ChatMessage> messages,
                                                 Map<String, Object> schema) {
        return httpClient.sendAsync(buildRequest(call, messages, schema),
                        HttpResponse.BodyHandlers.ofLines())
                .thenApply(http -> http.statusCode() / 100 == 2
                        ? new Response(http.statusCode(), null, http.body())
                        // 오류 본문은 SSE가 아니라 JSON 한 덩이 — 스트림을 여기서 접어 문자열로 본다.
                        : new Response(http.statusCode(),
                                http.body().collect(Collectors.joining("\n")), null));
    }

    private record Response(int status, String errorBody, Stream<String> events) {
    }

    // 스키마가 커서 문법으로 컴파일되지 못한 경우만 골라낸다 — 다른 400은 그대로 실패시킨다.
    private boolean exceedsGrammarLimit(Response response) {
        if (response.status() != 400 || response.errorBody() == null) {
            return false;
        }
        boolean tooLarge = response.errorBody().contains("compiled grammar")
                || response.errorBody().contains("union types");
        if (tooLarge) {
            log.warn("anthropic 판독 스키마가 문법 한도 초과 — 스키마를 프롬프트로 옮겨 재시도");
            Metrics.counter("llm.retries", "provider", "anthropic", "reason", "schema_too_large")
                    .increment();
        }
        return tooLarge;
    }

    // 강제 수단이 없어지는 대신 계약을 마지막 지시로 붙인다(사연 뒤 = 가장 가까운 자리).
    private List<ChatMessage> withSchemaInPrompt(List<ChatMessage> messages,
                                                 Map<String, Object> schema) {
        List<ChatMessage> out = new ArrayList<>(messages);
        try {
            out.add(ChatMessage.user("출력은 아래 JSON Schema를 그대로 따르는 JSON 객체 하나다."
                    + " 설명, 인사, 코드펜스 없이 '{'로 시작해 '}'로 끝낸다."
                    + " required에 있는 키는 하나도 빠뜨리지 않고, 값이 없는 자리에는 null을 쓴다.\n\n"
                    + objectMapper.writeValueAsString(schema)));
        } catch (Exception e) {
            throw new LlmException();
        }
        return out;
    }

    private HttpRequest buildRequest(Call call, List<ChatMessage> messages, Map<String, Object> schema) {
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> turns = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.role() == LlmRole.SYSTEM) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(message.content());
            } else {
                turns.add(Map.of(
                        "role", message.role() == LlmRole.ASSISTANT ? "assistant" : "user",
                        "content", message.content()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", call.model());
        body.put("max_tokens", call.maxTokens());
        body.put("stream", true);
        // 지시 전문은 호출마다 동일 — 캐시 표시를 붙이면 재요청 입력이 약 1/10 단가가 된다.
        // 모델별 최소 길이에 못 미치면 캐시가 조용히 안 걸릴 뿐 오류는 아니다.
        body.put("system", List.of(Map.of(
                "type", "text",
                "text", system.toString(),
                "cache_control", Map.of("type", "ephemeral"))));
        body.put("messages", turns);
        Map<String, Object> outputConfig = new LinkedHashMap<>();
        if (schema != null) {
            outputConfig.put("format", Map.of("type", "json_schema", "schema", schema));
        }
        if (call.effort() != null && !call.effort().isBlank()) {
            outputConfig.put("effort", call.effort());
        }
        if (!outputConfig.isEmpty()) {
            body.put("output_config", outputConfig);
        }

        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    // 스트리밍이라 이 상한은 첫 응답(헤더)까지만 잰다. 본문이 오래 흐르는 것은
                    // 정상이므로 여기서 끊지 않는다 — 판독이 길어졌다고 실패시키지 않기 위함이다.
                    .timeout(Duration.ofSeconds(call.timeoutSeconds()))
                    .header("x-api-key", properties.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new LlmException();
        }
    }

    private String readStream(Call call, Response response) {
        if (response.events() == null) {
            String body = response.errorBody() == null ? "" : response.errorBody();
            log.error("anthropic {} 응답 오류: status={} body={}", call.kind(), response.status(),
                    body.substring(0, Math.min(body.length(), 500)));
            throw new LlmException();
        }

        StringBuilder text = new StringBuilder();
        long[] tokens = new long[4];
        String[] stopReason = {""};

        try (Stream<String> events = response.events()) {
            events.forEach(line -> {
                if (!line.startsWith("data:")) {
                    return;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    return;
                }
                JsonNode event;
                try {
                    event = objectMapper.readTree(payload);
                } catch (Exception e) {
                    log.warn("anthropic 스트림 이벤트 파싱 실패 — 건너뜀");
                    return;
                }
                switch (event.path("type").asText()) {
                    case "message_start" -> readTokens(event.path("message").path("usage"), tokens);
                    case "content_block_delta" -> {
                        JsonNode delta = event.path("delta");
                        if ("text_delta".equals(delta.path("type").asText())) {
                            text.append(delta.path("text").asText());
                        }
                    }
                    case "message_delta" -> {
                        stopReason[0] = event.path("delta").path("stop_reason").asText("");
                        readTokens(event.path("usage"), tokens);
                    }
                    case "error" -> {
                        log.error("anthropic 스트림 오류: {}",
                                event.path("error").path("message").asText("사유 없음"));
                        throw new LlmException();
                    }
                    default -> {
                        // ping, content_block_start/stop, message_stop — 누적할 것이 없다
                    }
                }
            });
        }

        logUsage(call, tokens);

        if ("refusal".equals(stopReason[0])) {
            log.error("anthropic {} 거절(refusal)", call.kind());
            throw new LlmException();
        }
        if ("max_tokens".equals(stopReason[0])) {
            log.error("anthropic {} 응답이 max_tokens에 잘림 — 상한 상향 필요", call.kind());
            throw new LlmException();
        }
        if (text.isEmpty()) {
            log.error("anthropic {} 응답이 비어 있음 — stop_reason={}", call.kind(), stopReason[0]);
            throw new LlmException();
        }
        return text.toString();
    }

    // usage는 message_start(입력)와 message_delta(출력)에 나눠 실린다 — 0이 아닌 값만 채운다.
    private void readTokens(JsonNode usage, long[] tokens) {
        if (!usage.isObject()) {
            return;
        }
        tokens[0] = Math.max(tokens[0], usage.path("input_tokens").asLong(0));
        tokens[1] = Math.max(tokens[1], usage.path("cache_read_input_tokens").asLong(0));
        tokens[2] = Math.max(tokens[2], usage.path("cache_creation_input_tokens").asLong(0));
        tokens[3] = Math.max(tokens[3], usage.path("output_tokens").asLong(0));
    }

    // 호출당 실제 토큰과 추정 비용을 남긴다 — "실제 얼마 나가는지"는 이 실측 로그와 콘솔 청구서로 본다.
    private void logUsage(Call call, long[] tokens) {
        double[] price = properties.effectivePrices(call.model());
        double usd = (tokens[0] * price[0] + tokens[1] * price[1]
                + tokens[2] * price[2] + tokens[3] * price[3]) / 1_000_000;
        if (usd == 0) {
            log.info("anthropic[{} {}] 토큰: 신규입력 {} / 캐시읽기 {} / 캐시생성 {} / 출력+추론 {} — 단가 미설정",
                    call.kind(), call.model(), tokens[0], tokens[1], tokens[2], tokens[3]);
            return;
        }
        // 기존 제미니 경로가 같은 이름을 summary로 쓴다 — 타입이 다르면 등록이 거부된다(실측 경고).
        Metrics.summary("llm.cost.usd", "provider", "anthropic",
                "kind", "채팅".equals(call.kind()) ? "chat" : "reading").record(usd);
        log.info("anthropic[{} {}] 토큰: 신규입력 {} / 캐시읽기 {} / 캐시생성 {} / 출력+추론 {} — 비용 약 {}원 (정가 기준, 환율 {})",
                call.kind(), call.model(), tokens[0], tokens[1], tokens[2], tokens[3],
                Math.round(usd * properties.getUsdKrw()), Math.round(properties.getUsdKrw()));
    }
}
