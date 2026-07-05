package com.threeam.story.entity;

// 유저가 먼저 헤어지자고 했을 때 그 이유. 먼저 원한 쪽이 유저일 때만 묻는다.
// 이별 사유 자체를 보기로 고르게 하는 것과는 다르다 — 이건 본인의 동기라 답이 하나다.
public enum SelfEndReason {
    IMPULSIVE("홧김에, 충동적으로"),
    WORN_OUT("지쳐서, 쌓여서"),
    EXTERNAL("외부 사정 때문에"),
    TEST("떠보려다"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");
    private final String label;
    SelfEndReason(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
