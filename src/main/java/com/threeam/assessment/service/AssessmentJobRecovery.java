package com.threeam.assessment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// 재시작 뒤 갱신이 끊긴 진행 중 작업을 FAILED로 돌린다 — 콜백은 메모리에 있었으니 돌아오지 않는다.
// 끝난 단계의 텍스트는 남아 있어 다음 접수가 그 단계를 건너뛴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentJobRecovery {

    private final AssessmentJobTxService jobTxService;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            int n = jobTxService.failStale();
            if (n > 0) {
                log.warn("재시작 복구: 갱신이 끊긴 분석 작업 {}건을 FAILED로 돌림", n);
            }
        } catch (RuntimeException e) {
            log.error("분석 작업 재시작 복구 실패", e);
        }
    }
}
