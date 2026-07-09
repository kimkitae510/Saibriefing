package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.dto.ReunionDiagnosis.FactorItem;
import com.threeam.assessment.dto.ReunionDiagnosis.WatchItem;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.RelapseRisk;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.repository.AssessmentReadingRepository;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.llm.LlmException;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

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

    // 콜백 전용 풀 자리. 테스트에선 인라인 실행이라 비동기 대기 없이 검증한다(운영에선 LlmCallbackConfig의 풀).
    @Spy
    private Executor llmCallbackExecutor = new InlineExecutor();

    static class InlineExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

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

    private static final List<FactorItem> FACTORS = List.of(
            new FactorItem(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE, "일주일째 읽씹",
                    "무반응이 이어지는 방향", null),
            new FactorItem(FactorName.REPLACEMENT, FactorLevel.NEUTRAL, "근거 없음", null, null),
            new FactorItem(FactorName.USER_CONDUCT, FactorLevel.NEUTRAL, "근거 없음", null, null),
            new FactorItem(FactorName.NOTICE_TONE, FactorLevel.NEUTRAL, "근거 없음", null, null),
            new FactorItem(FactorName.PARTNER_PATTERN, FactorLevel.NEUTRAL, "근거 없음", null, null));

    private static ReunionDiagnosis possible(boolean offer) {
        return new ReunionDiagnosis(ReunionVerdict.POSSIBLE, offer, BreakupType.BURNOUT,
                "반복 다툼 끝에 지쳐 통보", JumpRule.NONE, FACTORS, RelapseRisk.HIGH, "교정 미확인",
                List.of(new WatchItem("상대의 선연락 여부", "오면 상대신호가 유리로 바뀜")),
                List.of(), null, null, "총평", List.of("상대가 먼저 통보함"),
                List.of(), List.of(), List.of(), null, null);
    }

    private static ReunionDiagnosis locked(ReunionVerdict verdict, String reason, List<String> newFacts) {
        return new ReunionDiagnosis(verdict, false, null, null, JumpRule.NONE, List.of(),
                null, null, List.of(), List.of(), null, null, reason, newFacts,
                List.of(), List.of(), List.of(), null, null);
    }

    private static ReunionDiagnosis insufficient() {
        return locked(ReunionVerdict.INSUFFICIENT, "조금 더 들려줄래요?", List.of());
    }

    // 판독 스텁 재료 — 내용은 뷰 조립 테스트(AssessmentReadingViewTest)에서 검증하고
    // 여기선 흐름(부착 여부)만 본다.
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

    // 단일 호출 결과 헬퍼 — 게이트가 POSSIBLE일 때만 draft가 실린다.
    private static ReadingLlm.DirectReading direct(String status, String note, ReadingDraft draft) {
        return new ReadingLlm.DirectReading(status, note, draft);
    }

    private void givenDirect(ReadingLlm.DirectReading result) {
        given(readingLlm.readDirect(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture(result));
    }

    @Test
    @DisplayName("진단 - 게이트 POSSIBLE이면 판정 행과 판독을 저장하고 차감한다")
    void assess_possible() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("POSSIBLE", "", DRAFT));
        given(txService.save(eq(10L), any(Assessment.class), anyList(), any()))
                .willAnswer(inv -> inv.getArgument(1));
        given(txService.saveReading(eq(10L), any(), eq(DRAFT))).willReturn(READING_VIEW);

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        // 확률, 유형, 요인은 폐기됐다 — 판(낮음~높음)은 판독 리포트의 decision이 말한다.
        assertThat(response.getProbability()).isNull();
        assertThat(response.getBreakupType()).isNull();
        assertThat(response.getFactors()).isEmpty();
        assertThat(response.getReading()).isSameAs(READING_VIEW);
        // 유료 상품의 본체(판독)까지 성사됐으니 여기서만 차감한다.
        verify(usageLimiter).record(UsageKind.ASSESSMENT, 1L, 1);
    }

    @Test
    @DisplayName("진단 - 판독(2호출)이 실패하면 분석 전체가 실패다(구화면 낙하 방지, 무차감, 실패 1회)")
    void assess_readingFailureFailsWhole() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(readingLlm.readDirect(any(), any(), anyList()))
                .willReturn(CompletableFuture.failedFuture(new LlmException()));

        // 판독이 실패하면 분석 전체가 실패다 — 반쪽 화면이 없다.
        assertThatThrownBy(() -> assessmentService.assess(1L, 10L).join())
                .hasCauseInstanceOf(LlmException.class);
        // 유료 상품의 본체는 판독이라 반쪽만 만든 판은 차감하지 않는다.
        verify(usageLimiter, never()).record(UsageKind.ASSESSMENT, 1L, 1);
        // 실패 기록은 assess()의 whenComplete 한 곳에서 정확히 1회 — 이중이면 실패 1회에
        // 연속 2회로 계산돼 즉시 쿨다운이 걸린다.
        verify(txService, org.mockito.Mockito.times(1)).markAssessFailed(10L);
        // 실패해도 인플라이트 잠금은 풀려야 다음 시도가 가능하다.
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("진단 - REUNITED(재회 성공)면 확률 없이 저장하고 쿼터는 안 깎는다")
    void assess_reunitedSavesWithoutProbability() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("REUNITED", "다시 만나게 됐네", null));
        given(txService.save(eq(10L), any(Assessment.class), anyList(), any()))
                .willAnswer(inv -> inv.getArgument(1));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.REUNITED);
        assertThat(response.getProbability()).isNull(); // 목표 달성 상태 — 확률 산출 없음
        verify(scorer, never()).apply(any(), any(), anyList());
        verify(usageLimiter, never()).record(UsageKind.ASSESSMENT, 1L, 1);
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT(근거 부족)면 저장하지 않고 가이드만 돌려준다")
    void assess_insufficient() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("INSUFFICIENT", "조금 더 들려줄래요?", null));

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(response.getProbability()).isNull();
        assertThat(response.getReason()).isEqualTo("조금 더 들려줄래요?");
        verify(txService, never()).save(any(), any(), anyList(), any()); // 히스토리에 저장 안 함
        // 진단을 제공하지 못했으니 쿼터를 깎지 않는다 (재시도 남발은 INSUFFICIENT 가드가 막는다)
        verify(usageLimiter, never()).record(UsageKind.ASSESSMENT, 1L, 1);
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT 후 새 대화가 없으면 LLM 재호출 없이 안내만 돌려준다")
    void assess_insufficientRetryBlockedWithoutNewMessage() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("INSUFFICIENT", "조금 더 들려줄래요?", null));
        // 1차엔 아직 표시 없음(false) → LLM 판정, 2차엔 표시됨(true) → LLM 없이 거부.
        given(txService.isInsufficientRetryBlocked(10L)).willReturn(false, true);

        assessmentService.assess(1L, 10L).join(); // 1차: LLM이 INSUFFICIENT 판정
        AssessmentResponse retry = assessmentService.assess(1L, 10L).join(); // 2차: 새 대화 없음

        assertThat(retry.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        verify(readingLlm, org.mockito.Mockito.times(1))
                .readDirect(any(), any(), anyList()); // 2차는 미호출
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("진단 - INSUFFICIENT 후라도 새 대화가 생기면 다시 LLM으로 진단한다")
    void assess_insufficientRetryAllowedWithNewMessage() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("INSUFFICIENT", "조금 더 들려줄래요?", null));
        // 새 대화가 계속 있으니 표시가 있어도 재시도가 막히지 않는다(항상 false).
        given(txService.isInsufficientRetryBlocked(10L)).willReturn(false);

        assessmentService.assess(1L, 10L).join();
        assessmentService.assess(1L, 10L).join();

        verify(readingLlm, org.mockito.Mockito.times(2)).readDirect(any(), any(), anyList());
    }

    @Test
    @DisplayName("진단 - 같은 재료 연속 실패로 막혀 있으면 LLM 없이 안내만 돌려준다")
    void assess_failRetryBlocked() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(txService.assessFailRetryBlockedSeconds(10L)).willReturn(167);

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        assertThat(response.getReason()).contains("차감되지 않았습니다");
        // 화면이 카운트다운을 띄우려면 남은 초가 응답에 실려야 한다.
        assertThat(response.getRetryAfterSeconds()).isEqualTo(167);
        verify(readingLlm, never()).readDirect(any(), any(), anyList()); // 무료 LLM 호출 루프 차단
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("진단 - LLM 실패 시 실패 표시를 남기고 잠금을 해제한다(쿼터는 미차감)")
    void assess_marksFailureOnLlmError() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        given(readingLlm.readDirect(any(), any(), anyList()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("응답 잘림")));

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L).join())
                .hasCauseInstanceOf(RuntimeException.class);

        verify(txService).markAssessFailed(10L); // 연속 실패 카운트 재료
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("진단 - LLM 왕복이 정상 처리되면 실패 연속 카운트를 지운다(INSUFFICIENT 판정 포함)")
    void assess_clearsFailureOnCompletedRoundtrip() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("INSUFFICIENT", "조금 더 들려줄래요?", null));

        assessmentService.assess(1L, 10L).join();

        verify(txService).clearAssessFailed(10L);
    }

    @Test
    @DisplayName("진단 - 유저 발화가 없으면 LLM 호출 없이 안내만, 쿼터도 안 깎고 잠금은 해제한다")
    void assess_preGateOnSparseConversation() {
        given(txService.loadContext(1L, 10L)).willReturn(SPARSE_CONTEXT);

        AssessmentResponse response = assessmentService.assess(1L, 10L).join();

        assertThat(response.getVerdict()).isEqualTo(ReunionVerdict.INSUFFICIENT);
        // 발화 부족 안내(사전 가드)는 근거 부족 안내(LLM 판정)와 문구가 다르다
        assertThat(response.getReason()).contains("사연을 먼저 들려주시면");
        verify(readingLlm, never()).readDirect(any(), any(), anyList()); // LLM 비용 없음
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("진단 - 없거나 남의 사연이면 STORY_NOT_FOUND, LLM 호출도 쿼터 기록도 없다")
    void assess_storyNotFound() {
        given(txService.loadContext(1L, 10L))
                .willThrow(new BusinessException(ErrorCode.STORY_NOT_FOUND));

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);

        verify(readingLlm, never()).readDirect(any(), any(), anyList());
        // 후차감이라 성공 전에 실패하면 기록할 것이 없다. 잠금만 해제.
        verify(usageLimiter, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("진단 - 같은 사연의 진단이 진행 중이면 접수를 거부한다(연타 차단)")
    void assess_inFlightRejected() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.GENERATION_IN_PROGRESS))
                .given(usageLimiter).acquireInFlight(UsageKind.ASSESSMENT, 1L);

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);

        verify(usageLimiter, never()).check(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(readingLlm, never()).readDirect(any(), any(), anyList());
    }

    @Test
    @DisplayName("진단 - 일일 한도를 넘으면 QUOTA_EXCEEDED, 잠금을 해제하고 LLM을 호출하지 않는다")
    void assess_quotaExceeded() {
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED))
                .given(usageLimiter).check(UsageKind.ASSESSMENT, 1L, 1);

        assertThatThrownBy(() -> assessmentService.assess(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);

        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
        verify(readingLlm, never()).readDirect(any(), any(), anyList());
    }

    @Test
    @DisplayName("진단 - 완료(성공) 시 in-flight 잠금이 해제된다")
    void assess_releasesLockOnCompletion() {
        given(txService.loadContext(1L, 10L)).willReturn(CONTEXT);
        givenDirect(direct("INSUFFICIENT", "조금 더 들려줄래요?", null));

        assessmentService.assess(1L, 10L).join();

        verify(usageLimiter).check(UsageKind.ASSESSMENT, 1L, 1);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
        // INSUFFICIENT는 진단을 제공하지 못했으니 차감하지 않는다
        verify(usageLimiter, never()).record(UsageKind.ASSESSMENT, 1L, 1);
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
