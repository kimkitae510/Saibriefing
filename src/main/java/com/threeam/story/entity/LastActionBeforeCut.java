package com.threeam.story.entity;

// 차단이나 읽씹 직전에 유저가 마지막으로 한 것. 연락 상태가 차단이나 읽씹일 때만 묻는다 —
// 매달리다 끊긴 판과 평범하게 연락했는데 끊긴 판은 끊긴 이유가 행동인지 마음인지가 다르다.
public enum LastActionBeforeCut {
    BEGGED("많이 매달렸다"),
    ANGRY("화내거나 다퉜다"),
    NORMAL("평범하게 연락했다"),
    UNKNOWN("모르겠다"),
    OTHER("기타");
    private final String label;
    LastActionBeforeCut(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
