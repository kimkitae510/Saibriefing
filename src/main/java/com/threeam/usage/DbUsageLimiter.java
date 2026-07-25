package com.threeam.usage;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 잔여 회수는 이용권(entitlements) 하나로만 센다. 예전에는 일일 무료 쿼터(usage_quota),
// 게스트 총량(같은 테이블의 고정 날짜 행), 이용권 셋이 병존해서 검사와 차감이 두 갈래로
// 갈라져 있었다 — 일일 무료를 폐지하면서 개념을 하나로 합쳤다. 무료로 주는 것(가입 선물,
// 게스트 체험)도 이용권으로 지급하므로 이 클래스는 "이용권에서 뺀다" 한 갈래만 안다.
//
// 생성 락은 별개 관심사라 그대로 둔다 — 유저+종류 단위 분산 락으로 동시 생성을 직렬화한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class DbUsageLimiter implements UsageLimiter {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UsageProperties properties;
    private final EntitlementRepository entitlementRepository;
    private final GenerationLockRepository generationLockRepository;
    private final UserRepository userRepository;

    // 두 문장을 한 트랜잭션에 묶지 않는다. INSERT IGNORE는 중복 행에 공유 락을 남기는데, 그걸 쥔 채
    // UPDATE의 배타 락으로 올라가려는 요청이 둘 이상이면 InnoDB가 교착으로 끊는다(유저는 429 대신 500).
    // 문장마다 커밋하면 공유 락이 UPDATE 전에 풀리고, 각 문장의 반환값만으로 판정이 선다.
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void acquireInFlight(UsageKind kind, Long userId) {
        LocalDateTime now = LocalDateTime.now(KST);
        LocalDateTime until = now.plusSeconds(properties.lockTtlSeconds(kind));
        String key = lockKey(kind, userId);
        if (generationLockRepository.insertIfAbsent(key, until) == 0
                && generationLockRepository.takeOverIfExpired(key, now, until) == 0) {
            throw new BusinessException(ErrorCode.GENERATION_IN_PROGRESS);
        }
    }

    @Override
    @Transactional
    public void releaseInFlight(UsageKind kind, Long userId) {
        generationLockRepository.release(lockKey(kind, userId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isGenerating(UsageKind kind, Long userId) {
        return generationLockRepository.existsByLockKeyAndLockedUntilAfter(
                lockKey(kind, userId), LocalDateTime.now(KST));
    }

    private String lockKey(UsageKind kind, Long userId) {
        return kind.name() + ":" + userId;
    }

    @Override
    @Transactional(readOnly = true)
    public void check(UsageKind kind, Long userId, int units) {
        if (entitlementRepository.remainingOf(userId, kind) >= units) {
            return;
        }
        // 게스트의 소진은 충전이 아니라 계정 연결로 풀린다 — 코드로 화면 안내를 가른다.
        // 게스트에게 분석 이용권은 애초에 지급하지 않으므로 분석은 항상 이 경로로 온다.
        throw new BusinessException(isGuest(userId)
                ? ErrorCode.GUEST_LINK_REQUIRED : ErrorCode.QUOTA_EXCEEDED);
    }

    @Override
    @Transactional
    public void record(UsageKind kind, Long userId, int units) {
        int rest = units;
        // 조건부 UPDATE가 0이면(동시 차감 경합, 그 사이 환불) 다음 이용권으로 넘어간다.
        for (Long entitlementId : entitlementRepository.findConsumableIds(userId, kind)) {
            while (rest > 0 && entitlementRepository.consumeOne(entitlementId) == 1) {
                rest--;
            }
            if (rest == 0) {
                return;
            }
        }
        // 검사 시점엔 있던 이용권이 사라진 극단 케이스(동시 환불 등). 이미 성공한 생성을 무를
        // 수는 없으니 그냥 넘어가되, 반복되면 검사와 차감 사이의 구멍이므로 관측되게 남긴다.
        log.warn("이용권 차감 부족 userId={} kind={} 부족분={}", userId, kind, rest);
    }

    @Override
    @Transactional(readOnly = true)
    public int remaining(UsageKind kind, Long userId) {
        return (int) entitlementRepository.remainingOf(userId, kind);
    }

    private boolean isGuest(Long userId) {
        return userRepository.findById(userId).map(User::isGuest).orElse(false);
    }
}
