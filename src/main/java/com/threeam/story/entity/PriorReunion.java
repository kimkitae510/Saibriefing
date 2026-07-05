package com.threeam.story.entity;

// "지금 이 상대와 헤어진 게 처음인가요". 헤어지자는 말을 꺼낸 횟수가 아니라 실제로 헤어졌다
// 다시 만난 횟수다 — CaseScorer의 repeatBreakup이 원래 그 뜻이다("온오프를 겪은 사이끼리만 인정").
// 프로필로는 boolean으로 접힌다(사례 데이터에 횟수 라벨이 없다). 횟수 원본은 진단 재료로만 간다.
public enum PriorReunion {
    NONE("이번이 처음"),
    ONCE("헤어졌다 다시 만난 적 한 번"),
    MANY("헤어졌다 다시 만난 적 두 번 이상"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");

    private final String label;

    PriorReunion(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    // 모름과 기타는 프로필에 안 싣는다(null이 곧 미상). 모름을 false로 접으면 반복 아님이 된다.
    public Boolean repeated() {
        return switch (this) {
            case NONE -> Boolean.FALSE;
            case ONCE, MANY -> Boolean.TRUE;
            case UNSURE, OTHER -> null;
        };
    }
}
