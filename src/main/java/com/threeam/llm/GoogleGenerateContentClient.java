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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

// Google generateContent 계열 공통 구현. AI Studio(API 키)와 Vertex AI(서비스 계정)는
// 요청 본문과 응답 구조가 같고 엔드포인트와 인증만 달라서, 그 둘만 구현체에 맡긴다.
@Slf4j
abstract class GoogleGenerateContentClient implements LlmClient {

    // 연결 수립 대기 상한. 응답 대기(timeoutSeconds)와 별개로, 네트워크 단절을 빨리 알아챈다.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    // 503(혼잡) 재시도 대기. 폴링 여유(45초) 안에 "즉시 실패 + 대기 + 재시도(최대 30초)"가 들어간다.
    private static final long RETRY_DELAY_SECONDS = 2;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final LlmCallMetrics callMetrics;

    protected GoogleGenerateContentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        ExecutorService httpPool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "llm-http-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        LlmCallMetrics.bindPool(httpPool, "llm-http");
        this.callMetrics = new LlmCallMetrics(providerName());
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).executor(httpPool).build();
    }

    abstract String endpoint();

    // 정밀 판단 호출용 엔드포인트. 분리 설정이 없으면 기본 모델과 같다.
    String deepEndpoint() {
        return endpoint();
    }

    // 요청마다 호출된다 — 만료되는 토큰 방식(Vertex)도 여기서 최신 값을 실을 수 있게.
    abstract void authorize(HttpRequest.Builder builder);

    abstract long timeoutSeconds();

    abstract long assessmentTimeoutSeconds();

    abstract int thinkingBudget();

    // 100만 토큰당 USD. 순서대로 신규 입력, 캐시 적중 입력, 출력.
    abstract double[] pricesPerMillion();

    abstract String thinkingLevel();

    // 채팅 temperature. 분석은 같은 입력에 같은 결과가 나오게 0으로 고정하고,
    // 채팅은 말투가 굳지 않을 만큼만 기본값(1.0)보다 낮춘다.
    abstract double temperature();

    // 분석(deep) 전용 thinking 세기 — 채팅(low)과 분리. 2.5 계열은 예산 숫자(-1=동적), 3.x는 단계.
    abstract int assessmentThinkingBudget();

    abstract String assessmentThinkingLevel();

    // 저가 판별용 계층. 상담이 아니라 "이 말이 어느 갈래인가"만 가리는 자리라 강한 모델도
    // 긴 추론도 필요 없다. 비우면 채팅 모델을 그대로 쓴다(설정 없이도 돌게).
    abstract String quickEndpoint();

    abstract String quickThinkingLevel();

    abstract int quickThinkingBudget();

    // 로그 라벨용
    abstract String providerName();

    // 토큰 로그의 용도 라벨. 메시지 한 번에 채팅과 추출 호출이 연달아 찍혀서 호출 종류로 구분한다.
    private static final String KIND_CHAT = "채팅";
    private static final String KIND_EXTRACT = "추출";
    private static final String KIND_DEEP = "분석";
    private static final String KIND_QUICK = "판별";

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return send(buildRequest(messages, false, LlmTier.CHAT, null), KIND_CHAT);
    }

    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        return send(buildRequest(messages, true, LlmTier.CHAT, null), KIND_EXTRACT);
    }

    @Override
    public CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages,
                                                      Map<String, Object> responseSchema) {
        return send(buildRequest(messages, true, LlmTier.DEEP, responseSchema), KIND_DEEP);
    }

    @Override
    public CompletableFuture<String> generateJsonQuick(List<ChatMessage> messages) {
        return send(buildRequest(messages, true, LlmTier.QUICK, null), KIND_QUICK);
    }

    private String endpointFor(LlmTier tier) {
        return switch (tier) {
            case DEEP -> deepEndpoint();
            case QUICK -> quickEndpoint();
            default -> endpoint();
        };
    }

    private String tierLabel(LlmTier tier) {
        return switch (tier) {
            case DEEP -> "분석";
            case QUICK -> "판별";
            default -> "채팅";
        };
    }

    private CompletableFuture<String> send(HttpRequest request, String kind) {
        long timeoutSeconds = request.timeout().map(Duration::toSeconds).orElse(0L);
        return callMetrics.measure(timeoutSeconds, () ->
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                        .thenCompose(this::retryOnceIfOverloaded)
                        .thenApply(response -> extractText(response, kind)));
    }

    // 503은 피크 시간대의 일상이라 짧게 기다렸다 1회 재시도한다.
    // 429(한도 소진)나 4xx는 다시 보내도 같은 결과라 재시도하지 않는다.
    private CompletableFuture<HttpResponse<String>> retryOnceIfOverloaded(HttpResponse<String> response) {
        if (response.statusCode() != 503) {
            return CompletableFuture.completedFuture(response);
        }
        log.warn("{} 503(혼잡) — {}초 뒤 1회 재시도", providerName(), RETRY_DELAY_SECONDS);
        // 재시도는 곧 호출 비용 2배다. 재시도 빈도를 세어 두면 "재시도로 비용이 튄 날"을 뒤늦게라도 짚을 수 있다.
        // reason 태그는 다른 클라이언트의 llm.retries와 키를 맞춘 것 — 키가 다르면 Prometheus 레지스트리가 거부한다.
        Metrics.counter("llm.retries", "provider", providerName(), "reason", "overloaded").increment();
        return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(RETRY_DELAY_SECONDS, TimeUnit.SECONDS))
                .thenCompose(ignored -> httpClient.sendAsync(
                        response.request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    // 안전성 필터 해제(BLOCK_NONE): 이별 상담 도메인은 불륜, 환승, 이별 통보 같은 무거운 소재가
    // 일상 입력인데, 기본 임계값에서는 생성이 도중에 차단돼 JSON이 잘린다.
    // 상담 응답의 수위는 필터가 아니라 페르소나와 루브릭이 관리한다. 그래도 잘리면
    // finishReason=SAFETY 경고 로그로 드러난다(일부 카테고리는 BLOCK_NONE에서도 강제 차단이 남는다).
    private static final List<Map<String, String>> SAFETY_SETTINGS = List.of(
            Map.of("category", "HARM_CATEGORY_HARASSMENT", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_HATE_SPEECH", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold", "BLOCK_NONE"),
            Map.of("category", "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold", "BLOCK_NONE"));

    private HttpRequest buildRequest(List<ChatMessage> messages, boolean json, LlmTier tier,
                                     Map<String, Object> responseSchema) {
        boolean deep = tier == LlmTier.DEEP;
        // system은 system_instruction으로, 대화는 contents(user/model)로 나눠 받는다.
        // 단 '대화보다 뒤에 온 system 메시지'는 예외다 — 그건 대화 뒤에 놓이길 의도하고 넣은 것이라
        // (출력 직전 점검), system_instruction으로 접으면 대화 앞으로 가버려 의도가 정반대가 된다.
        StringBuilder system = new StringBuilder();
        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.role() == LlmRole.SYSTEM) {
                if (contents.isEmpty()) {
                    system.append(message.content()).append('\n');
                } else {
                    // 유저 발화로 오해하지 않게 앞머리를 붙인다 — 모델이 여기에 답하면 안 된다.
                    contents.add(Map.of("role", "user", "parts", List.of(Map.of("text",
                            "(유저가 보낸 말이 아니라 시스템 지시다. 이 지시 자체에 답하지 말고 답변에만 반영해라.)\n"
                                    + message.content()))));
                }
                continue;
            }
            String role = message.role() == LlmRole.USER ? "user" : "model";
            contents.add(Map.of("role", role, "parts", List.of(Map.of("text", message.content()))));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (system.length() > 0) {
            body.put("system_instruction", Map.of("parts", List.of(Map.of("text", system.toString().trim()))));
        }
        body.put("contents", contents);
        body.put("safetySettings", SAFETY_SETTINGS);
        // JSON 모드: 모델이 코드펜스, 잡설 없이 순수 JSON만 뱉도록 강제한다.
        // 정밀 판단(deep=분석): temperature 0(같은 사실 위 점수 출렁임 실측 대응) + thinking 유지(긴 루브릭 추론).
        // 채팅/추출(그 외): thinking을 낮게 켠다 — 끄면 페르소나 규칙(메아리, 양자택일) 위반이 반복
        // 실측됐고, 기본(동적)은 비용이 튄다. 주의: thinking 제어 필드가 세대마다 다르다 —
        // 2.5 계열은 thinkingBudget(토큰 숫자), 3.x는 thinkingLevel. 문법이 안 맞으면 조용히
        // 무시돼 설정이 안 먹는다(실측: low로 올려도 thoughts=0). 엔드포인트의 모델명으로 판별한다.
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (json) {
            generationConfig.put("responseMimeType", "application/json");
        }
        // responseMimeType은 "JSON으로 답해달라"는 지시일 뿐이라 모델이 중간에 멈추면 그대로 잘린 JSON이 온다
        // (finishReason=STOP인데도 본문 미완결). 스키마를 주면 생성 단계에서 문법에 맞지 않는 토큰이
        // 후보에서 제외돼 미완결, 필드 오타, 범위 밖 enum이 구조적으로 나올 수 없다.
        if (json && responseSchema != null) {
            generationConfig.put("responseSchema", responseSchema);
        }
        if (deep) {
            generationConfig.put("temperature", 0);
            // 분석은 여러 단계를 거치는 긴 추론이라 thinking을 명시한다.
            // 판별은 분석이 실제로 때리는 deep 엔드포인트의 모델명으로(분석 전용 모델이 다를 수 있다).
            if (deepEndpoint().contains("gemini-2.5")) {
                if (assessmentThinkingBudget() >= 0) {
                    generationConfig.put("thinkingConfig",
                            Map.of("thinkingBudget", assessmentThinkingBudget()));
                }
            } else if (!assessmentThinkingLevel().isBlank()) {
                generationConfig.put("thinkingConfig",
                        Map.of("thinkingLevel", assessmentThinkingLevel()));
            }
        } else if (tier == LlmTier.QUICK) {
            // 판별은 같은 문장에 같은 답이 나와야 한다 — 흔들리면 같은 질문이 턴마다 다른 모듈로 간다.
            generationConfig.put("temperature", 0);
            if (quickEndpoint().contains("gemini-2.5")) {
                generationConfig.put("thinkingConfig", Map.of("thinkingBudget", quickThinkingBudget()));
            } else if (!quickThinkingLevel().isBlank()) {
                generationConfig.put("thinkingConfig", Map.of("thinkingLevel", quickThinkingLevel()));
            }
        } else {
            generationConfig.put("temperature", temperature());
            if (endpoint().contains("gemini-2.5")) {
                // 답을 짓는 것과 출력 직전 점검으로 되짚는 것이 한 예산 안에 들어가야 한다.
                generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget()));
            } else {
                generationConfig.put("thinkingConfig", Map.of("thinkingLevel", thinkingLevel()));
            }
        }
        body.put("generationConfig", generationConfig);
        // 무엇이 실제로 실려 나가는지 남긴다 — thinking 제어 필드는 문법이 안 맞으면 조용히 무시된다.
        // 프롬프트와 키는 안 찍고 generationConfig만 찍는다(개인정보 없음, 몇 십 바이트).
        log.info("{} {} 생성 설정: {}", providerName(), tierLabel(tier), generationConfig);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointFor(tier)))
                    .header("Content-Type", "application/json")
                    // 타임아웃 없이는 LLM이 매달릴 때 future가 영원히 미완 → 답도 폴백도 저장되지 않는다.
                    // 분석(deep)은 긴 루브릭 + 추론이라 채팅보다 길지만, 채팅 값의 배수가 아니라
                    // 자기 설정값을 쓴다 — 배수로 묶어두면 채팅을 만질 때 분석이 따라 움직여
                    // usage.assessment-lock-ttl-seconds와 spring request-timeout을 말없이 넘어간다.
                    .timeout(Duration.ofSeconds(deep ? assessmentTimeoutSeconds() : timeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            authorize(builder);
            return builder.build();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 요청 조립 실패", providerName(), e);
            throw new LlmException();
        }
    }

    // 로그로 남기는 응답 본문 상한. 오류 분석에 필요한 앞부분만 남기고 잘라 로그 폭탄, 개인정보 노출을 줄인다.
    private static final int LOG_BODY_LIMIT = 500;

    private String extractText(HttpResponse<String> response, String kind) {
        if (response.statusCode() / 100 != 2) {
            // 오류 본문은 보통 provider 에러 메타(429 한도, 안전성 차단 등)라 분석에 필요하지만, 길이는 자른다.
            log.error("{} {} 응답 오류: status={} body={}", providerName(), kind, response.statusCode(),
                    snippet(response.body()));
            throw new LlmHttpStatusException(response.statusCode());
        }
        return parseBody(response.body(), kind);
    }

    // 패키지 공개는 테스트용 — 파트 분할 응답의 회귀 방지.
    String parseBody(String body) {
        return parseBody(body, "테스트");
    }

    private String parseBody(String body, String kind) {
        try {
            JsonNode root = objectMapper.readTree(body);
            logUsage(root, kind);
            JsonNode candidate = root.path("candidates").path(0);
            // 생성이 왜 멈췄는지 반드시 남긴다 — JSON이 중간에 잘려 파싱이 깨질 때
            // MAX_TOKENS(출력 잘림)인지 SAFETY(안전성 차단)인지 이 값 없이는 추적이 불가능하다.
            String finishReason = candidate.path("finishReason").asText("");
            if (!finishReason.isEmpty() && !"STOP".equals(finishReason)) {
                log.warn("{} {} 생성 비정상 종료: finishReason={}", providerName(), kind, finishReason);
            }
            // 긴 응답은 여러 텍스트 파트로 쪼개져 올 수 있다. 첫 파트만 읽으면 정상 종료(STOP)인데도
            // 본문이 첫 조각에서 끊긴다 — 전부 이어붙인다.
            JsonNode parts = candidate.path("content").path("parts");
            StringBuilder text = new StringBuilder();
            int textParts = 0;
            int thoughtParts = 0;
            for (JsonNode part : parts) {
                // thought=true는 추론 요약 파트라 응답 본문이 아니다.
                if (part.path("thought").asBoolean(false)) {
                    thoughtParts++;
                    continue;
                }
                JsonNode partText = part.path("text");
                if (partText.isTextual()) {
                    text.append(partText.asText());
                    textParts++;
                }
            }
            // 응답 구조를 매 호출 남긴다(내용은 미기록) — "정상 종료인데 본문이 잘림" 같은 문제를
            // 파트 분할인지, 정말 이 길이로 온 건지 로그만으로 판별하기 위함.
            log.info("{} {} 응답 구조: 파트 {}개(텍스트 {}, 추론 {}), finishReason={}, 본문 {}자",
                    providerName(), kind, parts.size(), textParts, thoughtParts,
                    finishReason.isEmpty() ? "없음" : finishReason, text.length());
            if (textParts == 0) {
                log.error("{} 응답에 텍스트가 없음: finishReason={} body={}",
                        providerName(), finishReason, snippet(body));
                throw new LlmException();
            }
            return text.toString();
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} 응답 파싱 실패", providerName(), e);
            throw new LlmException();
        }
    }

    private String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= LOG_BODY_LIMIT ? body : body.substring(0, LOG_BODY_LIMIT) + "...(truncated)";
    }

    // 호출당 실제 토큰량을 남긴다 — 비용 검증(프롬프트 창 크기, 추출 호출 비용)은 추정이 아니라 이 값으로 한다.
    private void logUsage(JsonNode root, String kind) {
        JsonNode usage = root.path("usageMetadata");
        if (usage.isMissingNode()) {
            return;
        }
        int total = usage.path("totalTokenCount").asInt(0);
        int input = usage.path("promptTokenCount").asInt(0);
        // 캐시 적중 토큰. input에 포함된 값이라 따로 빼서 봐야 "얼마가 할인가로 나갔는지"가 보인다.
        // 캐시 단가는 입력의 10%라 이 비율이 곧 비용 구조다. 고정 블록을 프롬프트 맨 앞에 둬
        // 암묵적 캐싱을 노리는데, 실제로 맞는지는 이 값으로만 확인된다.
        int cached = usage.path("cachedContentTokenCount").asInt(0);
        // thoughts(추론) 토큰을 따로 남긴다 — output이 작게 잘렸을 때 추론이 예산을 먹었는지 가려낸다.
        // 설정값이 아니라 응답이 알려주는 실제 모델을 남긴다 — 모델을 바꿔가며 비교할 때
        // "지금 뭐가 돌고 있나"를 로그만 보고 확정할 수 있어야 한다(설정은 환경변수라 눈에 안 보인다).
        String model = usage.isMissingNode() ? "" : root.path("modelVersion").asText("");
        log.info("{}[{}] {} 토큰 사용: input={}(캐시 {} / 신규 {}), output={}, thoughts={}, total={}",
                providerName(), model, kind,
                input, cached, input - cached,
                usage.path("candidatesTokenCount").asInt(0),
                usage.path("thoughtsTokenCount").asInt(0),
                total);
        // 단가가 설정돼 있으면 호출당 실제 비용까지 계산해 남긴다 — 콘솔 청구서는 합계라서
        // "이 호출이 얼마짜리였나"를 못 본다. 모델을 바꿔 비교할 때 이 줄이 비교 단위가 된다.
        // 추론(thoughts) 토큰은 출력 단가로 과금되므로 output에 합산한다 — 빠뜨리기 쉬운 자리다.
        double[] prices = pricesPerMillion();
        if (prices != null && prices.length == 3 && (prices[0] > 0 || prices[2] > 0)) {
            int output = usage.path("candidatesTokenCount").asInt(0)
                    + usage.path("thoughtsTokenCount").asInt(0);
            double cost = ((input - cached) * prices[0] + cached * prices[1] + output * prices[2]) / 1_000_000d;
            log.info("{}[{}] {} 호출 비용: {} (신규입력 {} / 캐시 {} / 출력+추론 {})",
                    providerName(), model, kind, String.format("%.4f", cost), input - cached, cached, output);
            // 누적 지출과 호출당 분포를 함께 본다(합계만 보면 어떤 종류가 비싼지 안 갈린다).
            Metrics.summary("llm.cost.usd", "provider", providerName(), "kind", kind).record(cost);
        }
        // 토큰 총량 분포 — 프롬프트 창, 추출 호출이 비용에 미치는 영향을 집계한다.
        Metrics.summary("llm.tokens.total", "provider", providerName()).record(total);
        // 캐시 적중률은 호출 종류마다 다르다(채팅은 연속 호출이라 잘 맞고, 분석은 띄엄띄엄해서 불리).
        // 종류별로 갈라 집계해야 어느 쪽에 명시적 캐싱이 필요한지가 갈린다.
        Metrics.summary("llm.tokens.cached", "provider", providerName(), "kind", kind).record(cached);
    }
}
