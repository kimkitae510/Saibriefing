package com.threeam.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentItem;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.support.MysqlIntegrationTest;
import com.threeam.support.Race;
import com.threeam.support.Race.Outcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentClaimIntegrationTest extends MysqlIntegrationTest {

    private static final String READY = PaymentStatus.READY.name();
    private static final String IN_PROGRESS = PaymentStatus.IN_PROGRESS.name();

    @Autowired
    private PaymentRepository paymentRepository;

    @BeforeEach
    void clean() {
        jdbc().update("DELETE FROM payments");
    }

    @Test
    @DisplayName("READY 결제에 승인 선점이 동시에 몰리면 정확히 하나만 1을 받고 그 쪽의 거래 키가 남는다")
    void concurrentApprovalClaimHasOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            clean();
            String orderId = readyPayment();

            List<Outcome<Integer>> outcomes = Race.run(THREADS, i -> updateInTx(() ->
                    paymentRepository.claimWithKey(orderId, READY, IN_PROGRESS, "pk-" + i)));

            assertThat(outcomes).as("%d번째 판", round).allMatch(Outcome::succeeded);
            List<Outcome<Integer>> winners = outcomes.stream().filter(o -> o.value() == 1).toList();
            assertThat(winners).as("%d번째 판의 승자", round).hasSize(1);

            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
            assertThat(payment.getPaymentKey()).isEqualTo("pk-" + winners.get(0).index());
        }
    }

    @Test
    @DisplayName("이미 선점된 결제는 다시 선점되지 않고 거래 키도 덮이지 않는다")
    void claimedPaymentIsNotClaimedAgain() {
        String orderId = readyPayment();
        assertThat(updateInTx(() -> paymentRepository.claimWithKey(orderId, READY, IN_PROGRESS, "first")))
                .isEqualTo(1);

        assertThat(updateInTx(() -> paymentRepository.claimWithKey(orderId, READY, IN_PROGRESS, "second")))
                .isZero();
        assertThat(paymentRepository.findByOrderId(orderId).orElseThrow().getPaymentKey()).isEqualTo("first");
    }

    private String readyPayment() {
        String orderId = UUID.randomUUID().toString();
        paymentRepository.save(Payment.builder()
                .userId(1L).orderId(orderId).item(PaymentItem.BUNDLE_STANDARD).build());
        return orderId;
    }
}
