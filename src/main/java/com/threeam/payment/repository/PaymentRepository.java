package com.threeam.payment.repository;

import com.threeam.payment.entity.Payment;
import com.threeam.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(String orderId);

    // PG 결과 반영은 행 락을 잡고 한다 — 승인 응답, 웹훅, 재동기화가 같은 결제에 동시에
    // 도착해도 전이가 한 줄로 직렬화된다(이용권 지급은 유니크 키가 한 번 더 막는다).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") String orderId);

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);

    // 조정(환불) 웹훅이 거래 id만 들고 올 때 우리 주문을 역으로 찾는다.
    Optional<Payment> findFirstByPaymentKey(String paymentKey);

    // 미결 주문 도배 방어용. 만료 전(만료 스케줄러가 아직 안 돈) READY 주문 수를 센다.
    int countByUserIdAndStatus(Long userId, PaymentStatus status);

    // 상태 전이를 조건부 UPDATE 한 문장으로 — 같은 주문의 동시 요청 중 한 쪽만 성공한다(선점).
    // updated_at은 재동기화의 "얼마나 머물렀나" 기준이라 벌크 UPDATE에서도 직접 갱신한다.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET status = :to, payment_key = :paymentKey, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = :from
            """, nativeQuery = true)
    int claimWithKey(@Param("orderId") String orderId, @Param("from") String from,
                     @Param("to") String to, @Param("paymentKey") String paymentKey);

    // 결제창을 열기 전에 PG가 발급한 거래 식별자를 붙인다(패들). READY로 제한해 승인이
    // 시작된 뒤에는 덮어쓰지 않는다 — 진행 중인 결제의 식별자가 바뀌면 조회 대상이 어긋난다.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET payment_key = :paymentKey, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = 'READY'
            """, nativeQuery = true)
    int attachPaymentKey(@Param("orderId") String orderId, @Param("paymentKey") String paymentKey);

    // cancel_attempts 증가는 PG 멱등키 갱신용 — 시도마다 새 키, 같은 시도의 재전송은 같은 키.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET status = :to, cancel_reason = :reason,
                cancel_attempts = cancel_attempts + 1, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = :from
            """, nativeQuery = true)
    int claimCancel(@Param("orderId") String orderId, @Param("from") String from,
                    @Param("to") String to, @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET status = :to, updated_at = NOW(6)
            WHERE order_id = :orderId AND status = :from
            """, nativeQuery = true)
    int transition(@Param("orderId") String orderId, @Param("from") String from, @Param("to") String to);

    // 재동기화 대상 조회. 배치당 상한을 둬 스케줄 한 턴이 무한정 길어지지 않게 하고, 정렬을 받아
    // 적체가 상한을 넘을 때 가장 오래 기다린 결제부터 풀리게 한다(정렬이 없으면 순서는 인덱스 운에 맡겨진다).
    List<Payment> findByStatusAndUpdatedAtBefore(PaymentStatus status, LocalDateTime before, Pageable pageable);

    List<Payment> findTop20ByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime before);

    List<Payment> findTop20ByStatusAndVbankDueAtBefore(PaymentStatus status, LocalDateTime before);

    // 결과를 모르는 상태로 가장 오래 머문 결제가 그 상태에 들어온 시각(전이 쿼리가 updated_at을 찍는다).
    @Query("select min(p.updatedAt) from Payment p where p.status in :statuses")
    LocalDateTime oldestUpdatedAtIn(@Param("statuses") List<PaymentStatus> statuses);

    // 만료 처리했지만 PG 쪽 거래가 실재했던 주문. 그 결제창은 우리 만료와 무관하게 살아 있어
    // 뒤늦게 결제될 수 있다 — 웹훅이 유실된 경우를 대비해 생성 후 하루 동안만 다시 확인한다.
    // 상한을 생성 시각으로 거는 이유: 확인할 때마다 updated_at을 갱신(touch)해 재확인 간격을
    // 버는데, 상한까지 updated_at 기준이면 확인이 기한을 계속 미뤄 영원히 조회하게 된다.
    List<Payment> findTop20ByStatusAndPaymentKeyIsNotNullAndCreatedAtAfterAndUpdatedAtBefore(
            PaymentStatus status, LocalDateTime createdAfter, LocalDateTime updatedBefore);

    // 만료 주문 재확인의 간격 조절용. 상태가 그대로면 아무 컬럼도 안 바뀌어 updated_at이
    // 멈추고, 그러면 매 주기마다 같은 주문을 다시 조회한다 — 확인 시각을 명시적으로 남긴다.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE payments SET updated_at = NOW(6)
            WHERE order_id = :orderId AND status = 'EXPIRED'
            """, nativeQuery = true)
    int touchExpired(@Param("orderId") String orderId);
}
