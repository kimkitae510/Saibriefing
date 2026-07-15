package com.threeam.assessment.service;

import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentJobResponse;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.AssessmentJob;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.llm.ChatMessage;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 분석 작업 오케스트레이션 — 접수(사전 검사, 합류, 생성)와 A/B 병렬 실행, 조립, 마감.
// 상태의 원본은 assessment_jobs 행이다. 인플라이트 잠금(인메모리)은 연타를 접수 단계에서 끊는 빠른 가드일 뿐이다.
// 트랜잭션 밖(NOT_SUPPORTED) — DB 쓰기는 전부 짧은 트랜잭션 서비스로, 느린 LLM 대기는 그 사이에서 논블로킹으로.
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentJobService {

    private final AssessmentService assessmentService;
    private final AssessmentTxService txService;
    private final AssessmentJobTxService jobTxService;
    private final ReadingLlm readingLlm;
    private final UsageLimiter usageLimiter;
    // 단계 저장과 조립을 HttpClient 스레드가 아니라 우리 풀에서 돌린다(LlmCallbackConfig 참고).
    private final Executor llmCallbackExecutor;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AssessmentJobResponse start(Long userId, Long storyId) {
        txService.loadOwnership(userId, storyId);
        // 이미 도는 작업이 있으면 잠금도 검사도 없이 합류 — 새로고침한 화면이 같은 작업에 다시 붙는다.
        Optional<AssessmentJob> active = jobTxService.findActive(storyId);
        if (active.isPresent()) {
            return AssessmentJobResponse.of(active.get(), null);
        }
        // 이 유저가 이미 분석을 생성 중이면 거부(연타 차단 + 동시 발사 한도 우회 차단).
        usageLimiter.acquireInFlight(UsageKind.ASSESSMENT, userId);
        try {
            AssessmentService.Preflight preflight = assessmentService.preflight(userId, storyId);
            if (preflight.guide() != null) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                return AssessmentJobResponse.guide(preflight.guide());
            }
            AssessmentJobTxService.Started started =
                    jobTxService.createOrJoin(userId, storyId, readingLlm.judgementOn());
            if (!started.created()) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                return AssessmentJobResponse.of(started.job(), null);
            }
            run(started.job(), preflight.context(), userId);
            return AssessmentJobResponse.of(started.job(), null);
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
            throw e;
        }
    }

    // 한 단계의 결말 — 텍스트거나 예외. handle로 받아 둘 다 끝난 뒤에 판단한다(한쪽 실패가 다른 쪽 저장을 못 막게).
    private record Outcome(String text, Throwable error) {
        static Outcome of(String text, Throwable error) {
            return new Outcome(text, error == null ? null : unwrap(error));
        }

        boolean failed() {
            return error != null;
        }
    }

    // A와 B를 동시에 띄운다. 끝난 단계는 즉시 저장되고, 둘 다 끝나면 조립해 판정과 판독으로 저장한다.
    // 어느 쪽이든 실패하면 작업은 FAILED — 끝난 쪽 텍스트는 남아 다음 접수가 그 호출을 건너뛴다.
    private void run(AssessmentJob job, AssessmentContext context, Long userId) {
        Long jobId = job.getId();
        Long storyId = job.getStoryId();
        String intake = context.intakeBlock();
        String today = context.todayLine();
        List<String> messages = context.conversation().stream().map(ChatMessage::content).toList();
        jobTxService.markRunning(jobId);

        CompletableFuture<String> analysis = job.isAnalysisDone()
                ? CompletableFuture.completedFuture(job.getAnalysisText())
                : readingLlm.analysisText(intake, today, messages)
                        .thenApplyAsync(text -> {
                            jobTxService.completeAnalysis(jobId, text);
                            return text;
                        }, llmCallbackExecutor);
        CompletableFuture<String> judgement = job.isJudgementDone()
                ? CompletableFuture.completedFuture(job.getJudgementText())
                : readingLlm.judgementJson(intake, today, messages)
                        .thenApplyAsync(text -> {
                            jobTxService.completeJudgement(jobId, text);
                            return text;
                        }, llmCallbackExecutor);

        analysis.handle(Outcome::of)
                .thenCombine(judgement.handle(Outcome::of), List::of)
                .thenComposeAsync(outcomes -> {
                    Outcome a = outcomes.get(0);
                    Outcome b = outcomes.get(1);
                    if (a.failed() || b.failed()) {
                        Throwable cause = a.failed() ? a.error() : b.error();
                        log.error("분석 작업 {} 단계 실패 — 분석 {} 판독 {}", jobId,
                                a.failed() ? "실패" : "완료", b.failed() ? "실패" : "완료", cause);
                        return CompletableFuture.failedFuture(cause);
                    }
                    return readingLlm.assemble(a.text(), b.text(), intake, today, messages);
                }, llmCallbackExecutor)
                .thenApplyAsync(direct -> {
                    // LLM 왕복이 정상 처리됐으니 실패 연속 카운트를 지운다.
                    clearAssessFailQuietly(storyId);
                    AssessmentService.Applied applied = assessmentService.apply(storyId, userId, direct);
                    jobTxService.finish(jobId, applied.assessmentId(), applied.note());
                    return applied;
                }, llmCallbackExecutor)
                .whenComplete((ignored, ex) -> {
                    if (ex != null) {
                        Throwable cause = unwrap(ex);
                        log.error("분석 작업 {} 실패 storyId={} userId={}", jobId, storyId, userId, cause);
                        failQuietly(jobId, describe(cause));
                        markAssessFailedQuietly(storyId);
                    }
                    usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                });
    }

    public AssessmentJobResponse get(Long userId, Long storyId, Long jobId) {
        txService.loadOwnership(userId, storyId);
        AssessmentJob job = jobTxService.find(jobId, storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ASSESSMENT_JOB_NOT_FOUND));
        return AssessmentJobResponse.of(job, resultOf(job));
    }

    // 진행 폴링용 — 도는 작업이 있으면 그 상태, 없으면 비어 있다.
    public Optional<AssessmentJobResponse> progress(Long userId, Long storyId) {
        txService.loadOwnership(userId, storyId);
        return jobTxService.findActive(storyId).map(job -> AssessmentJobResponse.of(job, null));
    }

    private AssessmentResponse resultOf(AssessmentJob job) {
        if (job.getStatus() != AssessmentJob.Status.DONE) {
            return null;
        }
        return job.getAssessmentId() != null
                ? assessmentService.findResponse(job.getStoryId(), job.getAssessmentId())
                : assessmentService.guideResponse(job.getStoryId(), job.getNote());
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }

    // 표시 기록 실패가 마감을 막지 않게 격리한다 — 잠금 해제는 whenComplete 끝에서 반드시 일어나야 한다.
    private void failQuietly(Long jobId, String error) {
        try {
            jobTxService.fail(jobId, error);
        } catch (RuntimeException e) {
            log.error("분석 작업 실패 표시 기록 실패 jobId={}", jobId, e);
        }
    }

    private void clearAssessFailQuietly(Long storyId) {
        try {
            txService.clearAssessFailed(storyId);
        } catch (RuntimeException e) {
            log.error("분석 실패 표시 해제 실패 storyId={}", storyId, e);
        }
    }

    private void markAssessFailedQuietly(Long storyId) {
        try {
            txService.markAssessFailed(storyId);
        } catch (RuntimeException e) {
            log.error("분석 실패 표시 기록 실패 storyId={}", storyId, e);
        }
    }
}
