package com.threeam.story.entity;

// 상대의 새 사람이 언제부터인가. 새 사람이 있는 것을 확인했을 때만 묻는다 —
// 사귈 때부터 겹친 것과 한참 뒤에 생긴 것은 이별의 성격이 다르다.
public enum NewRelationOverlap {
    DURING("사귈 때부터 겹쳤다"),
    RIGHT_AFTER("헤어지자마자"),
    LATER("한참 뒤에"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");
    private final String label;
    NewRelationOverlap(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
