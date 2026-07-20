package com.threeam.assessment.service;

import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 재시작 뒤 갱신이 끊긴 진행 중 작업을 FAILED로 돌린다 — 콜백은 메모리에 있었으니 돌아오지 않는다.
// 끝난 단계의 텍스트는 남아 있어 다음 접수가 그 단계를 건너뛴다.
// 그 유저들의 생성 락도 같이 푼다. 락을 풀 콜백도 이전 프로세스와 함께 사라졌다.
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentJobRecovery {

    private final AssessmentJobTxService jobTxService;
    private final UsageLimiter usageLimiter;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            List<Long> userIds = jobTxService.failStale();
            userIds.forEach(userId -> usageLimiter.releaseInFlight(UsageKind.ASSESSMENT, userId));
            if (!userIds.isEmpty()) {
                log.warn("재시작 복구: 갱신이 끊긴 분석 작업을 FAILED로 돌리고 유저 {}명의 생성 락을 풂", userIds.size());
            }
        } catch (RuntimeException e) {
            log.error("분석 작업 재시작 복구 실패", e);
        }
    }
}
