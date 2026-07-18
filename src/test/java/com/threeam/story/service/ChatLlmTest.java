package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
        given(anthropicClient.generateChatText(anyList(), eq(false)))
                .willReturn(CompletableFuture.completedFuture("[답] 그랬구나"));

        String reply = chatLlm.reply(PROMPT, false).join();

        assertThat(reply).isEqualTo("[답] 그랬구나");
        verify(llmClient, never()).generate(anyList());
    }



    @Test
    @DisplayName("답변 - Claude 채팅 모델이 비어 있으면 Claude를 부르지 않고 기존 경로로 간다")
    void reply_skipsAnthropicWhenModelBlank() {
        anthropicProperties.setApiKey("anthropic-key");
        given(llmClient.generate(anyList()))
                .willReturn(CompletableFuture.completedFuture("기존 경로 답"));

        String reply = chatLlm.reply(PROMPT, false).join();

        assertThat(reply).isEqualTo("기존 경로 답");
        verify(anthropicClient, never()).generateChatText(anyList(), anyBoolean());
    }

    @Test
    @DisplayName("답변 - 긴 메시지 모델이 잡혀 있고 방금 메시지가 기준 이상이면 긴 메시지 모델로 답한다")
    void reply_routesLongMessageToDeepModel() {
        enableDeep();
        given(anthropicClient.generateChatText(anyList(), eq(true)))
                .willReturn(CompletableFuture.completedFuture("짚는 답"));

        String reply = chatLlm.reply(withLatestUser("가".repeat(100)), false).join();

        assertThat(reply).isEqualTo("짚는 답");
        verify(anthropicClient, never()).generateChatText(anyList(), eq(false));
    }

    @Test
    @DisplayName("답변 - 방금 메시지가 기준보다 짧으면 앞의 긴 사연과 상관없이 기본 채팅 모델로 답한다")
    void reply_routesShortMessageToBaseModel() {
        enableDeep();
        given(anthropicClient.generateChatText(anyList(), eq(false)))
                .willReturn(CompletableFuture.completedFuture("짧은 답"));

        String reply = chatLlm.reply(withLatestUser("가".repeat(99)), false).join();

        assertThat(reply).isEqualTo("짧은 답");
        verify(anthropicClient, never()).generateChatText(anyList(), eq(true));
    }


    @Test
    @DisplayName("답변 - 방의 첫 말이면 짧아도 긴 메시지 모델로 답한다")
    void reply_routesFirstTurnToDeepModel() {
        enableDeep();
        given(anthropicClient.generateChatText(anyList(), eq(true)))
                .willReturn(CompletableFuture.completedFuture("첫 답"));

        String reply = chatLlm.reply(PROMPT, true).join();

        assertThat(reply).isEqualTo("첫 답");
        verify(anthropicClient, never()).generateChatText(anyList(), eq(false));
    }

    @Test
    @DisplayName("답변 - Claude가 실패하거나 거절하면 다른 모델로 넘기지 않고 실패로 끝난다")
    void reply_failsWithoutFallback() {
        enableAnthropic();
        given(anthropicClient.generateChatText(anyList(), eq(false)))
                .willReturn(CompletableFuture.failedFuture(new LlmException()));

        assertThatThrownBy(() -> chatLlm.reply(PROMPT, false).join())
                .hasCauseInstanceOf(LlmException.class);
        verify(openAiClient, never()).generateChatText(anyList());
        verify(llmClient, never()).generate(anyList());
    }

    @Test
    @DisplayName("답변 - 긴 메시지 모델이 실패하면 기본 채팅 모델로 넘기지 않고 실패로 끝난다")
    void reply_deepFailsWithoutFallback() {
        enableDeep();
        given(anthropicClient.generateChatText(anyList(), eq(true)))
                .willReturn(CompletableFuture.failedFuture(new LlmException()));

        assertThatThrownBy(() -> chatLlm.reply(withLatestUser("가".repeat(150)), false).join())
                .hasCauseInstanceOf(LlmException.class);
        verify(anthropicClient, never()).generateChatText(anyList(), eq(false));
    }

    private void enableAnthropic() {
        anthropicProperties.setApiKey("anthropic-key");
        anthropicProperties.setChatModel("claude-haiku-5-5");
    }

    private void enableDeep() {
        enableAnthropic();
        anthropicProperties.setChatDeepModel("claude-sonnet-5-5");
    }

    // 앞에 긴 사연이 있어도 기준은 방금 보낸 메시지다 — 그래서 긴 첫 사연과 그 뒤 답을 함께 싣는다.
    private static List<ChatMessage> withLatestUser(String latest) {
        return List.of(
                ChatMessage.system("지시"),
                ChatMessage.user("가".repeat(300)),
                ChatMessage.assistant("그랬구나"),
                ChatMessage.user(latest),
                ChatMessage.system("점검"));
    }
}
