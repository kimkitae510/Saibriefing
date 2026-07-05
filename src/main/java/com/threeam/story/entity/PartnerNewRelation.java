package com.threeam.story.entity;

// 상대에게 새 사람이 있는가. 재회를 막는 가장 무거운 조건이라 "없음"과 "모름"을 반드시 가른다 —
// 확인 안 된 것을 없음으로 저장하면 프로필이 유리한 쪽으로 조용히 기운다. 기본값은 UNKNOWN.
public enum PartnerNewRelation {
    CONFIRMED("있는 것을 확인함"),
    DENIED("없는 것을 확인함"),
    UNKNOWN("유저가 모름"),
    OTHER("기타");

    private final String label;

    PartnerNewRelation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    // 프로필은 boolean 한 칸이라 모름은 값을 안 싣는다(null이 곧 미상).
    public Boolean toFlag() {
        return switch (this) {
            case CONFIRMED -> Boolean.TRUE;
            case DENIED -> Boolean.FALSE;
            case UNKNOWN, OTHER -> null;
        };
    }
}
