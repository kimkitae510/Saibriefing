package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.AssessmentContext;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.AssessmentReading;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.entity.WatchPoint;
import com.threeam.assessment.repository.AssessmentReadingRepository;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmRole;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentService {

    // 사전 가드: 유저 발화 1회(첫 사연)부터 분석을 연다. 언제 리포트로 넘어갈지는 사연자가 정한다 —
    // 탐색 채팅이 목표를 다 채우면 권하지만, 그 전에 눌러도 막지 않는다. 근거가 얇으면 판독이
    // INSUFFICIENT로 돌려보내는 문이 따로 있다.
    private static final int MIN_USER_TURNS = 1;

    // 진행 단계 폴링용 인메모리 표시(단일 인스턴스 전제 — 로그인 가드와 동일).
    // 값은 LLM 왕복 동안만 존재한다: DIAGNOSIS(판정) → READING(심층 판독) → 제거.
    private final Map<Long, String> runStage = new ConcurrentHashMap<>();

    private final AssessmentTxService txService;
    private final ReunionLlm reunionLlm;
    private final ReadingLlm readingLlm;
    private final TypeBandScorer scorer;
    private final AssessmentRepository assessmentRepository;
    private final AssessmentReadingRepository readingRepository;
    private final UsageLimiter usageLimiter;
    private final ObjectMapper objectMapper;
    // 분석 저장을 HttpClient 스레드가 아니라 우리 풀에서 돌린다(LlmCallbackConfig 참고).
    private final Executor llmCallbackExecutor;

    // 트랜잭션 밖(NOT_SUPPORTED)에서 오케스트레이션한다.
    // DB 저장은 txService의 짧은 트랜잭션, 느린 LLM 호출은 그 사이에서 논블로킹으로.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CompletableFuture<AssessmentResponse> assess(Long userId, Long storyId) {
        // 이 유저가 이미 분석을 생성 중이면 거부(연타 차단 + 기억 upsert 레이스 방지 + 동시 발사 한도 우회 차단).
        usageLimiter.acquireInFlight(UsageKind.ASSESSMENT, userId);
        try {
            // 후차감: 여기서는 한도 검사만. 기록은 분석이 정상 처리된 뒤에 한다(LLM 장애 시 미차감).
            usageLimiter.check(UsageKind.ASSESSMENT, userId, 1);

            AssessmentContext context = txService.loadContext(userId, storyId);
            long userTurns = context.conversation().stream()
                    .filter(message -> message.role() == LlmRole.USER)
                    .count();
            if (userTurns < MIN_USER_TURNS) {
                // LLM 비용이 없으므로 쿼터도 차감하지 않는다. 잠금만 풀고 안내를 돌려준다.
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId, TURNS_GUIDE));
            }
            // 지난 INSUFFICIENT 이후 새 대화가 없으면 다시 물어봐도 같은 답이다 — LLM 없이 거부.
            // 이 표시는 DB(stories.last_insufficient_at)에 있어 재시작, 멀티인스턴스에서도 유지된다.
            if (txService.isInsufficientRetryBlocked(storyId)) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                return CompletableFuture.completedFuture(insufficientGuide(storyId, NO_BASIS_GUIDE));
            }
            // 실패 재시도 가드: 실패는 후차감(미차감)이라, 같은 재료가 계속 같은 이유(안전성 차단,
            // 응답 잘림 등)로 실패하면 무한 무료 LLM 호출 루프가 된다(실측). 연속 2회부터 LLM 없이 거부.
            int retryAfterSeconds = txService.assessFailRetryBlockedSeconds(storyId);
            if (retryAfterSeconds > 0) {
                usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                // 남은 초를 함께 내려 화면이 "잠시 뒤"가 아니라 실제 카운트다운을 보여주게 한다.
                return CompletableFuture.completedFuture(
                        insufficientGuide(storyId, FAIL_RETRY_GUIDE).withRetryAfterSeconds(retryAfterSeconds));
            }
            runStage.put(storyId, "READING");
            return readingLlm.readDirect(context.intakeBlock(),
                            context.todayLine(),
                            context.conversation().stream()
                                    .map(com.threeam.llm.ChatMessage::content).toList())
                    .thenApplyAsync(direct -> {
                        // LLM 왕복이 정상 처리됐으니 실패 연속 카운트를 지운다.
                        clearAssessFailQuietly(storyId);
                        return applyDirect(storyId, userId, direct);
                    }, llmCallbackExecutor)
                    .whenComplete((ignored, ex) -> {
                        if (ex != null) {
                            log.error("분석 처리 실패 storyId={} userId={}", storyId, userId, ex);
                            markAssessFailedQuietly(storyId);
                        }
                        runStage.remove(storyId);
                        usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
                    });
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            runStage.remove(storyId);
            usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId);
            throw e;
        }
    }

    // 단일 호출 결과를 판정 행과 판독으로 저장한다. 게이트가 POSSIBLE이 아니면
    // 기존 정책 그대로: 근거부족은 저장 없이 안내(무차감), 잠금 판정은 안내 행만 저장(무차감).
    private AssessmentResponse applyDirect(Long storyId, Long userId,
                                           ReadingLlm.DirectReading direct) {
        String note = direct.gateNote();
        if ("INSUFFICIENT".equals(direct.caseStatus())) {
            txService.markInsufficient(storyId);
            return insufficientGuide(storyId,
                    note == null || note.isBlank() ? NO_BASIS_GUIDE : note);
        }
        if ("REUNITED".equals(direct.caseStatus())) {
            txService.clearInsufficient(storyId);
            Assessment locked = Assessment.builder()
                    .storyId(storyId)
                    .verdict(ReunionVerdict.REUNITED)
                    .reason(note == null || note.isBlank() ? REUNITED_GUIDE : note)
                    .build();
            Assessment saved = txService.save(storyId, locked, List.of(), null);
            return AssessmentResponse.from(saved);
        }

        txService.clearInsufficient(storyId);
        ReadingDraft draft = direct.draft();
        Assessment assessment = Assessment.builder()
                .storyId(storyId)
                .verdict(ReunionVerdict.POSSIBLE)
                .reason(reasonSummary(draft.decision()))
                .build();
        Assessment saved = txService.save(storyId, assessment, List.of(), null);
        AssessmentResponse response = AssessmentResponse.from(saved)
                .withReading(txService.saveReading(storyId, saved.getId(), draft));
        // 후차감 — 유료 상품의 본체(판독)까지 성사된 여기서만 기록한다.
        recordUsageQuietly(userId);
        return response;
    }

    // 진행 단계 폴링 — LLM 호출도 차감도 없다. 진행 중이 아니면 null.
    public String progressStage(Long userId, Long storyId) {
        txService.loadOwnership(userId, storyId);
        return runStage.get(storyId);
    }

    // 쿼터 기록 실패가 이미 저장된 분석 응답을 500으로 오염시키지 않게 격리한다.
    private void recordUsageQuietly(Long userId) {
        try {
            usageLimiter.record(UsageKind.ASSESSMENT, userId, 1);
        } catch (RuntimeException e) {
            log.error("분석 쿼터 기록 실패 userId={}", userId, e);
        }
    }

    // 표시 기록 실패가 정상 응답을 오염시키거나(clear), 잠금 해제를 막지 않게(mark) 격리한다.
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

    // "만나는 중" 잠금을 유저가 직접 번복하는 창구. 오판이던 잠금 판정을 지우고
    // 직전 확률 분석으로 즉시 복귀시킨다(없으면 빈 값 — 첫 분석 안내로).
    public Optional<AssessmentResponse> confirmBreakup(Long userId, Long storyId) {
        return txService.confirmBreakup(userId, storyId);
    }

    // "재회 제안 유효(100%)" 확정을 유저가 직접 번복하는 창구. 저장된 신호의 재합산 값으로
    // 즉시 되돌린 결과를 돌려준다(재분석 불필요).
    public AssessmentResponse retractOffer(Long userId, Long storyId) {
        return txService.retractOffer(userId, storyId);
    }

    // 화면이 그릴 수 있는 판독인지 — 진단 요약과 진단 항목이 본체다(v7). 비면 리포트로
    // 성립하지 않으므로 내려보내지 않는다(구조 개편 전 본문이 여기서 걸러진다).
    // 다른 구조로 저장된 본문은 전 필드 null 껍데기로 통과하므로 여기서 한 번 더 거른다.
    // 본체 검사는 구조 세대별로 다르다: 2단 편집 본문은 1장 블록(prologueBlocks)과
    // 마음 블록(mindBlocks), 그 이전 저장분은 심층 장(analysisChapters)과 마음 통짜(mind).
    private boolean isRenderable(ReadingDraft report) {
        if (report == null || report.decision() == null) {
            return false;
        }
        ReadingDraft.Decision d = report.decision();
        // 새 구조는 판정 카드(verdictBlocks)가 본체고 mind와 분석 장은 카드가 흡수해
        // 빌 수 있다 — mind를 필수로 걸면 새 판독 전부가 "구조 불일치"로 버려진다
        // (실측: 저장은 되는데 응답에서 탈락해 실패 화면으로 보였다). 전 필드 null
        // 껍데기(구조 개편 전 행)만 거르면 된다.
        boolean hasCards = d.verdictBlocks() != null && !d.verdictBlocks().isEmpty();
        boolean hasBody = (d.prologueBlocks() != null && !d.prologueBlocks().isEmpty())
                || (report.analysisChapters() != null && !report.analysisChapters().isEmpty());
        return hasCards || hasBody;
    }

    // 이력 목록의 한 줄 요약 — 마음 장의 첫 문단을 쓴다(블록 구조와 통짜 문자열 둘 다 지원).
    private String reasonSummary(ReadingDraft.Decision decision) {
        if (decision == null) {
            return "판독 완료";
        }
        if (decision.mind() != null && !decision.mind().isBlank()) {
            return decision.mind();
        }
        if (decision.mindBlocks() != null && !decision.mindBlocks().isEmpty()) {
            return decision.mindBlocks().get(0).body();
        }
        return "판독 완료";
    }

    // 감점 목록(@ElementCollection, LAZY)을 매핑에서 읽으므로 트랜잭션 안이어야 한다.
    // (open-in-view: false — 트랜잭션 밖에서 접근하면 LazyInitializationException → 500)
    @Transactional(readOnly = true)
    public List<AssessmentResponse> getHistory(Long userId, Long storyId) {
        txService.loadOwnership(userId, storyId);
        List<Assessment> assessments = assessmentRepository.findByStoryIdOrderByCreatedAtDesc(storyId);
        if (assessments.isEmpty()) {
            return List.of();
        }
        Map<Long, Assessment> byId = new HashMap<>();
        assessments.forEach(a -> byId.put(a.getId(), a));
        // 판독은 일부 판정에만 있다 — 있는 것만 붙이고, 델타 기준(base)은 같은 목록에서 찾는다.
        Map<Long, AssessmentReading> readings = new HashMap<>();
        readingRepository.findByAssessmentIdIn(byId.keySet())
                .forEach(r -> readings.put(r.getAssessmentId(), r));
        return assessments.stream()
                .map(a -> {
                    AssessmentResponse response = AssessmentResponse.from(a);
                    AssessmentReading reading = readings.get(a.getId());
                    if (reading != null) {
                        Assessment base = reading.getBaseAssessmentId() != null
                                ? byId.get(reading.getBaseAssessmentId()) : null;
                        // 본문 역직렬화 실패(구조 개편 전 행 등)는 판독 없음으로 접는다 —
                        // 옛 행 하나 때문에 이력 조회 전체가 500이 되면 안 된다.
                        // 성공해도 내용을 확인한다: 스프링의 ObjectMapper는 모르는 필드를 에러 없이
                        // 무시하므로, 구조 개편 전 본문이 전 필드 null인 껍데기로 조용히 통과한다.
                        // 그 껍데기가 화면에 내려가면 뷰어가 렌더 중에 터진다(실측: 빈 화면).
                        try {
                            ReadingDraft report =
                                    objectMapper.readValue(reading.getBody(), ReadingDraft.class);
                            if (isRenderable(report)) {
                                response.withReading(AssessmentResponse.Reading.of(
                                        report, a, base, reading.getCreatedAt()));
                            } else {
                                log.warn("판독 본문이 현재 구조와 맞지 않음 assessmentId={} — 판정만 표시",
                                        a.getId());
                            }
                        } catch (Exception e) {
                            log.warn("판독 본문 역직렬화 실패 assessmentId={} — 판정만 표시", a.getId());
                        }
                    }
                    return response;
                })
                .toList();
    }

    // 미분석 사유는 원인별로 갈라 말해준다 — "왜 안 되는지"를 유저가 스스로 고칠 수 있게.
    // 유저 발화가 2회 미만인 경우(사전 가드). 사연만 있고 확인 질문에 답하기 전이 여기 걸린다.
    private static final String TURNS_GUIDE =
            "아직 들려주신 이야기가 없습니다. 사연을 먼저 들려주시면 그때부터 분석할 수 있습니다.";

    // 대화는 있었지만 확률을 매길 '사실'이 부족한 경우(LLM 판정, 원장 빈약): 무엇을 말해야 하는지 안내.
    private static final String NO_BASIS_GUIDE =
            "이야기는 들었지만 확률을 매길 만한 사실이 아직 부족합니다. 어쩌다 헤어졌는지, "
                    + "상대가 최근 어떻게 행동했는지 같은 '있었던 일'을 들려주세요.";

    // 같은 재료로 분석 생성이 연속 실패해 재시도를 막은 경우. 실패는 차감되지 않았음을 함께 알린다.
    // 남은 시간은 문구에 적지 않는다 — retryAfterSeconds로 내려가 화면이 카운트다운으로 보여준다.
    // 문구에 "5분쯤 뒤"처럼 박아두면 쿨다운을 조정할 때마다 여기까지 같이 고쳐야 하고, 실제 남은
    // 시간과 어긋나기도 한다.
    private static final String FAIL_RETRY_GUIDE =
            "분석을 만들지 못하는 상태가 이어지고 있습니다. 이번 분석은 차감되지 않았습니다.";

    private static final String REUNITED_GUIDE =
            "다시 만나게 되었습니다. 여기서부터는 확률이 아니라 관계를 이어가는 이야기입니다. 대화에서 함께합니다.";

    // 사전 가드용 임시 응답. 히스토리에 저장하지 않는다(확률 추이 오염 방지).
    private AssessmentResponse insufficientGuide(Long storyId, String guide) {
        return AssessmentResponse.from(Assessment.builder()
                .storyId(storyId)
                .verdict(ReunionVerdict.INSUFFICIENT)
                .reason(guide)
                .build());
    }

}
