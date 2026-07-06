package com.threeam.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.payment.client.PaymentGateway;
import com.threeam.payment.client.PaymentGatewayException;
import com.threeam.payment.client.PaymentProperties;
import com.threeam.payment.client.PgPaymentResult;
import com.threeam.payment.client.PgStatus;
import com.threeam.payment.dto.CancelRequest;
import com.threeam.payment.dto.ConfirmRequest;
import com.threeam.payment.dto.OrderCreateRequest;
import com.threeam.payment.dto.OrderCreateResponse;
import com.threeam.payment.dto.PaymentResponse;
import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentItem;
import com.threeam.payment.entity.PaymentStatus;
import com.threeam.usage.Entitlement;
import com.threeam.usage.UsageKind;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    // BUNDLE_STANDARD: 분석 리포트 1건 = 6,900원
    private static final int BUNDLE_AMOUNT = 6900;

    @Mock
    private PaymentTxService txService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private com.threeam.consent.service.ConsentService consentService;

    // 게스트 결제 차단용 조회 — 스텁 없으면 Optional.empty()라 일반 회원으로 취급된다
    @Mock
    private com.threeam.user.repository.UserRepository userRepository;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(txService, paymentGateway, new PaymentProperties(), consentService,
                userRepository);
    }

    private Payment payment(PaymentStatus status) {
        Payment payment = Payment.builder()
                .userId(1L).orderId("order-1").item(PaymentItem.BUNDLE_STANDARD).build();
        ReflectionTestUtils.setField(payment, "id", 100L);
        ReflectionTestUtils.setField(payment, "status", status);
        ReflectionTestUtils.setField(payment, "paymentKey", "pay-1");
        return payment;
    }

    private ConfirmRequest confirmRequest(int amount) {
        ConfirmRequest request = new ConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentKey", "pay-1");
        ReflectionTestUtils.setField(request, "orderId", "order-1");
        ReflectionTestUtils.setField(request, "amount", amount);
        return request;
    }

    private Entitlement entitlement(UsageKind kind, int total, int used) {
        Entitlement entitlement = Entitlement.builder()
                .userId(1L).kind(kind).totalCount(total).paymentId(100L).build();
        ReflectionTestUtils.setField(entitlement, "usedCount", used);
        return entitlement;
    }

    private PaymentResponse response(Payment payment) {
        return PaymentResponse.of(payment, List.of());
    }

    @Test
    @DisplayName("주문 생성 - 없는 상품 코드는 PAYMENT_ITEM_NOT_FOUND")
    void createOrder_unknownItem() {
        OrderCreateRequest request = orderRequest("NOPE", true);

        assertThatThrownBy(() -> service.createOrder(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ITEM_NOT_FOUND);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("주문 생성 - 청약철회 고지 미동의면 주문을 만들지 않는다")
    void createOrder_withoutRefundConsent() {
        OrderCreateRequest request = orderRequest("BUNDLE_STANDARD", false);

        assertThatThrownBy(() -> service.createOrder(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONSENT_REQUIRED);
        verifyNoInteractions(txService);
        verifyNoInteractions(consentService);
    }

    @Test
    @DisplayName("주문 생성 - 게스트는 결제할 수 없다(토큰 유실 시 자산 복구 불가 — 계정 연결 유도)")
    void createOrder_guestBlocked() {
        given(userRepository.findById(9L)).willReturn(java.util.Optional.of(
                com.threeam.user.entity.User.builder()
                        .role(com.threeam.user.entity.Role.USER)
                        .provider(com.threeam.user.entity.AuthProvider.GUEST)
                        .providerId("guest-uuid")
                        .build()));

        assertThatThrownBy(() -> service.createOrder(9L, orderRequest("BUNDLE_STANDARD", true)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GUEST_LINK_REQUIRED);
        verifyNoInteractions(txService);
    }

    @Test
    @DisplayName("주문 생성 - 성공 시 고지 동의를 주문 번호와 묶어 기록한다")
    void createOrder_recordsConsent() {
        OrderCreateRequest request = orderRequest("BUNDLE_STANDARD", true);
        given(txService.createOrder(eq(1L), eq(PaymentItem.BUNDLE_STANDARD), anyInt()))
                .willReturn(payment(PaymentStatus.READY));
        given(paymentGateway.prepare(eq("order-1"), eq(PaymentItem.BUNDLE_STANDARD), anyInt()))
                .willReturn(CompletableFuture.completedFuture(null));

        service.createOrder(1L, request).join();

        verify(consentService).recordPurchaseConsent(1L, "order-1");
    }

    @Test
    @DisplayName("주문 생성 - PG가 거래 식별자를 주면 결제창을 열기 전에 주문에 붙여둔다")
    void createOrder_attachesPgReference() {
        OrderCreateRequest request = orderRequest("BUNDLE_STANDARD", true);
        given(txService.createOrder(eq(1L), eq(PaymentItem.BUNDLE_STANDARD), anyInt()))
                .willReturn(payment(PaymentStatus.READY));
        given(paymentGateway.prepare(eq("order-1"), eq(PaymentItem.BUNDLE_STANDARD), anyInt()))
                .willReturn(CompletableFuture.completedFuture("txn_01abc"));

        OrderCreateResponse response = service.createOrder(1L, request).join();

        // 저장해 두지 않으면 결제 후 창이 닫혔을 때 그 결제를 찾을 방법이 없다.
        verify(txService).attachPaymentKey("order-1", "txn_01abc");
        assertThat(response.getPgRef()).isEqualTo("txn_01abc");
    }

    @Test
    @DisplayName("주문 생성 - PG 거래 생성이 실패하면 주문을 내주지 않는다")
    void createOrder_failsWhenPrepareFails() {
        OrderCreateRequest request = orderRequest("BUNDLE_STANDARD", true);
        given(txService.createOrder(eq(1L), eq(PaymentItem.BUNDLE_STANDARD), anyInt()))
                .willReturn(payment(PaymentStatus.READY));
        given(paymentGateway.prepare(eq("order-1"), eq(PaymentItem.BUNDLE_STANDARD), anyInt()))
                .willReturn(CompletableFuture.failedFuture(
                        new BusinessException(ErrorCode.PAYMENT_PRICE_NOT_CONFIGURED)));

        assertThatThrownBy(() -> service.createOrder(1L, request).join())
                .hasCauseInstanceOf(BusinessException.class);
        verify(txService, never()).attachPaymentKey(any(), any());
    }

    private OrderCreateRequest orderRequest(String item, boolean refundPolicyAgreed) {
        OrderCreateRequest request = new OrderCreateRequest();
        ReflectionTestUtils.setField(request, "item", item);
        ReflectionTestUtils.setField(request, "refundPolicyAgreed", refundPolicyAgreed);
        return request;
    }

    @Test
    @DisplayName("승인 - 이미 DONE이면 PG를 다시 부르지 않고 기존 결과를 준다(멱등)")
    void confirm_idempotentWhenDone() {
        Payment done = payment(PaymentStatus.DONE);
        given(txService.loadOwned(1L, "order-1")).willReturn(done);
        given(txService.toResponseOwned(1L, "order-1")).willReturn(response(done));

        PaymentResponse result = service.confirm(1L, confirmRequest(BUNDLE_AMOUNT)).join();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
        verifyNoInteractions(paymentGateway);
        verify(txService, never()).claimConfirm(anyString(), anyString());
    }

    @Test
    @DisplayName("승인 - 결과 불명(IN_PROGRESS) 결제에 다시 승인 요청하면 거절한다(이중 승인 방지)")
    void confirm_rejectsWhileInProgress() {
        given(txService.loadOwned(1L, "order-1")).willReturn(payment(PaymentStatus.IN_PROGRESS));

        assertThatThrownBy(() -> service.confirm(1L, confirmRequest(BUNDLE_AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_PROCESSING);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("승인 - 요청 금액이 주문 금액과 다르면 승인 자체를 하지 않는다(위변조 차단)")
    void confirm_amountMismatch() {
        given(txService.loadOwned(1L, "order-1")).willReturn(payment(PaymentStatus.READY));

        assertThatThrownBy(() -> service.confirm(1L, confirmRequest(100)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("승인 - 선점 실패(동시 요청 경합)면 PAYMENT_ALREADY_PROCESSING")
    void confirm_claimLost() {
        given(txService.loadOwned(1L, "order-1")).willReturn(payment(PaymentStatus.READY));
        given(txService.claimConfirm("order-1", "pay-1")).willReturn(false);

        assertThatThrownBy(() -> service.confirm(1L, confirmRequest(BUNDLE_AMOUNT)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_ALREADY_PROCESSING);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("승인 - 정상 경로: PG 승인 결과를 반영하고 응답을 준다")
    void confirm_success() {
        Payment ready = payment(PaymentStatus.READY);
        given(txService.loadOwned(1L, "order-1")).willReturn(ready);
        given(txService.claimConfirm("order-1", "pay-1")).willReturn(true);
        PgPaymentResult done = PgPaymentResult.of("pay-1", "order-1", PgStatus.DONE);
        given(paymentGateway.confirm("pay-1", "order-1", BUNDLE_AMOUNT))
                .willReturn(CompletableFuture.completedFuture(done));
        Payment donePayment = payment(PaymentStatus.DONE);
        given(txService.applyPgResult("order-1", done)).willReturn(response(donePayment));

        PaymentResponse result = service.confirm(1L, confirmRequest(BUNDLE_AMOUNT)).join();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("승인 - PG의 확실한 거절은 상태를 반영한 뒤 PAYMENT_CONFIRM_REJECTED로 알린다")
    void confirm_rejectedByPg() {
        given(txService.loadOwned(1L, "order-1")).willReturn(payment(PaymentStatus.READY));
        given(txService.claimConfirm("order-1", "pay-1")).willReturn(true);
        PgPaymentResult failed = new PgPaymentResult("pay-1", "order-1", PgStatus.FAILED,
                null, null, "카드 한도 초과", 0, null);
        given(paymentGateway.confirm("pay-1", "order-1", BUNDLE_AMOUNT))
                .willReturn(CompletableFuture.completedFuture(failed));
        given(txService.applyPgResult("order-1", failed)).willReturn(response(payment(PaymentStatus.FAILED)));

        CompletableFuture<PaymentResponse> future = service.confirm(1L, confirmRequest(BUNDLE_AMOUNT));

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_CONFIRM_REJECTED);
        verify(txService).applyPgResult("order-1", failed);   // 거절도 기록에 남긴다
    }

    @Test
    @DisplayName("승인 - 응답 불명이면 상태를 확정하지 않고 PAYMENT_RESULT_PENDING(재동기화 대기)")
    void confirm_unknownOutcome() {
        given(txService.loadOwned(1L, "order-1")).willReturn(payment(PaymentStatus.READY));
        given(txService.claimConfirm("order-1", "pay-1")).willReturn(true);
        given(paymentGateway.confirm("pay-1", "order-1", BUNDLE_AMOUNT))
                .willReturn(CompletableFuture.failedFuture(new PaymentGatewayException("timeout")));

        CompletableFuture<PaymentResponse> future = service.confirm(1L, confirmRequest(BUNDLE_AMOUNT));

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_RESULT_PENDING);
        // 불명인데 상태를 만지면 안 된다 — IN_PROGRESS 그대로 두고 재동기화가 확정한다.
        verify(txService, never()).applyPgResult(anyString(), any());
    }

    @Test
    @DisplayName("환불 - 전량 미사용이면 전액 취소를 요청한다")
    void cancel_fullRefundWhenUnused() {
        Payment done = payment(PaymentStatus.DONE);
        ReflectionTestUtils.setField(done, "method", "카드");
        given(txService.loadOwned(1L, "order-1")).willReturn(done);
        given(txService.entitlementsOf(100L)).willReturn(List.of(
                entitlement(UsageKind.CHAT, 20, 0),
                entitlement(UsageKind.ASSESSMENT, 3, 0)));
        given(txService.claimCancel(eq("order-1"), eq(PaymentStatus.DONE), anyString())).willReturn(true);
        given(txService.cancelAttemptsOf("order-1")).willReturn(1);
        PgPaymentResult canceled = new PgPaymentResult("pay-1", "order-1", PgStatus.CANCELED,
                "카드", null, null, BUNDLE_AMOUNT, null);
        given(paymentGateway.cancel(eq("pay-1"), eq(BUNDLE_AMOUNT), anyString(), eq("order-1-cancel-1"), isNull()))
                .willReturn(CompletableFuture.completedFuture(canceled));
        given(txService.applyPgResult("order-1", canceled)).willReturn(response(payment(PaymentStatus.CANCELED)));

        PaymentResponse result = service.cancel(1L, "order-1", new CancelRequest()).join();

        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(paymentGateway).cancel(eq("pay-1"), eq(BUNDLE_AMOUNT), anyString(), eq("order-1-cancel-1"), isNull());
    }

    @Test
    @DisplayName("환불 - 한 번이라도 썼으면 REFUND_NOT_ALLOWED(부분 환불 없음)")
    void cancel_rejectedAfterAnyUsage() {
        Payment done = payment(PaymentStatus.DONE);
        given(txService.loadOwned(1L, "order-1")).willReturn(done);
        // 대화 1회만 써도 환불 불가
        given(txService.entitlementsOf(100L)).willReturn(List.of(
                entitlement(UsageKind.CHAT, 20, 1),
                entitlement(UsageKind.ASSESSMENT, 3, 0)));

        assertThatThrownBy(() -> service.cancel(1L, "order-1", new CancelRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFUND_NOT_ALLOWED);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("환불 - 가상계좌 입금 결제는 환불 계좌 없이는 거절한다")
    void cancel_virtualAccountNeedsRefundAccount() {
        Payment done = payment(PaymentStatus.DONE);
        ReflectionTestUtils.setField(done, "method", "가상계좌");
        given(txService.loadOwned(1L, "order-1")).willReturn(done);
        given(txService.entitlementsOf(100L)).willReturn(List.of(
                entitlement(UsageKind.CHAT, 20, 0),
                entitlement(UsageKind.ASSESSMENT, 3, 0)));

        assertThatThrownBy(() -> service.cancel(1L, "order-1", new CancelRequest()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.REFUND_ACCOUNT_REQUIRED);
        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("환불 - PG가 확실히 거절하면 원상 복귀 후 PAYMENT_CANCEL_REJECTED")
    void cancel_rejectedReverts() {
        Payment done = payment(PaymentStatus.DONE);
        ReflectionTestUtils.setField(done, "method", "카드");
        given(txService.loadOwned(1L, "order-1")).willReturn(done);
        // 환불 가능 조건(전량 미사용)은 통과시키고, PG 거절 경로를 본다.
        given(txService.entitlementsOf(100L)).willReturn(List.of(
                entitlement(UsageKind.CHAT, 20, 0),
                entitlement(UsageKind.ASSESSMENT, 3, 0)));
        given(txService.claimCancel(eq("order-1"), eq(PaymentStatus.DONE), anyString())).willReturn(true);
        given(txService.cancelAttemptsOf("order-1")).willReturn(1);
        PgPaymentResult rejected = new PgPaymentResult("pay-1", "order-1", PgStatus.FAILED,
                null, null, "취소 불가", 0, null);
        given(paymentGateway.cancel(anyString(), anyInt(), anyString(), anyString(), isNull()))
                .willReturn(CompletableFuture.completedFuture(rejected));

        CompletableFuture<PaymentResponse> future = service.cancel(1L, "order-1", new CancelRequest());

        assertThatThrownBy(future::join)
                .isInstanceOf(CompletionException.class)
                .cause()
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PAYMENT_CANCEL_REJECTED);
        verify(txService).revertCancelRequest("order-1", PaymentStatus.DONE);
    }

    @Test
    @DisplayName("동기화 - 우리 주문이 아니면 PG 조회 없이 조용히 넘긴다(위조 웹훅 무해화)")
    void sync_ignoresUnknownOrder() {
        given(txService.syncTargetOf("evil-order")).willReturn(java.util.Optional.empty());

        service.syncByOrderId("evil-order").join();

        verifyNoInteractions(paymentGateway);
    }

    @Test
    @DisplayName("동기화 - 이미 종결된 주문은 PG 조회 없이 넘긴다(반복 웹훅 비용 차단)")
    void sync_skipsTerminalOrder() {
        given(txService.syncTargetOf("done-canceled")).willReturn(java.util.Optional.of(
                new PaymentTxService.SyncTarget(PaymentStatus.CANCELED, "txn-1")));

        service.syncByOrderId("done-canceled").join();

        verifyNoInteractions(paymentGateway);
    }
}
