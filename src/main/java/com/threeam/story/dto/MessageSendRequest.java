package com.threeam.story.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class MessageSendRequest {

    @NotBlank(message = "메시지 내용은 필수입니다.")
    // LLM 입력 비용 방어 — 대화 창(최근 20개)에 실려 매 호출 반복 과금되는 게 진짜 비용.
    // 600자에서 사연이 끊겨 흐름이 깨졌던 실측이 하한이고, 한 턴에 담아 읽을 양이 상한이다.
    // 상담자의 질문에 단 답들도 이어 붙여 이 한 건으로 나가므로, 개당 상한은 이 값을
    // 질문 수로 나눠 잡는다(ChatPage의 limit). 프론트 카운터(MAX_LENGTH)와 동일 값 유지.
    @Size(max = 2000, message = "메시지는 2000자까지 보낼 수 있습니다.")
    private String content;
}
