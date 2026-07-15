package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentJobResponse;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentJob;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmException;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssessmentJobServiceTest {

    @Mock
    private AssessmentService assessmentService;

    @Mock
    private AssessmentTxService txService;

    @Mock
    private AssessmentJobTxService jobTxService;

    @Mock
    private ReadingLlm readingLlm;

    @Mock
    private UsageLimiter usageLimiter;

    // 콜백 전용 풀 자리. 테스트에선 인라인 실행이라 비동기 대기 없이 검증한다.
    // 람다는 Mockito가 spy하지 못한다 — 같은 스레드에서 바로 실행하는 보통 클래스로 둔다.
    @Spy
    private Executor llmCallbackExecutor = new DirectExecutor();

    static class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    @InjectMocks
    private AssessmentJobService jobService;

    private static final AssessmentContext CONTEXT =
            new AssessmentContext(List.of(), List.of(
                    ChatMessage.user("걔가 먼저 헤어지자 했어"),
                    ChatMessage.assistant("연락은 지금 어때?"),
                    ChatMessage.user("일주일째 읽씹이야")), "오늘 날짜: 2026-05-01", null, "문진");

    private static final ReadingLlm.DirectReading DIRECT =
            new ReadingLlm.DirectReading("POSSIBLE", null, null);

    private static final AssessmentResponse RESPONSE = AssessmentResponse.from(
            Assessment.builder().storyId(10L).verdict(ReunionVerdict.POSSIBLE).reason("r").build());

    private static AssessmentJob job(String analysis, String judgement, boolean judgementOn) {
        AssessmentJob job = AssessmentJob.builder().storyId(10L).userId(1L)
                .analysisText(analysis).judgementText(judgement).judgementOn(judgementOn).build();
        ReflectionTestUtils.setField(job, "id", 100L);
        return job;
    }

    private void givenFreshJob(AssessmentJob job) {
        given(jobTxService.findActive(10L)).willReturn(Optional.empty());
        given(assessmentService.preflight(1L, 10L)).willReturn(new AssessmentService.Preflight(CONTEXT, null));
        given(readingLlm.judgementOn()).willReturn(true);
        given(jobTxService.createOrJoin(1L, 10L, true)).willReturn(new AssessmentJobTxService.Started(job, true));
    }

    @Test
    @DisplayName("접수 - 새 작업을 만들고 A와 B를 돌려 각 단계를 저장한 뒤 조립, 저장, 마감한다")
    void start_runsBothStepsAndFinishes() {
        givenFreshJob(job(null, null, true));
        given(readingLlm.analysisText(eq("문진"), eq("오늘 날짜: 2026-05-01"), anyList()))
                .willReturn(CompletableFuture.completedFuture("분석 원석"));
        given(readingLlm.judgementJson(eq("문진"), eq("오늘 날짜: 2026-05-01"), anyList()))
                .willReturn(CompletableFuture.completedFuture("{\"grade\":\"높음\"}"));
        given(readingLlm.assemble(eq("분석 원석"), eq("{\"grade\":\"높음\"}"), eq("문진"), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture(DIRECT));
        given(assessmentService.apply(10L, 1L, DIRECT))
                .willReturn(new AssessmentService.Applied(RESPONSE, 77L, null));

        AssessmentJobResponse response = jobService.start(1L, 10L);

        assertThat(response.jobId()).isEqualTo(100L);
        assertThat(response.result()).isNull();
        verify(jobTxService).markRunning(100L);
        verify(jobTxService).completeAnalysis(100L, "분석 원석");
        verify(jobTxService).completeJudgement(100L, "{\"grade\":\"높음\"}");
        verify(jobTxService).finish(100L, 77L, null);
        verify(txService).clearAssessFailed(10L);
        verify(jobTxService, never()).fail(any(), any());
        verify(usageLimiter).acquireInFlight(UsageKind.ASSESSMENT, 1L);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("접수 - 진행 중 작업이 있으면 잠금도 검사도 LLM도 없이 그 작업에 합류한다")
    void start_joinsActiveJob() {
        AssessmentJob running = job(null, null, true);
        running.start();
        given(jobTxService.findActive(10L)).willReturn(Optional.of(running));

        AssessmentJobResponse response = jobService.start(1L, 10L);

        assertThat(response.jobId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(AssessmentJob.Status.RUNNING);
        verify(usageLimiter, never()).acquireInFlight(any(), any());
        verify(assessmentService, never()).preflight(any(), any());
        verify(readingLlm, never()).analysisText(any(), any(), anyList());
    }

    @Test
    @DisplayName("접수 - 잠금 안에서 다시 봤을 때 작업이 생겨 있으면 그 작업에 합류하고 잠금을 푼다")
    void start_joinsWhenCreatedMeanwhile() {
        AssessmentJob other = job(null, null, true);
        given(jobTxService.findActive(10L)).willReturn(Optional.empty());
        given(assessmentService.preflight(1L, 10L)).willReturn(new AssessmentService.Preflight(CONTEXT, null));
        given(readingLlm.judgementOn()).willReturn(true);
        given(jobTxService.createOrJoin(1L, 10L, true)).willReturn(new AssessmentJobTxService.Started(other, false));

        AssessmentJobResponse response = jobService.start(1L, 10L);

        assertThat(response.jobId()).isEqualTo(100L);
        verify(jobTxService, never()).markRunning(any());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("접수 - 사전 가드 안내(쿨다운 등)는 작업 없이 DONE + result로 돌려주고 잠금을 푼다")
    void start_guideWithoutJob() {
        given(jobTxService.findActive(10L)).willReturn(Optional.empty());
        AssessmentResponse guide = AssessmentResponse.from(Assessment.builder().storyId(10L)
                .verdict(ReunionVerdict.INSUFFICIENT).reason("차감되지 않았습니다").build())
                .withRetryAfterSeconds(167);
        given(assessmentService.preflight(1L, 10L)).willReturn(new AssessmentService.Preflight(CONTEXT, guide));

        AssessmentJobResponse response = jobService.start(1L, 10L);

        assertThat(response.jobId()).isNull();
        assertThat(response.status()).isEqualTo(AssessmentJob.Status.DONE);
        assertThat(response.result().getRetryAfterSeconds()).isEqualTo(167);
        verify(jobTxService, never()).createOrJoin(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("접수 - 한도 초과면 그대로 던지고 잠금을 푼다")
    void start_quotaExceeded() {
        given(jobTxService.findActive(10L)).willReturn(Optional.empty());
        given(assessmentService.preflight(1L, 10L)).willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED));

        assertThatThrownBy(() -> jobService.start(1L, 10L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("접수 - 이 유저의 다른 생성이 돌고 있으면 접수를 거부한다(연타 차단)")
    void start_inFlightRejected() {
        given(jobTxService.findActive(10L)).willReturn(Optional.empty());
        org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.GENERATION_IN_PROGRESS))
                .given(usageLimiter).acquireInFlight(UsageKind.ASSESSMENT, 1L);

        assertThatThrownBy(() -> jobService.start(1L, 10L))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);
        verify(assessmentService, never()).preflight(any(), any());
    }

    @Test
    @DisplayName("실행 - B가 실패하면 A 텍스트는 저장된 채 작업이 FAILED가 되고 실패 표시가 남는다(무차감)")
    void run_judgementFailureKeepsAnalysis() {
        givenFreshJob(job(null, null, true));
        given(readingLlm.analysisText(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("분석 원석"));
        given(readingLlm.judgementJson(any(), any(), anyList()))
                .willReturn(CompletableFuture.failedFuture(new LlmException()));

        jobService.start(1L, 10L);

        verify(jobTxService).completeAnalysis(100L, "분석 원석");
        verify(jobTxService, never()).completeJudgement(any(), any());
        verify(jobTxService).fail(eq(100L), contains("LlmException"));
        verify(txService).markAssessFailed(10L);
        verify(readingLlm, never()).assemble(any(), any(), any(), any(), anyList());
        verify(assessmentService, never()).apply(any(), any(), any());
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("실행 - 지난 실패에서 물려받은 단계(분석 DONE)는 다시 사지 않고 B만 돌린다")
    void run_skipsInheritedStep() {
        givenFreshJob(job("옛 분석", null, true));
        given(readingLlm.judgementJson(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("{}"));
        given(readingLlm.assemble(eq("옛 분석"), eq("{}"), any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture(DIRECT));
        given(assessmentService.apply(10L, 1L, DIRECT))
                .willReturn(new AssessmentService.Applied(RESPONSE, 77L, null));

        jobService.start(1L, 10L);

        verify(readingLlm, never()).analysisText(any(), any(), anyList());
        verify(jobTxService, never()).completeAnalysis(any(), any());
        verify(jobTxService).completeJudgement(100L, "{}");
        verify(jobTxService).finish(100L, 77L, null);
    }

    @Test
    @DisplayName("실행 - B가 꺼진 판(판독 단계 DONE, 텍스트 없음)은 A만 돌리고 옛 단일 호출로 조립한다")
    void run_withoutJudgement() {
        givenFreshJob(job(null, null, false));
        given(readingLlm.analysisText(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("분석 원석"));
        given(readingLlm.assemble(eq("분석 원석"), eq(null), any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture(DIRECT));
        given(assessmentService.apply(10L, 1L, DIRECT))
                .willReturn(new AssessmentService.Applied(RESPONSE, 77L, null));

        jobService.start(1L, 10L);

        verify(readingLlm, never()).judgementJson(any(), any(), anyList());
        verify(jobTxService).finish(100L, 77L, null);
    }

    @Test
    @DisplayName("실행 - 조립이나 저장이 실패해도 작업은 FAILED, 두 단계 텍스트는 남는다")
    void run_assembleFailure() {
        givenFreshJob(job(null, null, true));
        given(readingLlm.analysisText(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("분석 원석"));
        given(readingLlm.judgementJson(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("{}"));
        given(readingLlm.assemble(any(), any(), any(), any(), anyList()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("응답 잘림")));

        jobService.start(1L, 10L);

        verify(jobTxService).completeAnalysis(100L, "분석 원석");
        verify(jobTxService).completeJudgement(100L, "{}");
        verify(jobTxService).fail(eq(100L), contains("응답 잘림"));
        verify(txService).markAssessFailed(10L);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
    }

    @Test
    @DisplayName("실행 - 근거부족 게이트는 판정 없이 DONE, 안내문이 note로 남는다")
    void run_insufficientGateFinishesWithoutAssessment() {
        givenFreshJob(job(null, null, true));
        given(readingLlm.analysisText(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("분석 원석"));
        given(readingLlm.judgementJson(any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture("{}"));
        ReadingLlm.DirectReading gate = new ReadingLlm.DirectReading("INSUFFICIENT", "조금 더", null);
        given(readingLlm.assemble(any(), any(), any(), any(), anyList()))
                .willReturn(CompletableFuture.completedFuture(gate));
        given(assessmentService.apply(10L, 1L, gate))
                .willReturn(new AssessmentService.Applied(RESPONSE, null, "조금 더"));

        jobService.start(1L, 10L);

        verify(jobTxService).finish(100L, null, "조금 더");
        verify(jobTxService, never()).fail(any(), anyString());
    }

    @Test
    @DisplayName("조회 - DONE이면 저장된 판정을 결과로 싣는다")
    void get_doneWithAssessment() {
        AssessmentJob job = job(null, null, true);
        job.finish(77L, null);
        given(jobTxService.find(100L, 10L)).willReturn(Optional.of(job));
        given(assessmentService.findResponse(10L, 77L)).willReturn(RESPONSE);

        AssessmentJobResponse response = jobService.get(1L, 10L, 100L);

        assertThat(response.status()).isEqualTo(AssessmentJob.Status.DONE);
        assertThat(response.result()).isSameAs(RESPONSE);
        verify(txService).loadOwnership(1L, 10L);
    }

    @Test
    @DisplayName("조회 - 판정 없이 끝난 작업(근거부족)은 note로 안내를 되살린다")
    void get_doneWithoutAssessment() {
        AssessmentJob job = job(null, null, true);
        job.finish(null, "조금 더");
        given(jobTxService.find(100L, 10L)).willReturn(Optional.of(job));
        given(assessmentService.guideResponse(10L, "조금 더")).willReturn(RESPONSE);

        assertThat(jobService.get(1L, 10L, 100L).result()).isSameAs(RESPONSE);
    }

    @Test
    @DisplayName("조회 - 진행 중이면 result 없이 상태만, 없는 작업이면 ASSESSMENT_JOB_NOT_FOUND")
    void get_runningAndMissing() {
        AssessmentJob job = job(null, null, true);
        job.start();
        given(jobTxService.find(100L, 10L)).willReturn(Optional.of(job));
        assertThat(jobService.get(1L, 10L, 100L).result()).isNull();

        given(jobTxService.find(101L, 10L)).willReturn(Optional.empty());
        assertThatThrownBy(() -> jobService.get(1L, 10L, 101L))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ASSESSMENT_JOB_NOT_FOUND);
    }
}
