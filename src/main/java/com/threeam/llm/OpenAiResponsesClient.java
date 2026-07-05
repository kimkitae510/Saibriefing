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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// OpenAI Chat Completions 호출 — 정밀 판독(2호출) 실험 전용 경로.
// LLM_READING_PROVIDER=openai일 때 ReadingLlm이 기존 deep 호출 대신 이걸 탄다.
//
// 제미니가 임상어와 애착유형 라벨과 상대 속마음 창작을 강하게 하는 습성이 있어(맨몸 출력 실측),
// 판독 지시의 상당 부분이 그 습성을 막는 방어 규칙으로 채워져 있었다. 그 방어물이 다시
// 오염원이 되는 사고가 반복돼(금지 목록이 어휘를 가르치고, 인용이 출력으로 새고), 습성이
// 다른 모델이면 지시를 크게 줄일 수 있는지 보려고 만든 경로다.
//
// 스트리밍은 쓰지 않는다. 첫 실험의 목적이 "얼마나 나오고 얼마 드는가"라, 응답 한 덩이와
// usage를 함께 받는 쪽이 읽기 쉽다. 오래 걸려 끊기면 그때 스트림으로 바꾼다.
@Slf4j
@Component
public class OpenAiResponsesClient {

    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long RETRY_DELAY_SECONDS = 2;

    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;
    private final HttpClient httpClient;

    public OpenAiResponsesClient(ObjectMapper objectMapper, OpenAiProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        AtomicInteger seq = new AtomicInteger();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .executor(Executors.newFixedThreadPool(4, r -> {
                    Thread t = new Thread(r, "llm-openai-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }))
                .build();
    }

    // 판독 JSON 생성. Google 스키마를 strict 모드 JSON Schema로 바꿔 강제한다 —
    // 스키마 원본은 ReadingLlm 하나로 두고 여기서 변환만 한다(두 벌을 손으로 맞추면 어긋난다).
    public CompletableFuture<String> generateReadingJson(List<ChatMessage> messages,
                                                         Map<String, Object> googleSchema) {
        return generateReadingJson(messages, googleSchema, "판독");
    }

    // kind는 비용 로그 표기용 — 같은 판독 모델로 도는 호출(분석, 카드 분류)을 로그에서 가른다.
    public CompletableFuture<String> generateReadingJson(List<ChatMessage> messages,
                                                         Map<String, Object> googleSchema,
                                                         String kind) {
        return generate(messages, googleSchema, properties.getReadingModel(), kind);
    }

    // 모델을 호출별로 바꿔 보는 실험용 — 비우면 판독 모델. 분류를 terra로 돌려 보는 데 쓴다.
    public CompletableFuture<String> generateReadingJson(List<ChatMessage> messages,
                                                         Map<String, Object> googleSchema,
                                                         String kind, String modelOverride) {
        String model = modelOverride == null || modelOverride.isBlank()
                ? properties.getReadingModel() : modelOverride;
        return generate(messages, googleSchema, model, kind);
    }

    // 결정 호출(판 확정)은 재료가 다 차려진 압축 작업이라 더 싼 모델을 따로 배정할 수 있다.
    // LLM_OPENAI_DECISION_MODEL이 비어 있으면 판독 모델을 그대로 쓴다.
    public CompletableFuture<String> generateDecisionJson(List<ChatMessage> messages,
                                                          Map<String, Object> googleSchema) {
        return generateDecisionJson(messages, googleSchema, "결정");
    }

    // kind는 비용 로그 표기용 — 같은 결정 모델로 도는 호출(편집, 카드 분류)을 로그에서 가른다.
    public CompletableFuture<String> generateDecisionJson(List<ChatMessage> messages,
                                                          Map<String, Object> googleSchema,
                                                          String kind) {
        return generate(messages, googleSchema, properties.effectiveDecisionModel(), kind);
    }

    // 자유 산문 생성(1단 분석, 판정) — 스키마 없이 보낸다. 구조화 출력이 사고를 조각내
    // 통찰을 누른다는 실측(2026-08-25)으로 만든 경로다. kind는 비용 로그 표기용.
    public CompletableFuture<String> generateReadingText(List<ChatMessage> messages) {
        return generateReadingText(messages, "분석");
    }

    public CompletableFuture<String> generateReadingText(List<ChatMessage> messages, String kind) {
        return generate(messages, null, properties.getReadingModel(), kind);
    }

    // 모델을 호출별로 바꿔 보는 실험용 — 비우면 판독 모델. 분석만 다른 모델(terra)로 돌려 보는 데 쓴다.
    public CompletableFuture<String> generateReadingText(List<ChatMessage> messages, String kind,
                                                         String modelOverride) {
        String model = modelOverride == null || modelOverride.isBlank()
                ? properties.getReadingModel() : modelOverride;
        return generate(messages, null, model, kind);
    }

    public CompletableFuture<String> generateVerdictText(List<ChatMessage> messages) {
        return generate(messages, null, properties.effectiveVerdictModel(), "판정");
    }

    // 탐색 채팅 답변(산문). 채팅 모델(LLM_OPENAI_CHAT_MODEL)로 돌고, 대기 상한은 채팅 값이다.
    public CompletableFuture<String> generateChatText(List<ChatMessage> messages) {
        return generate(messages, null, properties.getChatModel(), "채팅",
                properties.getChatTimeoutSeconds());
    }

    // 탐색 채팅의 목표 판정(JSON). 답변과 같은 채팅 모델 — 판정은 "이 말이 무엇을 채웠나"만
    // 가리는 자리라 강한 모델이 필요 없고, 같은 모델이면 프롬프트 캐시도 나눠 쓴다.
    public CompletableFuture<String> generateChatJson(List<ChatMessage> messages,
                                                      Map<String, Object> googleSchema) {
        return generate(messages, googleSchema, properties.getChatModel(), "목표판정",
                properties.getChatTimeoutSeconds());
    }

    private CompletableFuture<String> generate(List<ChatMessage> messages,
                                               Map<String, Object> googleSchema,
                                               String model, String kind) {
        return generate(messages, googleSchema, model, kind, properties.getReadingTimeoutSeconds());
    }

    private CompletableFuture<String> generate(List<ChatMessage> messages,
                                               Map<String, Object> googleSchema,
                                               String model, String kind, long timeoutSeconds) {
        String missing = missingConfig(model, kind);
        if (missing != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(missing));
        }
        Map<String, Object> schema = googleSchema == null ? null : StrictJsonSchema.fromGoogle(googleSchema);
        return send(messages, schema, model, kind, timeoutSeconds)
                .thenApply(response -> readBody(response, model, kind))
                .whenComplete((result, ex) -> Metrics.counter("llm.calls",
                        "provider", "openai",
                        "result", ex == null ? "success" : "error",
                        "reason", ex == null ? "none" : "call_failed").increment());
    }

    // 모델 이름은 기본값을 두지 않았다 — 안 뜨는 이유를 로그에서 바로 읽게 하려고 여기서 막는다.
    // 호출마다 모델이 다르므로(판독, 결정, 채팅) 검사도 그 호출의 모델로 한다.
    private String missingConfig(String model, String kind) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return "OPENAI_API_KEY가 비어 있다 — openai 프로바이더 설정 확인";
        }
        if (model == null || model.isBlank()) {
            return "openai " + kind + " 호출의 모델 이름이 비어 있다 — LLM_OPENAI_*_MODEL 확인";
        }
        return null;
    }

    private CompletableFuture<Response> send(List<ChatMessage> messages,
                                             Map<String, Object> schema, String model, String kind) {
        return send(messages, schema, model, kind, properties.getReadingTimeoutSeconds());
    }

    private CompletableFuture<Response> send(List<ChatMessage> messages,
                                             Map<String, Object> schema, String model, String kind,
                                             long timeoutSeconds) {
        return sendOnce(messages, schema, model, timeoutSeconds).thenCompose(response -> {
            // 5xx는 잠깐 기다렸다 1회 재시도. 429(한도)와 4xx는 다시 보내도 같은 결과라 그대로 둔다.
            if (response.status() / 100 != 5) {
                return CompletableFuture.completedFuture(response);
            }
            log.warn("openai {} {}(서버 오류) — {}초 뒤 1회 재시도", kind, response.status(), RETRY_DELAY_SECONDS);
            Metrics.counter("llm.retries", "provider", "openai", "reason", "server_error").increment();
            return CompletableFuture.supplyAsync(() -> null,
                            CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS))
                    .thenCompose(ignored -> sendOnce(messages, schema, model, timeoutSeconds));
        });
    }

    private CompletableFuture<Response> sendOnce(List<ChatMessage> messages,
                                                 Map<String, Object> schema, String model,
                                                 long timeoutSeconds) {
        return httpClient.sendAsync(buildRequest(messages, schema, model, timeoutSeconds),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(http -> new Response(http.statusCode(), http.body()));
    }

    private record Response(int status, String body) {
    }

    private HttpRequest buildRequest(List<ChatMessage> messages, Map<String, Object> schema,
                                     String model, long timeoutSeconds) {
        List<Map<String, Object>> turns = new ArrayList<>();
        for (ChatMessage message : messages) {
            String role = switch (message.role()) {
                case SYSTEM -> "system";
                case ASSISTANT -> "assistant";
                default -> "user";
            };
            turns.add(Map.of("role", role, "content", message.content()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", turns);
        if (schema != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "reading",
                            "strict", true,
                            "schema", schema)));
        }
        if (properties.getReasoningEffort() != null && !properties.getReasoningEffort().isBlank()) {
            body.put("reasoning_effort", properties.getReasoningEffort());
        }

        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("authorization", "Bearer " + properties.getApiKey())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            throw new LlmException();
        }
    }

    private String readBody(Response response, String model, String kind) {
        if (response.status() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            log.error("openai {} 응답 오류: status={} body={}", kind, response.status(),
                    body.substring(0, Math.min(body.length(), 500)));
            throw new LlmException();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("openai {} 응답 파싱 실패", kind);
            throw new LlmException();
        }

        logUsage(root.path("usage"), model, kind);

        JsonNode choice = root.path("choices").path(0);
        String finish = choice.path("finish_reason").asText("");
        // 길이로 잘린 응답은 JSON이 깨져 파서가 실패한다 — 이유를 여기서 남겨야 상한을 올릴지 안다.
        if ("length".equals(finish)) {
            log.error("openai {} 응답이 길이 상한에 잘림 — 상한 상향 또는 추론 강도 하향 필요", kind);
            throw new LlmException();
        }
        JsonNode message = choice.path("message");
        if (!message.path("refusal").isNull() && message.hasNonNull("refusal")) {
            log.error("openai {} 거절: {}", kind, message.path("refusal").asText(""));
            throw new LlmException();
        }
        String text = message.path("content").asText("");
        if (text.isBlank()) {
            log.error("openai {} 응답이 비어 있음 — finish_reason={}", kind, finish);
            throw new LlmException();
        }
        return text;
    }

    // 호출당 실제 토큰과 추정 비용을 남긴다 — "실제 얼마 나가는지"는 이 로그와 콘솔 청구서로 본다.
    // 캐시 읽기분은 신규 입력에서 빼야 두 번 세지 않는다.
    private void logUsage(JsonNode usage, String model, String kind) {
        if (!usage.isObject()) {
            return;
        }
        long cached = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
        long fresh = Math.max(0, usage.path("prompt_tokens").asLong(0) - cached);
        long output = usage.path("completion_tokens").asLong(0);
        long reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0);

        double[] price = properties.effectivePrices(model);
        double usd = (fresh * price[0] + cached * price[1] + output * price[2]) / 1_000_000;
        if (usd == 0) {
            log.info("openai[{} {}] 토큰: 신규입력 {} / 캐시입력 {} / 출력 {}(추론 {}) — 단가 미설정",
                    kind, model, fresh, cached, output, reasoning);
            return;
        }
        Metrics.summary("llm.cost.usd", "provider", "openai", "kind", kind).record(usd);
        log.info("openai[{} {}] 토큰: 신규입력 {} / 캐시입력 {} / 출력 {}(추론 {}) — 비용 약 {}원 (환율 {})",
                kind, model, fresh, cached, output, reasoning,
                Math.round(usd * properties.getUsdKrw()), Math.round(properties.getUsdKrw()));
    }
}
