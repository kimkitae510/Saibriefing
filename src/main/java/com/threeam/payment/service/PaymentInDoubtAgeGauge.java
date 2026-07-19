package com.threeam.payment.service;

import com.threeam.payment.entity.PaymentStatus;
import com.threeam.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

// 결과를 모르는 결제(승인, 취소 응답 불명) 중 가장 오래 머문 건의 나이. 알림은 건수가 아니라 이 값에 건다 —
// 정상 진행 중인 승인도 잠깐 이 상태를 지나므로 건수는 늘 0보다 크고, 재동기화가 밀려 방치가 시작되면
// 늘어나는 것은 가장 오래 기다린 건의 나이다.
//
// 스크레이프 때마다 조회한다. 재동기화 주기에 맞춰 갱신하면 PG가 느려 한 주기가 길어진 바로 그때 값이 멈춘다.
@Component
public class PaymentInDoubtAgeGauge {

    private static final List<PaymentStatus> IN_DOUBT =
            List.of(PaymentStatus.IN_PROGRESS, PaymentStatus.CANCEL_REQUESTED);

    private final PaymentRepository paymentRepository;

    public PaymentInDoubtAgeGauge(PaymentRepository paymentRepository, MeterRegistry meterRegistry) {
        this.paymentRepository = paymentRepository;
        Gauge.builder("payment.unknown.oldest.age", this, PaymentInDoubtAgeGauge::oldestAgeSeconds)
                .baseUnit("seconds")
                .description("결과를 모르는 결제 중 가장 오래 머문 건의 나이")
                .register(meterRegistry);
    }

    double oldestAgeSeconds() {
        LocalDateTime since = paymentRepository.oldestUpdatedAtIn(IN_DOUBT);
        if (since == null) {
            return 0;
        }
        return Math.max(0, Duration.between(since, LocalDateTime.now()).toSeconds());
    }
}
