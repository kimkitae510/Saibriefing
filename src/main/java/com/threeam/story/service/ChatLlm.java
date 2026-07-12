package com.threeam.story.service;

import com.threeam.llm.AnthropicMessagesClient;
import com.threeam.llm.AnthropicProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.OpenAiProperties;
import com.threeam.llm.OpenAiResponsesClient;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 탐색 채팅이 부르는 모델의 자리. 우선순위는 Claude 채팅 모델(LLM_ANTHROPIC_CHAT_MODEL),
// OpenAI 채팅 모델(LLM_OPENAI_CHAT_MODEL), 기존 프로바이더 스위치(LlmClient: mock, gemini, vertex) 순 —
// 키 없는 개발 환경과 테스트가 mock으로 그대로 돌게 하려는 분기다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatLlm {

    private final AnthropicProperties anthropicProperties;
    private final AnthropicMessagesClient anthropicClient;
    private final OpenAiProperties openAiProperties;
    private final OpenAiResponsesClient openAiClient;
    private final LlmClient llmClient;

    // Claude 경로가 실패하면 한 번만 다음 경로로 넘긴다. Haiku는 안전 거절을 서버가 다른 모델로
    // 넘겨 주지 않아서, 무거운 사연에서 거절이 나면 답 없이 끝나지 않게 여기서 받는다.
    public CompletableFuture<String> reply(List<ChatMessage> prompt) {
        if (!anthropicProperties.chatEnabled()) {
            return fallbackReply(prompt);
        }
        return anthropicClient.generateChatText(prompt)
                .exceptionallyCompose(ex -> {
                    log.warn("anthropic 채팅 실패 — 다음 경로로 1회 재시도: {}", ex.toString());
                    Metrics.counter("llm.chat.fallback", "from", "anthropic").increment();
                    return fallbackReply(prompt);
                });
    }

    private CompletableFuture<String> fallbackReply(List<ChatMessage> prompt) {
        return openAiProperties.chatEnabled()
                ? openAiClient.generateChatText(prompt)
                : llmClient.generate(prompt);
    }

    // 목표 판정 — 스키마는 Google 형식으로 받는다(OpenAI 경로가 strict 스키마로 변환한다).
    // 판정은 JSON 스키마 강제가 필요해서 Claude 채팅 경로를 타지 않는다.
    public CompletableFuture<String> judge(List<ChatMessage> prompt, Map<String, Object> googleSchema) {
        return openAiProperties.chatEnabled()
                ? openAiClient.generateChatJson(prompt, googleSchema)
                : llmClient.generateJsonQuick(prompt);
    }
}
