package com.threeam.story.service;

import com.threeam.llm.AnthropicMessagesClient;
import com.threeam.llm.AnthropicProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmRole;
import com.threeam.llm.OpenAiProperties;
import com.threeam.llm.OpenAiResponsesClient;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 탐색 채팅이 부르는 모델의 자리. 설정된 경로 하나만 탄다: Claude 채팅 모델(LLM_ANTHROPIC_CHAT_MODEL,
// 긴 메시지는 LLM_ANTHROPIC_CHAT_DEEP_MODEL), 없으면 OpenAI 채팅 모델(LLM_OPENAI_CHAT_MODEL), 그것도
// 없으면 기존 프로바이더 스위치(LlmClient: mock, gemini, vertex) — 키 없는 개발 환경과 테스트가 mock으로
// 그대로 돌게 하려는 분기다. 실패하면 다른 모델로 다시 부르지 않는다. 모델마다 말투가 달라 대화 중간에
// 결이 바뀌고, 실패는 안내 말풍선과 다시 보내기로 받는다(StoryService).
@Component
@RequiredArgsConstructor
public class ChatLlm {

    private final AnthropicProperties anthropicProperties;
    private final AnthropicMessagesClient anthropicClient;
    private final OpenAiProperties openAiProperties;
    private final OpenAiResponsesClient openAiClient;
    private final LlmClient llmClient;

    // 방의 첫 말과 긴 메시지는 긴 메시지 모델로, 나머지는 기본 채팅 모델로. 첫 답은 짧은 말이어도
    // 이 상담자를 계속 쓸지 정하는 자리라 길이와 상관없이 맡긴다.
    public CompletableFuture<String> reply(List<ChatMessage> prompt, boolean firstTurn) {
        if (anthropicProperties.chatEnabled()) {
            boolean deep = anthropicProperties.chatDeepEnabled()
                    && (firstTurn || latestUserLength(prompt) >= anthropicProperties.getChatDeepMinChars());
            Metrics.counter("llm.chat.route", "model", deep ? "deep" : "base").increment();
            return anthropicClient.generateChatText(prompt, deep);
        }
        return openAiProperties.chatEnabled()
                ? openAiClient.generateChatText(prompt)
                : llmClient.generate(prompt);
    }

    // 방금 사연자가 보낸 메시지 길이. 코드포인트로 센다 — 이모지가 두 글자로 잡히지 않게.
    private static int latestUserLength(List<ChatMessage> prompt) {
        for (int i = prompt.size() - 1; i >= 0; i--) {
            ChatMessage message = prompt.get(i);
            if (message.role() == LlmRole.USER) {
                return message.content().codePointCount(0, message.content().length());
            }
        }
        return 0;
    }

    // 목표 판정 — 스키마는 Google 형식으로 받는다(OpenAI 경로가 strict 스키마로 변환한다).
    // 판정은 JSON 스키마 강제가 필요해서 Claude 채팅 경로를 타지 않는다.
    public CompletableFuture<String> judge(List<ChatMessage> prompt, Map<String, Object> googleSchema) {
        return openAiProperties.chatEnabled()
                ? openAiClient.generateChatJson(prompt, googleSchema)
                : llmClient.generateJsonQuick(prompt);
    }
}
