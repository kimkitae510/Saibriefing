package com.threeam.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.user.entity.AuthProvider;
import com.threeam.user.entity.Role;
import com.threeam.user.entity.User;
import com.threeam.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// 잔여는 이용권 하나로만 센다(일일 무료 쿼터 폐지). 검사와 차감이 한 갈래라 이 테스트도
// "이용권이 있으면 통과, 없으면 거절, 차감은 오래된 것부터"만 확인하면 된다.
@ExtendWith(MockitoExtension.class)
class DbUsageLimiterTest {

    @Mock
    private EntitlementRepository entitlementRepository;

    @Mock
    private GenerationLockRepository generationLockRepository;

    @Mock
    private UserRepository userRepository;

    @org.mockito.Spy
    private UsageProperties properties = new UsageProperties();

    @InjectMocks
    private DbUsageLimiter limiter;

    private void givenGuest(Long userId, boolean guest) {
        User user = guest
                ? User.builder().role(Role.USER).provider(AuthProvider.GUEST).providerId("g").build()
                : User.builder().role(Role.USER).email("a@b.c").password("x").build();
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
    }

    @Test
    @DisplayName("이용권 잔여가 요청 회수 이상이면 통과한다")
    void check_passesWithEnoughEntitlement() {
        given(entitlementRepository.remainingOf(1L, UsageKind.CHAT)).willReturn(3L);

        assertThatCode(() -> limiter.check(UsageKind.CHAT, 1L, 3)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이용권이 모자라면 회원은 충전 안내(QUOTA_EXCEEDED)로 막는다")
    void check_memberExhausted() {
        given(entitlementRepository.remainingOf(1L, UsageKind.CHAT)).willReturn(1L);
        givenGuest(1L, false);

        assertThatThrownBy(() -> limiter.check(UsageKind.CHAT, 1L, 2))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("게스트 소진은 충전이 아니라 계정 연결로 푼다 — 진단은 이용권을 안 줘서 항상 여기로 온다")
    void check_guestGoesToLinkAccount() {
        given(entitlementRepository.remainingOf(9L, UsageKind.ASSESSMENT)).willReturn(0L);
        givenGuest(9L, true);

        assertThatThrownBy(() -> limiter.check(UsageKind.ASSESSMENT, 9L, 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GUEST_LINK_REQUIRED);
    }

    @Test
    @DisplayName("차감은 오래된 이용권부터, 요청 회수만큼 반복한다")
    void record_consumesOldestFirst() {
        given(entitlementRepository.findConsumableIds(1L, UsageKind.CHAT)).willReturn(List.of(10L, 11L));
        given(entitlementRepository.consumeOne(10L)).willReturn(1, 0); // 첫 장은 1회 남아 있었다
        given(entitlementRepository.consumeOne(11L)).willReturn(1);

        limiter.record(UsageKind.CHAT, 1L, 2);

        verify(entitlementRepository, org.mockito.Mockito.times(2)).consumeOne(10L);
        verify(entitlementRepository).consumeOne(11L);
    }

    @Test
    @DisplayName("차감 도중 이용권이 사라져도 이미 성공한 생성을 무르지 않는다(로그만 남기고 통과)")
    void record_survivesMissingEntitlement() {
        given(entitlementRepository.findConsumableIds(1L, UsageKind.ASSESSMENT)).willReturn(List.of());

        assertThatCode(() -> limiter.record(UsageKind.ASSESSMENT, 1L, 1)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("잔여 조회는 이용권 합을 그대로 돌려준다")
    void remaining_delegates() {
        given(entitlementRepository.remainingOf(1L, UsageKind.ASSESSMENT)).willReturn(7L);

        assertThat(limiter.remaining(UsageKind.ASSESSMENT, 1L)).isEqualTo(7);
    }

    @Test
    @DisplayName("생성 락 — 행이 없으면 새로 넣어 획득하고, 만료 검사는 하지 않는다")
    void acquireInFlight_insertsWhenAbsent() {
        given(generationLockRepository.insertIfAbsent(eq("ASSESSMENT:1"), any())).willReturn(1);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.ASSESSMENT, 1L)).doesNotThrowAnyException();
        verify(generationLockRepository, never()).takeOverIfExpired(any(), any(), any());
    }

    @Test
    @DisplayName("생성 락 — 행이 있어도 만료됐으면 뺏어서 획득한다")
    void acquireInFlight_takesOverExpired() {
        given(generationLockRepository.insertIfAbsent(eq("ASSESSMENT:1"), any())).willReturn(0);
        given(generationLockRepository.takeOverIfExpired(eq("ASSESSMENT:1"), any(), any())).willReturn(1);

        assertThatCode(() -> limiter.acquireInFlight(UsageKind.ASSESSMENT, 1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("생성 락 — 유효한 락이 있으면(넣기 0행, 뺏기 0행) 접수를 거부한다")
    void acquireInFlight_rejectsWhenLocked() {
        given(generationLockRepository.insertIfAbsent(eq("ASSESSMENT:1"), any())).willReturn(0);
        given(generationLockRepository.takeOverIfExpired(eq("ASSESSMENT:1"), any(), any())).willReturn(0);

        assertThatThrownBy(() -> limiter.acquireInFlight(UsageKind.ASSESSMENT, 1L))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GENERATION_IN_PROGRESS);
    }
}
