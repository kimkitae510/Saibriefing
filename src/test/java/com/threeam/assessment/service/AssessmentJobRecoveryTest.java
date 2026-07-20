package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessmentJobRecoveryTest {

    @Mock
    private AssessmentJobTxService jobTxService;

    @Mock
    private UsageLimiter usageLimiter;

    @InjectMocks
    private AssessmentJobRecovery recovery;

    @Test
    @DisplayName("재시작 복구 - 실패로 돌린 작업의 유저마다 판정 생성 락을 푼다")
    void releasesLocksOfFailedJobs() {
        given(jobTxService.failStale()).willReturn(List.of(1L, 2L));

        recovery.recover();

        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 1L);
        verify(usageLimiter).releaseInFlight(UsageKind.ASSESSMENT, 2L);
    }

    @Test
    @DisplayName("재시작 복구 - 진행 중 작업이 없으면 락을 건드리지 않는다")
    void noJobsNoRelease() {
        given(jobTxService.failStale()).willReturn(List.of());

        recovery.recover();

        verify(usageLimiter, never()).releaseInFlight(any(), any());
    }

    @Test
    @DisplayName("재시작 복구 - 복구가 실패해도 기동을 막지 않는다")
    void failureDoesNotBlockStartup() {
        given(jobTxService.failStale()).willThrow(new IllegalStateException("db down"));

        assertThatCode(() -> recovery.recover()).doesNotThrowAnyException();
    }
}
