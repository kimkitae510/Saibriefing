package com.threeam.story.entity;

// 시간을 갖는 동안의 연락 규칙. 시간을 갖는 중일 때만 묻는 가지다.
// "정한 게 없다"를 따로 둔다 — 규칙 없이 끊긴 연락은 규칙대로 안 하는 연락과 뜻이 다르다.
public enum PauseContactRule {
    NO_CONTACT("연락하지 않기로 했다"),
    FREE("연락은 자유롭게 하기로 했다"),
    NOT_DISCUSSED("따로 정한 게 없다"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");

    private final String label;

    PauseContactRule(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
