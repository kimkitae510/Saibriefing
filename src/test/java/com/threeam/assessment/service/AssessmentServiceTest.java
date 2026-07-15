package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentReadingRepository;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// 접수 검사(preflight)와 저장(apply)만 본다 — 두 호출의 병렬 실행과 작업 행은 AssessmentJobServiceTest.
@ExtendWith(MockitoExtension.class)
class AssessmentServiceTest {

    @Mock
    private AssessmentTxService txService;

    @Mock
    private ReunionLlm reunionLlm;

    @Mock
    private ReadingLlm readingLlm;

    @Mock
    private TypeBandScorer scorer;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private AssessmentReadingRepository readingRepository;

    @Mock
    private UsageLimiter usageLimiter;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AssessmentService assessmentService;

    // 사전 가드(유저 발화 없음 거부)를 통과하는 기본 컨텍스트
    private static final AssessmentContext CONTEXT =
            new AssessmentContext(List.of(), List.of(
                    ChatMessage.user("걔가 먼저 헤어지자 했어"),
                    ChatMessage.assistant("언제부터 그런 말이 나왔어?"),
                    ChatMessage.user("한 달 전부터 지쳤다고 하더라"),
                    ChatMessage.assistant("연락은 지금 어때?"),
                    ChatMessage.user("일주일째 읽씹이야")), "오늘 날짜: 2026-05-01", null, null);

    // 유저 발화가 아예 없는 판 — 사연 한 번(1회)부터 분석이 열리므로 경계는 0회다.
    private static final AssessmentContext SPARSE_CONTEXT =
            new AssessmentContext(List.of(), List.of(
                    ChatMessage.assistant("두 사람의 이야기를 들려주십시오")),
                    "오늘 날짜: 2026-05-01", null, null);

    // 판독 스텁 재료 — 내용은 뷰 조립 테스트(AssessmentReadingViewTest)에서 검증하고
    // 여기선 흐름(저장 여부)만 본다.
    private static final ReadingDraft DRAFT = new ReadingDraft("진단 요약", null,
            List.of(new ReadingDraft.Diagnosis("partnerSignal", "상대신호", "CORE", 1, "유리",
                    "CONFIRMED", "판정", "서술", List.of())),
            "지금 상대는 어떤 마음일까",
            List.of(new ReadingDraft.Chapter("정말 마음이 식은 걸까?", "판독 결론", "서술",
                    "TRAJECTORY", "주장 한 줄", null, List.of())),
            new ReadingDraft.CurrentState("남은 감정", "지금의 선택", "회복 기대"),
            null,
            null,
            new ReadingDraft.ActionPlan("지금은 어떻게 움직일까?", "HOLD_AND_REASSESS", "답",
                    "2주 뒤", "이유", "다음 행동", "마음가짐", "목표", "무엇이 갈리는가",
                    List.of("행동"), "멈출 조건", "닫히면", List.of("피할 것")),
            null);

    private static final AssessmentResponse.Reading READING_VIEW =
            new AssessmentResponse.Reading(DRAFT, null, null);

    private static ReadingLlm.DirectReading direct(String status, String note, ReadingDraft draft) {
        return new ReadingLlm.DirectReading(status, note, draft);
    }

    private static Assessment withId(Assessment a, long id) {
        ReflectionTestUtils.setField(a, "id", id);
        return a;
    }

    @Test
    @DisplayName("접수 검사 - 재료가 충분하면 안내 없이 컨텍스트만 돌려준다(한도 검사는 하고 차감은 안 한다)")
    void preflight_passes() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);

        AssessmentService.Preflight preflight = assessmentService.preflight(1L, 10L);

        assertThat(preflight.guide()).isNull();
        assertThat(preflight.context()).isSameAs(CONTEXT);
        verify(usageLimiter).check(UsageKind.ASSESSMENT, 1L, 1);
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("접수 검사 - 유저 발화가 없으면 LLM 없이 발화 안내(근거 부족 안내와 문구가 다르다)")
    void preflight_sparseConversation() {
        given(txService.loadContext(1L, 10L)).willReturn(SPARSE_CONTEXT);

        AssessmentService.Preflight preflight = assessmentService.preflight(1L, 10L);

        assertThat(preflight.guide()).isNotNull();
        assertThat(preflight.guide().getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(preflight.guide().getReason()).contains("사연을 먼저 들려주시면");
    }

    @Test
    @DisplayName("접수 검사 - INSUFFICIENT 후 새 대화가 없으면 LLM 없이 근거 부족 안내")
    void preflight_insufficientRetryBlocked() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(txService.isInsufficientRetryBlocked(10L)).willReturn(true);

        AssessmentService.Preflight preflight = assessmentService.preflight(1L, 10L);

        assertThat(preflight.guide().getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(preflight.guide().getReason()).contains("사실이 아직 부족");
    }

    @Test
    @DisplayName("접수 검사 - 같은 재료 연속 실패로 막혀 있으면 남은 초와 함께 안내")
    void preflight_failRetryBlocked() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(txService.assessFailRetryBlockedSeconds(10L)).willReturn(167);

        AssessmentService.Preflight preflight = assessmentService.preflight(1L, 10L);

        assertThat(preflight.guide().getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(preflight.guide().getReason()).contains("차감되지 않았습니다");
        // 화면이 카운트다운을 띄우려면 남은 초가 응답에 실려야 한다.
        assertThat(preflight.guide().getRetryAfterSeconds()).isEqualTo(167);
    }

    @Test
    @DisplayName("접수 검사 - 없거나 남의 사연이면 STORY_NOT_FOUND")
    void preflight_storyNotFound() {
        given(txService.loadContext(1L, 10L))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> assessmentService.preflight(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("접수 검사 - 한도를 넘으면 QUOTA_EXCEEDED, 컨텍스트도 안 읽는다")
    void preflight_quotaExceeded() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).check(UsageKind.ASSESSMENT, 1L, 1);

        assertThatThrownBy(() -> assessmentService.preflight(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
        verify(txService, never()).loadContext(any(), any());
    }

    @Test
    @DisplayName("저장 - 게이트 POSSIBLE이면 판정 행과 판독을 저장하고 차감하며 판정 id를 돌려준다")
    void apply_possible() {
        given(txService.save(eq(10L), any(Assessment.class), anyList(), any()))
                .willAnswer(inv -> withId(inv.getArgument(1), 77L));
        given(txService.saveReading(eq(10L), eq(77L), eq(DRAFT))).willReturn(READING_VIEW);

        AssessmentService.Applied applied = assessmentService.apply(10L, 1L, direct("POSSIBLE", "", DRAFT));

        assertThat(applied.assessmentId()).isEqualTo(77L);
        assertThat(applied.note()).isNull();
        assertThat(applied.response().getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(applied.response().getReading()).isSameAs(READING_VIEW);
        verify(txService).clearInsufficient(10L);
        // 유료 상품의 본체(판독)까지 성사됐으니 여기서만 차감한다.
        verify(usageLimiter).record(UsageKind.ASSESSMENT, 1L, 1);
    }

    @Test
    @DisplayName("저장 - REUNITED(재회 성공)면 확률 없이 안내 행만 저장하고 쿼터는 안 깎는다")
    void apply_reunited() {
        given(txService.save(eq(10L), any(Assessment.class), anyList(), any()))
                .willAnswer(inv -> withId(inv.getArgument(1), 78L));

        AssessmentService.Applied applied = assessmentService.apply(10L, 1L, direct("REUNITED", "다시 만나게 됐네", null));

        assertThat(applied.assessmentId()).isEqualTo(78L);
        assertThat(applied.response().getVerdict()).isEqualTo(ReunionVerdict.REUNITED);
        assertThat(applied.response().getProbability()).isNull();
        verify(scorer, never()).apply(any(), any(), anyList());
        verify(usageLimiter, never()).record(UsageKind.ASSESSMENT, 1L, 1);
    }

    @Test
    @DisplayName("저장 - INSUFFICIENT(근거 부족)면 저장 없이 안내만, 안내문은 note로 남아 폴링이 되살린다")
    void apply_insufficient() {
        AssessmentService.Applied applied = assessmentService.apply(10L, 1L, direct("INSUFFICIENT", "조금 더 들려줄래요?", null));

        assertThat(applied.assessmentId()).isNull();
        assertThat(applied.note()).isEqualTo("조금 더 들려줄래요?");
        assertThat(applied.response().getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(applied.response().getReason()).isEqualTo("조금 더 들려줄래요?");
        verify(txService).markInsufficient(10L);
        verify(txService, never()).save(any(), any(), anyList(), any());
        verify(usageLimiter, never()).record(UsageKind.ASSESSMENT, 1L, 1);
        // 되살린 결과도 같은 안내다.
        assertThat(assessmentService.guideResponse(10L, applied.note()).getReason()).isEqualTo("조금 더 들려줄래요?");
    }

    @Test
    @DisplayName("결과 조회 - 다른 사연의 판정 id면 ASSESSMENT_NOT_FOUND")
    void findResponse_rejectsOtherStory() {
        Assessment other = withId(Assessment.builder().storyId(11L).verdict(ReunionVerdict.POSSIBLE)
                .reason("r").build(), 77L);
        given(assessmentRepository.findById(77L)).willReturn(Optional.of(other));

        assertThatThrownBy(() -> assessmentService.findResponse(10L, 77L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("히스토리 - 없거나 남의 사연이면 STORY_NOT_FOUND")
    void getHistory_storyNotFound() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND))
                .given(txService).loadOwnership(1L, 10L);

        assertThatThrownBy(() -> assessmentService.getHistory(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }
}
