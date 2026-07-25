package com.threeam.usage;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {

    // 묶음 상품은 결제 하나가 이용권 여러 종(대화, 분석)을 지급한다.
    List<Entitlement> findByPaymentId(Long paymentId);

    List<Entitlement> findByPaymentIdIn(Collection<Long> paymentIds);

    @Query("""
            select coalesce(sum(e.totalCount - e.usedCount), 0) from Entitlement e
            where e.userId = :userId and e.kind = :kind and e.revokedAt is null
            """)
    long remainingOf(@Param("userId") Long userId, @Param("kind") UsageKind kind);

    // 차감 후보를 오래된 것부터 — 먼저 산 이용권부터 소진시킨다(환불 분쟁 여지 최소화).
    @Query("""
            select e.id from Entitlement e
            where e.userId = :userId and e.kind = :kind
                and e.revokedAt is null and e.usedCount < e.totalCount
            order by e.createdAt asc
            """)
    List<Long> findConsumableIds(@Param("userId") Long userId, @Param("kind") UsageKind kind);

    // 조건부 증가 한 문장 — 동시 차감이 잔여분을 초과하지 못하게 DB가 직렬화한다.
    // 0을 돌려주면(경합 패배, 그 사이 환불) 호출부가 다음 후보로 넘어간다.
    @Modifying(clearAutomatically = true)
    @Query("""
            update Entitlement e set e.usedCount = e.usedCount + 1
            where e.id = :id and e.usedCount < e.totalCount and e.revokedAt is null
            """)
    int consumeOne(@Param("id") Long id);

    // flushAutomatically가 필수 — 취소 반영 흐름은 같은 트랜잭션에서 결제 엔티티를 먼저 수정한 뒤
    // 이 쿼리를 부른다. flush 없이 clear만 하면 아직 안 쓰인 결제 상태 변경(CANCELED)이
    // 영속성 컨텍스트와 함께 증발한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Entitlement e set e.revokedAt = :at where e.id = :id and e.revokedAt is null")
    int revoke(@Param("id") Long id, @Param("at") LocalDateTime at);
}
