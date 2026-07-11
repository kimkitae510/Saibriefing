package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatGoalProperties;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.ChatPersonaProperties;
import com.threeam.llm.LlmRole;
import com.threeam.story.dto.MessageResponse;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import com.threeam.story.entity.ReunionDirection;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MessageTxServiceTest {

    // 실문구는 로컬 설정(persona.yml 등)으로 주입되므로 테스트는 자리표시자를 채운 실객체를 쓴다.
    // 점검 기본값은 빈 문자열이고 비면 주입을 건너뛰므로, 프롬프트 '구조'를 검증하려면 여기서 채워야 한다.
    @Spy
    private ChatPersonaProperties personaProperties = personaProperties();

    private static final String FINAL_CHECK = "출력 직전 점검 자리표시자";
    private static final String GOAL_GUIDE = "목표 사용 규칙 자리표시자";
    private static final String CLOSING = "마무리 규칙 자리표시자";

    private static ChatPersonaProperties personaProperties() {
        ChatPersonaProperties properties = new ChatPersonaProperties();
        properties.setFinalCheck(FINAL_CHECK);
        properties.setGoalGuide(GOAL_GUIDE);
        properties.setClosing(CLOSING);
        return properties;
    }

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryIntakeRepository storyIntakeRepository;

    @InjectMocks
    private MessageTxService messageTxService;

    @Test
    @DisplayName("재시도 준비 - 폴백을 지우고 같은 유저 메시지를 돌려준다(새로 저장하지 않는다)")
    void prepareRetry_deletesFallbackAndReusesUserMessage() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        Message userMessage = message(MessageRole.USER, "오늘 너무 힘들어");
        ReflectionTestUtils.setField(userMessage, "id", 7L);
        Message fallback = Message.fallback(story);
        ReflectionTestUtils.setField(fallback, "id", 8L);
        // 최신순 조회라 폴백이 먼저, 그 앞이 유저 메시지다
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(fallback, userMessage), PageRequest.of(0, 2), false));

        MessageTxService.PreparedRetry prepared = messageTxService.prepareRetry(1L, 10L);

        assertThat(prepared.pollAfterId()).isEqualTo(7L);
        assertThat(prepared.userContent()).isEqualTo("오늘 너무 힘들어");
        verify(messageRepository).delete(fallback);
        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    @DisplayName("재시도 준비 - 마지막이 폴백이 아니면 거부한다(연타, 그새 답이 붙은 경우)")
    void prepareRetry_rejectedWhenNothingFailed() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L))
                .willReturn(Optional.of(story(10L)));
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(
                        List.of(message(MessageRole.ASSISTANT, "들었어"),
                                message(MessageRole.USER, "오늘 너무 힘들어")),
                        PageRequest.of(0, 2), false));

        assertThatThrownBy(() -> messageTxService.prepareRetry(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CHAT_RETRY_NOT_APPLICABLE);

        verify(messageRepository, never()).delete(any(Message.class));
    }

    @Test
    @DisplayName("프롬프트 조립 - 페르소나, 대화 전체(양쪽 역할, 시간순), 맨 끝 점검 순이다")
    void buildPrompt_personaThenWholeConversationThenFinalCheck() {
        given(messageRepository.findByStoryIdOrderByIdAsc(10L)).willReturn(List.of(
                message(MessageRole.USER, "사연이야"),
                message(MessageRole.ASSISTANT, "먼저 연락한 쪽이 있었나요"),
                message(MessageRole.USER, "내가 한 번")));

        List<ChatMessage> prompt = messageTxService.promptFor(10L, GoalJudge.GoalState.DISABLED);

        assertThat(prompt.get(0).role()).isEqualTo(LlmRole.SYSTEM); // 맨 앞은 페르소나
        // 대화는 창 없이 전부, 역할 그대로 시간순
        assertThat(prompt).extracting(ChatMessage::role).containsExactly(
                LlmRole.SYSTEM, LlmRole.USER, LlmRole.ASSISTANT, LlmRole.USER, LlmRole.SYSTEM);
        assertThat(prompt.get(2).content()).isEqualTo("먼저 연락한 쪽이 있었나요");
        // 출력 직전 점검은 마지막으로 읽히는 지시라 반드시 맨 끝
        assertThat(prompt.get(prompt.size() - 1).content()).isEqualTo(FINAL_CHECK);
        // 목표가 꺼져 있으면 목표 블록 자체가 없다
        assertThat(prompt).extracting(ChatMessage::content)
                .noneMatch(c -> c.contains("[탐색 목표]"))
                .doesNotContain(GOAL_GUIDE, CLOSING);
    }

    @Test
    @DisplayName("프롬프트 조립 - 목표 블록은 채워진 것과 근거, 남은 것과 묻는 꼴을 싣고 사용 규칙이 뒤따른다")
    void buildPrompt_goalBlockCarriesFilledAndRemaining() {
        given(messageRepository.findByStoryIdOrderByIdAsc(10L))
                .willReturn(List.of(message(MessageRole.USER, "사연이야")));
        ChatGoalProperties.Goal remaining = goal("contact", "접점 현황", "번호가 살아 있는지 같은 것을 묻는다");
        GoalJudge.GoalState goals = new GoalJudge.GoalState(
                List.of(new GoalJudge.FilledGoal("role", "상대에게 어떤 존재였나", "걔한텐 내가 유일한 편이었어")),
                List.of(remaining), false);

        List<ChatMessage> prompt = messageTxService.promptFor(10L, goals);

        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("[탐색 목표]")
                        && c.contains("상대에게 어떤 존재였나 — 걔한텐 내가 유일한 편이었어")
                        && c.contains("접점 현황 — 번호가 살아 있는지 같은 것을 묻는다")
                        && c.endsWith(GOAL_GUIDE))
                // 아직 안 찬 판이라 마무리 규칙은 안 실린다
                .noneMatch(c -> c.contains(CLOSING));
    }

    @Test
    @DisplayName("프롬프트 조립 - 목표가 다 찼거나 상한에 닿으면 사용 규칙 대신 마무리 규칙이 실린다")
    void buildPrompt_closingWhenGoalsDoneOrCapped() {
        given(messageRepository.findByStoryIdOrderByIdAsc(10L))
                .willReturn(List.of(message(MessageRole.USER, "사연이야")));
        GoalJudge.GoalState complete = new GoalJudge.GoalState(
                List.of(new GoalJudge.FilledGoal("role", "상대에게 어떤 존재였나", "근거")), List.of(), false);

        List<ChatMessage> done = messageTxService.promptFor(10L, complete);

        assertThat(done).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("남은 것: 없음") && c.endsWith(CLOSING))
                .noneMatch(c -> c.contains(GOAL_GUIDE));

        // 덜 찼어도 턴 상한에 닿은 판은 마무리로 넘긴다 — 회피하는 유저에게 같은 질문이 영영 이어지지 않게
        GoalJudge.GoalState capped = new GoalJudge.GoalState(
                List.of(), List.of(goal("contact", "접점 현황", null)), true);

        List<ChatMessage> late = messageTxService.promptFor(10L, capped);

        assertThat(late).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("접점 현황") && c.endsWith(CLOSING));
    }

    @Test
    @DisplayName("프롬프트 조립 - 유저가 직접 적어준 사실만 싣고 추출된 사실은 싣지 않는다(대화 전체가 실리므로)")
    void buildPrompt_includesOnlyUserProvidedFacts() {
        given(messageRepository.findByStoryIdOrderByIdAsc(10L))
                .willReturn(List.of(message(MessageRole.USER, "안녕")));
        given(storyFactRepository.findByStoryIdOrderByIdAsc(10L)).willReturn(List.of(
                StoryFact.of(10L, "추출된 사실", 1L),
                StoryFact.userProvided(10L, "상대가 먼저 이별을 통보함")));

        List<ChatMessage> prompt = messageTxService.promptFor(10L, GoalJudge.GoalState.DISABLED);

        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("직접 적어준 사실") && c.contains("상대가 먼저 이별을 통보함"))
                .noneMatch(c -> c.contains("추출된 사실"));
    }

    @Test
    @DisplayName("유저 메시지 저장 - 저장하고 즉시 반환할 응답과 폴링 기준 id를 돌려준다")
    void appendUser_success() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> {
            Message saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 5L);
            return saved;
        });

        MessageTxService.PreparedSend prepared = messageTxService.appendUserMessage(1L, 10L, "오늘 힘들어");

        assertThat(prepared.userMessageId()).isEqualTo(5L);
        assertThat(prepared.userMessage().getRole()).isEqualTo(MessageRole.USER);
        assertThat(prepared.userMessage().getContent()).isEqualTo("오늘 힘들어");
        // 판독으로 넘기는 강제 턴은 없다 — 유저 메시지 하나만 저장된다
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    @DisplayName("유저 메시지 저장 - 제목이 기본값이면 첫 메시지 내용으로 제목을 바꾼다")
    void appendUser_renamesDefaultTitle() {
        Story story = Story.builder().userId(1L).title(Story.DEFAULT_TITLE).build();
        ReflectionTestUtils.setField(story, "id", 10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        messageTxService.appendUserMessage(1L, 10L,
                "3년 만난 남자친구랑 2주 전에 헤어졌어. 걔가 먼저 헤어지자고 했어.");

        // 공백 정리 후 앞 20자 + 말줄임
        assertThat(story.getTitle()).isEqualTo("3년 만난 남자친구랑 2주 전에 헤어…");
    }

    @Test
    @DisplayName("유저 메시지 저장 - 제목을 이미 지정한 사연은 건드리지 않는다")
    void appendUser_keepsCustomTitle() {
        Story story = story(10L);   // 제목 "사연"
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        messageTxService.appendUserMessage(1L, 10L, "안녕");

        assertThat(story.getTitle()).isEqualTo("사연");
    }

    @Test
    @DisplayName("유저 메시지 저장 - 없거나 남의 사연이면 STORY_NOT_FOUND, 저장하지 않는다")
    void appendUser_notFound() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> messageTxService.appendUserMessage(1L, 10L, "hi"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(messageRepository, never()).save(any(Message.class));
    }

    @Test
    @DisplayName("어시스턴트 응답 저장 - 응답을 저장하고 사연 활동시각을 갱신한다")
    void appendAssistant_success() {
        Story story = story(10L);
        given(storyRepository.findById(10L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageTxService.appendAssistantReply(10L, "괜찮아, 여기 있어");

        assertThat(response.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(response.getContent()).isEqualTo("괜찮아, 여기 있어");
        verify(messageRepository).save(any(Message.class));
    }

    @Test
    @DisplayName("어시스턴트 응답 저장 - 마크다운 기호는 저장 전에 걷어낸다(흘린 굵기가 무작위로 렌더링되지 않게)")
    void appendAssistant_stripsMarkdownMarks() {
        Story story = story(10L);
        given(storyRepository.findById(10L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageTxService.appendAssistantReply(10L,
                "## 분석\n**의사소통과 정서적 기대의 충돌**이 반복된 것으로 보입니다. 별표 하나 *는 남습니다.");

        // 굵게(**)와 줄머리 제목(#)만 걷는다. 별표 하나는 건드리지 않는다.
        assertThat(response.getContent()).isEqualTo(
                "분석\n의사소통과 정서적 기대의 충돌이 반복된 것으로 보입니다. 별표 하나 *는 남습니다.");
    }

    // 옛 판이 답변 끝에 붙이던 내부 메타데이터. 새 페르소나는 안 만들지만 흘러나오면 여전히 뗀다 —
    // 안 떼면 JSON이 그대로 말풍선에 찍히고 다음 턴 프롬프트에도 실린다.
    @Test
    @DisplayName("어시스턴트 응답 저장 - chat-meta는 사연에 옮기고 본문에서 뗀다")
    void appendAssistant_extractsChatMeta() {
        Story story = story(10L);
        given(storyRepository.findById(10L)).willReturn(Optional.of(story));
        given(messageRepository.save(any(Message.class))).willAnswer(inv -> inv.getArgument(0));

        MessageResponse response = messageTxService.appendAssistantReply(10L,
                "상대는 아직 관계를 다시 잇겠다는 행동을 하지 않고 있습니다.\n\n"
                        + "---chat-meta---\n{\"reunionDirection\":\"NEGATIVE\"}");

        assertThat(response.getContent())
                .isEqualTo("상대는 아직 관계를 다시 잇겠다는 행동을 하지 않고 있습니다.")
                .doesNotContain("reunionDirection");
        assertThat(story.getReunionDirection()).isEqualTo(ReunionDirection.NEGATIVE);
    }

    // 옛 판이 남긴 질문 구분선. 기록은 고치지 않으므로 표시에서 걷어 질문을 본문 줄로 잇는다.
    @Test
    @DisplayName("응답 표시 - 옛 질문 마커는 걷어내고 질문 줄은 본문에 남긴다")
    void messageResponse_flattensLegacyQuestionMarker() {
        Message legacy = message(MessageRole.ASSISTANT, "들었어요.\n\n---질문---\n먼저 연락한 쪽이 있었나요?");

        MessageResponse response = MessageResponse.from(legacy);

        assertThat(response.getContent()).isEqualTo("들었어요.\n\n\n먼저 연락한 쪽이 있었나요?")
                .doesNotContain("---질문---");
    }


    @Test
    @DisplayName("프롬프트 조립 - 대화가 없으면 첫 말 지시가 user 턴으로 실린다(system만 보내면 Gemini가 거절)")
    void buildPrompt_openingTurnWhenEmpty() {
        personaProperties.setOpening("첫 말 지시 자리표시자");
        given(messageRepository.findByStoryIdOrderByIdAsc(10L)).willReturn(List.of());

        List<ChatMessage> prompt = messageTxService.promptFor(10L, GoalJudge.GoalState.DISABLED);

        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.USER)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.contains("첫 말 지시 자리표시자") && c.contains("시스템 지시"));
        // 대화가 있으면 첫 말 지시는 없다
        given(messageRepository.findByStoryIdOrderByIdAsc(10L))
                .willReturn(List.of(message(MessageRole.USER, "사연이야")));
        assertThat(messageTxService.promptFor(10L, GoalJudge.GoalState.DISABLED))
                .extracting(ChatMessage::content).noneMatch(c -> c.contains("첫 말 지시 자리표시자"));
    }

    @Test
    @DisplayName("프롬프트 조립 - 문진 블록에는 시점과 사실의 기준이라는 머리말이 붙는다")
    void buildPrompt_intakeBlockHasHeader() {
        given(messageRepository.findByStoryIdOrderByIdAsc(10L))
                .willReturn(List.of(message(MessageRole.USER, "사연이야")));
        com.threeam.story.entity.StoryIntake intake = com.threeam.story.entity.StoryIntake.builder()
                .storyId(10L).callName("지민").daysSinceBreakup(30).build();
        given(storyIntakeRepository.findByStoryId(10L)).willReturn(Optional.of(intake));

        List<ChatMessage> prompt = messageTxService.promptFor(10L, GoalJudge.GoalState.DISABLED);

        assertThat(prompt).filteredOn(m -> m.role() == LlmRole.SYSTEM)
                .extracting(ChatMessage::content)
                .anyMatch(c -> c.startsWith("[문진으로 확인된 것") && c.contains("이별 후 경과"));
    }

    @Test
    @DisplayName("재시도 준비 - 첫 말이 실패해 폴백만 있는 방은 폴백을 지우고 폴링 기준 0을 돌려준다")
    void prepareRetry_openingFallbackOnly() {
        Story story = story(10L);
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(10L, 1L)).willReturn(Optional.of(story));
        Message fallback = Message.fallback(story);
        ReflectionTestUtils.setField(fallback, "id", 3L);
        given(messageRepository.findByStoryIdOrderByIdDesc(eq(10L), any(Pageable.class)))
                .willReturn(new SliceImpl<>(List.of(fallback), PageRequest.of(0, 2), false));

        MessageTxService.PreparedRetry prepared = messageTxService.prepareRetry(1L, 10L);

        assertThat(prepared.pollAfterId()).isZero();
        assertThat(prepared.userContent()).isNull();
        verify(messageRepository).delete(fallback);
    }

    private Story story(Long id) {
        Story story = Story.builder().userId(1L).title("사연").build();
        ReflectionTestUtils.setField(story, "id", id);
        return story;
    }

    private Message message(MessageRole role, String content) {
        return Message.builder().role(role).content(content).build();
    }

    private ChatGoalProperties.Goal goal(String key, String name, String ask) {
        ChatGoalProperties.Goal goal = new ChatGoalProperties.Goal();
        goal.setKey(key);
        goal.setName(name);
        goal.setAsk(ask);
        return goal;
    }
}
