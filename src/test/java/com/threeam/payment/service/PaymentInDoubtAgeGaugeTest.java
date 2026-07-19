package com.threeam.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.threeam.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentInDoubtAgeGaugeTest {

    @Mock
    private PaymentRepository paymentRepository;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        new PaymentInDoubtAgeGauge(paymentRepository, registry);
    }

    @Test
    @DisplayName("가장 오래된 미확정 결제가 들어온 뒤 흐른 초를 낸다")
    void reportsAgeOfOldestInDoubtPayment() {
        given(paymentRepository.oldestUpdatedAtIn(anyList())).willReturn(LocalDateTime.now().minusSeconds(90));

        assertThat(gaugeValue()).isCloseTo(90, within(2.0));
    }

    @Test
    @DisplayName("미확정 결제가 없으면 0이다")
    void reportsZeroWhenNothingInDoubt() {
        given(paymentRepository.oldestUpdatedAtIn(anyList())).willReturn(null);

        assertThat(gaugeValue()).isZero();
    }

    private double gaugeValue() {
        return registry.get("payment.unknown.oldest.age").gauge().value();
    }
}
