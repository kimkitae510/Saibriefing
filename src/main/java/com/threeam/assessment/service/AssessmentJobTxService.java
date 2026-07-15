package com.threeam.assessment.service;

import com.threeam.assessment.entity.AssessmentJob;
import com.threeam.assessment.repository.AssessmentJobRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 분석 작업 행의 짧은 트랜잭션들. LLM 대기는 전부 이 밖(AssessmentJobService)에서 일어난다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentJobTxService {

    static final List<AssessmentJob.Status> ACTIVE =
            List.of(AssessmentJob.Status.QUEUED, AssessmentJob.Status.RUNNING);

    // 이만큼 갱신이 없는 진행 중 작업은 죽은 것으로 본다 — 두 호출의 대기 상한(240초)에 1분 여유.
    // 더 짧게 잡으면 아직 정상으로 도는 4분짜리 판을 도중에 실패로 돌린다. 재시작 뒤의 정리는 이 값과
    // 무관하게 기동 복구가 전부 걷는다(failStale).
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    // 지난 실패 작업의 끝난 쪽 텍스트를 이어 쓰는 창 — 하루가 지난 판은 사연이 그대로여도 다시 산다.
    static final Duration PARTIAL_REUSE_WINDOW = Duration.ofHours(24);

    static final String STALE_ERROR = "서버 재시작으로 중단";

    private final AssessmentJobRepository jobRepository;
    private final StoryRepository storyRepository;
    private final MessageRepository messageRepository;

    public record Started(AssessmentJob job, boolean created) {
    }

    // 접수 — 사연 행을 잠근 채 진행 중 작업이 있으면 그것을 돌려주고(합류), 없으면 새 작업을 만든다.
    // 새 작업은 최근 실패 작업의 끝난 단계 텍스트를 물려받는다(같은 재료일 때만 — 새 대화가 있으면 옛 분석은 낡았다).
    @Transactional
    public Started createOrJoin(Long userId, Long storyId, boolean judgementOn) {
        storyRepository.findByIdForUpdate(storyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORY_NOT_FOUND));
        Optional<AssessmentJob> active = findAlive(storyId);
        if (active.isPresent()) {
            return new Started(active.get(), false);
        }
        String analysis = null;
        String judgement = null;
        Optional<AssessmentJob> failed = jobRepository
                .findFirstByStoryIdAndStatusOrderByIdDesc(storyId, AssessmentJob.Status.FAILED);
        if (failed.isPresent() && reusable(failed.get())) {
            AssessmentJob f = failed.get();
            analysis = f.isAnalysisDone() ? f.getAnalysisText() : null;
            judgement = judgementOn && f.isJudgementDone() ? f.getJudgementText() : null;
            if (analysis != null || judgement != null) {
                log.info("실패 작업 {}의 끝난 단계를 이어 씀 storyId={} 분석={} 판독={}", f.getId(), storyId,
                        analysis != null, judgement != null);
            }
        }
        AssessmentJob job = jobRepository.save(AssessmentJob.builder()
                .storyId(storyId)
                .userId(userId)
                .analysisText(analysis)
                .judgementText(judgement)
                .judgementOn(judgementOn)
                .build());
        return new Started(job, true);
    }

    private boolean reusable(AssessmentJob failed) {
        LocalDateTime at = failed.getUpdatedAt() == null ? failed.getCreatedAt() : failed.getUpdatedAt();
        if (at == null || at.isBefore(LocalDateTime.now().minus(PARTIAL_REUSE_WINDOW))) {
            return false;
        }
        return failed.getCreatedAt() == null
                || !messageRepository.existsByStoryIdAndCreatedAtAfter(failed.getStoryId(), failed.getCreatedAt());
    }

    // 진행 중 작업 조회(접수 전 빠른 길, 진행 폴링). 갱신이 끊긴 것은 여기서 FAILED로 돌려 영원히 막지 않게 한다.
    @Transactional
    public Optional<AssessmentJob> findActive(Long storyId) {
        return findAlive(storyId);
    }

    @Transactional
    public Optional<AssessmentJob> find(Long jobId, Long storyId) {
        Optional<AssessmentJob> job = jobRepository.findByIdAndStoryId(jobId, storyId);
        job.ifPresent(this::failIfStale);
        return job;
    }

    // 진행 중 작업 — 갱신이 끊긴 것이면 FAILED로 돌리고 없는 셈 친다.
    private Optional<AssessmentJob> findAlive(Long storyId) {
        Optional<AssessmentJob> active = jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(storyId, ACTIVE);
        return active.filter(j -> !failIfStale(j));
    }

    private boolean failIfStale(AssessmentJob job) {
        boolean stale = job.isActive() && job.getUpdatedAt() != null && job.getUpdatedAt().isBefore(staleCutoff());
        if (stale) {
            log.warn("갱신이 끊긴 분석 작업 {}을 FAILED로 돌림 storyId={}", job.getId(), job.getStoryId());
            job.fail(STALE_ERROR);
        }
        return stale;
    }

    // 재시작 복구 — 진행 중 작업 전부. 갱신 시각을 안 본다: 기동 시점에 진행 중인 작업은 몇 초 전에 갱신됐어도
    // 콜백이 이전 프로세스 메모리에 있었으니 돌아오지 않는다. 갱신 시각으로 걸렀을 때는 죽은 직후 재시작한
    // 작업이 10분 동안 RUNNING으로 남아 화면이 그동안 폴링만 했다(2026-09-21 실측). 단일 인스턴스 전제.
    @Transactional
    public int failStale() {
        List<AssessmentJob> stale = jobRepository.findByStatusIn(ACTIVE);
        stale.forEach(j -> j.fail(STALE_ERROR));
        return stale.size();
    }

    private static LocalDateTime staleCutoff() {
        return LocalDateTime.now().minus(STALE_AFTER);
    }

    @Transactional
    public void markRunning(Long jobId) {
        load(jobId).start();
    }

    @Transactional
    public void completeAnalysis(Long jobId, String text) {
        load(jobId).completeAnalysis(text);
    }

    @Transactional
    public void completeJudgement(Long jobId, String text) {
        load(jobId).completeJudgement(text);
    }

    @Transactional
    public void finish(Long jobId, Long assessmentId, String note) {
        load(jobId).finish(assessmentId, note);
    }

    @Transactional
    public void fail(Long jobId, String error) {
        load(jobId).fail(error);
    }

    private AssessmentJob load(Long jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("분석 작업 없음 jobId=" + jobId));
    }
}
