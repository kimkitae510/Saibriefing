package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.llm.AnthropicMessagesClient;
import com.threeam.llm.AnthropicProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.OpenAiProperties;
import com.threeam.llm.OpenAiResponsesClient;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChatLlmTest {

    private static final List<ChatMessage> PROMPT = List.of(ChatMessage.user("사연이야"));

    @Spy
    private AnthropicProperties anthropicProperties = new AnthropicProperties();

    @Spy
    private OpenAiProperties openAiProperties = new OpenAiProperties();

    @Mock
    private AnthropicMessagesClient anthropicClient;

    @Mock
    private OpenAiResponsesClient openAiClient;

    @Mock
    private LlmClient llmClient;

    @InjectMocks
    private ChatLlm chatLlm;

    @Test
    @DisplayName("답변 - Claude 채팅 모델이 잡혀 있으면 Claude로 답한다")
    void reply_usesAnthropicWhenConfigured() {
        enableAnthropic();
        given(anthropicClient.generateChatText(anyList()))
                .willReturn(CompletableFuture.completedFuture("[답] 그랬구나"));

        String reply = chatLlm.reply(PROMPT).join();

        assertThat(reply).isEqualTo("[답] 그랬구나");
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("답변 - Claude가 실패하거나 거절하면 기존 경로로 한 번 넘긴다")
    void reply_fallsBackWhenAnthropicFails() {
        enableAnthropic();
        given(anthropicClient.generateChatText(anyList()))
                .willReturn(CompletableFuture.failedFuture(new LlmException()));
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.completedFuture("넘겨받은 답"));

        String reply = chatLlm.reply(PROMPT).join();

        assertThat(reply).isEqualTo("넘겨받은 답");
    }

    @Test
    @DisplayName("답변 - Claude 실패 시 OpenAI 채팅 모델이 잡혀 있으면 그쪽으로 넘긴다")
    void reply_fallsBackToOpenAiChatModel() {
        enableAnthropic();
        openAiProperties.setApiKey("openai-key");
        openAiProperties.setChatModel("gpt-chat");
        given(anthropicClient.generateChatText(anyList()))
                .willReturn(CompletableFuture.failedFuture(new LlmException()));
        given(openAiClient.generateChatText(anyList()))
                .willReturn(CompletableFuture.completedFuture("openai 답"));

        String reply = chatLlm.reply(PROMPT).join();

        assertThat(reply).isEqualTo("openai 답");
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("답변 - Claude 채팅 모델이 비어 있으면 Claude를 부르지 않고 기존 경로로 간다")
    void reply_skipsAnthropicWhenModelBlank() {
        anthropicProperties.setApiKey("anthropic-key");
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.completedFuture("기존 경로 답"));

        String reply = chatLlm.reply(PROMPT).join();

        assertThat(reply).isEqualTo("기존 경로 답");
        verify(anthropicClient, never()).generateChatText(anyList());
    }

    private void enableAnthropic() {
        anthropicProperties.setApiKey("anthropic-key");
        anthropicProperties.setChatModel("claude-haiku-5-5");
    }
}
