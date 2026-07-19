package com.threeam.payment.service;

import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 결제 상태 머신의 안전망. 승인/취소 응답이 유실돼 불명 상태에 갇힌 결제를
// PG 조회로 실상태 확정한다 — "돈은 나갔는데 이용권이 없다"를 스스로 복구하는 장치.
// 웹훅이 정상 동작하면 대부분 여기까지 오기 전에 끝나지만, 웹훅은 로컬 개발에선 수신이
// 안 되고 운영에서도 유실될 수 있어 이 폴링이 최종 보증을 맡는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSyncScheduler {

    // 만료 주문을 다시 확인해 주는 기간. 유저가 결제창을 열어둔 채 자리를 뜬 경우를 덮을
    // 만큼만 잡는다 — 늘리면 이탈 주문까지 계속 조회해 PG 호출이 낭비된다.
    private static final int EXPIRED_RECHECK_HOURS = 24;

    // 만료 주문 하나를 다시 물어보는 최소 간격. 웹훅이 빠른 길이고 이건 유실 대비 안전망이라
    // 이 정도 지연은 감수한다 — 없으면 만료 주문마다 매 주기 조회가 나가 하루 수천 건이 낭비된다.
    private static final int EXPIRED_RECHECK_INTERVAL_MINUTES = 30;

    private final PaymentRepository paymentRepository;
    private final PaymentTxService txService;
    private final PaymentService paymentService;
    private final PaymentProperties properties;

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    public void reconcile() {
        LocalDateTime now = LocalDateTime.now();
        expireAbandonedOrders(now);
        LocalDateTime staleBefore = now.minusMinutes(properties.getSyncAfterMinutes());
        Pageable oldestFirst = PageRequest.of(0, properties.getSyncBatchSize(), Sort.by("updatedAt"));
        syncEach(paymentRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.IN_PROGRESS, staleBefore, oldestFirst));
        syncEach(paymentRepository.findByStatusAndUpdatedAtBefore(PaymentStatus.CANCEL_REQUESTED, staleBefore, oldestFirst));
        // 입금 기한이 지난 가상계좌: 입금됐는데 웹훅이 유실됐을 수도, 그냥 만료일 수도 — 조회로 확정.
        syncEach(paymentRepository.findTop20ByStatusAndVbankDueAtBefore(PaymentStatus.WAITING_FOR_DEPOSIT, now));
        recheckExpiredWithLiveTransaction(now);
    }

    // 결제창에서 결제가 끝나는 PG(패들)는 우리가 주문을 만료시킨 뒤에도 결제가 성사될 수 있다.
    // 그 경우 돈만 나가고 지급이 없어 분쟁이 되므로, 거래가 실재했던 만료 주문만 골라
    // 생성 후 하루 동안, 30분에 한 번씩 다시 확인한다. 웹훅이 정상이면 여기까지 오지 않는다.
    private void recheckExpiredWithLiveTransaction(LocalDateTime now) {
        List<Payment> targets = paymentRepository
                .findTop20ByStatusAndPaymentKeyIsNotNullAndCreatedAtAfterAndUpdatedAtBefore(
                        PaymentStatus.EXPIRED,
                        now.minusHours(EXPIRED_RECHECK_HOURS),
                        now.minusMinutes(EXPIRED_RECHECK_INTERVAL_MINUTES));
        for (Payment payment : targets) {
            try {
                paymentService.syncByOrderId(payment.getOrderId()).join();
                // 확인 시각을 남겨야 다음 주기에 같은 주문을 또 조회하지 않는다(간격 확보).
                txService.touchExpired(payment.getOrderId());
                log.info("만료 주문 재확인 orderId={}", payment.getOrderId());
            } catch (RuntimeException e) {
                log.error("만료 주문 재확인 실패 orderId={}", payment.getOrderId(), e);
            }
        }
    }

    // 주문만 만들고 위젯에서 이탈한 건. PG에 승인 요청을 한 적이 없으니 조회 없이 만료 처리한다.
    private void expireAbandonedOrders(LocalDateTime now) {
        List<Payment> abandoned = paymentRepository.findTop20ByStatusAndCreatedAtBefore(
                PaymentStatus.READY, now.minusMinutes(properties.getOrderExpireMinutes()));
        for (Payment payment : abandoned) {
            if (txService.expire(payment.getOrderId())) {
                log.info("방치 주문 만료 orderId={}", payment.getOrderId());
            }
        }
    }

    // syncConcurrency개씩 묶어 동시에 묻고 묶음이 끝나면 다음 묶음으로 간다. 하나씩 기다리면 PG가 느릴 때
    // 한 주기가 건수 × 응답 대기로 늘어나고, 전부 한꺼번에 쏘면 이미 느린 PG에 조회를 몰아 준다.
    private void syncEach(List<Payment> payments) {
        int concurrency = Math.max(1, properties.getSyncConcurrency());
        for (int from = 0; from < payments.size(); from += concurrency) {
            List<Payment> group = payments.subList(from, Math.min(from + concurrency, payments.size()));
            CompletableFuture.allOf(group.stream()
                    .map(this::syncQuietly)
                    .toArray(CompletableFuture[]::new)).join();
        }
    }

    // 한 건의 실패가 나머지 동기화를 막지 않게 한다. 다음 주기에 다시 시도된다.
    private CompletableFuture<Void> syncQuietly(Payment payment) {
        try {
            return paymentService.syncByOrderId(payment.getOrderId())
                    .handle((ignored, ex) -> {
                        if (ex == null) {
                            log.info("결제 재동기화 orderId={} 이전상태={}", payment.getOrderId(), payment.getStatus());
                        } else {
                            log.error("결제 재동기화 실패 orderId={}", payment.getOrderId(), ex);
                        }
                        return null;
                    });
        } catch (RuntimeException e) {
            log.error("결제 재동기화 실패 orderId={}", payment.getOrderId(), e);
            return CompletableFuture.completedFuture(null);
        }
    }
}
