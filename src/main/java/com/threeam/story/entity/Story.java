package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 사연 = 한 사람(전 연인)에 대한 상담 스레드 전체. 밑에 메시지들이 달리고, 재회 확률도 여기에 귀속된다.
@Entity
@Table(name = "stories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Story {

    public static final String DEFAULT_TITLE = "새 대화";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 새 메시지가 들어올 때 touch()로 직접 갱신한다. 사연 목록을 최근 활동순으로 정렬하기 위함.
    // (메시지는 별도 엔티티라 @UpdateTimestamp로는 사연 갱신이 잡히지 않는다.)
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // 소프트 딜리트: 물리 삭제 대신 시각만 찍는다. 대화, 분석은 남겨둬야 할 기록이라 지우지 않는다.
    // null이면 살아있는 사연. 조회 쿼리는 deletedAt IS NULL만 노출한다.
    @Column
    private LocalDateTime deletedAt;

    // 유저가 이 방을 마지막으로 읽은 시각. updatedAt이 이보다 뒤면 목록에 안읽음 표시(카톡의 1).
    // 메시지 조회(입장, 폴링 수신) 시점에 갱신된다.
    @Column
    private LocalDateTime lastReadAt;

    // 마지막으로 분석이 "근거 부족(INSUFFICIENT)"을 받은 시각. 이후 새 대화가 없으면 재분석을
    // LLM 없이 거부하는 근거(무차감 재호출로 비싼 분석이 반복되는 것 방지). 성공 분석 시 null로 지운다.
    // 인메모리 맵을 대체 — 재시작, 멀티인스턴스에서도 유지된다.
    @Column
    private LocalDateTime lastInsufficientAt;

    // 분석 생성 실패(응답 잘림, 안전성 차단, 장애)의 "같은 재료 연속" 횟수와 마지막 시각.
    // 실패는 후차감이라 쿼터가 안 깎여, 같은 재료로 무한 재시도(무료 LLM 호출)가 가능하다.
    // 같은 재료 연속 2회 실패면 새 대화 전까지 LLM 없이 거부한다. 1회는 재시도 허용(일시 장애 복구 여지).
    // LLM 왕복이 정상 처리되면(INSUFFICIENT 판정 포함) 초기화한다.
    @Column
    private LocalDateTime lastAssessFailedAt;

    @Column(nullable = false)
    private int assessFailStreak;

    // 채팅 사실 추출이 어디까지 훑었는지(마지막으로 추출에 넘긴 message id).
    // 매 턴 추출을 돌리던 것을 이 워터마크 기준의 묶음 추출로 바꾸면서 생겼다 —
    // 이게 없으면 "새로 쌓인 것만" 보낼 수가 없어 매번 같은 창을 다시 읽게 된다.
    // null은 아직 한 번도 안 훑은 사연(처음부터가 미추출 구간).
    @Column
    private Long lastExtractedMessageId;

    // 채팅이 스스로 낸 재회 방향(turn-2의 ---chat-meta---에서 뽑는다). 진단 확률과는 다른 층이라
    // 진단이 없는 사연에서도 값이 있다. 추천 칩 셀렉터에 참고값으로 나간다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private ReunionDirection reunionDirection;

    @Builder
    private Story(Long userId, String title) {
        this.userId = userId;
        this.title = title;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    // 값을 못 읽은 턴은 건드리지 않는다 — turn-rest는 chat-meta를 안 내보내는 게 정상이라,
    // null로 덮으면 재분석 턴마다 방향이 사라졌다 다시 생긴다.
    public void updateReunionDirection(ReunionDirection direction) {
        if (direction != null) {
            this.reunionDirection = direction;
        }
    }

    public void markRead() {
        this.lastReadAt = LocalDateTime.now();
    }

    public boolean hasUnread() {
        return lastReadAt != null && updatedAt.isAfter(lastReadAt);
    }

    public void rename(String title) {
        this.title = title;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    // 추출이 성공한 만큼만 전진시킨다 — 실패하면 그대로 두어 다음 회차가 같은 구간을 다시 집는다.
    public void markExtractedUpTo(Long messageId) {
        this.lastExtractedMessageId = messageId;
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }
}
