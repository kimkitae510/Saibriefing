package com.threeam.story.dto;

import com.threeam.story.entity.ChatMeta;
import com.threeam.story.entity.Message;
import com.threeam.story.entity.MessageRole;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class MessageResponse {

    // 옛 상담자가 답변 끝에 붙이던 질문 구분선. 지금은 안 만들지만 그때 저장된 대화에는 남아 있다 —
    // 기록은 고치지 않으므로 표시에서만 걷어 질문을 본문 줄로 잇는다.
    static final String LEGACY_QUESTION_MARKER = "---질문---";

    private final Long id;
    private final MessageRole role;
    private final String content;
    private final LocalDateTime createdAt;
    // 답을 못 받아 폴백이 저장된 턴. 화면은 이 값으로 재시도 버튼을 띄운다 —
    // 프론트가 폴백 문구를 복사해 문자열로 비교하면 문구를 고칠 때마다 두 곳이 어긋난다.
    private final boolean failed;
    // 탐색 목표의 진행(채워진 수 / 전체). 상담자 답에만 붙고, 목표가 꺼져 있으면 비어 있다.
    // 화면은 이 둘이 같아지는 순간을 리포트 입구를 세우는 자리로 쓴다.
    private Integer goalsDone;
    private Integer goalsTotal;

    private MessageResponse(Long id, MessageRole role, String content, LocalDateTime createdAt,
                            boolean failed) {
        this.id = id;
        this.role = role;
        this.content = content;
        this.createdAt = createdAt;
        this.failed = failed;
    }

    public MessageResponse withGoals(int done, int total) {
        this.goalsDone = done;
        this.goalsTotal = total;
        return this;
    }

    public static MessageResponse from(Message message) {
        // 이제는 저장 전에 떼지만(MessageTxService), 그 코드가 생기기 전에 저장된 대화에는
        // JSON이 본문에 박혀 있다. 기록은 고치지 않으므로 표시에서 가린다.
        String raw = ChatMeta.strip(message.getContent());
        String body = raw == null ? null : raw.replace(LEGACY_QUESTION_MARKER, "").strip();
        return new MessageResponse(
                message.getId(),
                message.getRole(),
                body,
                message.getCreatedAt(),
                message.isFallback());
    }
}
