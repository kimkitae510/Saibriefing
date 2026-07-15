package com.threeam.story.entity;

// 지금 관계가 어느 상태인지. 헤어짐과 시간 갖기는 겉으로는 같은 "연락 없음"이어도 상대 행동의
// 뜻이 다르고, 이 칸이 없으면 상담자가 첫 턴을 기간과 연락 규칙을 되묻는 데 써버린다(실측:
// 모델과 프롬프트를 바꿔도 매 판 반복). 애매함은 모름이 아니라 그 자체가 정보라 따로 둔다.
public enum RelationState {
    BROKEN_UP("헤어졌다"),
    ON_BREAK("시간을 갖는 중이다"),
    UNCLEAR("헤어진 건지 애매하다"),
    OTHER("기타");

    private final String label;

    RelationState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
