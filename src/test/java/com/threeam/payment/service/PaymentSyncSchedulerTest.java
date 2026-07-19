package com.threeam.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.threeam.payment.client.PaymentGatewayException;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentItem;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PaymentSyncSchedulerTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentTxService txService;

    @Mock
    private PaymentService paymentService;

    private PaymentProperties properties;
    private PaymentSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        scheduler = new PaymentSyncScheduler(paymentRepository, txService, paymentService, properties);
    }

    @Test
    @DisplayName("불명 결제는 설정한 건수만큼 가장 오래 기다린 것부터 집는다")
    void picksOldestFirstUpToBatchSize() {
        properties.setSyncBatchSize(100);

        scheduler.reconcile();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(paymentRepository).findByStatusAndUpdatedAtBefore(
                eq(PaymentStatus.IN_PROGRESS), any(LocalDateTime.class), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
        assertThat(page.getValue().getSort()).isEqualTo(Sort.by("updatedAt").ascending());
    }

    @Test
    @DisplayName("한 건이 실패하거나 즉시 던져도 나머지는 모두 동기화한다")
    void oneFailureDoesNotStopOthers() {
        given(paymentRepository.findByStatusAndUpdatedAtBefore(eq(PaymentStatus.IN_PROGRESS), any(), any()))
                .willReturn(List.of(payment("o1"), payment("o2"), payment("o3")));
        given(paymentService.syncByOrderId("o1"))
                .willReturn(CompletableFuture.failedFuture(new PaymentGatewayException("토스 응답 5xx: 503")));
        given(paymentService.syncByOrderId("o2")).willThrow(new IllegalStateException("DB 접속 실패"));
        given(paymentService.syncByOrderId("o3")).willReturn(CompletableFuture.completedFuture(null));

        scheduler.reconcile();

        verify(paymentService).syncByOrderId("o3");
    }

    @Test
    @DisplayName("동시 조회 수만큼 묶어 묻고, 묶음이 끝나야 다음 묶음으로 간다")
    void syncsInConcurrentGroups() throws InterruptedException {
        properties.setSyncConcurrency(2);
        given(paymentRepository.findByStatusAndUpdatedAtBefore(eq(PaymentStatus.IN_PROGRESS), any(), any()))
                .willReturn(List.of(payment("o1"), payment("o2"), payment("o3")));
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        given(paymentService.syncByOrderId("o1")).willReturn(first);
        given(paymentService.syncByOrderId("o2")).willReturn(second);
        given(paymentService.syncByOrderId("o3")).willReturn(CompletableFuture.completedFuture(null));

        Thread run = new Thread(scheduler::reconcile);
        run.start();

        verify(paymentService, timeout(2_000)).syncByOrderId("o1");
        verify(paymentService, timeout(2_000)).syncByOrderId("o2");
        verify(paymentService, never()).syncByOrderId("o3");

        first.complete(null);
        second.complete(null);
        run.join(2_000);

        verify(paymentService).syncByOrderId("o3");
    }

    private static Payment payment(String orderId) {
        return Payment.builder().userId(1L).orderId(orderId).item(PaymentItem.BUNDLE_STANDARD).build();
    }
}
