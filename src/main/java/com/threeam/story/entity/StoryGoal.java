package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 탐색 채팅이 채운 목표 한 건. 행이 있으면 채워진 것이고 없으면 아직이다 —
// 상태 컬럼을 두지 않는 이유는 "채워졌다가 다시 빈다"가 없어서다(관측은 되돌리지 않는다).
// 목표 정의(무엇을 물어야 하는지)는 goals.yml에 있고 여기엔 키와 근거 발화만 남는다.
@Entity
@Table(name = "story_goals",
        indexes = @Index(name = "idx_story_goals_story", columnList = "story_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_story_goals_story_key",
                columnNames = {"story_id", "goal_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoryGoal {

    public static final int KEY_MAX_LENGTH = 40;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    // MySQL 예약어(key)를 피해 goal_key.
    @Column(name = "goal_key", nullable = false, length = KEY_MAX_LENGTH)
    private String goalKey;

    // 판정이 인용한 유저 발화. 근거 없는 "채워짐"을 받지 않으려고 비워두지 못하게 한다.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String evidence;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private StoryGoal(Long storyId, String goalKey, String evidence) {
        this.storyId = storyId;
        this.goalKey = goalKey;
        this.evidence = evidence;
    }

    public static StoryGoal filled(Long storyId, String goalKey, String evidence) {
        return new StoryGoal(storyId, goalKey, evidence);
    }
}
