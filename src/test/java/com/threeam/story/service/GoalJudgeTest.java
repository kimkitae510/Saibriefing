package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.llm.ChatGoalProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmRole;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.StoryGoal;
import com.threeam.story.repository.StoryGoalRepository;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoalJudgeTest {

    private static final Long STORY_ID = 10L;

    @Spy
    private ChatGoalProperties goalProperties = goalProperties();

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ChatLlm chatLlm;

    @Mock
    private StoryGoalRepository storyGoalRepository;

    @InjectMocks
    private GoalJudge goalJudge;

    private static ChatGoalProperties goalProperties() {
        ChatGoalProperties properties = new ChatGoalProperties();
        properties.setJudge("판정 지시 자리표시자");
        properties.setMaxUserTurns(3);
        properties.setItems(List.of(
                goal("role", "상대에게 어떤 존재였나", "관계 안에서의 자리가 행동으로 드러남"),
                goal("contact", "접점 현황", "번호, 차단, 공통 지인 같은 남은 통로")));
        return properties;
    }

    @Test
    @DisplayName("판정 - 남은 목표만 지시에 싣고, 대화는 역할 표시와 함께 시간순으로 넘긴다")
    void judge_promptCarriesRemainingGoalsAndTranscript() {
        given(storyGoalRepository.findByStoryIdOrderByIdAsc(STORY_ID))
                .willReturn(List.of(StoryGoal.filled(STORY_ID, "role", "걔한텐 내가 유일한 편이었어")));
        given(chatLlm.judge(anyList(), any())).willReturn(CompletableFuture.completedFuture("{\"filled\":[]}"));

        goalJudge.judge(STORY_ID, List.of(
                message(MessageRole.USER, "사연이야"),
                message(MessageRole.ASSISTANT, "먼저 연락한 쪽이 있었나요")), 1).join();

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatLlm).judge(captor.capture(), any());
        List<ChatMessage> prompt = captor.getValue();
        assertThat(prompt.get(0).role()).isEqualTo(LlmRole.SYSTEM);
        // 이미 찬 목표(role)는 다시 판정하지 않는다
        assertThat(prompt.get(0).content()).contains("contact: 접점 현황").doesNotContain("role:");
        assertThat(prompt.get(1).content())
                .contains("(사연자) 사연이야")
                .contains("(상담자) 먼저 연락한 쪽이 있었나요");
    }

    @Test
    @DisplayName("판정 - 근거가 있는 채워짐만 저장한다. 근거 없는 것, 모르는 키, 이미 찬 키는 버린다")
    void judge_persistsOnlyEvidencedKnownGoals() {
        given(storyGoalRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(List.of());
        given(storyGoalRepository.save(any(StoryGoal.class))).willAnswer(inv -> inv.getArgument(0));
        given(chatLlm.judge(anyList(), any())).willReturn(CompletableFuture.completedFuture("""
                {"filled": [
                  {"key": "contact", "evidence": "번호는 살아 있고 차단은 안 했어"},
                  {"key": "role", "evidence": ""},
                  {"key": "unknown", "evidence": "아무 말"}
                ]}"""));

        GoalJudge.GoalState state = goalJudge.judge(STORY_ID, List.of(message(MessageRole.USER, "사연")), 1).join();

        ArgumentCaptor<StoryGoal> saved = ArgumentCaptor.forClass(StoryGoal.class);
        verify(storyGoalRepository).save(saved.capture());
        assertThat(saved.getValue().getGoalKey()).isEqualTo("contact");
        assertThat(saved.getValue().getEvidence()).isEqualTo("번호는 살아 있고 차단은 안 했어");
        // 근거 없는 role은 안 찼다 — 성급한 채워짐이 상담자의 질문을 일찍 끊지 않게
        assertThat(state.filled()).extracting(GoalJudge.FilledGoal::key).containsExactly("contact");
        assertThat(state.remaining()).extracting(ChatGoalProperties.Goal::getKey).containsExactly("role");
        assertThat(state.complete()).isFalse();
    }

    @Test
    @DisplayName("판정 - 다 찼으면 LLM을 부르지 않고 완료 상태를 돌려준다")
    void judge_skipsLlmWhenAllFilled() {
        given(storyGoalRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(List.of(
                StoryGoal.filled(STORY_ID, "role", "근거1"),
                StoryGoal.filled(STORY_ID, "contact", "근거2")));

        GoalJudge.GoalState state = goalJudge.judge(STORY_ID, List.of(message(MessageRole.USER, "사연")), 5).join();

        verify(chatLlm, never()).judge(anyList(), any());
        assertThat(state.complete()).isTrue();
        assertThat(state.closing()).isTrue();
        assertThat(state.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("판정 - LLM이 실패하거나 JSON이 깨져도 저장된 상태로 진행한다(답변을 막지 않는다)")
    void judge_fallsBackToSavedStateOnFailure() {
        given(storyGoalRepository.findByStoryIdOrderByIdAsc(STORY_ID))
                .willReturn(List.of(StoryGoal.filled(STORY_ID, "role", "근거")));
        given(chatLlm.judge(anyList(), any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("down")));

        GoalJudge.GoalState failed = goalJudge.judge(STORY_ID, List.of(message(MessageRole.USER, "사연")), 1).join();

        assertThat(failed.filled()).extracting(GoalJudge.FilledGoal::key).containsExactly("role");
        assertThat(failed.remaining()).hasSize(1);

        given(chatLlm.judge(anyList(), any())).willReturn(CompletableFuture.completedFuture("이건 JSON이 아니다"));

        GoalJudge.GoalState broken = goalJudge.judge(STORY_ID, List.of(message(MessageRole.USER, "사연")), 1).join();

        assertThat(broken.filled()).hasSize(1);
        verify(storyGoalRepository, never()).save(any(StoryGoal.class));
    }

    @Test
    @DisplayName("상태 - 유저 발화가 상한에 닿으면 덜 찼어도 마무리로 넘긴다(capped)")
    void current_capsAtMaxUserTurns() {
        given(storyGoalRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(List.of());

        assertThat(goalJudge.current(STORY_ID, 2).capped()).isFalse();
        GoalJudge.GoalState capped = goalJudge.current(STORY_ID, 3);
        assertThat(capped.capped()).isTrue();
        assertThat(capped.complete()).isFalse();
        assertThat(capped.closing()).isTrue();
    }

    @Test
    @DisplayName("상태 - 목표 설정이 비어 있으면 판정 없이 꺼진 상태다")
    void disabledWhenNoGoals() {
        goalProperties.setItems(List.of());

        assertThat(goalJudge.current(STORY_ID, 1)).isSameAs(GoalJudge.GoalState.DISABLED);
        assertThat(goalJudge.judge(STORY_ID, List.of(), 1).join().total()).isZero();
        verify(chatLlm, never()).judge(anyList(), any());
    }

    private static ChatGoalProperties.Goal goal(String key, String name, String filledWhen) {
        ChatGoalProperties.Goal goal = new ChatGoalProperties.Goal();
        goal.setKey(key);
        goal.setName(name);
        goal.setFilledWhen(filledWhen);
        return goal;
    }

    private Message message(MessageRole role, String content) {
        return Message.builder().role(role).content(content).build();
    }
}
