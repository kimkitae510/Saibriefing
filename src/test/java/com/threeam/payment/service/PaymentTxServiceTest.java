package com.threeam.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.payment.client.PgPaymentResult;
import com.threeam.payment.client.PgStatus;
import com.threeam.payment.dto.PaymentResponse;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentItem;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.payment.repository.PaymentRepository;
import com.threeam.usage.Entitlement;
import com.threeam.usage.EntitlementRepository;
import com.threeam.usage.UsageKind;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentTxServiceTest {

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private EntitlementRepository entitlementRepository;

    @InjectMocks
    private PaymentTxService service;

    private Payment payment(PaymentStatus status) {
        Payment payment = Payment.builder()
                .userId(1L).orderId("order-1").item(PaymentItem.BUNDLE_STANDARD).build();
        ReflectionTestUtils.setField(payment, "id", 100L);
        ReflectionTestUtils.setField(payment, "status", status);
        return payment;
    }

    private Entitlement entitlement(Long id, UsageKind kind, int total) {
        Entitlement entitlement = Entitlement.builder()
                .userId(1L).kind(kind).totalCount(total).paymentId(100L).build();
        ReflectionTestUtils.setField(entitlement, "id", id);
        return entitlement;
    }

    @Test
    @DisplayName("소유권 - 남의 결제는 404로 존재 자체를 숨긴다")
    void loadOwned_hidesOthersPayment() {
        given(paymentRepository.findByOrderId("order-1")).willReturn(Optional.of(payment(PaymentStatus.DONE)));

        assertThatThrownBy(() -> service.loadOwned(999L, "order-1"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("반영 - DONE 결과는 승인 확정과 묶음 이용권 지급(분석+매칭)을 함께 처리한다")
    void apply_doneGrantsAllEntitlements() {
        Payment inProgress = payment(PaymentStatus.IN_PROGRESS);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(inProgress));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        PgPaymentResult done = new PgPaymentResult("pay-1", "order-1", PgStatus.DONE,
                "카드", LocalDateTime.now(), null, 0, null);
        service.applyPgResult("order-1", done);

        assertThat(inProgress.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(inProgress.getMethod()).isEqualTo("카드");
        ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
        // 채팅 회차는 팔지 않는다(선불 회차가 계정에 쌓이면 미이행 채무 — PaymentItem 참고).
        verify(entitlementRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Entitlement::getKind, Entitlement::getTotalCount, Entitlement::getPaymentId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(UsageKind.ASSESSMENT, 1, 100L),
                        org.assertj.core.groups.Tuple.tuple(UsageKind.MATCH, 1, 100L));
    }

    @Test
    @DisplayName("반영 - 만료 처리한 주문도 PG가 결제됐다고 하면 되살려 지급한다(돈만 받고 지급 누락 방지)")
    void apply_revivesExpiredWhenActuallyPaid() {
        // 패들 결제창은 우리 만료 시각과 무관하게 살아 있다 — 만료 뒤 결제가 성사될 수 있다.
        Payment expired = payment(PaymentStatus.EXPIRED);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(expired));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        PgPaymentResult done = new PgPaymentResult("txn-1", "order-1", PgStatus.DONE,
                "카드", LocalDateTime.now(), null, 0, null);
        service.applyPgResult("order-1", done);

        assertThat(expired.getStatus()).isEqualTo(PaymentStatus.DONE);
        verify(entitlementRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("반영 - 만료 주문이라도 결제되지 않았다면 그대로 둔다")
    void apply_keepsExpiredWhenNotPaid() {
        Payment expired = payment(PaymentStatus.EXPIRED);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(expired));

        service.applyPgResult("order-1", PgPaymentResult.of("txn-1", "order-1", PgStatus.NOT_FOUND));

        assertThat(expired.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("반영 - 환불 완료된 결제는 DONE 신호가 와도 되살리지 않는다(PG가 확인해 준 종결)")
    void apply_keepsCanceledTerminal() {
        Payment canceled = payment(PaymentStatus.CANCELED);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(canceled));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        PgPaymentResult done = new PgPaymentResult("txn-1", "order-1", PgStatus.DONE,
                "카드", LocalDateTime.now(), null, 0, null);
        service.applyPgResult("order-1", done);

        assertThat(canceled.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("반영 - 일부만 지급된 채 끊겼으면 빠진 종류만 마저 지급한다")
    void apply_doneFillsMissingGrantOnly() {
        Payment done = payment(PaymentStatus.DONE);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(done));
        given(entitlementRepository.findByPaymentId(100L))
                .willReturn(List.of(entitlement(55L, UsageKind.CHAT, 20)));

        service.applyPgResult("order-1", PgPaymentResult.of("pay-1", "order-1", PgStatus.DONE));

        ArgumentCaptor<Entitlement> captor = ArgumentCaptor.forClass(Entitlement.class);
        verify(entitlementRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Entitlement::getKind)
                .containsExactlyInAnyOrder(UsageKind.ASSESSMENT, UsageKind.MATCH);
    }

    @Test
    @DisplayName("반영 - 이미 다 지급된 결제에 DONE이 다시 와도(웹훅 재전송) 이중 지급하지 않는다")
    void apply_doneIsIdempotent() {
        Payment done = payment(PaymentStatus.DONE);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(done));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of(
                entitlement(55L, UsageKind.CHAT, 20),
                entitlement(56L, UsageKind.ASSESSMENT, 3),
                entitlement(57L, UsageKind.MATCH, 1)));

        service.applyPgResult("order-1", PgPaymentResult.of("pay-1", "order-1", PgStatus.DONE));

        verify(entitlementRepository, never()).save(any());
        assertThat(done.getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("반영 - 종결(CANCELED)된 결제는 어떤 신호가 와도 되살아나지 않는다")
    void apply_terminalStateIsFrozen() {
        Payment canceled = payment(PaymentStatus.CANCELED);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(canceled));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        PaymentResponse response = service.applyPgResult("order-1",
                PgPaymentResult.of("pay-1", "order-1", PgStatus.DONE));

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("반영 - 취소 결과는 환불액 기록과 이용권 전부 회수를 함께 처리한다")
    void apply_cancelRevokesAllEntitlements() {
        Payment requested = payment(PaymentStatus.CANCEL_REQUESTED);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(requested));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of(
                entitlement(55L, UsageKind.CHAT, 20),
                entitlement(56L, UsageKind.ASSESSMENT, 3),
                entitlement(57L, UsageKind.MATCH, 1)));

        PgPaymentResult canceled = new PgPaymentResult("pay-1", "order-1", PgStatus.PARTIAL_CANCELED,
                "카드", null, null, 1300, null);
        service.applyPgResult("order-1", canceled);

        assertThat(requested.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(requested.getCanceledAmount()).isEqualTo(1300);
        verify(entitlementRepository).revoke(eq(55L), any(LocalDateTime.class));
        verify(entitlementRepository).revoke(eq(56L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("반영 - PG에 주문이 없으면(NOT_FOUND) 승인 시도 흔적이 있어도 만료 처리한다")
    void apply_notFoundExpires() {
        Payment inProgress = payment(PaymentStatus.IN_PROGRESS);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(inProgress));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        service.applyPgResult("order-1", PgPaymentResult.of(null, "order-1", PgStatus.NOT_FOUND));

        assertThat(inProgress.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    @DisplayName("반영 - 가상계좌 발급 결과는 입금 안내 정보(은행, 계좌, 기한)를 담아 대기 전환한다")
    void apply_virtualAccountIssued() {
        Payment inProgress = payment(PaymentStatus.IN_PROGRESS);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(inProgress));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        LocalDateTime due = LocalDateTime.now().plusDays(1);
        PgPaymentResult waiting = new PgPaymentResult("pay-1", "order-1", PgStatus.WAITING_FOR_DEPOSIT,
                "가상계좌", null, null, 0, new PgPaymentResult.VirtualAccount("20", "1234567890", due));
        service.applyPgResult("order-1", waiting);

        assertThat(inProgress.getStatus()).isEqualTo(PaymentStatus.WAITING_FOR_DEPOSIT);
        assertThat(inProgress.getVbankBank()).isEqualTo("20");
        assertThat(inProgress.getVbankAccount()).isEqualTo("1234567890");
        assertThat(inProgress.getVbankDueAt()).isEqualTo(due);
        // 입금 전에는 돈을 받은 게 아니다 — 이용권 지급 없음.
        verify(entitlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("반영 - 결론 없는 상태(UNKNOWN)는 아무것도 바꾸지 않는다")
    void apply_unknownIsNoop() {
        Payment inProgress = payment(PaymentStatus.IN_PROGRESS);
        given(paymentRepository.findByOrderIdForUpdate("order-1")).willReturn(Optional.of(inProgress));
        given(entitlementRepository.findByPaymentId(100L)).willReturn(List.of());

        service.applyPgResult("order-1", PgPaymentResult.of(null, "order-1", PgStatus.UNKNOWN));

        assertThat(inProgress.getStatus()).isEqualTo(PaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("주문 생성 - 미결 주문이 상한 미만이면 저장한다")
    void createOrder_underPendingLimit() {
        given(paymentRepository.countByUserIdAndStatus(1L, PaymentStatus.READY)).willReturn(4);
        given(paymentRepository.save(any(Payment.class))).willAnswer(inv -> inv.getArgument(0));

        Payment created = service.createOrder(1L, PaymentItem.BUNDLE_STANDARD, 5);

        assertThat(created.getStatus()).isEqualTo(PaymentStatus.READY);
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    @DisplayName("주문 생성 - 미결 주문이 상한에 닿았으면 TOO_MANY_PENDING_ORDERS로 거절한다")
    void createOrder_pendingLimitReached() {
        given(paymentRepository.countByUserIdAndStatus(1L, PaymentStatus.READY)).willReturn(5);

        assertThatThrownBy(() -> service.createOrder(1L, PaymentItem.BUNDLE_STANDARD, 5))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOO_MANY_PENDING_ORDERS);
        verify(paymentRepository, never()).save(any());
    }
}
