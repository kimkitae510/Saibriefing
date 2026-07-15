package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.entity.AssessmentJob;
import com.threeam.assessment.repository.AssessmentJobRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.MessageRepository;
import com.threeam.story.repository.StoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssessmentJobTxServiceTest {

    @Mock
    private AssessmentJobRepository jobRepository;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private AssessmentJobTxService service;

    private static final Story STORY = Story.builder().userId(1L).title("t").build();

    private static AssessmentJob job(long id, String analysis, String judgement, LocalDateTime at) {
        AssessmentJob job = AssessmentJob.builder().storyId(10L).userId(1L)
                .analysisText(analysis).judgementText(judgement).judgementOn(true).build();
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "createdAt", at);
        ReflectionTestUtils.setField(job, "updatedAt", at);
        return job;
    }

    private void givenLockedStory() {
        given(storyRepository.findByIdForUpdate(10L)).willReturn(Optional.of(STORY));
        given(jobRepository.save(any(AssessmentJob.class))).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("접수 - 살아 있는 작업이 있으면 새로 만들지 않고 합류한다")
    void createOrJoin_joinsActive() {
        given(storyRepository.findByIdForUpdate(10L)).willReturn(Optional.of(STORY));
        AssessmentJob running = job(5L, null, null, LocalDateTime.now());
        running.start();
        given(jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(10L, AssessmentJobTxService.ACTIVE))
                .willReturn(Optional.of(running));

        AssessmentJobTxService.Started started = service.createOrJoin(1L, 10L, true);

        assertThat(started.created()).isFalse();
        assertThat(started.job()).isSameAs(running);
        verify(jobRepository, never()).save(any());
    }

    @Test
    @DisplayName("접수 - 갱신이 끊긴 진행 중 작업은 FAILED로 돌리고 새 작업을 만든다(영원히 막히지 않게)")
    void createOrJoin_failsStaleThenCreates() {
        givenLockedStory();
        AssessmentJob stuck = job(5L, null, null, LocalDateTime.now().minusMinutes(11));
        stuck.start();
        given(jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(10L, AssessmentJobTxService.ACTIVE))
                .willReturn(Optional.of(stuck));
        given(jobRepository.findFirstByStoryIdAndStatusOrderByIdDesc(10L, AssessmentJob.Status.FAILED))
                .willReturn(Optional.empty());

        AssessmentJobTxService.Started started = service.createOrJoin(1L, 10L, true);

        assertThat(stuck.getStatus()).isEqualTo(AssessmentJob.Status.FAILED);
        assertThat(stuck.getError()).isEqualTo(AssessmentJobTxService.STALE_ERROR);
        assertThat(started.created()).isTrue();
        assertThat(started.job().getStatus()).isEqualTo(AssessmentJob.Status.QUEUED);
        assertThat(started.job().getAnalysisStatus()).isEqualTo(AssessmentJob.StepStatus.PENDING);
        assertThat(started.job().getJudgementStatus()).isEqualTo(AssessmentJob.StepStatus.PENDING);
    }

    @Test
    @DisplayName("접수 - 최근 실패 작업의 끝난 단계 텍스트를 물려받아 그 단계는 DONE으로 시작한다")
    void createOrJoin_copiesPartialFromRecentFailure() {
        givenLockedStory();
        given(jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(10L, AssessmentJobTxService.ACTIVE))
                .willReturn(Optional.empty());
        LocalDateTime hourAgo = LocalDateTime.now().minusHours(1);
        AssessmentJob failed = job(5L, "옛 분석", null, hourAgo);
        failed.fail("LlmException");
        given(jobRepository.findFirstByStoryIdAndStatusOrderByIdDesc(10L, AssessmentJob.Status.FAILED))
                .willReturn(Optional.of(failed));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(10L, hourAgo)).willReturn(false);

        AssessmentJob created = service.createOrJoin(1L, 10L, true).job();

        assertThat(created.getAnalysisStatus()).isEqualTo(AssessmentJob.StepStatus.DONE);
        assertThat(created.getAnalysisText()).isEqualTo("옛 분석");
        assertThat(created.getJudgementStatus()).isEqualTo(AssessmentJob.StepStatus.PENDING);
        assertThat(created.getJudgementText()).isNull();
    }

    @Test
    @DisplayName("접수 - 실패 뒤 새 대화가 있으면 옛 텍스트는 낡은 재료라 물려받지 않는다")
    void createOrJoin_ignoresPartialAfterNewMessages() {
        givenLockedStory();
        given(jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(10L, AssessmentJobTxService.ACTIVE))
                .willReturn(Optional.empty());
        LocalDateTime hourAgo = LocalDateTime.now().minusHours(1);
        AssessmentJob failed = job(5L, "옛 분석", "{}", hourAgo);
        failed.fail("RuntimeException");
        given(jobRepository.findFirstByStoryIdAndStatusOrderByIdDesc(10L, AssessmentJob.Status.FAILED))
                .willReturn(Optional.of(failed));
        given(messageRepository.existsByStoryIdAndCreatedAtAfter(10L, hourAgo)).willReturn(true);

        AssessmentJob created = service.createOrJoin(1L, 10L, true).job();

        assertThat(created.getAnalysisStatus()).isEqualTo(AssessmentJob.StepStatus.PENDING);
        assertThat(created.getJudgementStatus()).isEqualTo(AssessmentJob.StepStatus.PENDING);
    }

    @Test
    @DisplayName("접수 - 하루 지난 실패 작업의 텍스트는 물려받지 않는다")
    void createOrJoin_ignoresOldPartial() {
        givenLockedStory();
        given(jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(10L, AssessmentJobTxService.ACTIVE))
                .willReturn(Optional.empty());
        AssessmentJob failed = job(5L, "옛 분석", null, LocalDateTime.now().minusHours(25));
        failed.fail("LlmException");
        given(jobRepository.findFirstByStoryIdAndStatusOrderByIdDesc(10L, AssessmentJob.Status.FAILED))
                .willReturn(Optional.of(failed));

        AssessmentJob created = service.createOrJoin(1L, 10L, true).job();

        assertThat(created.getAnalysisStatus()).isEqualTo(AssessmentJob.StepStatus.PENDING);
        verify(messageRepository, never()).existsByStoryIdAndCreatedAtAfter(any(), any());
    }

    @Test
    @DisplayName("접수 - B가 꺼져 있으면 판독 단계는 처음부터 DONE(텍스트 없음)")
    void createOrJoin_judgementOffMarksStepDone() {
        givenLockedStory();
        given(jobRepository.findFirstByStoryIdAndStatusInOrderByIdDesc(10L, AssessmentJobTxService.ACTIVE))
                .willReturn(Optional.empty());
        given(jobRepository.findFirstByStoryIdAndStatusOrderByIdDesc(10L, AssessmentJob.Status.FAILED))
                .willReturn(Optional.empty());

        AssessmentJob created = service.createOrJoin(1L, 10L, false).job();

        assertThat(created.getJudgementStatus()).isEqualTo(AssessmentJob.StepStatus.DONE);
        assertThat(created.getJudgementText()).isNull();
    }

    @Test
    @DisplayName("접수 - 사연이 없거나 지워졌으면 STORY_NOT_FOUND")
    void createOrJoin_storyNotFound() {
        given(storyRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrJoin(1L, 10L, true))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("재시작 복구 - 진행 중 작업을 갱신 시각과 무관하게 전부 FAILED로 돌리고 끝난 단계는 남긴다")
    void failStale_marksAll() {
        AssessmentJob a = job(1L, "분석", null, LocalDateTime.now().minusMinutes(30));
        a.start();
        // 죽기 직전에 갱신된 작업 — 갱신 시각으로 걸렀으면 살아 있는 것으로 오판하던 판
        AssessmentJob b = job(2L, null, null, LocalDateTime.now().minusSeconds(5));
        given(jobRepository.findByStatusIn(AssessmentJobTxService.ACTIVE))
                .willReturn(List.of(a, b));

        assertThat(service.failStale()).isEqualTo(2);

        assertThat(a.getStatus()).isEqualTo(AssessmentJob.Status.FAILED);
        assertThat(a.getError()).isEqualTo("서버 재시작으로 중단");
        assertThat(a.getAnalysisStatus()).isEqualTo(AssessmentJob.StepStatus.DONE);
        assertThat(a.getAnalysisText()).isEqualTo("분석");
        assertThat(a.getJudgementStatus()).isEqualTo(AssessmentJob.StepStatus.FAILED);
        assertThat(b.getStatus()).isEqualTo(AssessmentJob.Status.FAILED);
    }

    @Test
    @DisplayName("폴링 조회 - 갱신이 끊긴 작업은 조회 때도 FAILED로 돌려 화면이 영원히 기다리지 않게 한다")
    void find_failsStaleOnPoll() {
        AssessmentJob stuck = job(5L, null, null, LocalDateTime.now().minusMinutes(11));
        stuck.start();
        given(jobRepository.findByIdAndStoryId(5L, 10L)).willReturn(Optional.of(stuck));

        AssessmentJob found = service.find(5L, 10L).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(AssessmentJob.Status.FAILED);

        AssessmentJob fresh = job(6L, null, null, LocalDateTime.now());
        fresh.start();
        given(jobRepository.findByIdAndStoryId(6L, 10L)).willReturn(Optional.of(fresh));
        assertThat(service.find(6L, 10L).orElseThrow().getStatus()).isEqualTo(AssessmentJob.Status.RUNNING);
    }
}
