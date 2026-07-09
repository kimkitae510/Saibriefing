package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.match.service.MatchProfileService;
import com.threeam.story.entity.FactSource;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.Story;
import com.threeam.story.entity.StoryFact;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryFactRepository;
import com.threeam.story.repository.StoryIntakeRepository;
import com.threeam.story.repository.StoryRepository;
import com.threeam.story.service.StoryFactService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssessmentTxServiceTest {

    private static final Long STORY_ID = 10L;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private StoryFactRepository storyFactRepository;

    @Mock
    private StoryFactService storyFactService;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private MatchProfileService matchProfileService;

    @Mock
    private TypeBandScorer scorer;

    @Mock
    private StoryIntakeRepository storyIntakeRepository;

    @InjectMocks
    private AssessmentTxService txService;

    private Assessment savedAssessment(Long id) {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(20)
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(assessment, "id", id);
        return assessment;
    }

    @Test
    @DisplayName("진단 저장 - 새 사실을 저장된 진단의 id(출처)와 함께 원장 서비스로 넘긴다")
    void save_delegatesFactsWithAssessmentId() {
        given(assessmentRepository.save(any(Assessment.class))).willReturn(savedAssessment(99L));
        List<String> newFacts = List.of("일주일 전 상대에게서 연락 옴");

        txService.save(STORY_ID, savedAssessment(null), newFacts, null);

        verify(storyFactService).appendFacts(STORY_ID, 99L, newFacts);
    }

    private Story storyWithFailure(int streak, LocalDateTime failedAt) {
        Story story = Story.builder().userId(1L).title("사연").build();
        ReflectionTestUtils.setField(story, "assessFailStreak", streak);
        ReflectionTestUtils.setField(story, "lastAssessFailedAt", failedAt);
        return story;
    }

    private static final LocalDateTime FAILED_AT = LocalDateTime.of(2025, 11, 10, 12, 0);

    @Test
    @DisplayName("실패 가드 - 같은 재료 연속 2회 실패면 쿨다운 동안 막는다")
    void failGuard_blocksAfterStreakWithoutNewMessage() {
        LocalDateTime recentFail = LocalDateTime.now().minusMinutes(2);
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, recentFail)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, recentFail)).willReturn(false);

        // 3분 쿨다운 중 2분이 지났으니 남은 시간은 60초 안쪽 — 화면 카운트다운이 이 값을 쓴다.
        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isBetween(1, 60);
    }

    @Test
    @DisplayName("실패 가드 - 1회 실패까지는 재시도를 허용한다(일시 장애 복구 여지)")
    void failGuard_allowsSingleFailure() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(1, LocalDateTime.now().minusMinutes(2))));

        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isZero();
    }

    @Test
    @DisplayName("실패 가드 - 연속 2회여도 새 대화가 생기면 다시 허용한다")
    void failGuard_allowsAfterNewMessage() {
        LocalDateTime recentFail = LocalDateTime.now().minusMinutes(2);
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, recentFail)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, recentFail)).willReturn(true);

        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isZero();
    }

    @Test
    @DisplayName("실패 가드 - 새 대화가 없어도 쿨다운(3분)이 지나면 다시 허용한다")
    void failGuard_allowsAfterCooldown() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, LocalDateTime.now().minusMinutes(4))));

        assertThat(txService.assessFailRetryBlockedSeconds(STORY_ID)).isZero();
    }

    @Test
    @DisplayName("실패 표시 - 지난 실패 이후 새 대화가 없으면 연속 카운트를 올린다")
    void markFailed_incrementsOnSameMaterial() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(1, FAILED_AT)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, FAILED_AT)).willReturn(false);

        txService.markAssessFailed(STORY_ID);

        verify(storyRepository).incrementAssessFailStreak(eq(STORY_ID), any(LocalDateTime.class));
        verify(storyRepository, never()).restartAssessFailStreak(any(), any());
    }

    @Test
    @DisplayName("실패 표시 - 재료가 바뀐 뒤의 첫 실패는 1부터 다시 센다")
    void markFailed_restartsAfterNewMaterial() {
        given(storyRepository.findById(STORY_ID))
                .willReturn(Optional.of(storyWithFailure(2, FAILED_AT)));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(STORY_ID, FAILED_AT)).willReturn(true);

        txService.markAssessFailed(STORY_ID);

        verify(storyRepository).restartAssessFailStreak(eq(STORY_ID), any(LocalDateTime.class));
        verify(storyRepository, never()).incrementAssessFailStreak(any(), any());
    }

    private Assessment lastAssessment() {
        Assessment last = savedAssessment(77L);
        ReflectionTestUtils.setField(last, "createdAt", LocalDateTime.of(2025, 11, 10, 12, 0));
        return last;
    }

    private void givenOwnedStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(Story.builder().userId(1L).title("사연").build()));
    }

    // 재진단 가드(새 대화 없으면 AS002 거부)는 폐지 — 같은 재료 재분석도 유저의 선택이고
    // 후차감이라 비용은 본인이 진다. 아래 테스트가 그 폐지를 고정한다.
    @Test
    @DisplayName("마지막 진단 이후 새 대화가 없어도 재분석을 거부하지 않는다(가드 폐지)")
    void loadContext_allowsReassessWithoutNewMessages() {
        givenOwnedStory();
        Assessment last = lastAssessment();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        givenConversation();

        assertThatCode(() -> txService.loadContext(1L, STORY_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("직전 진단 앵커 - 유형 없는 점프 판(유저 통보)도 요지가 실린다")
    void loadContext_buildsDigestForJumpOnlyAssessment() {
        givenOwnedStory();
        // 유저 통보 판은 설계상 breakupType이 null이고 jumpRule만 있다 — 이 판이야말로
        // 뚜렷/흔적 경계가 흔들려서 앵커가 꼭 필요하다(질문 한 줄에 +10 실측).
        Assessment last = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(65)
                .jumpRule(JumpRule.USER_DUMPED_FAINT)
                .reason("총평")
                .build();
        ReflectionTestUtils.setField(last, "id", 77L);
        ReflectionTestUtils.setField(last, "createdAt", LocalDateTime.of(2025, 11, 10, 12, 0));
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(last));
        givenConversation();

        var context = txService.loadContext(1L, STORY_ID);

        assertThat(context.previousDigest()).contains("점프 규칙");
        assertThat(context.previousDigest()).contains("확률=65");
    }

    @Test
    @DisplayName("첫 진단(기록 없음)도 정상 조립된다")
    void loadContext_firstAssessment() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());
        givenConversation();

        assertThatCode(() -> txService.loadContext(1L, STORY_ID)).doesNotThrowAnyException();
    }

    private Assessment reunitedAssessment() {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.REUNITED)
                .reason("다시 만나는 중")
                .build();
        ReflectionTestUtils.setField(assessment, "id", 77L);
        return assessment;
    }

    @Test
    @DisplayName("헤어짐 확인 - 잠금 판정을 지우고 직전 확률 진단으로 즉시 복귀한다")
    void confirmBreakup_deletesLockAndRestoresPrevious() {
        givenOwnedStory();
        Assessment reunion = reunitedAssessment();
        Assessment previous = lastAssessment();
        given(assessmentRepository.findByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(List.of(reunion, previous));

        var restored = txService.confirmBreakup(1L, STORY_ID);

        assertThat(restored).isPresent();
        assertThat(restored.get().getProbability()).isEqualTo(20); // 재진단 없이 직전 확률로
        verify(assessmentRepository).deleteAll(List.of(reunion));
        verify(storyFactService).appendCorrection(STORY_ID,
                AssessmentTxService.BREAKUP_CONFIRMED_FACT);
    }

    @Test
    @DisplayName("헤어짐 확인 - 직전 확률 진단이 없으면 빈 값(첫 진단 안내로 복귀)")
    void confirmBreakup_emptyWhenNoPrevious() {
        givenOwnedStory();
        given(assessmentRepository.findByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(List.of(reunitedAssessment()));

        assertThat(txService.confirmBreakup(1L, STORY_ID)).isEmpty();
    }

    // 번복하고 재진단했는데 또 잠금이 나오면 잠금이 겹쳐 쌓인다. 하나만 지우면 바로 아래
    // 잠금이 올라와 화면이 그대로라 아무 일도 안 일어난 것처럼 보인다(실측).
    @Test
    @DisplayName("헤어짐 확인 - 잠금 판정이 여러 개 쌓여 있으면 한 번에 다 걷어낸다")
    void confirmBreakup_clearsStackedLocks() {
        givenOwnedStory();
        Assessment newer = reunitedAssessment();
        Assessment older = reunitedAssessment();
        Assessment previous = lastAssessment();
        given(assessmentRepository.findByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(List.of(newer, older, previous));

        var restored = txService.confirmBreakup(1L, STORY_ID);

        assertThat(restored).isPresent();
        assertThat(restored.get().getProbability()).isEqualTo(20);
        verify(assessmentRepository).deleteAll(List.of(newer, older));
    }

    @Test
    @DisplayName("헤어짐 확인 - 마지막 판정이 재회 성공 잠금이 아니면 거부한다(원장 오염 방지)")
    void confirmBreakup_rejectsWhenNotLocked() {
        givenOwnedStory();
        given(assessmentRepository.findByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(List.of(lastAssessment())); // POSSIBLE

        assertThatThrownBy(() -> txService.confirmBreakup(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_LOCKED);

        verifyNoInteractions(storyFactService);
    }

    @Test
    @DisplayName("헤어짐 확인 - 진단 기록이 아예 없어도 거부한다")
    void confirmBreakup_rejectsWithoutAssessment() {
        givenOwnedStory();
        given(assessmentRepository.findByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(List.of());

        assertThatThrownBy(() -> txService.confirmBreakup(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_LOCKED);

        verifyNoInteractions(storyFactService);
    }

    private Assessment offerAssessment() {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(100)
                .reason("상대 제안 유효")
                .build();
        ReflectionTestUtils.setField(assessment, "id", 77L);
        return assessment;
    }

    @Test
    @DisplayName("제안 번복 - 새 판(유형/요인 없음)은 확률을 비워 재분석을 유도하고 원장에 정정을 남긴다")
    void retractOffer_clearsProbabilityOnNewPipelineRow() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(offerAssessment()));

        var response = txService.retractOffer(1L, STORY_ID);

        // 재합산 대역(유형, 요인)이 저장돼 있지 않으므로 null 복귀 — 화면은 다시 분석으로 안내.
        assertThat(response.getProbability()).isNull();
        verify(storyFactService).appendCorrection(STORY_ID,
                AssessmentTxService.OFFER_RETRACTED_FACT);
    }

    @Test
    @DisplayName("제안 번복 - 마지막 진단이 100이 아니면 거부한다")
    void retractOffer_rejectsWhenNotOffer() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(lastAssessment())); // POSSIBLE 20%

        assertThatThrownBy(() -> txService.retractOffer(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_OFFER);

        verifyNoInteractions(storyFactService);
    }

    @Test
    @DisplayName("제안 번복 - 진단 기록이 없어도 거부한다")
    void retractOffer_rejectsWithoutAssessment() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> txService.retractOffer(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_OFFER);

        verifyNoInteractions(storyFactService);
    }

    private void givenConversation() {
        Message message = Message.user(Story.builder().userId(1L).title("사연").build(), "걔가 먼저 헤어지자 했어");
        given(messageRepository.findByStoryIdOrderByIdAsc(STORY_ID)).willReturn(List.of(message));
        given(storyFactRepository.findByStoryIdOrderByIdDesc(eq(STORY_ID), any(Pageable.class)))
                .willReturn(List.of());
    }

    // 탐색 채팅의 유저 답은 짧아서 상담자의 질문 없이는 무엇에 대한 답인지가 사라진다(실측 592).
    // 양쪽 역할을 시간순으로 다 싣되 누구 말인지 표시하고, 폴백(우리가 대신 낸 안내)은 뺀다.
    @Test
    @DisplayName("판독 입력 - 대화 전체를 시간순으로, 역할 표시와 날짜를 붙여 싣고 폴백은 뺀다")
    void loadConversation_carriesBothRolesInOrder() {
        Story story = Story.builder().userId(1L).title("사연").build();
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(story));
        Message first = Message.user(story, "걔가 먼저 헤어지자 했어");
        ReflectionTestUtils.setField(first, "createdAt", LocalDateTime.of(2026, 9, 1, 3, 0));
        Message ask = Message.assistant(story, "헤어지고 나서 먼저 연락한 쪽이 있었나요");
        ReflectionTestUtils.setField(ask, "createdAt", LocalDateTime.of(2026, 9, 1, 3, 1));
        Message answer = Message.user(story, "내가 한 번");
        ReflectionTestUtils.setField(answer, "createdAt", LocalDateTime.of(2026, 9, 2, 3, 0));
        Message fallback = Message.fallback(story);
        given(messageRepository.findByStoryIdOrderByIdAsc(STORY_ID))
                .willReturn(List.of(first, ask, answer, fallback));

        List<com.threeam.llm.ChatMessage> conversation = txService.loadConversation(1L, STORY_ID);

        assertThat(conversation).extracting(com.threeam.llm.ChatMessage::content).containsExactly(
                "(9/1) (사연자) 걔가 먼저 헤어지자 했어",
                "(9/1) (상담자) 헤어지고 나서 먼저 연락한 쪽이 있었나요",
                "(9/2) (사연자) 내가 한 번");
        assertThat(conversation.get(1).role()).isEqualTo(com.threeam.llm.LlmRole.ASSISTANT);
    }

    @Test
    @DisplayName("판독 입력 - 유저 발화가 하나도 없으면 ASSESSMENT_NO_MESSAGES")
    void loadConversation_rejectsWithoutUserMessage() {
        Story story = Story.builder().userId(1L).title("사연").build();
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, 1L))
                .willReturn(Optional.of(story));
        given(messageRepository.findByStoryIdOrderByIdAsc(STORY_ID))
                .willReturn(List.of(Message.assistant(story, "안녕하세요")));

        assertThatThrownBy(() -> txService.loadConversation(1L, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NO_MESSAGES);
    }
}
