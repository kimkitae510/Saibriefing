package com.threeam.story.service;

import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.OpenAiProperties;
import com.threeam.llm.OpenAiResponsesClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 탐색 채팅이 부르는 모델의 자리. 채팅 모델(LLM_OPENAI_CHAT_MODEL)이 잡혀 있으면 OpenAI 경로로,
// 아니면 기존 프로바이더 스위치(LlmClient: mock, gemini, vertex)로 돈다 — 키 없는 개발 환경과
// 테스트가 mock으로 그대로 돌게 하려는 분기다. 판독(ReadingLlm)과 같은 꼴로 채팅만 따로 갈랐다.
@Component
@RequiredArgsConstructor
public class ChatLlm {

    private final OpenAiProperties openAiProperties;
    private final OpenAiResponsesClient openAiClient;
    private final LlmClient llmClient;

    public CompletableFuture<String> reply(List<ChatMessage> prompt) {
        return openAiProperties.chatEnabled()
                ? openAiClient.generateChatText(prompt)
                : llmClient.generate(prompt);
    }

    // 목표 판정 — 스키마는 Google 형식으로 받는다(OpenAI 경로가 strict 스키마로 변환한다).
    public CompletableFuture<String> judge(List<ChatMessage> prompt, Map<String, Object> googleSchema) {
        return openAiProperties.chatEnabled()
                ? openAiClient.generateChatJson(prompt, googleSchema)
                : llmClient.generateJsonQuick(prompt);
    }
}
