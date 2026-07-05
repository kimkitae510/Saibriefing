package com.threeam.story.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
// 커서 페이지네이션("이 사연의 id < cursor 최신순 N개")을 인덱스 범위 스캔으로 처리하기 위한 복합 인덱스.
@Table(name = "messages", indexes = {
        @Index(name = "idx_messages_story_id", columnList = "story_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "story_id", nullable = false)
    private Story story;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Message(Story story, MessageRole role, String content) {
        this.story = story;
        this.role = role;
        this.content = content;
    }

    public static Message user(Story story, String content) {
        return Message.builder().story(story).role(MessageRole.USER).content(content).build();
    }

    public static Message assistant(Story story, String content) {
        return Message.builder().story(story).role(MessageRole.ASSISTANT).content(content).build();
    }

    // LLM 호출이 실패했을 때 답 대신 저장하는 말풍선. 폴링이 이걸 받고 정상 종료한다.
    // 페르소나가 하는 말이라 페르소나 문법을 따른다 — 하십시오체, 마침표 없이.
    // 여기 두는 이유: 화면의 재시도 버튼과 서버의 재시도 검사가 "이게 실패한 턴인가"를
    // 같은 기준으로 판정해야 한다. 문구가 두 군데로 갈리면 버튼만 뜨고 요청은 거절된다.
    public static final String FALLBACK_CONTENT =
            "죄송합니다, 지금 답을 정리하기가 어렵습니다\n조금 뒤에 다시 보내주시겠습니까?";

    public static Message fallback(Story story) {
        return assistant(story, FALLBACK_CONTENT);
    }

    // 이 말풍선이 '답을 못 받은 턴'인지. 문구를 바꾸면 그 전에 저장된 폴백은 재시도 대상에서
    // 빠지지만, 지난 대화의 버튼이 사라질 뿐이라 문제되지 않는다(컬럼을 새로 파지 않는 값).
    public boolean isFallback() {
        return role == MessageRole.ASSISTANT && FALLBACK_CONTENT.equals(content);
    }
}
